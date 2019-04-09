package org.inaturalist.inatcamera.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.CameraAccessException;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.inaturalist.inatcamera.R;
import org.inaturalist.inatcamera.classifier.ImageClassifier;
import org.inaturalist.inatcamera.classifier.Prediction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Basic fragments for the Camera. */
public class Camera2BasicFragment extends Fragment
        implements FragmentCompat.OnRequestPermissionsResultCallback {

    /** Tag for the {@link Log}. */
    private static final String TAG = "TfLiteCameraDemo";

    private static final String HANDLE_THREAD_NAME = "CameraBackground";

    private static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.8f;

    // Reasons why the device is not supported
    private static final int REASON_DEVICE_SUPPORTED = 0;
    private static final int REASON_DEVICE_NOT_SUPPORTED = 1;
    private static final int REASON_OS_TOO_OLD = 2;
    private static final int REASON_NOT_ENOUGH_MEMORY = 3;
    // TODO: Add more reasons (e.g. graphic card, ...)

    private final Object lock = new Object();
    private boolean mRunClassifier = false;
    private boolean mCheckedPermissions = false;
    private ImageClassifier mClassifier;

    /** Max preview width that is guaranteed by Camera2 API */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /** Max preview height that is guaranteed by Camera2 API */
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private CameraListener mCameraCallback;
    private String mModelFilename;
    private String mTaxonomyFilename;
    private boolean mDeviceSupported;

    private boolean mCameraConfigured = false;

    private float mConfidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;

    private int mSensorOrientation = 0;

    public interface CameraListener {
        void onCameraError(String error);
        void onCameraPermissionMissing();
        void onClassifierError(String error);
        void onTaxaDetected(Prediction prediction);
        void onDeviceNotSupported(String reason);
    }
    
    public void setOnCameraErrorListener(CameraListener listener) {
        mCameraCallback = listener;
    }

    public void setConfidenceThreshold(float confidence) {
        mConfidenceThreshold = confidence;
    }

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a {@link
     * TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
            };

    /** ID of the current {@link CameraDevice}. */
    private String cameraId;

    /** An {@link AutoFitTextureView} for camera preview. */
    private AutoFitTextureView textureView;

    /** A {@link CameraCaptureSession } for camera preview. */
    private CameraCaptureSession captureSession;

    /** A reference to the opened {@link CameraDevice}. */
    private CameraDevice cameraDevice;

    /** The {@link Size} of camera preview. */
    private Size previewSize;

    /** {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state. */
    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {

                @Override
                public void onOpened(@NonNull CameraDevice currentCameraDevice) {
                    // This method is called when the camera is opened.  We start camera preview here.
                    cameraOpenCloseLock.release();
                    cameraDevice = currentCameraDevice;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice currentCameraDevice) {
                    cameraOpenCloseLock.release();
                    currentCameraDevice.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice currentCameraDevice, int error) {
                    cameraOpenCloseLock.release();
                    currentCameraDevice.close();
                    cameraDevice = null;

                    if (mCameraCallback != null) mCameraCallback.onCameraError("Error obtaining camera: " + error);
                }
            };

    /** An additional thread for running tasks that shouldn't block the UI. */
    private HandlerThread backgroundThread;

    /** A {@link Handler} for running tasks in the background. */
    private Handler backgroundHandler;

    /** An {@link ImageReader} that handles image capture. */
    private ImageReader imageReader;

    /** {@link CaptureRequest.Builder} for the camera preview */
    private CaptureRequest.Builder previewRequestBuilder;

    /** {@link CaptureRequest} generated by {@link #previewRequestBuilder} */
    private CaptureRequest previewRequest;

    /** A {@link Semaphore} to prevent the app from exiting before closing the camera. */
    private Semaphore cameraOpenCloseLock = new Semaphore(1);

    /** A {@link CameraCaptureSession.CaptureCallback} that handles events related to capture. */
    private CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureProgressed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureResult partialResult) {}

                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {}
            };

    /**
     * Resizes image.
     *
     * Attempting to use too large a preview size could  exceed the camera bus' bandwidth limitation,
     * resulting in gorgeous previews but the storage of garbage capture data.
     *
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that is
     * at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size, and
     * whose aspect ratio matches with the specified value.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param textureViewWidth The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth The maximum width that can be chosen
     * @param maxHeight The maximum height that can be chosen
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(
            Size[] choices,
            int textureViewWidth,
            int textureViewHeight,
            int maxWidth,
            int maxHeight,
            Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth
                    && option.getHeight() <= maxHeight
                    && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public void setModelFilename(String filename) {
        mModelFilename = filename;
    }

    public void setTaxonomyFilename(String filename) {
        mTaxonomyFilename = filename;
    }


    /** Layout the preview and buttons. */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    /** Connect the buttons to their event handler. */
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        textureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    /** Load the model and labels. */
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        int reason = checkForSupportedDevice();

        mDeviceSupported = (reason == REASON_DEVICE_SUPPORTED);

        if (!mDeviceSupported) {
            Log.w(TAG, "Device not supported - not running classifier");

            if (mCameraCallback != null) mCameraCallback.onDeviceNotSupported(deviceNotSupportedReasonToString(reason));

            return;
        }

        try {
            mClassifier = new ImageClassifier(getActivity(), mModelFilename, mTaxonomyFilename);
        } catch (IOException e) {
            e.printStackTrace();
            if (mCameraCallback != null) mCameraCallback.onClassifierError("Failed to initialize an image mClassifier: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            Log.w(TAG, "Out of memory - Device not supported - classifier failed to load - " + e);
            if (mCameraCallback != null) mCameraCallback.onDeviceNotSupported(deviceNotSupportedReasonToString(REASON_NOT_ENOUGH_MEMORY));
            return;
        } catch (Exception e) {
            e.printStackTrace();
            Log.w(TAG, "Other type of exception - Device not supported - classifier failed to load - " + e);
            if (mCameraCallback != null) mCameraCallback.onDeviceNotSupported(deviceNotSupportedReasonToString(REASON_DEVICE_NOT_SUPPORTED));
            return;
        }

        startBackgroundThread();
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

    @Override
    public void onResume() {
        super.onResume();

        if (!mDeviceSupported) {
            Log.w(TAG, "Device not supported - not running classifier");
            return;
        }

        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mClassifier != null) {
            mClassifier.close();
        }
        stopBackgroundThread();

        super.onDestroy();
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // // For still image captures, we use the largest available size.
                Size largest =
                        Collections.max(
                                Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                imageReader =
                        ImageReader.newInstance(
                                largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/ 2);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                // noinspection ConstantConditions
                /* Orientation of the camera sensor */
                int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (sensorOrientation == 90 || sensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (sensorOrientation == 0 || sensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                mSensorOrientation = sensorOrientation;

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                previewSize =
                        chooseOptimalSize(
                                map.getOutputSizes(SurfaceTexture.class),
                                rotatedPreviewWidth,
                                rotatedPreviewHeight,
                                maxPreviewWidth,
                                maxPreviewHeight,
                                largest);


                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                } else {
                    textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                }

                this.cameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            if (mCameraCallback != null) mCameraCallback.onCameraError("Error obtaining camera: " + e.getMessage());
        }
    }

    /** Opens the camera specified by {@link Camera2BasicFragment#cameraId}. */
    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        if (!mCheckedPermissions && !cameraPermissionGranted()) {
            if (mCameraCallback != null) mCameraCallback.onCameraPermissionMissing();
            return;
        } else {
            mCheckedPermissions = true;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                if (mCameraCallback != null) mCameraCallback.onCameraError("Time out waiting to lock camera opening.");
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            if (mCameraCallback != null) mCameraCallback.onCameraError("Interrupted while trying to lock camera opening: " + e.getMessage());
        }
    }

    private boolean cameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /** Closes the current {@link CameraDevice}. */
    public void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            if (mCameraCallback != null) mCameraCallback.onCameraError("Interrupted while trying to lock camera closing: " + e.getMessage());
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /** Starts a background thread and its {@link Handler}. */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread(HANDLE_THREAD_NAME);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        synchronized (lock) {
            mRunClassifier = true;
        }
        backgroundHandler.post(periodicClassify);
    }

    /** Stops the background thread and its {@link Handler}. */
    public void stopBackgroundThread() {
        try {
            if (backgroundThread != null) {
                backgroundThread.quitSafely();
                backgroundThread.join();
            }

            backgroundThread = null;
            backgroundHandler = null;
            synchronized (lock) {
                mRunClassifier = false;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /** Takes photos and classify them periodically. */
    private Runnable periodicClassify =
            new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        if (mRunClassifier) {
                            classifyFrame();
                        }
                    }
                    backgroundHandler.post(periodicClassify);
                }
            };

    /** Creates a new {@link CameraCaptureSession} for camera preview. */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(
                                        previewRequest, captureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                            mCameraConfigured = true;
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (mCameraCallback != null) mCameraCallback.onCameraError("Failed configuring camera");
                        }
                    },
                    null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `textureView`. This
     * method should be called after the camera preview size is determined in setUpCameraOutputs and
     * also the size of `textureView` is fixed.
     *
     * @param viewWidth The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /** Classifies a frame from the preview stream. */
    private void classifyFrame() {
        if ((mClassifier == null) || (getActivity() == null) || (cameraDevice == null) || (!mCameraConfigured)) {
            return;
        }
        Bitmap bitmap = textureView.getBitmap(ImageClassifier.DIM_IMG_SIZE_X, ImageClassifier.DIM_IMG_SIZE_Y);

        if (bitmap == null) {
            Log.e(TAG, "Null input bitmap");
            return;
        }

        List<Prediction> predictions = mClassifier.classifyFrame(bitmap);
        bitmap.recycle();

        // Return only one prediction, as accurate as possible (e.g. prefer species over family), that passes the minimal threshold
        Prediction selectedPrediction = null;

        Collections.reverse(predictions);
        for (Prediction prediction : predictions) {
            if (prediction.probability > mConfidenceThreshold) {
                selectedPrediction = prediction;
                break;
            }
        }

        if (mCameraCallback != null) mCameraCallback.onTaxaDetected(selectedPrediction);
    }

    public int getSensorOrientation() {
        return mSensorOrientation;
    }

    /** Takes a picture */
    public Bitmap takePicture() {
        Bitmap bitmap = textureView.getBitmap();

        return bitmap;
    }

    /** Retrieves predictions for a single frame */
    public List<Prediction> getPredictionsForImage(Bitmap bitmap) {
        if (mClassifier == null) {
            Log.e(TAG, "getPredictionsForImage - classifier is null!");
            return new ArrayList<Prediction>();
        }

        // Resize bitmap to the size the classifier supports
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, ImageClassifier.DIM_IMG_SIZE_X, ImageClassifier.DIM_IMG_SIZE_Y, true);

        List<Prediction> predictions = mClassifier.classifyFrame(resizedBitmap);
        resizedBitmap.recycle();

        return predictions;
    }

    /** Pauses the preview */
    public void pausePreview() {
        try {
            captureSession.stopRepeating();
            synchronized (lock) {
                mRunClassifier = false;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /** Resumes the preview */
    public void resumePreview() {
        createCameraPreviewSession();
        synchronized (lock) {
            mRunClassifier = true;
        }
    }


    /** Compares two {@code Size}s based on their areas. */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}

