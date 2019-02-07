/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow
 * @lint-ignore-every XPLATJSCOPYRIGHT1
 */

import React, {Component} from 'react';
import {Platform, StyleSheet, Text, View, Alert} from 'react-native';
import INatCamera from './INatCamera';

const instructions = Platform.select({
  ios: 'Press Cmd+R to reload,\n' + 'Cmd+D or shake for dev menu',
  android:
    'Double tap R on your keyboard to reload,\n' +
    'Shake or press menu button for dev menu',
});

type Props = {};
export default class App extends Component<Props> {
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
    
  render() {
    return (
      <View style={styles.container}>
        <Text style={styles.welcome}>iNaturalist Native Camera Demo</Text>

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
    );
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#F5FCFF',
  },
  welcome: {
    fontSize: 20,
    textAlign: 'center',
    margin: 10,
  },
  instructions: {
    textAlign: 'center',
    color: '#333333',
    marginBottom: 5,
  },
  camera: {
    width: "100%",
    height: 450,
    backgroundColor: 'black'
  },
  predictions: {
    margin: 10,
    fontSize: 10,
    fontFamily: 'sans-serif-condensed',
    color: 'black'
  }, 
});



