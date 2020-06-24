package us.abaz.googlephotos.process;

import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.types.proto.Album;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

class AlbumManager {
    private final Map<String, Album> existingAlbumMap = new ConcurrentHashMap<>();
    private final PhotosLibraryClient photosLibraryClient;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    AlbumManager(PhotosLibraryClient photosLibraryClient) {
        this.photosLibraryClient = photosLibraryClient;
    }

    Album getOrCreateAlbum(String albumName) {
        if (!initialized.get()) {
            initializeAlbums();
        }
        return existingAlbumMap.computeIfAbsent(albumName, this::createAlbum);
    }

    private Album createAlbum(String albumName) {
        return photosLibraryClient.createAlbum(albumName);
    }

    private void initializeAlbums() {
        synchronized (initialized) {
            if (!initialized.get()) {
                for (Album element : photosLibraryClient.listAlbums(false).iterateAll()) {
                    existingAlbumMap.put(element.getTitle(), element);
                }
                initialized.set(true);
            }
        }
    }
}
