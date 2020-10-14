package org.inaturalist.inatcamera.nativecamera;

import java.text.ParseException;
import android.annotation.SuppressLint;
import android.util.Log;
import java.util.Date;
import java.text.SimpleDateFormat;
import org.inaturalist.inatcamera.classifier.ImageClassifier;
import android.graphics.Bitmap;
import java.io.File;
import java.util.List;
import java.io.IOException;
import android.os.Environment;
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
import timber.log.*;
import com.facebook.react.uimanager.events.RCTEventEmitter;

public class INatCameraModule extends ReactContextBaseJavaModule {
    private static final String TAG = "INatCameraModule";

    public static final String OPTION_URI = "uri";
    public static final String OPTION_TAXONOMY_FILENAME = "taxonomyFilename";
    public static final String OPTION_MODEL_FILENAME = "modelFilename";
    public static final String OPTION_OFFLINE_FREQUENCY_FILENAME = "offlineFrequencyFilename";
    public static final String OPTION_FREQUENCY_DATE = "offlineFrequencyDate";
    public static final String OPTION_FREQUENCY_LOCATION = "offlineFrequencyLocation";

    private ReactApplicationContext mContext;

    public INatCameraModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mContext = reactContext;

        Timber.plant(new Timber.DebugTree());
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
                RNCameraView cameraView = (RNCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                try {
                    cameraView.pausePreview();
                    WritableMap result = Arguments.createMap();
                    promise.resolve(result);
                } catch (Exception e) {
                    promise.reject("stopCamera: Expected a Camera component");
                }
            }
        });
    }

    @ReactMethod
    public void takePictureAsync(final ReadableMap options, final int viewTag, final Promise promise) {
        Timber.tag(TAG).d("takePictureAsync 1");
        final ReactApplicationContext context = getReactApplicationContext();
        UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
        uiManager.addUIBlock(new UIBlock() {
            @Override
            public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
                Timber.tag(TAG).d("takePictureAsync 2");
                RNCameraView cameraView = (RNCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                Timber.tag(TAG).d("takePictureAsync 3 - " + cameraView);
                try {
                    File cacheDirectory = mContext.getCacheDir();
                    cameraView.takePicture(options, promise, cacheDirectory);
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
                RNCameraView cameraView = (RNCameraView) nativeViewHierarchyManager.resolveView(viewTag);
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
        String offlineFrequencyFilename = options.getString(OPTION_OFFLINE_FREQUENCY_FILENAME);
        SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd");
        Date offlineFrequencyDate = null;
        Float offlineFrequencyLatitude = null;
        Float offlineFrequencyLongitude = null;
        String dateString = options.getString(OPTION_FREQUENCY_DATE);
        String location = options.getString(OPTION_FREQUENCY_LOCATION);
        if ((dateString != null) && (location != null)) {
            String[] parts = location.split(",");
            String latString = parts[0];
            String lngString = parts[1];
            try {
                offlineFrequencyDate = format.parse(dateString);
            } catch (ParseException e) {
                e.printStackTrace();
            }
            offlineFrequencyLatitude = Float.valueOf(latString);
            offlineFrequencyLongitude = Float.valueOf(lngString);
        }

        ImageClassifier classifier = null;

        try {
            classifier = new ImageClassifier(modelFilename, taxonomyFilename, offlineFrequencyFilename);
            if ((offlineFrequencyDate != null) && (offlineFrequencyLatitude != null) && (offlineFrequencyLongitude != null)) {
                classifier.setFrequencyDate(offlineFrequencyDate);
                classifier.setFrequencyLocation(offlineFrequencyLatitude, offlineFrequencyLongitude);
            }
        } catch (IOException e) {
            e.printStackTrace();
            promise.reject("E_CLASSIFIER", "Failed to initialize an image mClassifier: " + e.getMessage());
            return;
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            Timber.tag(TAG).w("Out of memory - Device not supported - classifier failed to load - " + e);
            promise.reject("E_OUT_OF_MEMORY", "Out of memory");
            return;
        } catch (Exception e) {
            e.printStackTrace();
            Timber.tag(TAG).w("Other type of exception - Device not supported - classifier failed to load - " + e);
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
            WritableMap map = RNCameraView.nodeToMap(prediction);
            if (map == null) continue;

            results.pushMap(map);
        }

        result.putArray("predictions", results);

        promise.resolve(result);
    }

}

