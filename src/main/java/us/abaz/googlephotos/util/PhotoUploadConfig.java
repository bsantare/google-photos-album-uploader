package us.abaz.googlephotos.util;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PhotoUploadConfig {
    String searchRootDir;
    @Builder.Default
    String tempStoragePath = "/tmp";
    @Builder.Default
    String credFilePath = "google-photos-api-credentials.json";
    @Builder.Default
    boolean includePhotos = true;
    @Builder.Default
    boolean includeVideos = true;
    @Builder.Default
    int maxParallelUploads = 10;
}
