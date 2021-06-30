package org.inaturalist.inatcamera.nativecamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.CamcorderProfile;
import android.media.MediaActionSound;
import android.os.Build;
import androidx.core.content.ContextCompat;
import android.view.View;
import android.os.AsyncTask;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.ThemedReactContext;
import org.inaturalist.inatcamera.ui.CameraView;
import org.inaturalist.inatcamera.ui.Constants;
import org.inaturalist.inatcamera.ui.AspectRatio;
import org.inaturalist.inatcamera.classifier.ImageClassifier;
import android.graphics.Bitmap;
import org.inaturalist.inatcamera.classifier.Prediction;
import org.inaturalist.inatcamera.classifier.Node;
import android.util.Log;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import android.view.TextureView;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import android.location.Location;
import android.location.LocationManager;
import android.location.Criteria;
import android.os.Bundle;
import android.content.Context;
import com.google.android.gms.common.ConnectionResult;
import java.io.StringWriter;
import java.io.PrintWriter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import timber.log.*;

public class RNCameraView extends CameraView implements LifecycleEventListener, PictureSavedDelegate {
    private static final String TAG = "RNCameraView";

    public static final String EVENT_NAME_ON_TAXA_DETECTED = "onTaxaDetected";
    public static final String EVENT_NAME_ON_CAMERA_ERROR = "onCameraError";
    public static final String EVENT_NAME_ON_CAMERA_PERMISSION_MISSING = "onCameraPermissionMissing";
    public static final String EVENT_NAME_ON_CLASSIFIER_ERROR = "onClassifierError";
    public static final String EVENT_NAME_ON_DEVICE_NOT_SUPPORTED = "onDeviceNotSupported";

    private static final int LAST_PREDICTIONS_COUNT = 5;

    private static final Map<Float, String> RANK_LEVEL_TO_NAME;
    static {
        Map<Float, String> map = new HashMap<>() ;

        map.put(100f, "stateofmatter");
        map.put(70f, "kingdom");
        map.put(67f, "subkingdom");
        map.put(60f, "phylum");
        map.put(57f, "subphylum");
        map.put(53f, "superclass");
        map.put(50f, "class");
        map.put(47f, "subclass");
        map.put(45f, "infraclass");
        map.put(43f, "superorder");
        map.put(40f, "order");
        map.put(37f, "suborder");
        map.put(35f, "infraorder");
        map.put(34.5f, "parvorder");
        map.put(34f, "zoosection");
        map.put(33.5f, "zoosubsection");
        map.put(33f, "superfamily");
        map.put(32f, "epifamily");
        map.put(30f, "family");
        map.put(27f, "subfamily");
        map.put(26f, "supertribe");
        map.put(25f, "tribe");
        map.put(24f, "subtribe");
        map.put(20f, "genus");
        map.put(15f, "subgenus");
        map.put(13f, "section");
        map.put(12f, "subsection");
        map.put(10f, "species");
        map.put(5f, "subspecies");

        RANK_LEVEL_TO_NAME = Collections.unmodifiableMap(map);
    }

    private GoogleApiClient mLocationClient;
    private ThemedReactContext mThemedReactContext;
    private Queue<Promise> mPictureTakenPromises = new ConcurrentLinkedQueue<>();
    private Map<Promise, ReadableMap> mPictureTakenOptions = new ConcurrentHashMap<>();
    private Map<Promise, File> mPictureTakenDirectories = new ConcurrentHashMap<>();
    private Promise mVideoRecordedPromise;
    private Boolean mPlaySoundOnCapture = false;

    private boolean mIsPaused = false;
    private boolean mIsNew = true;
    private boolean invertImageData = false;
    private Boolean mIsRecording = false;
    private Boolean mIsRecordingInterrupted = false;
    private List<Prediction> mLastPredictions = new ArrayList<>();

    private String mModelFilename;
    private String mTaxonomyFilename;
    private boolean mDeviceSupported;

    // Reasons why the device is not supported
    private static final int REASON_DEVICE_SUPPORTED = 0;
    private static final int REASON_DEVICE_NOT_SUPPORTED = 1;
    private static final int REASON_OS_TOO_OLD = 2;
    private static final int REASON_NOT_ENOUGH_MEMORY = 3;
    // TODO: Add more reasons (e.g. graphic card, ...)

