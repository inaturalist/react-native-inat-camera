import React from 'react'
import {
  View,
  Text,
  TouchableNativeFeedback,
  Alert,
  StyleSheet,
  NativeModules,
  DeviceEventEmitter,
  AppRegistry,
} from 'react-native'

import INatCamera from './INatCamera';

class App extends React.Component {

    constructor(props) {
        super(props);
        this.state = { content: '' };
    }    

    onTaxaDetected = event => {
        let predictions = Object.assign({}, event.nativeEvent);
        this.setState(previousState => (
            { content: JSON.stringify(predictions) }
        ))
    }

    onCameraError = event => {
        Alert.alert(`Camera error: ${event.nativeEvent.error}`)
    }

    onCameraPermissionMissing = event => {
        Alert.alert(`Missing camera permission`)
    }

    onClassifierError = event => {
        Alert.alert(`Classifier error: ${event.nativeEvent.error}`)
    }


    componentWillMount() {
    }

    componentWillUnmount() {
    }

    render() {
        return (
            <View style={styles.container} background={TouchableNativeFeedback.SelectableBackground()}>
            <Text style={styles.title}>
            This is React Native Code
            </Text>
            <INatCamera
                onTaxaDetected={this.onTaxaDetected}
                onCameraError={this.onCameraError}
                onCameraPermissionMissing={this.onCameraPermissionMissing}
                onClassifierError={this.onClassifierError}
                modelPath="/sdcard/Download/inat_new_optimized_model.tflite"
                taxonomyPath="/sdcard/Download/taxa.json"
                taxaDetectionInterval="2000"
                style={styles.camera} />

            <Text style={styles.predictions}>
            {this.state.content}
            </Text>
            </View>
        )
    }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center'
  },
  predictions: {
    margin: 10,
    fontSize: 10,
    fontFamily: 'sans-serif-condensed',
    color: 'black',
  },

  title: {
    margin: 15,
    fontSize: 10,
    fontFamily: 'sans-serif-condensed',
    color: 'black',
  },
  camera: {
    width: "100%",
    height: 450,
    backgroundColor: 'black',
  },
})

AppRegistry.registerComponent('INatCamera', () => App)
