/**
 * @format
 * @lint-ignore-every XPLATJSCOPYRIGHT1
 */

import 'react-native-gesture-handler';

import * as React from "react";
import {AppRegistry} from 'react-native';
import Example from './App';
// import INatCamera from './INatCamera';
import {name as appName} from './app.json';

const App = ( ) => (
  <Example />
);

// AppRegistry.registerComponent('INatCamera', () => App)
AppRegistry.registerComponent(appName, () => App);