    private final Object lock = new Object();
    private boolean mRunClassifier = false;
    private ImageClassifier mClassifier;
    private long mLastErrorTime = 0;
    Boolean mLinneanPredictionsOnly = true;

    public static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.7f;
    private static final int DEFAULT_TAXON_DETECTION_INTERVAL = 1000;

    private int mTaxaDetectionInterval = DEFAULT_TAXON_DETECTION_INTERVAL;
    private long mLastPredictionTime = 0;
    private float mConfidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;

    private Integer mFilterByTaxonId = null; // If null -> no filter by taxon ID defined
    private boolean mNegativeFilter = false;

    public void setFilterByTaxonId(Integer taxonId) {
        mFilterByTaxonId = taxonId;
        if (mClassifier != null) {
            mClassifier.setFilterByTaxonId(mFilterByTaxonId);
        }
    }

    public void setNegativeFilter(boolean negative) {
        mNegativeFilter = negative;
        if (mClassifier != null) {
            mClassifier.setNegativeFilter(mNegativeFilter);
        }
    }

    public void setConfidenceThreshold(float confidence) {
        mConfidenceThreshold = confidence;
    }

    public float getConfidenceThreshold() {
        return mConfidenceThreshold;
    }


    public void setDetectionInterval(int interval) {
        mTaxaDetectionInterval = interval;
    }

    public void setModelFilename(String filename) {
        mModelFilename = filename;
    }

    public void setTaxonomyFilename(String filename) {
        mTaxonomyFilename = filename;
    }


