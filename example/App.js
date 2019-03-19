/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow
 * @lint-ignore-every XPLATJSCOPYRIGHT1
 */

import React, {Component} from 'react';
import {Platform, StyleSheet, Text, View, Alert, Button} from 'react-native';
import INatCamera from './INatCamera';

type Props = {};
export default class App extends Component<Props> {
  constructor(props) {
    super(props);
    this.state = { content: '', resumeHidden: true };

      console.log('Initialized page');

  }   

  onTakePhoto = () => {
      console.log('Taking picture', this.refs.camera);

      this.refs.camera.takePictureAsync({
          pauseAfterCapture: true
      }).then(path => {
          console.log('Took photo - ' + path);
          this.setState(previousState => (
              { resumeHidden: false }
          ));
      });
  }

  onResumePreview = () => {
      console.log('Resuming preview');
      this.refs.camera.resumePreview();
          this.setState(previousState => (
              { resumeHidden: true }
          ));
  }

  onTaxaDetected = predictions => {
       this.setState(previousState => (
            { content: JSON.stringify(predictions) }
        ))
  }

  onCameraError = error => {
        Alert.alert(`Camera error: ${error}`)
  }

  onCameraPermissionMissing = event => {
        Alert.alert(`Missing camera permission`)
  }

  onClassifierError = event => {
        Alert.alert(`Classifier error: ${event.nativeEvent.error}`)
  }

  onDeviceNotSupported = event => {
        Alert.alert(`Device not supported, reason: ${event.nativeEvent.reason}`)
  }
    
  render() {
    return (
      <View style={styles.container}>
        <INatCamera
            ref="camera"
            onTaxaDetected={this.onTaxaDetected}
            onCameraError={this.onCameraError}
            onCameraPermissionMissing={this.onCameraPermissionMissing}
            onClassifierError={this.onClassifierError}
            onDeviceNotSupported={this.onDeviceNotSupported}
            modelPath="/sdcard/Download/optimized_model.tflite"
            taxonomyPath="/sdcard/Download/taxonomy_data.csv"
            taxaDetectionInterval="2000"
            style={styles.camera} />

        <View style={styles.buttonContainer}>
            {
                this.state.resumeHidden ?
                <Button
                    onPress={this.onTakePhoto}
                    style={styles.takePhoto}
                    color="#841584"
                    title="Take Photo"
                    />
                : null
            }
            {
                this.state.resumeHidden ? null :
            <Button
                ref="resumePreview"
                onPress={this.onResumePreview}
                style={styles.takePhoto}
                color="#00FF00"
                title="Resume preview"
                />
            }

        </View>

        <Text style={styles.predictions}>
            {this.state.content}
        </Text>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  buttonContainer: {
      top: 10,
      left: 10,
      width: 200,
      height: 50,
      position: 'absolute',
  },

 hide: {
     display: 'none',
     width: 0,
     height: 0
 },

  takePhoto: {
      fontSize: 24,
      fontWeight: 'bold',
      overflow: 'hidden',
      padding: 12,
      textAlign:'center',
      borderColor: 'white',
      width: 200,
      height: 50,
      borderWidth: 1,
      borderRadius: 12,
  },
  container: {
    top: 0,
    left: 0,
    height: '100%',
    width: '100%',
    backgroundColor: 'red'
  },
  camera: {
    top: 0,
    left: 0,
    height: '100%',
    width: '100%',
    backgroundColor: 'blue'
  },
  predictions: {
    margin: 10,
    fontSize: 10,
    fontFamily: 'sans-serif-condensed',
    color: 'black',
    position: 'absolute',
    bottom: 0,
    left: 0
  }, 
});



