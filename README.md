# react-native-inat-camera
React Native package that provides a camera with optional species recognition.


## Android

The `android` sub folder contains the source code for the Android library project. This project generates an AAR file which can be used in React Native projects.


### Building the library

1. From the root folder of the repository: Run `npm install` (the Android library project relies on the `node_modules` directory created in the root folder).
2. Open `android` sub folder in Android Studio.
3. Build -> Make module 'inatcamera'.
4. This generates an AAR file under `android/inatcamera/build/outputs/aar`.

### Integrating the Library with a React Native app

The `example` folder contains a React Native app that shows the different integration points (run it using `npm install` + `react-native run-android`).

1. Open your RN project's `android` sub folder in Android Studio (easier to make changes).
2. Make sure you have the `<uses-permission android:name="android.permission.CAMERA" />` permission, in your `AndroidManifest.xml` file.
3. Make sure your RN code asks the user for that permission as well.
4. File -> New Module -> Import .JAR/.AAR Package -> Choose the camera library's AAR file (see "Building the Library" section).
5. Add the following to `MainApplication.java`, inside the `getPackages` method (inside the `asList` method call): `new INatCameraViewPackage()`. Don't forget to add `import org.inaturalist.inatcamera.nativecamera.INatCameraViewPackage;` at the beginning of the file.
6. Open your RN app's `build.gradle` file and add the following:

Under the `dependencies` section:
```gradle
implementation project(":inatcamera")
implementation 'com.android.support:support-v13:28.0.0'
implementation 'com.android.support:appcompat-v7:28.0.0'
implementation 'org.tensorflow:tensorflow-lite:+'
implementation 'com.google.code.gson:gson:2.8.5'`
```

Under the `defaultConfig` section:
```gradle
ndk {
    abiFilters "armeabi-v7a", "x86", 'armeabi', 'arm64-v8a'
}
packagingOptions {
    exclude "lib/arm64-v8a/libgnustl_shared.so"
    exclude '/lib/mips64/**'
    exclude '/lib/arm64-v8a/**'
    exclude '/lib/x86_64/**'
}
```

### Troubleshooting

1. Can't load React Native screen: Once React Native screen is open - shake phone to open dev menu -> Dev Settings -> Debug server & port for device -> write IP address and port of your server (step #2).
2. Remember to download the .tflite and .json files into the device (e.g. `/sdcard/Download` folder) and initialize the `INatCamera` component with the correct paths.


