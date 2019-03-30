package org.inaturalist.inatcamera.nativecamera;

import android.view.WindowManager;
import android.util.SparseIntArray;
import android.view.Surface;
import android.content.res.Configuration;
import android.os.Environment;
import java.text.SimpleDateFormat;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import java.util.Calendar;
import android.support.media.ExifInterface;

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
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;

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
import java.util.Arrays;  

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.location.Criteria;
import com.google.android.gms.common.ConnectionResult;
 

public class INatCameraView extends FrameLayout implements Camera2BasicFragment.CameraListener {
    private static final String TAG = "INatCameraView";

    public static final String OPTION_PAUSE_AFTER_CAPTURE = "pauseAfterCapture";

    public static final String EVENT_NAME_ON_TAXA_DETECTED = "onTaxaDetected";
    public static final String EVENT_NAME_ON_CAMERA_ERROR = "onCameraError";
    public static final String EVENT_NAME_ON_CAMERA_PERMISSION_MISSING = "onCameraPermissionMissing";
    public static final String EVENT_NAME_ON_CLASSIFIER_ERROR = "onClassifierError";
    public static final String EVENT_NAME_ON_DEVICE_NOT_SUPPORTED = "onDeviceNotSupported";

    private static final int DEFAULT_TAXON_DETECTION_INTERVAL = 1000;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final Map<Integer, String> RANK_LEVEL_TO_NAME;
    static {
        Map<Integer, String> map = new HashMap<>() ;

        map.put(100, "stateofmatter");
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


        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 270);
        ORIENTATIONS.append(Surface.ROTATION_180, 90);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private GoogleApiClient mLocationClient;

    private Camera2BasicFragment mCameraFragment;

    private ReactContext reactContext;

    private int mTaxaDetectionInterval = DEFAULT_TAXON_DETECTION_INTERVAL;
    private long mLastErrorTime = 0;
    private long mLastPredictionTime = 0;
    private Activity mActivity = null;

    private boolean mReplacedFragment = false;

    public INatCameraView(Context context) {
        super(context);
        reactContext = (ReactContext) context;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (!mReplacedFragment) {
            mReplacedFragment = true;

            FrameLayout cameraLayout = (FrameLayout) mActivity.getLayoutInflater().inflate(R.layout.inat_camera, null);
            this.addView(cameraLayout);

            mActivity.getFragmentManager()
                .beginTransaction()
                .replace(R.id.container, mCameraFragment)
                .commit();
        }
    }
    

    @SuppressLint("ResourceType")
    public INatCameraView(Context context, Activity activity) {
        super(context);
        reactContext = (ReactContext) context;

        mActivity = activity;

        mCameraFragment = new Camera2BasicFragment();
        mCameraFragment.setOnCameraErrorListener(this);

        // Initialize location client
        getLocation();
    }

