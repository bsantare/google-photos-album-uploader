package us.abaz.googlephotos.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.*;

@Slf4j
public class ArgsParser {
    public static PhotoUploadConfig parseArgs(String[] args) {
        Options options = new Options();

        Option searchRoot = new Option("r", "searchRootDir", true, "Root dir for search");
        searchRoot.setRequired(true);
        options.addOption(searchRoot);

        Option tempStoragePath = new Option("t", "tempStoragePath", true, "Temporary storage path");
        tempStoragePath.setRequired(false);
        options.addOption(tempStoragePath);

        Option credFilePath = new Option("c", "credFilePath", true, "Google Photos API credentials (json)");
        credFilePath.setRequired(false);
        options.addOption(credFilePath);

        Option excludePhotos = new Option("xp", "excludePhotos", false, "Exclude photos from upload");
        credFilePath.setRequired(false);
        options.addOption(excludePhotos);

        Option excludeVidoes = new Option("xv", "excludeVideos", false, "Exclude videos from upload");
        credFilePath.setRequired(false);
        options.addOption(excludeVidoes);

        Option parallelUploads = new Option("p", "parallelUploads", false, "Number of parallel uploads");
        credFilePath.setRequired(false);
        options.addOption(parallelUploads);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
            PhotoUploadConfig.PhotoUploadConfigBuilder builder = PhotoUploadConfig.builder();

            String searchPath = cmd.getOptionValue("r");
            if (searchPath == null) {
                throw new IllegalAccessException("Must specify search root");
            }
            builder.searchRootDir(searchPath);

            String tempStoragePathVal = cmd.getOptionValue("t");
            if (tempStoragePathVal != null) {
                builder.tempStoragePath(tempStoragePathVal);
            }

            String credFilePathVal = cmd.getOptionValue("c");
            if (credFilePathVal != null) {
                builder.tempStoragePath(credFilePathVal);
            }

            String excludePhotosVal = cmd.getOptionValue("xp");
            if (excludePhotosVal != null) {
                builder.includePhotos(false);
            }

            String excludeVideosVal = cmd.getOptionValue("xv");
            if (excludeVideosVal != null) {
                builder.includeVideos(false);
            }

            String parallelUploadsVal = cmd.getOptionValue("p");
            if (parallelUploadsVal != null) {
                builder.maxParallelUploads(Integer.parseInt(parallelUploadsVal));
            }

            return builder.build();
        } catch (Exception e) {
            log.error(e.getMessage());
            formatter.printHelp("GooglePhotoAlbumUploader", options);
            throw new RuntimeException("Invalid command line arguments");
        }
    }
}
