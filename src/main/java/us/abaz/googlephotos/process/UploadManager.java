package us.abaz.googlephotos.process;

import com.google.api.core.ApiFuture;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsRequest;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.google.photos.types.proto.Album;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import us.abaz.googlephotos.mediafinder.MediaFile;
import us.abaz.googlephotos.util.PhotoUploadConfig;
import us.abaz.googlephotos.util.PhotosLibraryClientFactory;
import us.abaz.googlephotos.util.SimpleProgressRenderer;

import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@SuppressWarnings("UnstableApiUsage")
public class UploadManager implements AutoCloseable {
    private static final List<String> REQUIRED_SCOPES =
            ImmutableList.of(
                    "https://www.googleapis.com/auth/photoslibrary.readonly",
                    "https://www.googleapis.com/auth/photoslibrary.appendonly");
    private static final String FILE_ACCESS_MODE = "r";
    // Maximum number of requests initiated per minute - these are rate limited on the Google side to 10
    private static final int GOOGLE_MAX_REQUESTS_PER_MINUTE = 15;

    private final PhotoUploadConfig config;

    private final PhotosLibraryClient photosLibraryClient;
    private final AlbumManager albumManager;
    private final Semaphore uploadSlotSemaphore;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final RateLimiter rateLimiter = RateLimiter.create((double) GOOGLE_MAX_REQUESTS_PER_MINUTE / 60.0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    private volatile boolean forcedShutdown = false;

    @SneakyThrows
    public UploadManager(PhotoUploadConfig config) {
        this.config = config;
        photosLibraryClient = PhotosLibraryClientFactory.createClient(config.getCredFilePath(), REQUIRED_SCOPES);
        albumManager = new AlbumManager(photosLibraryClient);
        uploadSlotSemaphore = new Semaphore(config.getMaxParallelUploads(), true);
    }

    public void close() {
        photosLibraryClient.close();
        shutdownLatch.countDown();
        log.info("Photos client closed");
    }

    @SneakyThrows
    public void stopUpload() {
        forcedShutdown = true;
        System.out.println("\nOrderly shutdown initiated.  Waiting for active uploads to complete...");
        shutdownLatch.await();
    }

    /**
     * Begin the file upload batch job
     */
    @SneakyThrows
    public void startUpload() {
        try (MediaItemManager mediaItemManager = new MediaItemManager(config)) {
            int progress = 0;
            Instant startInstant = Instant.now();
            int totalFiles = mediaItemManager.getTotalFiles();
            MediaFile curMediaFile = mediaItemManager.getNextFile();
            while (!forcedShutdown && curMediaFile != null) {
                // Get or create an Album for the current media file
                String albumName = config.getAlbumNamePrefix() + curMediaFile.getAlbumName();
                Album album = albumManager.getOrCreateAlbum(albumName);

                if (!forcedShutdown) {
                    // Initiate the next file upload - this will block until 1) an upload slot is available and
                    // 2) rate limiting is satisfied
                    int inProgress = uploadNextFile(album, mediaItemManager, curMediaFile);

                    double rate = (double) Math.max(progress, 1) / (double) Math.max(Duration.between(startInstant, Instant.now()).toMinutes(), 1);

                    progress++;

                    SimpleProgressRenderer.renderProgress(
                            progress,
                            totalFiles,
                            inProgress,
                            errorCount.get(),
                            String.format("\tRate: %.2f/minute\tCurrent Album: '%s' Current File: '%s'", rate, albumName, curMediaFile.getFileName())
                    );

                    // Find the next file to process
                    curMediaFile = mediaItemManager.getNextFile();
                }
            }

            // Orderly shutdown
            System.out.print("\n");
            int activeCount = getActiveUploadCount();
            while (activeCount > 0) {
                System.out.print(String.format("\rWaiting for %d upload(s) still in progress to complete...", getActiveUploadCount()));
                Thread.sleep(2500);
                activeCount = getActiveUploadCount();
            }
            System.out.println("\nShutdown complete.");
        } catch (Exception e) {
            log.error("Unknown exception during upload processing", e);
            System.exit(-1);
        }
    }

    /**
     * Upload the next file.  This will block until
     * 1) an upload slot is available and
     * 2) rate limiting is satisfied
     *
     * @param album            The album to upload to
     * @param mediaItemManager The media item manager to use
     * @param curMediaFile     The current file
     * @return Total upload slots currently in use
     */
    @SneakyThrows
    private int uploadNextFile(Album album, MediaItemManager mediaItemManager, MediaFile curMediaFile) {
        // Block until an upload slot becomes available
        uploadSlotSemaphore.acquire();
        // Rate limit to 10 requests per minute or the Google Photos API will reject any requests beyond this rate
        rateLimiter.acquire();

        initiateFileUpload(album, mediaItemManager, curMediaFile);

        return getActiveUploadCount();
    }

    private int getActiveUploadCount() {
        return config.getMaxParallelUploads() - uploadSlotSemaphore.availablePermits();
    }

    /**
     * Infer the mime type of a file
     *
     * @param fileName The file name to test
     * @return The String representing the file mime type
     */
    @SneakyThrows
    private String inferFileMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return StringUtils.isNotBlank(mimeType) ? mimeType : Files.probeContentType(Paths.get(fileName));
    }

