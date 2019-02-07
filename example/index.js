/**
 * @format
 * @lint-ignore-every XPLATJSCOPYRIGHT1
 */

import {AppRegistry} from 'react-native';
import App from './App';
import INatCamera from './INatCamera';
import {name as appName} from './app.json';

AppRegistry.registerComponent('INatCamera', () => App)
AppRegistry.registerComponent(appName, () => App);
