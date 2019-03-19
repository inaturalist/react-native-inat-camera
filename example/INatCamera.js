import React from 'react';
import PropTypes from 'prop-types'
import {
  findNodeHandle,
  Platform,
  NativeModules,
  ViewPropTypes,
  requireNativeComponent,
  View,
  ActivityIndicator,
  Text,
  StyleSheet,
  PermissionsAndroid,
} from 'react-native';


type PictureOptions = {
  pauseAfterCapture?: boolean,
};

export default class INatCamera extends React.Component<PropsType, StateType> {
    static propTypes = {
        taxaDetectionInterval: PropTypes.string,
        modelPath: PropTypes.string,
        taxonomyPath: PropTypes.string,
        ...ViewPropTypes,
    };

    static defaultProps: Object = {
        taxaDetectionInterval: "1000",
    };
    

    _cameraRef: ?Object;
    _cameraHandle: ?number;

    _setReference = (ref: ?Object) => {
        if (ref) {
            this._cameraRef = ref;
            this._cameraHandle = findNodeHandle(ref);
        } else {
            this._cameraRef = null;
            this._cameraHandle = null;
        }
    };

    _onTaxaDetected = (event) => {
        if (this.props.onTaxaDetected) {
            this.props.onTaxaDetected(event);
        }
    };

    _onCameraError = (event) => {
        if (this.props.onCameraError) {
            this.props.onCameraError(event);
        }
    };

    _onCameraPermissionMissing = (event) => {
        if (this.props.onCameraPermissionMissing) {
            this.props.onCameraPermissionMissing(event);
        }
    };

    _onClassifierError = (event) => {
        if (this.props.onClassifierError) {
            this.props.onClassifierError(event);
        }
    };

    _onDeviceNotSupported = (event) => {
        if (this.props.onDeviceNotSupported) {
            this.props.onDeviceNotSupported(event);
        }
    };


    render() {
        return (
            <View style={this.props.style || {} }>
                <RCTINatCameraView
                    style={StyleSheet.absoluteFill}
                    ref={this._setReference}

                    onTaxaDetected={this._onTaxaDetected}
                    onCameraError={this._onCameraError}
                    onCameraPermissionMissing={this._onCameraPermissionMissing}
                    onClassifierError={this._onClassifierError}
                    onDeviceNotSupported={this._onDeviceNotSupported}
                    modelPath={this.props.modelPath}
                    taxonomyPath={this.props.taxonomyPath}
                    taxaDetectionInterval={this.props.taxaDetectionInterval}
                />
            </View>
        );
    }


    async takePictureAsync(options?: PictureOptions) {
        if (!options) {
            options = {};
        }

        if (options.pauseAfterCapture === undefined) {
            options.pauseAfterCapture = false;
        }

        return await NativeModules.INatCameraModule.takePictureAsync(options, this._cameraHandle); 
    }

    resumePreview() {
        NativeModules.INatCameraModule.resumePreview(this._cameraHandle); 
    }
};


const RCTINatCameraView = requireNativeComponent('RCTINatCameraView', INatCamera, {
});