    /**
     * Initiate the asynchronous file upload to an album
     *
     * @param album     The album to upload to
     * @param mediaFile The media file to upload
     */
    @SneakyThrows
    private void initiateFileUpload(Album album, MediaItemManager mediaItemManager, MediaFile mediaFile) {
        String fileName = mediaFile.getCompleteFilename();
        try {
            log.debug("Processing file {} for album {}", mediaFile.getFileName(), mediaFile.getAlbumName());
            UploadMediaItemRequest.Builder uploadRequestBuilder = UploadMediaItemRequest.newBuilder();
            uploadRequestBuilder
                    .setMimeType(inferFileMimeType(fileName))
                    .setDataFile(new RandomAccessFile(fileName, FILE_ACCESS_MODE));

            // Kick off the async upload
            ApiFuture<UploadMediaItemResponse> uploadResponseFuture =
                    photosLibraryClient.uploadMediaItemCallable()
                            .futureCall(uploadRequestBuilder.build());
            uploadResponseFuture.addListener(
                    buildHandleUploadFinishedRunnable(uploadResponseFuture, mediaItemManager, album, mediaFile),
                    MoreExecutors.directExecutor());
        } catch (FileNotFoundException e) {
            log.error("Error uploading file " + fileName, e);
            // Release the semaphore on error
            uploadSlotSemaphore.release();
            errorCount.addAndGet(1);
        }
    }

    /**
     * Handle a callback when the upload request finishes
     *
     * @param uploadResponseFuture The future that performed the request
     * @param album                The album for the request
     * @param mediaFile            The media file for the request
     * @return A {@link Runnable} that processes the request result
     */
    private Runnable buildHandleUploadFinishedRunnable(
            ApiFuture<UploadMediaItemResponse> uploadResponseFuture,
            MediaItemManager mediaItemManager,
            Album album,
            MediaFile mediaFile) {
        return () -> {
            try {
                UploadMediaItemResponse uploadResponse = uploadResponseFuture.get();
                // Check if the upload is successful
                if (uploadResponse.getUploadToken().isPresent()) {
                    BatchCreateMediaItemsRequest.Builder createRequestBuilder =
                            BatchCreateMediaItemsRequest.newBuilder();
                    createRequestBuilder
                            .setAlbumId(album.getId())
                            .addNewMediaItemsBuilder()
                            .getSimpleMediaItemBuilder()
                            .setFileName(mediaFile.getCompleteFilename())
                            .setUploadToken(uploadResponse.getUploadToken().get());
                    photosLibraryClient.batchCreateMediaItems(createRequestBuilder.build());

                    // Success - mark the file as uploaded
                    mediaItemManager.markMediaFileUploaded(mediaFile);
                } else {
                    UploadMediaItemResponse.Error error = uploadResponse.getError().orElse(null);
                    if (error != null) {
                        throw new RuntimeException("Error uploading file " + mediaFile, error.getCause());
                    } else {
                        throw new RuntimeException("Error uploading file - unknown cause");
                    }
                }
            } catch (Exception e) {
                log.error("Error uploading file", e);
                errorCount.addAndGet(1);
            } finally {
                // Release the upload slot
                uploadSlotSemaphore.release();
            }
        };
    }
}
