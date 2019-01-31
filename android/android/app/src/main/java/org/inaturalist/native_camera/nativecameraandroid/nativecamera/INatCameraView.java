package org.inaturalist.native_camera.nativecameraandroid.nativecamera;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.inaturalist.native_camera.nativecameraandroid.R;
import org.inaturalist.native_camera.nativecameraandroid.classifier.Prediction;
import org.inaturalist.native_camera.nativecameraandroid.ui.Camera2BasicFragment;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class INatCameraView extends FrameLayout implements Camera2BasicFragment.CameraListener {
    public static final String TAG = "INatCameraView";

    public static final String EVENT_NAME_ON_TAXA_DETECTED = "onTaxaDetected";
    public static final String EVENT_NAME_ON_CAMERA_ERROR = "onCameraError";
    public static final String EVENT_NAME_ON_CAMERA_PERMISSION_MISSING = "onCameraPermissionMissing";
    public static final String EVENT_NAME_ON_CLASSIFIER_ERROR = "onClassifierError";

    private static final int DEFAULT_TAXON_DETECTION_INTERVAL = 1000;

    private static final Map<Integer, String> RANK_NUMBER_TO_NAME;
    static {
        Map<Integer, String> map = new HashMap<>() ;
        map.put(0, "kingdom");
        map.put(1, "phylum");
        map.put(2, "class");
        map.put(3, "order");
        map.put(4, "family");
        map.put(5, "genus");
        map.put(6, "species");
        RANK_NUMBER_TO_NAME = Collections.unmodifiableMap(map);
    }

    private Camera2BasicFragment mCameraFragment;

    private ReactContext reactContext;

    private int mTaxaDetectionInterval = DEFAULT_TAXON_DETECTION_INTERVAL;
    private long mLastErrorTime = 0;
    private long mLastPredictionTime = 0;

    public INatCameraView(Context context) {
        super(context);
        reactContext = (ReactContext) context;
    }

    public INatCameraView(Context context, Activity activity) {
        super(context);
        reactContext = (ReactContext) context;

        FrameLayout counterLayout = (FrameLayout) activity.getLayoutInflater().inflate(R.layout.inat_camera, null);
        this.addView(counterLayout);

        mCameraFragment = new Camera2BasicFragment();
        mCameraFragment.setOnCameraErrorListener(this);

        activity.getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, mCameraFragment)
                .commit();
    }

    public void setModelPath(String path) {
        mCameraFragment.setModelFilename(path);
    }

    public void setTaxonomyPath(String path) {
        mCameraFragment.setTaxonomyFilename(path);
    }

    public void setTaxaDetectionInterval(int interval) {
        mTaxaDetectionInterval = interval;
    }

    @Override
    public void requestLayout() {
        super.requestLayout();
        post(measureAndLayout);
    }

    private final Runnable measureAndLayout = new Runnable() {
        @Override
        public void run() {
            measure(
                    MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY));
            layout(getLeft(), getTop(), getRight(), getBottom());
        }
    };

    @Override
    public void onCameraError(String error) {
        Log.e(TAG, "onCameraError: " + error);

        if (System.currentTimeMillis() - mLastErrorTime < 5000) {
            // Make sure we don't "bombard" the React Native code with too many callbacks (slows
            // down the UI significantly)
            return;
        }

        WritableMap event = Arguments.createMap();
        event.putString("error", error);
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_CAMERA_ERROR,
                event);

        mLastErrorTime = System.currentTimeMillis();
    }

    @Override
    public void onCameraPermissionMissing() {
        Log.e(TAG, "onCameraPermissionMissing");
        WritableMap event = Arguments.createMap();

        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_CAMERA_PERMISSION_MISSING,
                event);
    }

    @Override
    public void onClassifierError(String error) {
         Log.e(TAG, "onClassifierError: " + error);

        if (System.currentTimeMillis() - mLastErrorTime < 5000) {
            // Make sure we don't "bombard" the React Native code with too many callbacks (slows
            // down the UI significantly)
            return;
        }

        WritableMap event = Arguments.createMap();
        event.putString("error", error);
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_CLASSIFIER_ERROR,
                event);

        mLastErrorTime = System.currentTimeMillis();
    }

    @Override
    public void onTaxaDetected(Collection<Prediction> predictions) {
        if (System.currentTimeMillis() - mLastPredictionTime < mTaxaDetectionInterval) {
            // Make sure we don't call this callback too often
            return;
        }

        Log.d(TAG, "onTaxaDetected: " + predictions.size());

        // Convert list of predictions into a structure separating results by rank

        WritableMap event = Arguments.createMap();

        Map<Integer, WritableArray> ranks = new HashMap<>();

        for (Integer rank : RANK_NUMBER_TO_NAME.keySet()) {
            WritableArray array = Arguments.createArray();
            ranks.put(rank, array);
        }

        for (Prediction prediction :  predictions) {
            WritableMap result = Arguments.createMap();

            result.putInt("id", Integer.valueOf(prediction.node.key));
            result.putString("name", prediction.node.name);
            result.putDouble("score", prediction.probability);

            ranks.get(prediction.rank).pushMap(result);
        }

        for (Integer rank : RANK_NUMBER_TO_NAME.keySet()) {
            event.putArray(RANK_NUMBER_TO_NAME.get(rank), ranks.get(rank));
        }

        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_TAXA_DETECTED,
                event);

        mLastPredictionTime = System.currentTimeMillis();
    }
}

