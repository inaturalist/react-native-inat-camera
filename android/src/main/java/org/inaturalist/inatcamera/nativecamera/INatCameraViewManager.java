package org.inaturalist.inatcamera.nativecamera;

import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.bridge.ReactMethod;
import android.widget.Toast;


import java.util.Map;

public class INatCameraViewManager extends SimpleViewManager<INatCameraView> {

    private static final String REACT_CLASS = "RCT" + INatCameraView.class.getSimpleName();

    private ThemedReactContext mContext;

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected INatCameraView createViewInstance(ThemedReactContext reactContext) {
        mContext = reactContext;
        return new INatCameraView(reactContext, reactContext.getCurrentActivity());
    }

    @ReactMethod
    public void takePictureAsync(String message) {
        Toast.makeText(mContext, message, 1000).show();
    }

    @ReactProp(name = "taxaDetectionInterval")
    public void setTaxaDetectionInterval(INatCameraView view, String interval) {
        view.setTaxaDetectionInterval(Integer.valueOf(interval));
    }

    @ReactProp(name = "modelPath")
    public void setModelPath(INatCameraView view, String path) {
        view.setModelPath(path);
    }

    @ReactProp(name = "taxonomyPath")
    public void setTaxonomyPath(INatCameraView view, String path) {
        view.setTaxonomyPath(path);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.of(
                INatCameraView.EVENT_NAME_ON_TAXA_DETECTED,
                (Object) MapBuilder.of("registrationName", INatCameraView.EVENT_NAME_ON_TAXA_DETECTED),
                INatCameraView.EVENT_NAME_ON_CAMERA_ERROR,
                (Object) MapBuilder.of("registrationName", INatCameraView.EVENT_NAME_ON_CAMERA_ERROR),
                INatCameraView.EVENT_NAME_ON_CAMERA_PERMISSION_MISSING,
                (Object) MapBuilder.of("registrationName", INatCameraView.EVENT_NAME_ON_CAMERA_PERMISSION_MISSING),
                INatCameraView.EVENT_NAME_ON_CLASSIFIER_ERROR,
                (Object) MapBuilder.of("registrationName", INatCameraView.EVENT_NAME_ON_CLASSIFIER_ERROR),
                INatCameraView.EVENT_NAME_ON_DEVICE_NOT_SUPPORTED,
                (Object) MapBuilder.of("registrationName", INatCameraView.EVENT_NAME_ON_DEVICE_NOT_SUPPORTED)
        );
    }

}

