package us.abaz.googlephotos.mediafinder;

import lombok.Builder;
import lombok.Value;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@Value
@Builder
public class MediaFile {
    String absolutePath;
    String albumName;
    String fileName;

    public String getCompleteFilename() {
        return absolutePath + File.separator + fileName;
    }

    public static MediaFile fromPathString(String rootPath, String pathString) {
        Path path =  Paths.get(pathString);
        String relativePath = path.getParent().toAbsolutePath().toString().replace(rootPath + File.separator, "");
        String albumName = relativePath.replaceAll(File.separator, " ");
        String absoluteParentPath = path.getParent().toAbsolutePath().toString();
        return MediaFile.builder()
                .absolutePath(absoluteParentPath)
                .albumName(albumName)
                .fileName(path.toString().replaceFirst(absoluteParentPath + File.separator, ""))
                .build();
    }
}
