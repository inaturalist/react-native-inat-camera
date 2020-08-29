package org.inaturalist.inatcamera.nativecamera;

import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.bridge.ReactMethod;
import android.widget.Toast;
import org.inaturalist.inatcamera.nativecamera.RNCameraView;

import java.util.Map;

public class INatCameraViewManager extends SimpleViewManager<RNCameraView> {

    private static final String REACT_CLASS = "RCTINatCameraView";

    private ThemedReactContext mContext;

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected RNCameraView createViewInstance(ThemedReactContext reactContext) {
        mContext = reactContext;
        return new RNCameraView(reactContext);
    }

    @ReactProp(name = "filterByTaxonId")
    public void setFilterByTaxonId(RNCameraView view, String taxonId) {
        view.setFilterByTaxonId(taxonId != null ? Integer.valueOf(taxonId) : null);
    }

    @ReactProp(name = "negativeFilter")
    public void setNegativeFilter(RNCameraView view, Boolean negative) {
        view.setNegativeFilter(negative != null ? negative : false);
    }

    @ReactProp(name = "taxaDetectionInterval")
    public void setTaxaDetectionInterval(RNCameraView view, String interval) {
        view.setDetectionInterval(Integer.valueOf(interval));
    }

    @ReactProp(name = "confidenceThreshold")
    public void setConfidenceThreshold(RNCameraView view, String threshold) {
        view.setConfidenceThreshold(Float.valueOf(threshold));
    }

    @ReactProp(name = "modelPath")
    public void setModelPath(RNCameraView view, String path) {
        view.setModelFilename(path);
    }

    @ReactProp(name = "taxonomyPath")
    public void setTaxonomyPath(RNCameraView view, String path) {
        view.setTaxonomyFilename(path);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.of(
                RNCameraView.EVENT_NAME_ON_TAXA_DETECTED,
                (Object) MapBuilder.of("registrationName", RNCameraView.EVENT_NAME_ON_TAXA_DETECTED),
                RNCameraView.EVENT_NAME_ON_CAMERA_ERROR,
                (Object) MapBuilder.of("registrationName", RNCameraView.EVENT_NAME_ON_CAMERA_ERROR),
                RNCameraView.EVENT_NAME_ON_CAMERA_PERMISSION_MISSING,
                (Object) MapBuilder.of("registrationName", RNCameraView.EVENT_NAME_ON_CAMERA_PERMISSION_MISSING),
                RNCameraView.EVENT_NAME_ON_CLASSIFIER_ERROR,
                (Object) MapBuilder.of("registrationName", RNCameraView.EVENT_NAME_ON_CLASSIFIER_ERROR),
                RNCameraView.EVENT_NAME_ON_DEVICE_NOT_SUPPORTED,
                (Object) MapBuilder.of("registrationName", RNCameraView.EVENT_NAME_ON_DEVICE_NOT_SUPPORTED),
                LogEventTree.EVENT_NAME_ON_LOG,
                (Object) MapBuilder.of("registrationName", LogEventTree.EVENT_NAME_ON_LOG)
        );
    }

}