    public void setConfidenceThreshold(float confidence) {
        mCameraFragment.setConfidenceThreshold(confidence);
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
    

    public void resumePreview() {
        mCameraFragment.resumePreview();
    }

    private Location getLocationFromGPS() {
        Log.d(TAG, "getLocationFromGPS");

        LocationManager locationManager = (LocationManager) reactContext.getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        String provider = locationManager.getBestProvider(criteria, false);
        if (provider == null) return null;

        Location location = locationManager.getLastKnownLocation(provider);
        Log.d(TAG, "getLocationFromGPS: " + location);

        return location;
    }

    private Location getLastKnownLocationFromClient() {
        Location location = null;

        Log.d(TAG, "getLastKnownLocationFromClient");

        try {
            location = LocationServices.FusedLocationApi.getLastLocation(mLocationClient);
        } catch (IllegalStateException ex) {
            ex.printStackTrace();
        }

        Log.d(TAG, "getLastKnownLocationFromClient: " + location);
        if (location == null) {
            // Failed - try and return last place using GPS
            return getLocationFromGPS();
        } else {
            return location;
        }
    }

    private Location getLocation() {
        int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(reactContext);
        
        Log.d(TAG, "getLocation: isAvailable = " + resultCode);


        if (resultCode == ConnectionResult.SUCCESS) {
            Log.d(TAG, "getLocation: Connected already");
            // User Google Play services if available
            if ((mLocationClient != null) && (mLocationClient.isConnected())) {
                // Location client already initialized and connected - use it
                return getLastKnownLocationFromClient();
            } else {
                // Connect to the place services
                Log.d(TAG, "getLocation: Connecting to client");
                mLocationClient = new GoogleApiClient.Builder(reactContext)
                        .addApi(LocationServices.API)
                        .addConnectionCallbacks(new ConnectionCallbacks() {
                            @Override
                            public void onConnected(Bundle bundle) {
                                // Connected successfully
                                Log.d(TAG, "getLocation: Connected");
                            }

                            @Override
                            public void onConnectionSuspended(int i) { }
                        })
                        .addOnConnectionFailedListener(new OnConnectionFailedListener() {
                            @Override
                            public void onConnectionFailed(ConnectionResult connectionResult) {
                                Log.d(TAG, "getLocation: Connection failed");
                                mLocationClient.disconnect();
                            }
                        })
                        .build();
                mLocationClient.connect();

                return null;
            }

        } else {
            Log.d(TAG, "getLocation: Getting from GPS");
            // Use GPS alone for place
            return getLocationFromGPS();
        }
    }


    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     * Taken from: https://github.com/googlesamples/android-Camera2Basic/blob/master/Application/src/main/java/com/example/android/camera2basic/Camera2BasicFragment.java
     *
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation() {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        WindowManager windowManager = (WindowManager) reactContext.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        int sensorOrientation = mCameraFragment.getSensorOrientation();

        Log.d(TAG, "getOrientation: " + rotation + ":" + sensorOrientation + ":" + ORIENTATIONS.get(rotation));

        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }
    
    public void takePictureAsync(ReadableMap options, Promise promise) {
        Bitmap bitmap = mCameraFragment.takePicture();

        if ((options.hasKey(OPTION_PAUSE_AFTER_CAPTURE)) && (options.getBoolean(OPTION_PAUSE_AFTER_CAPTURE))) {
            // Freeze screen after capture
            mCameraFragment.pausePreview();
        }


        try {
            // Save the bitmap into a JPEG file in the cache directory
            File cacheDirectory = reactContext.getCacheDir();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());
            String path = cacheDirectory.getPath() + File.separator + "IMG_" + timeStamp + ".jpg";

            FileOutputStream fileOutputStream = new FileOutputStream(path);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.flush();
            fileOutputStream.close();


            // Set EXIF data for the photo

            ExifInterface exif = new ExifInterface(path);


            // Orientation

            int orientation = getOrientation();

            Log.d(TAG, "takePicture - orientation: " + orientation);

            switch (orientation) {
                case 0:
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                    break;
                case 90:
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
                    break;
                case 180:
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_180));
                    break;
                case 270:
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_270));
                    break;
            }


            // Location
            
            Location location = getLocation();

            Log.d(TAG, "takePicture - location: " + location);

            if (location != null) {
                double latitude = location.getLatitude();
                double longitude = location.getLongitude();

                Log.d(TAG, "takePicture - saving location: " + latitude + "/" + longitude + " ==> " + GPSEncoder.convert(latitude) + " and " + GPSEncoder.convert(longitude));

                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GPSEncoder.convert(latitude));
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, GPSEncoder.latitudeRef(latitude));
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GPSEncoder.convert(longitude));
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, GPSEncoder.longitudeRef(longitude));
            }

            exif.saveAttributes();
           

            // Get predictions for that image
            List<Prediction> predictions = mCameraFragment.getPredictionsForImage(bitmap);
            bitmap.recycle();

            // Return both photo URI and predictions

            WritableMap result = Arguments.createMap();
            result.putString("uri", path);

            WritableArray results = Arguments.createArray();
            for (Prediction prediction : predictions) {
                results.pushMap(nodeToMap(prediction));
            }

            result.putArray("predictions", results);

            promise.resolve(result);
        } catch (Exception exc) {
            exc.printStackTrace();
        }
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


    /** Converts a prediction result to a map */
    private WritableMap nodeToMap(Prediction prediction) {
        WritableMap result = Arguments.createMap();

        result.putInt("taxon_id", Integer.valueOf(prediction.node.key));
        result.putString("name", prediction.node.name);
        result.putDouble("score", prediction.probability);
        result.putInt("rank", prediction.node.rank);
        result.putInt("class_id", Integer.valueOf(prediction.node.classId));

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


    /** Converts the predictions array into "clean" map of results (separated by rank), sent back to React Native */
    private WritableMap predictionsToMap(Collection<Prediction> predictions) {
        WritableMap event = Arguments.createMap();

        Map<Integer, WritableArray> ranks = new HashMap<>();

        for (Prediction prediction : predictions) {
            WritableMap result = nodeToMap(prediction);
            if (!ranks.containsKey(prediction.node.rank)) {
                ranks.put(prediction.node.rank, Arguments.createArray());
            }

            ranks.get(prediction.node.rank).pushMap(result);
        }

        // Convert from rank level to rank name
        for (Integer rank : RANK_LEVEL_TO_NAME.keySet()) {
            if (ranks.containsKey(rank)) {
                event.putArray(RANK_LEVEL_TO_NAME.get(rank), ranks.get(rank));
            }
        }

        return event;
    }

    @Override
    public void onTaxaDetected(Prediction prediction) {
        if (System.currentTimeMillis() - mLastPredictionTime < mTaxaDetectionInterval) {
            // Make sure we don't call this callback too often
            return;
        }

        // Convert Prediction into a structure separating by rank name
        List<Prediction> predictions = Arrays.asList(prediction);
        WritableMap event = predictionsToMap(predictions);

        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                EVENT_NAME_ON_TAXA_DETECTED,
                event);

        mLastPredictionTime = System.currentTimeMillis();
    }
}

