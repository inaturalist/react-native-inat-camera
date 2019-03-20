# react-native-inat-camera
React Native package that provides a camera with optional species recognition.

## iOS

The `ios` subfolder contains the source code for the iOS library project. You should need to just `npm install inaturalist/react-native-inat-camera` and then perhaps `react-native link react-native-inat-camera` and you should be good to go. You'll need to provide the model and taxonomy files.


## Android

The `android` sub folder contains the source code for the Android library project. This project needs to be included in React Native projects that use the library.

## Integrating the Library with a React Native app

The `example` folder contains a React Native app that shows the different integration points (run it using `npm install` + `react-native run-android`).

1. Open your RN project's `android` sub folder and edit the settings.gradle file:
```gradle
include ':app', ':inatcamera'
project(':inatcamera').projectDir = new File(rootProject.projectDir, '../../android')
```
2. Make sure you have the `<uses-permission android:name="android.permission.CAMERA" />` permission, in your `AndroidManifest.xml` file.
3. Make sure your RN code asks the user for that permission as well.
4. Add the following to `MainApplication.java`:
```java
import org.inaturalist.inatcamera.nativecamera.INatCameraViewPackage;
...
    protected List<ReactPackage> getPackages() {
      return Arrays.<ReactPackage>asList(
          new MainReactPackage(),
          new INatCameraViewPackage() // <-- Add this line
      );
    }
```
5. Open your RN app's `build.gradle` file and add the following (`android/app/build.gradle`):
Under the `dependencies` section:
```gradle
implementation project(":inatcamera")
```

### Troubleshooting

1. Can't load React Native screen: Once React Native screen is open - shake phone to open dev menu -> Dev Settings -> Debug server & port for device -> write IP address and port of your computer. Make sure your computer and phone are on the same wifi network.
2. Remember to download the .tflite and .json files into the device (e.g. `/sdcard/Download` folder) and initialize the `INatCamera` component with the correct paths.


