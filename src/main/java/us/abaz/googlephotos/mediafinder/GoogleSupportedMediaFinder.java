package us.abaz.googlephotos.mediafinder;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class GoogleSupportedMediaFinder extends SimpleFileVisitor<Path> {
    private static final String[] GOOGLE_SUPPORTED_PHOTO_EXTENSIONS =
            {
                    "BMP", "GIF", "HEIC", "ICO", "JPG", "PNG", "TIFF", "WEBP", "RAW"
            };
    private static final String[] GOOGLE_SUPPORTED_VIDEO_EXTENSIONS = {
            "3GP", "3G2", "ASF", "AVI", "DIVX", "M2T", "M2TS", "M4V", "MKV", "MMV", "MOD", "MOV", "MP4", "MPG", "MTS", "TOD", "WMV"
    };

    private final Set<String> extMatchSet = new HashSet<>();

    public GoogleSupportedMediaFinder(boolean includePhotos, boolean includeVideos) {
        if (includePhotos) {
            log.info("Including photo files");
            Arrays.stream(GOOGLE_SUPPORTED_PHOTO_EXTENSIONS).forEach(ext -> extMatchSet.add("." + ext.toLowerCase()));
        }
        if (includeVideos) {
            log.info("Including video files");
            Arrays.stream(GOOGLE_SUPPORTED_VIDEO_EXTENSIONS).forEach(ext -> extMatchSet.add("." + ext.toLowerCase()));
        }
    }

    @SneakyThrows
    public Set<MediaFile> findMediaFiles(String rootPath) {
        return Files.walk(Paths.get(rootPath))
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            int dotIdx = fileName.lastIndexOf('.');
                            if (dotIdx == -1) {
                                return false;
                            }
                            String ext = fileName.substring(dotIdx, fileName.length());
                            boolean match = extMatchSet.contains(ext.toLowerCase());
                            if (!match) {
                                log.trace("Skipping mediafinder - no match: {}", path);
                            }
                            return match;
                        })
                        .map(path -> MediaFile.fromPathString(rootPath, path.toString()))
                        .collect(Collectors.toCollection( LinkedHashSet::new ));
    }
}