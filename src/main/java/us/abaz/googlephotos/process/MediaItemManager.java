package us.abaz.googlephotos.process;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import us.abaz.googlephotos.mediafinder.GoogleSupportedMediaFinder;
import us.abaz.googlephotos.mediafinder.MediaFile;
import us.abaz.googlephotos.util.PhotoUploadConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Slf4j
public class MediaItemManager implements AutoCloseable {
    private static final String PROCESSED_LOG = "GooglePhotoAlbumUploader_processed.txt";
    private final Object monitor = new Object();
    private final PhotoUploadConfig config;
    private final File processedLog;
    private volatile boolean initialized = false;
    private PrintWriter writer = null;
    private Queue<MediaFile> filesToProcessQueue;

    MediaItemManager(PhotoUploadConfig photoUploadConfig) {
        this.config = photoUploadConfig;
        this.processedLog = new File(photoUploadConfig.getTempStoragePath(), PROCESSED_LOG);
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (writer != null) {
                writer.close();
            }
        }
    }

    MediaFile getNextFile() {
        if (!initialized) {
            initialize();
        }
        return filesToProcessQueue.poll();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    void markMediaFileUploaded(MediaFile mediaFile) {
        // Make this thread safe for multiple consumers
        synchronized (monitor) {
            try {
                if (writer == null) {
                    if (!processedLog.exists()){
                        processedLog.createNewFile();
                    }
                    // No buffering since we will flush on every write anyway
                    writer = new PrintWriter(new FileWriter(processedLog, true));
                }
                writer.println(mediaFile.getCompleteFilename());
                writer.flush();
            } catch (Exception e) {
                log.error("Error marking file as uploaded " + mediaFile.getCompleteFilename(), e);
            }
        }
    }

    @SneakyThrows
    private List<MediaFile> getProcessedMediaFiles() {
        log.info("Processed file log path: {}", processedLog.getAbsolutePath());
        if (!processedLog.exists()) {
            return Collections.emptyList();
        }

        try (InputStream is = new BufferedInputStream(new FileInputStream(processedLog))) {
            List<String> files = IOUtils.readLines(is, StandardCharsets.UTF_8.name());
            return files.stream()
                    .filter(StringUtils::isNotBlank)
                    .map(fileName -> MediaFile.fromPathString(config.getSearchRootDir(), fileName))
                    .collect(Collectors.toList());
        }
    }

    private void initialize() {
        synchronized (monitor) {
            if (!initialized) {
                // Read the already processed files
                List<MediaFile> processedFiles = getProcessedMediaFiles();
                // Find the target files
                GoogleSupportedMediaFinder mediaFinder = new GoogleSupportedMediaFinder(config.isIncludePhotos(), config.isIncludeVideos());
                Set<MediaFile> foundFiles = mediaFinder.findMediaFiles(config.getSearchRootDir());
                // Remove the already processed files
                foundFiles.removeAll(processedFiles);
                log.info("Total files to process {} after removing {} already processed files", foundFiles.size(), processedFiles.size());
                filesToProcessQueue = new ConcurrentLinkedQueue<>(foundFiles);
                initialized = true;
            }
        }
    }
}
