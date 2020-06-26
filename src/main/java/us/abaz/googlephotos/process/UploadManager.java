package us.abaz.googlephotos.process;

import com.google.api.core.ApiFuture;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
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

import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class UploadManager implements AutoCloseable {
    private static final List<String> REQUIRED_SCOPES =
            ImmutableList.of(
                    "https://www.googleapis.com/auth/photoslibrary.readonly",
                    "https://www.googleapis.com/auth/photoslibrary.appendonly");
    private static final String FILE_ACCESS_MODE = "r";
    private static final int MAX_PARALLEL_UPLOADS = 15;

    private final PhotoUploadConfig config;

    private final PhotosLibraryClient photosLibraryClient;
    private final AlbumManager albumManager;
    private final Semaphore uploadSlotSemaphore = new Semaphore(MAX_PARALLEL_UPLOADS, true);
    private final AtomicBoolean uploadRunning = new AtomicBoolean(true);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @SneakyThrows
    public UploadManager(PhotoUploadConfig config) {
        this.config = config;
        photosLibraryClient = PhotosLibraryClientFactory.createClient(config.getCredFilePath(), REQUIRED_SCOPES);
        albumManager = new AlbumManager(photosLibraryClient);
    }

    public void close() {
        photosLibraryClient.close();
        shutdownLatch.countDown();
        log.info("Photos client closed");
    }

    @SneakyThrows
    public void stopUpload() {
        uploadRunning.set(false);
        log.info("Orderly shutdown initiated.  Waiting for active uploads to complete...");
        shutdownLatch.await();
    }

    /**
     * Begin the file upload batch job
     */
    @SneakyThrows
    public void startUpload() {
        int progress = 0;
        try (MediaItemManager mediaItemManager = new MediaItemManager(config)) {
            MediaFile curMediaFile = mediaItemManager.getNextFile();
            while (curMediaFile != null && uploadRunning.get()) {
                // Get or create an Album for the current media file
                Album album = albumManager.getOrCreateAlbum(curMediaFile.getAlbumName());

                // Block until an upload slot becomes available
                uploadSlotSemaphore.acquire();

                // Upload the file - this call will block until an upload slot becomes available
                initiateFileUpload(album, mediaItemManager, curMediaFile);

                progress++;
                if (progress % 100 == 0) {
                    log.info("{} files uploaded", progress);
                }

                // Find the next file to process
                curMediaFile = mediaItemManager.getNextFile();
            }

            // Orderly shutdown
            if (!uploadRunning.get()) {
                int permitCount = 0;
                while (permitCount < MAX_PARALLEL_UPLOADS) {
                    uploadSlotSemaphore.acquire();
                    permitCount++;
                }
                log.info("Orderly shutdown complete.");
            }
        } catch (Exception e) {
            log.error("Unknown exception during upload processing", e);
            System.exit(-1);
        }
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
            } finally {
                // Release the upload slot
                uploadSlotSemaphore.release();
            }
        };
    }

}