    public RNCameraView(ThemedReactContext themedReactContext) {
        super(themedReactContext, true);
        mThemedReactContext = themedReactContext;
        themedReactContext.addLifecycleEventListener(this);

        Timber.plant(new LogEventTree(themedReactContext, this));

        addCallback(new Callback() {
            @Override
            public void onMountError(CameraView cameraView, Exception exc) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                exc.printStackTrace(pw);
                String stackTrace = sw.toString(); // Stack trace as a string

                onCameraError(stackTrace);
            }

            @Override
            public void onPictureTaken(CameraView cameraView, final byte[] data, int deviceOrientation) {
                Timber.tag(TAG).d("onPictureTaken");

                Promise promise = mPictureTakenPromises.poll();
                if (promise == null) {
                    return;
                }
                ReadableMap options = mPictureTakenOptions.remove(promise);
                if (options.hasKey("fastMode") && options.getBoolean("fastMode")) {
                    promise.resolve(null);
                }
                final File cacheDirectory = mPictureTakenDirectories.remove(promise);
                new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, deviceOrientation, RNCameraView.this, RNCameraView.this)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }

            @Override
            public void onFramePreview(CameraView cameraView, byte[] data, int width, int height, int rotation) {
                int correctRotation = RNCameraViewHelper.getCorrectCameraRotation(rotation, getFacing(), getCameraOrientation());
            }
        });

        // Initialize location client
        getLocation();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View preview = getView();
        if (null == preview) {
            return;
        }
        float width = right - left;
        float height = bottom - top;
        AspectRatio aspectRatio = getAspectRatio();
        if (aspectRatio == null) aspectRatio = Constants.DEFAULT_ASPECT_RATIO;
        float ratio = aspectRatio.toFloat();
        int orientation = getResources().getConfiguration().orientation;
        int correctHeight;
        int correctWidth;
        this.setBackgroundColor(Color.BLACK);
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            if (ratio * height < width) {
                correctHeight = (int) (width / ratio);
                correctWidth = (int) width;
            } else {
                correctWidth = (int) (height * ratio);
                correctHeight = (int) height;
            }
        } else {
            if (ratio * width > height) {
                correctHeight = (int) (width * ratio);
                correctWidth = (int) width;
            } else {
                correctWidth = (int) (height / ratio);
                correctHeight = (int) height;
            }
        }
        int paddingX = (int) ((width - correctWidth) / 2);
        int paddingY = (int) ((height - correctHeight) / 2);
        preview.layout(paddingX, paddingY, correctWidth + paddingX, correctHeight + paddingY);
    }

    @SuppressLint("all")
    @Override
    public void requestLayout() {
        // React handles this for us, so we don't need to call super.requestLayout();
    }

    public void setPlaySoundOnCapture(Boolean playSoundOnCapture) {
        mPlaySoundOnCapture = playSoundOnCapture;
    }

    public void takePicture(final ReadableMap options, final Promise promise, final File cacheDirectory) {
        Timber.tag(TAG).d("takePicture 1");
        mBgHandler.post(new Runnable() {
            @Override
            public void run() {
                Timber.tag(TAG).d("takePicture 2");
                mPictureTakenPromises.add(promise);
                mPictureTakenOptions.put(promise, options);
                mPictureTakenDirectories.put(promise, cacheDirectory);
                if (mPlaySoundOnCapture) {
                    MediaActionSound sound = new MediaActionSound();
                    sound.play(MediaActionSound.SHUTTER_CLICK);
                }
                try {
                    Timber.tag(TAG).d("takePicture 3");
                    RNCameraView.super.takePicture(options);
                    if (options.hasKey("pauseAfterCapture") && options.getBoolean("pauseAfterCapture")) {
                        synchronized (lock) {
                            mRunClassifier = false;
                        }
                    }
                } catch (Exception e) {
                    mPictureTakenPromises.remove(promise);
                    mPictureTakenOptions.remove(promise);
                    mPictureTakenDirectories.remove(promise);

                    promise.reject("E_TAKE_PICTURE_FAILED", e.getMessage());
                }
            }
        });
    }

    @Override
    public void onPictureSaved(WritableMap response) {
    }

    @Override
    public void onHostResume() {
        Timber.tag(TAG).d("RNCameraView - onHostResume 1");

        if (hasCameraPermissions()) {
            Timber.tag(TAG).d("RNCameraView - onHostResume 2");
            mBgHandler.post(new Runnable() {
                @Override
                public void run() {
                    Timber.tag(TAG).d("RNCameraView - onHostResume 3 - " + mIsPaused + ":" + isCameraOpened() + ":" + mIsNew);
                    if ((mIsPaused && !isCameraOpened()) || mIsNew) {
                        mIsPaused = false;
                        mIsNew = false;
                        try {
                            Timber.tag(TAG).d("RNCameraView - onHostResume 4");
                            start();
                        } catch (RuntimeException exc) {
                            Timber.tag(TAG).d("RNCameraView - onHostResume 5 - " + exc);
                            WritableMap event = Arguments.createMap();
                            event.putString("error", exc.getMessage());
                            mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                                    getId(),
                                    EVENT_NAME_ON_CAMERA_ERROR,
                                    event);
                        }
                    }
                }
            });
        } else {
            onCameraPermissionMissing();
        }

        // Maybe should be called once and not every onResume?
        if (mModelFilename == null) {
            Timber.tag(TAG).d("RNCameraView - onHostResume 6");
            return;
        }

        int reason = checkForSupportedDevice();

        mDeviceSupported = (reason == REASON_DEVICE_SUPPORTED);

        if (!mDeviceSupported) {
            Timber.tag(TAG).w("Device not supported - not running classifier");

            onDeviceNotSupported(deviceNotSupportedReasonToString(reason));

            return;
        }

        if (mClassifier == null) {
            try {
                mClassifier = new ImageClassifier(mModelFilename, mTaxonomyFilename);
                mClassifier.setFilterByTaxonId(mFilterByTaxonId);
                mClassifier.setNegativeFilter(mNegativeFilter);
            } catch (IOException e) {
                e.printStackTrace();
                onClassifierError("Failed to initialize an image mClassifier: " + e.getMessage());
            } catch (OutOfMemoryError e) {
                e.printStackTrace();
                Timber.tag(TAG).w("Out of memory - Device not supported - classifier failed to load - " + e);
                onDeviceNotSupported(deviceNotSupportedReasonToString(REASON_NOT_ENOUGH_MEMORY));
                return;
            } catch (Exception e) {
                e.printStackTrace();
                Timber.tag(TAG).w("Other type of exception - Device not supported - classifier failed to load - " + e);
                onDeviceNotSupported(deviceNotSupportedReasonToString(REASON_DEVICE_NOT_SUPPORTED));
                return;
            }
        }

        synchronized (lock) {
            mRunClassifier = true;
        }

        Timber.tag(TAG).d("RNCameraView - onHostResume 7");
        mBgHandler.post(periodicClassify);
    }

    @Override
    public void onHostPause() {
        if (mIsRecording) {
            mIsRecordingInterrupted = true;
        }
        if (!mIsPaused && isCameraOpened()) {
            mIsPaused = true;
            stop();
        }

        synchronized (lock) {
            mRunClassifier = false;
        }
    }

    @Override
    public void onHostDestroy() {
        stop();
        mThemedReactContext.removeLifecycleEventListener(this);

        this.cleanup();

        if (mClassifier != null) {
            mClassifier.close();
        }

        synchronized (lock) {
            mRunClassifier = false;
        }
    }

    @Override
    public void pausePreview() {
        super.pausePreview();
        synchronized (lock) {
            mRunClassifier = false;
        }
    }

    @Override
    public void resumePreview() {
        super.resumePreview();
        synchronized (lock) {
            mRunClassifier = true;
        }
    }

    private boolean hasCameraPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
            return result == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }


    /** Takes photos and classify them periodically. */
    private Runnable periodicClassify =
            new Runnable() {
                @Override
                public void run() {
                    long timePassed = System.currentTimeMillis() - mLastPredictionTime;
                    if (timePassed < mTaxaDetectionInterval) {
                        // Make sure we don't run the image classification too often
                        try {
                            Thread.sleep(mTaxaDetectionInterval - timePassed);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    synchronized (lock) {
                        if (mRunClassifier) {
                            classifyFrame();
                        }
                    }

                    mLastPredictionTime = System.currentTimeMillis();

                    mBgHandler.post(periodicClassify);

                }
            };


    /** Classifies a frame from the preview stream. */
    private void classifyFrame() {
        if (mClassifier == null) {
            return;
        }
        TextureView textureView = getTextureView();
        Bitmap bitmap = null;
        try {
            bitmap = textureView.getBitmap(ImageClassifier.DIM_IMG_SIZE_X, ImageClassifier.DIM_IMG_SIZE_Y);
        } catch (Exception exc) {
            Timber.tag(TAG).e("TextureView.getBitmap exception: " + exc);
            Timber.tag(TAG).e(exc);
            return;
        }

        if (bitmap == null) {
            Timber.tag(TAG).e("Null input bitmap");
            return;
        }

        List<Prediction> predictions = null;

        try {
            predictions = mClassifier.classifyFrame(bitmap);
            bitmap.recycle();
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            Timber.tag(TAG).w("classifyFrame - Out of memory - " + e);
            Timber.tag(TAG).e(e);
            return;
        }

        // Return only one prediction, as accurate as possible (e.g. prefer species over family), that passes the minimal threshold
        Prediction selectedPrediction = null;

        Collections.reverse(predictions);
        for (Prediction prediction : predictions) {
            if (mLinneanPredictionsOnly) {
                // only KPCOFGS ranks qualify as "top" predictions
                // in the iNat taxonomy, KPCOFGS ranks are 70,60,50,40,30,20,10
                if (prediction.rank % 10 != 0) {
                    continue;
                }
            }
            if (prediction.probability > mConfidenceThreshold) {
                selectedPrediction = prediction;
                break;
            }
        }

        if (selectedPrediction != null) {
            onTaxaDetected(selectedPrediction);
        }
    }

    /** Retrieves predictions for a single frame */
    public List<Prediction> getPredictionsForImage(Bitmap bitmap) {
        if (mClassifier == null) {
            Timber.tag(TAG).e("getPredictionsForImage - classifier is null!");
            return new ArrayList<Prediction>();
        }

        // Resize bitmap to the size the classifier supports
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, ImageClassifier.DIM_IMG_SIZE_X, ImageClassifier.DIM_IMG_SIZE_Y, true);

        List<Prediction> predictions = mClassifier.classifyFrame(resizedBitmap);
        resizedBitmap.recycle();

        return predictions;
    }


    private int checkForSupportedDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // OS version is too old
            return REASON_OS_TOO_OLD;
        }

        // If we reached here - that means the device is supported
        return REASON_DEVICE_SUPPORTED;
    }

    private String deviceNotSupportedReasonToString(int reason) {
        switch (reason) {
            case REASON_DEVICE_NOT_SUPPORTED:
                return "Device is too old";
            case REASON_OS_TOO_OLD:
                return "Android version is too old - needs to be at least 6.0";
            case REASON_NOT_ENOUGH_MEMORY:
                return "Not enough memory";
        }

        return null;
    }

    void onTaxaDetected(Prediction prediction) {
        // Convert Prediction into a structure separating by rank name
        List<Prediction> predictions = Arrays.asList(prediction);
        WritableMap event = predictionsToMap(predictions);

        mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_TAXA_DETECTED,
                event);

        mLastPredictions.add(prediction);

        if (mLastPredictions.size() > LAST_PREDICTIONS_COUNT) {
            mLastPredictions.remove(0);
        }

    }

    void onCameraError(String error) {
        Timber.tag(TAG).e("onCameraError: " + error);

        if (System.currentTimeMillis() - mLastErrorTime < 5000) {
            // Make sure we don't "bombard" the React Native code with too many callbacks (slows
            // down the UI significantly)
            return;
        }

        WritableMap event = Arguments.createMap();
        event.putString("error", error);
        mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_CAMERA_ERROR,
                event);

        mLastErrorTime = System.currentTimeMillis();
    }

    void onCameraPermissionMissing() {
        Timber.tag(TAG).e("onCameraPermissionMissing");
        WritableMap event = Arguments.createMap();

        mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_CAMERA_PERMISSION_MISSING,
                event);
    }

    void onClassifierError(String error) {
        Timber.tag(TAG).e("onClassifierError: " + error);

        if (System.currentTimeMillis() - mLastErrorTime < 5000) {
            // Make sure we don't "bombard" the React Native code with too many callbacks (slows
            // down the UI significantly)
            return;
        }

        WritableMap event = Arguments.createMap();
        event.putString("error", error);
        mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_CLASSIFIER_ERROR,
                event);

        mLastErrorTime = System.currentTimeMillis();
    }

    void onDeviceNotSupported(String reason) {
        Timber.tag(TAG).e("onDeviceNotSupported: " + reason);

        WritableMap event = Arguments.createMap();
        event.putString("reason", reason);
        mThemedReactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_DEVICE_NOT_SUPPORTED,
                event);
    }


    /** Converts the predictions array into "clean" map of results (separated by rank), sent back to React Native */
    private WritableMap predictionsToMap(Collection<Prediction> predictions) {
        WritableMap event = Arguments.createMap();

        Map<Float, WritableArray> ranks = new HashMap<>();

        for (Prediction prediction : predictions) {
            if (prediction == null) continue;
            WritableMap result = nodeToMap(prediction);
            if (result == null) continue;

            if (!ranks.containsKey(prediction.node.rank)) {
                ranks.put(prediction.node.rank, Arguments.createArray());
            }

            ranks.get(prediction.node.rank).pushMap(result);
        }

        // Convert from rank level to rank name
        for (Float rank : RANK_LEVEL_TO_NAME.keySet()) {
            if (ranks.containsKey(rank)) {
                event.putArray(RANK_LEVEL_TO_NAME.get(rank), ranks.get(rank));
            }
        }

        return event;
    }

    /** Converts a prediction result to a map */
    public static WritableMap nodeToMap(Prediction prediction) {
        WritableMap result = Arguments.createMap();

        if (prediction.node == null) return null;

        try {
            result.putInt("taxon_id", Integer.valueOf(prediction.node.key));
            result.putString("name", prediction.node.name);
            result.putDouble("score", prediction.probability);
            result.putDouble("rank", prediction.node.rank);
        } catch (NumberFormatException exc) {
            // Invalid node key or class ID
            exc.printStackTrace();
            return null;
        }

        // Create the ancestors list for the result
        List<Integer> ancestorsList = new ArrayList<>();
        Node currentNode = prediction.node;
        while (currentNode.parent != null) {
            if ((currentNode.parent.key != null) && (currentNode.parent.key.matches("\\d+"))) {
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

        return result;
    }

    public void fillResults(WritableMap response, List<Prediction> predictions) {
        if (predictions == null || mLastPredictions == null || response == null) {
            return;
        }

        WritableArray results = Arguments.createArray();

        boolean hasGoodPrediction = false; // Whether or not the current result set has a species-level prediction with good enough confidence

        for (Prediction prediction : predictions) {
            WritableMap map = nodeToMap(prediction);
            if (map == null) continue;

            results.pushMap(map);

            if ((prediction.node.rank <= 10) && (prediction.probability > mConfidenceThreshold)) {
                hasGoodPrediction = true;
            }
        }

        Prediction predictionAdded = null;

        if (!hasGoodPrediction) {
            // No good prediction (=species rank and high enough confidence level) - add a good one from last remembered predictions
            for (int i = mLastPredictions.size() - 1; i >= 0; i--) {
                Prediction prediction = mLastPredictions.get(i);

                if ((prediction.node.rank <= 10) && (prediction.probability > mConfidenceThreshold)) {
                    predictionAdded = prediction;
                    break;
                }
            }
        }

        if (predictionAdded != null) {
            // Make sure to remove the less precise prediction of the same rank
            WritableArray results2 = Arguments.createArray();
            for (Prediction prediction : predictions) {
                if (predictionAdded.node.rank == prediction.node.rank) {
                    // Add the new, more precise prediction instead of this one
                    WritableMap map = nodeToMap(predictionAdded);
                    if (map == null) continue;
                    results2.pushMap(map);
                } else {
                    WritableMap map = nodeToMap(prediction);
                    if (map == null) continue;
                    results2.pushMap(map);
                }
            }

            results = results2;
        }

        response.putArray("predictions", results);
    }


    private Location getLocationFromGPS() {
        Timber.tag(TAG).d("getLocationFromGPS");

        LocationManager locationManager = (LocationManager) mThemedReactContext.getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        String provider = locationManager.getBestProvider(criteria, false);
        if (provider == null) return null;

        Location location = locationManager.getLastKnownLocation(provider);
        Timber.tag(TAG).d("getLocationFromGPS: " + location);

        return location;
    }

    private Location getLastKnownLocationFromClient() {
        Location location = null;

        Timber.tag(TAG).d("getLastKnownLocationFromClient");

        try {
            location = LocationServices.FusedLocationApi.getLastLocation(mLocationClient);
        } catch (IllegalStateException ex) {
            ex.printStackTrace();
        }

        Timber.tag(TAG).d("getLastKnownLocationFromClient: " + location);
        if (location == null) {
            // Failed - try and return last place using GPS
            return getLocationFromGPS();
        } else {
            return location;
        }
    }

    public Location getLocation() {
        int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(mThemedReactContext);

        Timber.tag(TAG).d("getLocation: isAvailable = " + resultCode);


        if (resultCode == ConnectionResult.SUCCESS) {
            Timber.tag(TAG).d("getLocation: Connected already");
            // User Google Play services if available
            if ((mLocationClient != null) && (mLocationClient.isConnected())) {
                // Location client already initialized and connected - use it
                return getLastKnownLocationFromClient();
            } else {
                // Connect to the place services
                Timber.tag(TAG).d("getLocation: Connecting to client");
                mLocationClient = new GoogleApiClient.Builder(mThemedReactContext)
                        .addApi(LocationServices.API)
                        .addConnectionCallbacks(new ConnectionCallbacks() {
                            @Override
                            public void onConnected(Bundle bundle) {
                                // Connected successfully
                                Timber.tag(TAG).d("getLocation: Connected");
                            }

                            @Override
                            public void onConnectionSuspended(int i) { }
                        })
                        .addOnConnectionFailedListener(new OnConnectionFailedListener() {
                            @Override
                            public void onConnectionFailed(ConnectionResult connectionResult) {
                                Timber.tag(TAG).d("getLocation: Connection failed");
                                mLocationClient.disconnect();
                            }
                        })
                        .build();
                mLocationClient.connect();

                return null;
            }

        } else {
            Timber.tag(TAG).d("getLocation: Getting from GPS");
            // Use GPS alone for place
            return getLocationFromGPS();
        }
    }

}
