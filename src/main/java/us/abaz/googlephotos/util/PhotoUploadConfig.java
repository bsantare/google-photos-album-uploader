package us.abaz.googlephotos.util;

import lombok.Builder;
import lombok.Value;
import org.apache.commons.io.FileUtils;

import java.io.File;

@Value
@Builder
public class PhotoUploadConfig {
    String searchRootDir;
    @Builder.Default
    String tempStoragePath = FileUtils.getUserDirectory() + File.separator;
    @Builder.Default
    String credFilePath = "google-photos-api-credentials.json";
    @Builder.Default
    boolean includePhotos = true;
    @Builder.Default
    boolean includeVideos = true;
    @Builder.Default
    String albumNamePrefix = "";
    @Builder.Default
    int maxParallelUploads = 15;
}
