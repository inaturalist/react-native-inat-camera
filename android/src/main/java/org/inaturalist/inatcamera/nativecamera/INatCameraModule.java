package org.inaturalist.inatcamera.nativecamera;

import android.annotation.SuppressLint;
import android.util.Log;

import org.inaturalist.inatcamera.classifier.ImageClassifier;
import android.graphics.Bitmap;
import java.util.List;
import java.io.IOException;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.uimanager.NativeViewHierarchyManager;
import com.facebook.react.uimanager.UIBlock;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Promise;
import android.graphics.BitmapFactory;
import org.inaturalist.inatcamera.classifier.Prediction;

public class INatCameraModule extends ReactContextBaseJavaModule {
    private static final String TAG = "INatCameraModule";

    public static final String OPTION_URI = "uri";
    public static final String OPTION_TAXONOMY_FILENAME = "taxonomyFilename";
    public static final String OPTION_MODEL_FILENAME = "modelFilename";

    private ReactApplicationContext mContext;

    public INatCameraModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mContext = reactContext;
    }

    @Override
    public String getName() {
        return TAG;
    }


    @ReactMethod
    public void stopCamera(final int viewTag, final Promise promise) {
        final ReactApplicationContext context = getReactApplicationContext();
        UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
        uiManager.addUIBlock(new UIBlock() {
            @Override
            public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
                INatCameraView cameraView = (INatCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                try {
                    cameraView.stopCamera(promise);
                } catch (Exception e) {
                    promise.reject("stopCamera: Expected a Camera component");
                }
            }
        });
    }

    @ReactMethod
    public void takePictureAsync(final ReadableMap options, final int viewTag, final Promise promise) {
        final ReactApplicationContext context = getReactApplicationContext();
        UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
        uiManager.addUIBlock(new UIBlock() {
            @Override
            public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
                INatCameraView cameraView = (INatCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                try {
                    cameraView.takePictureAsync(options, promise);
                } catch (Exception e) {
                    promise.reject("takePictureAsync: Expected a Camera component");
                }
            }
        });
    }

    @ReactMethod
    public void resumePreview(final int viewTag) {
        final ReactApplicationContext context = getReactApplicationContext();
        UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
        uiManager.addUIBlock(new UIBlock() {
            @Override
            public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
                INatCameraView cameraView = (INatCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                try {
                    cameraView.resumePreview();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @ReactMethod
    public void getPredictionsForImage(ReadableMap options, Promise promise) {

        if (!options.hasKey(OPTION_URI) || !options.hasKey(OPTION_MODEL_FILENAME) || !options.hasKey(OPTION_TAXONOMY_FILENAME)) {
            promise.reject("E_MISSING_ARGS", String.format("Missing one or more arguments: %s, %s, %s", OPTION_URI, OPTION_MODEL_FILENAME, OPTION_TAXONOMY_FILENAME));
            return;
        }

        String uri = options.getString(OPTION_URI);
        String modelFilename = options.getString(OPTION_MODEL_FILENAME);
        String taxonomyFilename = options.getString(OPTION_TAXONOMY_FILENAME);

        ImageClassifier classifier = null;

        try {
            classifier = new ImageClassifier(modelFilename, taxonomyFilename);
        } catch (IOException e) {
            e.printStackTrace();
            promise.reject("E_CLASSIFIER", "Failed to initialize an image mClassifier: " + e.getMessage());
            return;
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            Log.w(TAG, "Out of memory - Device not supported - classifier failed to load - " + e);
            promise.reject("E_OUT_OF_MEMORY", "Out of memory");
            return;
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Other type of exception - Device not supported - classifier failed to load - " + e);
            promise.reject("E_UNSUPPORTED_DEVICE", "Android version is too old - needs to be at least 6.0");
            return;
        }

        // Get predictions for that image
        Bitmap bitmap = null;

        try {
            // Read bitmap file
            bitmap = BitmapFactory.decodeFile(uri);
            // Resize to expected classifier input size
            Bitmap rescaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    ImageClassifier.DIM_IMG_SIZE_X,
                    ImageClassifier.DIM_IMG_SIZE_Y,
                    false);
            bitmap.recycle();
            bitmap = rescaledBitmap;
        } catch (Exception e) {
            e.printStackTrace();
            promise.reject("E_IO_EXCEPTION", "Couldn't read input file: " + uri + "; Exception: " + e);
            return;
        }

        List<Prediction> predictions = classifier.classifyFrame(bitmap);
        bitmap.recycle();

        // Return both photo URI and predictions

        WritableMap result = Arguments.createMap();

        WritableArray results = Arguments.createArray();

        for (Prediction prediction : predictions) {
            WritableMap map = INatCameraView.nodeToMap(prediction);
            if (map == null) continue;

            results.pushMap(map);
        }

        result.putArray("predictions", results);

        promise.resolve(result);
    }

}

