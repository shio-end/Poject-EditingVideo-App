# VeloraNova

Android 10+ offline video trimmer. Choose a local video, set the start and end points, preview the selection and save an H.264 MP4 with AAC audio to `Movies/VeloraNova`. Available codecs depend on the Android device.

The application ID is `com.veloranova.editor`. The app starts without an account and its APK declares no internet permission. The Gradle build requires internet access to download dependencies on first use.

## Build

Use JDK 17, Gradle 8.9, Android SDK Platform 35 and Build Tools 35.0.0.

```sh
gradle assembleDebug lintDebug
```

Run from this directory. The APK is at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also publishes it as the **VeloraNova-debug** artifact.
