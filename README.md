# google-photos-album-uploader
Quick and dirty Java Google Photos bulk uploader with album creation based on existing directory names.

This utility makes quick work of uploading photos and videos to Google Photos using existing directory structure to generate album names.  The utility will continue where it left off when killed/restarted.

## Building
```bash
./gradlew build
```

## Authorizing
Follow the instructions here to authorize your application and generate a credentials json file:
https://developers.google.com/photos/library/guides/get-started-java

On first execution, the Google Photos OAuth API will spew a URL which you need to navigate to in order to authorize.

## Running
Minimal arguments
```bash
java -jar ./build/libs/google-photos-album-uploader-0.0.1.jar -r <some root dir>
```

### Options
Defaults are set in us.abaz.googlephotos.util.PhotoUploadConfig

```
 -c,--credFilePath <arg>      Google Photos API credential (json)
 -p,--parallelUploads         Number of parallel uploads
 -r,--searchRootDir <arg>     Root dir for search
 -t,--tempStoragePath <arg>   Temporary storage path
 -xp,--excludePhotos          Exclude photos from upload
 -xv,--excludeVideos          Exclude videos from upload
 -a,--albumNamePrefix         Prefix the generated album name - i.e. Videos_<the generated album name>
```

