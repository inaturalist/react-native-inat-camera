package org.inaturalist.inatcamera.ui;

import androidx.annotation.Nullable;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewGroupManager;
import com.facebook.react.uimanager.annotations.ReactProp;
import org.inaturalist.inatcamera.ui.AspectRatio;
import org.inaturalist.inatcamera.ui.Size;


import org.inaturalist.inatcamera.nativecamera.RNCameraView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CameraViewManager extends ViewGroupManager<RNCameraView> {
    public enum Events {
        EVENT_CAMERA_READY("onCameraReady"),
                EVENT_ON_MOUNT_ERROR("onMountError"),
                EVENT_ON_PICTURE_TAKEN("onPictureTaken"),
                EVENT_ON_PICTURE_SAVED("onPictureSaved");

        private final String mName;

        Events(final String name) {
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    private static final String REACT_CLASS = "RNCamera";

    @Override
    public void onDropViewInstance(RNCameraView view) {
        view.onHostDestroy();
        super.onDropViewInstance(view);
    }


    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected RNCameraView createViewInstance(ThemedReactContext themedReactContext) {
        return new RNCameraView(themedReactContext);
    }

    @Override
    @Nullable
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        MapBuilder.Builder<String, Object> builder = MapBuilder.builder();
        for (Events event : Events.values()) {
            builder.put(event.toString(), MapBuilder.of("registrationName", event.toString()));
        }
        return builder.build();
    }

    @ReactProp(name = "type")
    public void setType(RNCameraView view, int type) {
        view.setFacing(type);
    }

    @ReactProp(name = "cameraId")
    public void setCameraId(RNCameraView view, String id) {
        view.setCameraId(id);
    }

    @ReactProp(name = "ratio")
    public void setRatio(RNCameraView view, String ratio) {
        view.setAspectRatio(AspectRatio.parse(ratio));
    }

    @ReactProp(name = "flashMode")
    public void setFlashMode(RNCameraView view, int torchMode) {
        view.setFlash(torchMode);
    }

    @ReactProp(name = "exposure")
    public void setExposureCompensation(RNCameraView view, float exposure){
        view.setExposureCompensation(exposure);
    }

    @ReactProp(name = "autoFocus")
    public void setAutoFocus(RNCameraView view, boolean autoFocus) {
        view.setAutoFocus(autoFocus);
    }

    @ReactProp(name = "focusDepth")
    public void setFocusDepth(RNCameraView view, float depth) {
        view.setFocusDepth(depth);
    }

    @ReactProp(name = "autoFocusPointOfInterest")
    public void setAutoFocusPointOfInterest(RNCameraView view, ReadableMap coordinates) {
        if(coordinates != null){
            float x = (float) coordinates.getDouble("x");
            float y = (float) coordinates.getDouble("y");
            view.setAutoFocusPointOfInterest(x, y);
        }
    }

    @ReactProp(name = "zoom")
    public void setZoom(RNCameraView view, float zoom) {
        view.setZoom(zoom);
    }

    @ReactProp(name = "whiteBalance")
    public void setWhiteBalance(RNCameraView view, int whiteBalance) {
        view.setWhiteBalance(whiteBalance);
    }

    @ReactProp(name = "pictureSize")
    public void setPictureSize(RNCameraView view, String size) {
        view.setPictureSize(size.equals("None") ? null : Size.parse(size));
    }

    @ReactProp(name = "playSoundOnCapture")
    public void setPlaySoundOnCapture(RNCameraView view, boolean playSoundOnCapture) {
        view.setPlaySoundOnCapture(playSoundOnCapture);
    }
}