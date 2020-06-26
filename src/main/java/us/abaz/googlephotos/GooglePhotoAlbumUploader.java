package us.abaz.googlephotos;

import lombok.extern.slf4j.Slf4j;
import us.abaz.googlephotos.process.UploadManager;
import us.abaz.googlephotos.util.ArgsParser;
import us.abaz.googlephotos.util.PhotoUploadConfig;

@Slf4j
public class GooglePhotoAlbumUploader {
    public static void main(String[] args) {
        PhotoUploadConfig config = null;
        try {
            config = ArgsParser.parseArgs(args);
            log.info("Input arguments are {}", config);
        } catch (Exception e) {
            log.error("Error initializing upload", e);
            System.exit(-1);
        }

        try (UploadManager uploadManager = new UploadManager(config)) {
            Runtime.getRuntime().addShutdownHook(new Thread(uploadManager::stopUpload));
            uploadManager.startUpload();
        } catch (Exception e) {
            log.error("Error upload files", e);
            System.exit(-1);
        }

        System.exit(0);
    }
}
