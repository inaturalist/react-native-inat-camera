# react-native-inat-camera
React Native package that provides a camera with optional species recognition.


## Android

(Under the `android` sub-folder)

1. `npm install`.
2. `npm start`.
3. Open `android/android` sub folder in Android Studio.
4. Run project - a simple one screen app, that launches a React Native screen (React Native screen is loaded using the local server that is running in step #2).
5. Make sure that both your phone (step #4) and the where the server is running from (step #2) are connected to the same wifi network.
6. The React Native screen source code is found at the root of the `android` sub folder. It demos usage of the `INatCamera` component.

### Troubleshooting

1. Can't load React Native screen: Once React Native screen is open - shake phone to open dev menu -> Dev Settings -> Debug server & port for device -> write IP address and port of your server (step #2).
2. Remember to download the .tflite and .json files into the device (e.g. `/sdcard/Download` folder) and initialize the `INatCamera` component with the correct paths.


