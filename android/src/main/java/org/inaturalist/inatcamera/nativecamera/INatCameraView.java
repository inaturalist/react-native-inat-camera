package org.inaturalist.inatcamera.nativecamera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.widget.FrameLayout;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.inaturalist.inatcamera.R;
import org.inaturalist.inatcamera.classifier.Node;
import org.inaturalist.inatcamera.classifier.Prediction;
import org.inaturalist.inatcamera.ui.Camera2BasicFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class INatCameraView extends FrameLayout implements Camera2BasicFragment.CameraListener {
    private static final String TAG = "INatCameraView";

    public static final String EVENT_NAME_ON_TAXA_DETECTED = "onTaxaDetected";
    public static final String EVENT_NAME_ON_CAMERA_ERROR = "onCameraError";
    public static final String EVENT_NAME_ON_CAMERA_PERMISSION_MISSING = "onCameraPermissionMissing";
    public static final String EVENT_NAME_ON_CLASSIFIER_ERROR = "onClassifierError";
    public static final String EVENT_NAME_ON_DEVICE_NOT_SUPPORTED = "onDeviceNotSupported";

    private static final int DEFAULT_TAXON_DETECTION_INTERVAL = 1000;

    private static final Map<Integer, String> RANK_LEVEL_TO_NAME;
    static {
        Map<Integer, String> map = new HashMap<>() ;

        map.put(100, "root");
        map.put(70, "kingdom");
        map.put(67, "subkingdom");
        map.put(60, "phylum");
        map.put(57, "subphylum");
        map.put(53, "superclass");
        map.put(50, "class");
        map.put(47, "subclass");
        map.put(45, "infraclass");
        map.put(43, "superorder");
        map.put(40, "order");
        map.put(37, "suborder");
        map.put(35, "infraorder");
        map.put(34, "zoosection");
        map.put(33, "superfamily");
        map.put(32, "epifamily");
        map.put(30, "family");
        map.put(27, "subfamily");
        map.put(26, "supertribe");
        map.put(25, "tribe");
        map.put(24, "subtribe");
        map.put(20, "genus");
        map.put(15, "subgenus");
        map.put(13, "section");
        map.put(12, "subsection");
        map.put(10, "species");
        map.put(5, "subspecies");

        RANK_LEVEL_TO_NAME = Collections.unmodifiableMap(map);
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

    @SuppressLint("ResourceType")
    public INatCameraView(Context context, Activity activity) {
        super(context);
        reactContext = (ReactContext) context;

        FrameLayout cameraLayout = (FrameLayout) activity.getLayoutInflater().inflate(R.layout.inat_camera, null);
        this.addView(cameraLayout);

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
    public void onDeviceNotSupported(String reason) {
         Log.e(TAG, "onDeviceNotSupported: " + reason);

        WritableMap event = Arguments.createMap();
        event.putString("reason", reason);
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_DEVICE_NOT_SUPPORTED,
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

        for (Prediction prediction :  predictions) {
            WritableMap result = Arguments.createMap();

            result.putInt("id", Integer.valueOf(prediction.node.key));
            result.putString("name", prediction.node.name);
            result.putDouble("score", prediction.probability);
            result.putInt("rank", prediction.rank);

            // Create the ancestors list for the result
            List<Integer> ancestorsList = new ArrayList<>();
            Node currentNode = prediction.node;
            while (currentNode.parent != null) {
                if (currentNode.parent.key.matches("\\d+")) {
                    ancestorsList.add(Integer.valueOf(currentNode.parent.key));
                }
                currentNode = currentNode.parent;
            }
            Collections.reverse(ancestorsList);
            WritableArray ancestors = Arguments.createArray();
            for (Integer id : ancestorsList) {
                ancestors.pushInt(id);
            }

            result.putArray("ancestor_ids", ancestors);

            if (!ranks.containsKey(prediction.rank)) {
                ranks.put(prediction.rank, Arguments.createArray());
            }

            ranks.get(prediction.rank).pushMap(result);
        }

        // Convert from rank level to rank name
        for (Integer rank : RANK_LEVEL_TO_NAME.keySet()) {
            if (ranks.containsKey(rank)) {
                event.putArray(RANK_LEVEL_TO_NAME.get(rank), ranks.get(rank));
            }
        }

        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_TAXA_DETECTED,
                event);

        mLastPredictionTime = System.currentTimeMillis();
    }
}

