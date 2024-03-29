/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.inaturalist.inatcamera.ui;

import timber.log.*;
import android.annotation.TargetApi;
import java.util.Iterator;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import androidx.annotation.NonNull;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.ReadableMap;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;

@SuppressWarnings("MissingPermission")
@TargetApi(21)
class Camera2 extends CameraViewImpl implements MediaRecorder.OnInfoListener, MediaRecorder.OnErrorListener {

    private static final String TAG = "Camera2";

    private static final SparseIntArray INTERNAL_FACINGS = new SparseIntArray();

    static {
        INTERNAL_FACINGS.put(Constants.FACING_BACK, CameraCharacteristics.LENS_FACING_BACK);
        INTERNAL_FACINGS.put(Constants.FACING_FRONT, CameraCharacteristics.LENS_FACING_FRONT);
    }

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    private static final int FOCUS_AREA_SIZE_DEFAULT = 300;

    private static final int FOCUS_METERING_AREA_WEIGHT_DEFAULT = 1000;

    private final CameraManager mCameraManager;

    private final CameraDevice.StateCallback mCameraDeviceCallback
            = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Timber.tag(TAG).d("Camera - onOpened: " + camera);
            mCamera = camera;
            mCallback.onCameraOpened();
            startCaptureSession();
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            Timber.tag(TAG).d("Camera - onClosed - " + camera);
            mCallback.onCameraClosed();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Timber.tag(TAG).d("Camera - onDisconnected - " + camera);
            mCamera = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Timber.tag(TAG).e("Camera - onError: " + camera.getId() + " (" + error + ")");
            mCamera = null;
        }

    };

    private final CameraCaptureSession.StateCallback mSessionCallback
            = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (mCamera == null) {
                return;
            }
            if (mPreviewRequestBuilder == null) {
                Timber.tag(TAG).e("onConfigured - mPreviewRequestBuilder is null");
                return;
            }
            if (session == null) {
                Timber.tag(TAG).e("onConfigured - session is null");
                return;
            }

            mCaptureSession = session;
            mInitialCropRegion = mPreviewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION);
            updateAutoFocus();
            updateFlash();
            updateFocusDepth();
            updateWhiteBalance();
            updateZoom();
            try {
                if (mPreviewRequestBuilder == null) {
                    Timber.tag(TAG).e("onConfigured - mPreviewRequestBuilder is null (2)");
                } else if (mCaptureSession == null) {
                    Timber.tag(TAG).e("onConfigured - mCaptureSession is null");
                } else {
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCaptureCallback, null);
                }
            } catch (CameraAccessException e) {
                Timber.tag(TAG).e("Failed to start camera preview because it couldn't access camera", e);
            } catch (IllegalStateException e) {
                Timber.tag(TAG).e("Failed to start camera preview.", e);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Timber.tag(TAG).e("Failed to configure capture session.");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (mCaptureSession != null && mCaptureSession.equals(session)) {
                mCaptureSession = null;
            }
        }

    };

    PictureCaptureCallback mCaptureCallback = new PictureCaptureCallback() {

        @Override
        public void onPrecaptureRequired() {
            Timber.tag(TAG).d("onPrecaptureRequired");
            if (mCaptureSession == null || mPreviewRequestBuilder == null) {
                Timber.tag(TAG).e("mCaptureSession is null - exiting");
                return;
            }

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            setState(STATE_PRECAPTURE);
            try {
                mCaptureSession.capture(mPreviewRequestBuilder.build(), this, null);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            } catch (IllegalStateException e) {
                Timber.tag(TAG).e("IllegalStateException - Failed to run precapture sequence.", e);
            } catch (CameraAccessException e) {
                Timber.tag(TAG).e("Failed to run precapture sequence.", e);
            }
        }

        @Override
        public void onReady() {
            Timber.tag(TAG).d("onReady");
            captureStillPicture();
        }

    };

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Timber.tag(TAG).d("onImageAvailable");
            try (Image image = reader.acquireNextImage()) {
                Image.Plane[] planes = null;
                try {
                    planes = image.getPlanes();
                } catch (RuntimeException exc) {
                    Timber.tag(TAG).e("Exception: " + exc);
                }

                if ((planes != null) && (planes.length > 0)) {
                    ByteBuffer buffer = planes[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    if (image.getFormat() == ImageFormat.JPEG) {
                        // @TODO: implement deviceOrientation
                        Timber.tag(TAG).d("onImageAvailable 2");
                        mCallback.onPictureTaken(data, 0);
                    } else {
                        mCallback.onFramePreview(data, image.getWidth(), image.getHeight(), mDisplayOrientation);
                    }
                    image.close();
                }
            }
        }

    };


    private String mCameraId;
    private String _mCameraId;

    private CameraCharacteristics mCameraCharacteristics;

    CameraDevice mCamera;

    CameraCaptureSession mCaptureSession;

    CaptureRequest.Builder mPreviewRequestBuilder;

    Set<String> mAvailableCameras = new HashSet<>();

    private ImageReader mStillImageReader;

    private ImageReader mScanImageReader;

    private int mImageFormat;

    private MediaRecorder mMediaRecorder;

    private String mVideoPath;

    private boolean mIsRecording;

    private final SizeMap mPreviewSizes = new SizeMap();

    private final SizeMap mPictureSizes = new SizeMap();

    private Size mPictureSize;

    private int mFacing;

    private AspectRatio mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;

    private AspectRatio mInitialRatio;

    private boolean mAutoFocus = true;

    private int mFlash;

    private float mExposure;

    private int mCameraOrientation;

    private int mDisplayOrientation;

    private int mDeviceOrientation;

    private float mFocusDepth;

    private float mZoom;

    private int mWhiteBalance;

    private boolean mIsScanning;

    private Surface mPreviewSurface;

    private Rect mInitialCropRegion;

    Camera2(Callback callback, PreviewImpl preview, Context context, Handler bgHandler) {
        super(callback, preview, bgHandler);
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        mCameraManager.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                super.onCameraAvailable(cameraId);
                mAvailableCameras.add(cameraId);
            }

            @Override
            public void onCameraUnavailable(@NonNull String cameraId) {
                super.onCameraUnavailable(cameraId);
                mAvailableCameras.remove(cameraId);
            }
        }, null);
        mImageFormat = mIsScanning ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
        mPreview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                Timber.tag(TAG).d("onSurfaceChanged - " + mCameraId);
                startCaptureSession();
            }

            @Override
            public void onSurfaceDestroyed() {
                Timber.tag(TAG).d("onSurfaceDestroyed");
                stop();
            }
        });
    }

    @Override
    boolean start() {
        Timber.tag(TAG).d("start - 1");

        if (!chooseCameraIdByFacing()) {
            Timber.tag(TAG).d("start - 2");
            mAspectRatio = mInitialRatio;
            return false;
        }
        Timber.tag(TAG).d("start - 3");
        collectCameraInfo();
        setAspectRatio(mInitialRatio);
        mInitialRatio = null;
        Timber.tag(TAG).d("start - 4");
        prepareStillImageReader();
        Timber.tag(TAG).d("start - 5");
        prepareScanImageReader();
        Timber.tag(TAG).d("start - 6");
        startOpeningCamera();
        Timber.tag(TAG).d("start - 7");
        return true;
    }

    @Override
    void stop() {
        Timber.tag(TAG).d("stop - " + mCaptureSession + ":" + mCamera + ":" + mStillImageReader + ":" + mScanImageReader);

        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCamera != null) {
            mCamera.close();
            mCamera = null;
        }
        if (mStillImageReader != null) {
            mStillImageReader.close();
            mStillImageReader = null;
        }

        if (mScanImageReader != null) {
            mScanImageReader.close();
            mScanImageReader = null;
        }

        if (mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;

            if (mIsRecording) {
                // @TODO: implement videoOrientation and deviceOrientation calculation
                mCallback.onVideoRecorded(mVideoPath, 0, 0);
                mIsRecording = false;
            }
        }
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    void setFacing(int facing) {
        Timber.tag(TAG).d("setFacing 1: " + facing + ":" + mFacing + ":" + mCamera);

        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            Timber.tag(TAG).d("setFacing 2");
            stop();
            start();
        }
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    void setCameraId(String id) {
        Timber.tag(TAG).d("setCameraId: " + id + ":" + mCamera + ":" + _mCameraId);
        if(!Objects.equals(_mCameraId, id)){
            _mCameraId = id;

            // only update if our camera ID actually changes
            // from what we currently have.
            // Passing null will always yield true
            if(!Objects.equals(_mCameraId, mCameraId)){
                // this will call chooseCameraIdByFacing
                if (isCameraOpened()) {
                    stop();
                    start();
                }
            }
        }
    }

    @Override
    String getCameraId() {
        return _mCameraId;
    }

    @Override
    Set<AspectRatio> getSupportedAspectRatios() {
        return mPreviewSizes.ratios();
    }

    @Override
    List<Properties> getCameraIds() {
        try{

            List<Properties> ids = new ArrayList<>();

            String[] cameraIds = mCameraManager.getCameraIdList();
            for (String id : cameraIds) {
                Properties p = new Properties();

                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(id);
                Integer internal = characteristics.get(CameraCharacteristics.LENS_FACING);

                p.put("id", id);
                p.put("type", String.valueOf(internal == CameraCharacteristics.LENS_FACING_FRONT ? Constants.FACING_FRONT : Constants.FACING_BACK));
                ids.add(p);
            }
            return ids;
        }
        catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera ids", e);
        }
    }

    @Override
    SortedSet<Size> getAvailablePictureSizes(AspectRatio ratio) {
        return mPictureSizes.sizes(ratio);
    }

    @Override
    void setPictureSize(Size size) {
        Timber.tag(TAG).d("setPictureSize - " + mCameraId + ":" + size + ":" + mCaptureSession);
        if (mCaptureSession != null) {
            try {
                mCaptureSession.stopRepeating();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mStillImageReader != null) {
            mStillImageReader.close();
        }
        if (size == null) {
            if (mAspectRatio == null) {
                Timber.tag(TAG).d("setPictureSize 2");
                return;
            }
            mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
        } else {
            mPictureSize = size;
        }
        Timber.tag(TAG).d("setPictureSize 3");
        prepareStillImageReader();
        startCaptureSession();
    }

    @Override
    Size getPictureSize() {
        return mPictureSize;
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        Timber.tag(TAG).d("setAspectRatio 1 - " + ratio);

        if (ratio != null && mPreviewSizes.isEmpty()) {
            mInitialRatio = ratio;
            Timber.tag(TAG).d("setAspectRatio 2");
            return false;
        }
        if (ratio == null || ratio.equals(mAspectRatio) ||
                !mPreviewSizes.ratios().contains(ratio)) {
            // TODO: Better error handling
            Timber.tag(TAG).d("setAspectRatio 3");
            return false;
        }
        mAspectRatio = ratio;
        Timber.tag(TAG).d("setAspectRatio 4");
        prepareStillImageReader();
        prepareScanImageReader();
        if (mCaptureSession != null) {
            Timber.tag(TAG).d("setAspectRatio 5");
            mCaptureSession.close();
            mCaptureSession = null;
            startCaptureSession();
        }
        return true;
    }

    @Override
    AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        mAutoFocus = autoFocus;
        if (mPreviewRequestBuilder != null) {
            updateAutoFocus();
            if (mCaptureSession != null) {
                try {
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCaptureCallback, null);
                } catch (CameraAccessException e) {
                    mAutoFocus = !mAutoFocus; // Revert
                }
            }
        }
    }

    @Override
    boolean getAutoFocus() {
        return mAutoFocus;
    }

    @Override
    void setFlash(int flash) {
        if (mFlash == flash) {
            return;
        }
        int saved = mFlash;
        mFlash = flash;
        if (mPreviewRequestBuilder != null) {
            updateFlash();
            if (mCaptureSession != null) {
                try {
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            mCaptureCallback, null);
                } catch (CameraAccessException e) {
                    mFlash = saved; // Revert
                }
            }
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    float getExposureCompensation() {
        return mExposure;
    }

    @Override
    void setExposureCompensation(float exposure) {
        Log.e("CAMERA_2:: ", "Adjusting exposure is not currently supported for Camera2");
    }


    @Override
    void takePicture(ReadableMap options) {
        Timber.tag(TAG).d("takePicture - " + mAutoFocus);
        mCaptureCallback.setOptions(options);

        if (mAutoFocus) {
            Timber.tag(TAG).d("takePicture - lockFocus");
            lockFocus();
        } else {
            Timber.tag(TAG).d("takePicture - captureStillPicture");
            captureStillPicture();
        }
    }

    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile, int orientation) {
        if (!mIsRecording) {
            setUpMediaRecorder(path, maxDuration, maxFileSize, recordAudio, profile);
            try {
                mMediaRecorder.prepare();

                if (mCaptureSession != null) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }

                Size size = chooseOptimalSize();
                mPreview.setBufferSize(size.getWidth(), size.getHeight());
                Surface surface = getPreviewSurface();
                Surface mMediaRecorderSurface = mMediaRecorder.getSurface();

                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                mPreviewRequestBuilder.addTarget(surface);
                mPreviewRequestBuilder.addTarget(mMediaRecorderSurface);
                mCamera.createCaptureSession(Arrays.asList(surface, mMediaRecorderSurface),
                        mSessionCallback, null);
                mMediaRecorder.start();
                mIsRecording = true;
                return true;
            } catch (CameraAccessException | IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    @Override
    void stopRecording() {
        Timber.tag(TAG).d("stopRecording 1 - " + mIsRecording);
        if (mIsRecording) {
            stopMediaRecorder();

            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            Timber.tag(TAG).d("stopRecording 2");
            startCaptureSession();
        }
    }

    @Override
    public void setFocusDepth(float value) {
        if (mFocusDepth == value) {
            return;
        }
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("setFocusDepth - mPreviewRequestBuilder is null");
            return;
        }

        float saved = mFocusDepth;
        mFocusDepth = value;
        if (mCaptureSession != null) {
            updateFocusDepth();
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                        mCaptureCallback, null);
            } catch (CameraAccessException e) {
                mFocusDepth = saved;  // Revert
            }
        }
    }

    @Override
    float getFocusDepth() {
        return mFocusDepth;
    }

    @Override
    public void setZoom(float zoom) {
        if (mZoom == zoom) {
            return;
        }
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("setZoom - mPreviewRequestBuilder is null");
            return;
        }

        float saved = mZoom;
        mZoom = zoom;
        if (mCaptureSession != null) {
            updateZoom();
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                        mCaptureCallback, null);
            } catch (CameraAccessException e) {
                mZoom = saved;  // Revert
            }
        }
    }

    @Override
    float getZoom() {
        return mZoom;
    }

    @Override
    public void setWhiteBalance(int whiteBalance) {
        if (mWhiteBalance == whiteBalance) {
            return;
        }
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("setWhiteBalance - mPreviewRequestBuilder is null");
            return;
        }

        int saved = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        if (mCaptureSession != null) {
            updateWhiteBalance();
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                        mCaptureCallback, null);
            } catch (CameraAccessException e) {
                mWhiteBalance = saved;  // Revert
            }
        }
    }

    @Override
    public int getWhiteBalance() {
        return mWhiteBalance;
    }

    @Override
    void setScanning(boolean isScanning) {
        Timber.tag(TAG).d("setScanning - " + isScanning + ":" + mIsScanning);
        if (mIsScanning == isScanning) {
            return;
        }
        mIsScanning = isScanning;
        if (!mIsScanning) {
            mImageFormat = ImageFormat.JPEG;
        } else {
            mImageFormat = ImageFormat.YUV_420_888;
        }
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        startCaptureSession();
    }

    @Override
    boolean getScanning() {
        return mIsScanning;
    }

    @Override
    int getCameraOrientation() {
        return mCameraOrientation;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        mPreview.setDisplayOrientation(mDisplayOrientation);
    }


    @Override
    void setDeviceOrientation(int deviceOrientation) {
        mDeviceOrientation = deviceOrientation;
        //mPreview.setDisplayOrientation(deviceOrientation); // this is not needed and messes up the display orientation
    }

    /**
     * <p>Chooses a camera ID by the specified camera facing ({@link #mFacing}).</p>
     * <p>This rewrites {@link #mCameraId}, {@link #mCameraCharacteristics}, and optionally
     * {@link #mFacing}.</p>
     */
    private boolean chooseCameraIdByFacing() {
        Timber.tag(TAG).d("chooseCameraIdByFacing 1 " + _mCameraId);

        if(_mCameraId == null){
            try {
                int internalFacing = INTERNAL_FACINGS.get(mFacing);
                final String[] ids = mCameraManager.getCameraIdList();
                if (ids.length == 0) { // No camera
                    Timber.tag(TAG).d("chooseCameraIdByFacing 2");
                    throw new RuntimeException("No camera available.");
                }
                for (String id : ids) {
                    CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(id);
                    Integer level = characteristics.get(
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    Timber.tag(TAG).d("chooseCameraIdByFacing 2b - " + id + ":" + level);
                    if (level == null ||
                            level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                        continue;
                    }
                    Integer internal = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (internal == null) {
                        Timber.tag(TAG).d("chooseCameraIdByFacing 3 - LENS_FACING null");
                        throw new NullPointerException("Unexpected state: LENS_FACING null");
                    }
                    if (internal == internalFacing) {
                        mCameraId = id;
                        mCameraCharacteristics = characteristics;
                        Timber.tag(TAG).d("chooseCameraIdByFacing 4 - " + mCameraId);
                        return true;
                    }
                }
                // Not found
                mCameraId = ids[0];
                mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
                Integer level = mCameraCharacteristics.get(
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level == null ||
                        level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    Timber.tag(TAG).d("chooseCameraIdByFacing 5 - " + level + ":" + CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
                    //return false;
                }
                Integer internal = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (internal == null) {
                    Timber.tag(TAG).d("chooseCameraIdByFacing 6 - LENS_FACING null");
                    throw new NullPointerException("Unexpected state: LENS_FACING null");
                }
                for (int i = 0, count = INTERNAL_FACINGS.size(); i < count; i++) {
                    if (INTERNAL_FACINGS.valueAt(i) == internal) {
                        mFacing = INTERNAL_FACINGS.keyAt(i);
                        Timber.tag(TAG).d("chooseCameraIdByFacing 7 - " + mFacing);
                        return true;
                    }
                }
                // The operation can reach here when the only camera device is an external one.
                // We treat it as facing back.
                mFacing = Constants.FACING_BACK;
                Timber.tag(TAG).d("chooseCameraIdByFacing 8");
                return true;
            } catch (CameraAccessException e) {
                Timber.tag(TAG).d("chooseCameraIdByFacing 9 - " + e);
                throw new RuntimeException("Failed to get a list of camera devices", e);
            }
        }
        else{
            Timber.tag(TAG).d("chooseCameraIdByFacing 10");

            try{
                // need to set the mCameraCharacteristics variable as above and also do the same checks
                // for legacy hardware
                mCameraCharacteristics = mCameraManager.getCameraCharacteristics(_mCameraId);

                Integer level = mCameraCharacteristics.get(
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level == null ||
                        level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    Timber.tag(TAG).d("chooseCameraIdByFacing 11");
                    return false;
                }

                // set our facing variable so orientation also works as expected
                Integer internal = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (internal == null) {
                    Timber.tag(TAG).d("chooseCameraIdByFacing 12");
                    throw new NullPointerException("Unexpected state: LENS_FACING null");
                }
                for (int i = 0, count = INTERNAL_FACINGS.size(); i < count; i++) {
                    if (INTERNAL_FACINGS.valueAt(i) == internal) {
                        mFacing = INTERNAL_FACINGS.keyAt(i);
                        break;
                    }
                }

                mCameraId = _mCameraId;
                Timber.tag(TAG).d("chooseCameraIdByFacing 13 - " + mCameraId);
                return true;
            }
            catch(Exception e){
                Timber.tag(TAG).d("chooseCameraIdByFacing 14 - " + e);
                throw new RuntimeException("Failed to get camera characteristics", e);
            }
        }
    }

    /**
     * <p>Collects some information from {@link #mCameraCharacteristics}.</p>
     * <p>This rewrites {@link #mPreviewSizes}, {@link #mPictureSizes},
     * {@link #mCameraOrientation}, and optionally, {@link #mAspectRatio}.</p>
     */
    private void collectCameraInfo() {
        StreamConfigurationMap map = mCameraCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Timber.tag(TAG).d("collectCameraInfo 1 - " + map);
        if (map == null) {
            throw new IllegalStateException("Failed to get configuration map: " + mCameraId);
        }
        mPreviewSizes.clear();
        for (android.util.Size size : map.getOutputSizes(mPreview.getOutputClass())) {
            int width = size.getWidth();
            int height = size.getHeight();
            if (width <= MAX_PREVIEW_WIDTH && height <= MAX_PREVIEW_HEIGHT) {
                Timber.tag(TAG).d(String.format("collectCameraInfo 2 - adding preview size: %d x %d ==> %s", width, height, AspectRatio.of(width, height)));
                mPreviewSizes.add(new Size(width, height));
            } else {
                Timber.tag(TAG).d(String.format("collectCameraInfo 2 - skipping preview size: %d x %d ==> %s", width, height, AspectRatio.of(width, height)));
            }
        }
        mPictureSizes.clear();
        collectPictureSizes(mPictureSizes, map);
        if (mPictureSize == null) {
            Timber.tag(TAG).d(String.format("collectCameraInfo 3 - setting picture size, mAspectRatio = %s, %s", mAspectRatio, mPictureSizes.sizes(mAspectRatio)));

            if (mPictureSizes.sizes(mAspectRatio) != null) {
                mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
            } else {
                // Revert to non-API 23 picture size collection
                mPictureSizes.clear();
                collectPictureSizesNonAPI23(mPictureSizes, map);
                Timber.tag(TAG).d(String.format("collectCameraInfo 3b - setting picture size (non API23), mAspectRatio = %s, %s", mAspectRatio, mPictureSizes.sizes(mAspectRatio)));

                if (mPictureSizes.sizes(mAspectRatio) != null) {
                    mPictureSize = mPictureSizes.sizes(mAspectRatio).last();
                } else {
                    Timber.tag(TAG).d(String.format("collectCameraInfo 3c - setting picture size from preview, mAspectRatio = %s, %s", mAspectRatio, mPreviewSizes.sizes(mAspectRatio)));
                    mPictureSize = mPreviewSizes.sizes(mAspectRatio).last();
                }
            }
        }
        for (AspectRatio ratio : mPreviewSizes.ratios()) {
            if (!mPictureSizes.ratios().contains(ratio)) {
                mPreviewSizes.remove(ratio);
            }
        }

        if (!mPreviewSizes.ratios().contains(mAspectRatio)) {
            mAspectRatio = mPreviewSizes.ratios().iterator().next();
        }

        mCameraOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    }

    protected void collectPictureSizes(SizeMap sizes, StreamConfigurationMap map) {
        collectPictureSizesNonAPI23(sizes, map);
    }

    protected void collectPictureSizesNonAPI23(SizeMap sizes, StreamConfigurationMap map) {
        Timber.tag(TAG).d(String.format(String.format("collectPictureSizes (<23) - image format = %s; map = %s", mImageFormat, map)));
        for (android.util.Size size : map.getOutputSizes(mImageFormat)) {
            Timber.tag(TAG).d(String.format(String.format("collectPictureSizes (<23) - adding - %d x %d - ratio %s", size.getWidth(), size.getHeight(), AspectRatio.of(size.getWidth(), size.getHeight()))));
            mPictureSizes.add(new Size(size.getWidth(), size.getHeight()));
        }
    }
    private void prepareStillImageReader() {
        if (mStillImageReader != null) {
            mStillImageReader.close();
        }
        Timber.tag(TAG).d("prepareScanImageReader");
        mStillImageReader = ImageReader.newInstance(mPictureSize.getWidth(), mPictureSize.getHeight(),
                ImageFormat.JPEG, 1);
        mStillImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
    }

    private void prepareScanImageReader() {
        Timber.tag(TAG).d("prepareScanImageReader");

        if (mScanImageReader != null) {
            mScanImageReader.close();
        }
        Size largest = mPreviewSizes.sizes(mAspectRatio).last();
        if (largest == null) {
            Iterator<Size> iterator = mPreviewSizes.sizes(mAspectRatio).iterator();
            Size nextLargest = null;
            while ((nextLargest == null) && iterator.hasNext()) {
                nextLargest = iterator.next();
            }
            if (nextLargest == null) {
                largest = mPictureSize;
            } else {
                largest = nextLargest;
            }
        }
        mScanImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                ImageFormat.YUV_420_888, 1);
        mScanImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
    }

    /**
     * <p>Starts opening a camera device.</p>
     * <p>The result will be processed in {@link #mCameraDeviceCallback}.</p>
     */
    private void startOpeningCamera() {
        try {
            Timber.tag(TAG).d("startOpeningCamera - " + mCameraId);
            mCameraManager.openCamera(mCameraId, mCameraDeviceCallback, null);
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("startOpeningCamera Error - " + e);
            throw new RuntimeException("Failed to open camera: " + mCameraId, e);
        }
    }

    /**
     * <p>Starts a capture session for camera preview.</p>
     * <p>This rewrites {@link #mPreviewRequestBuilder}.</p>
     * <p>The result will be continuously processed in {@link #mSessionCallback}.</p>
     */
    void startCaptureSession() {
        Timber.tag(TAG).d("startCaptureSession 1 - " + mCamera + ":" + mPreview.isReady() + ":" + mStillImageReader + ":" + mScanImageReader);
        if (!isCameraOpened() || !mPreview.isReady() || mStillImageReader == null || mScanImageReader == null || mCamera == null) {
            return;
        }
        Timber.tag(TAG).d("startCaptureSession 2 - " + mIsScanning);
        Size previewSize = chooseOptimalSize();
        mPreview.setBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surface = getPreviewSurface();
        try {
            mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            if (mIsScanning) {
                mPreviewRequestBuilder.addTarget(mScanImageReader.getSurface());
            }
            if (mStillImageReader == null || mScanImageReader == null || mCamera == null) {
                return;
            }

            mCamera.createCaptureSession(Arrays.asList(surface, mStillImageReader.getSurface(),
                    mScanImageReader.getSurface()), mSessionCallback, null);
        } catch (IllegalStateException e) {
            Timber.tag(TAG).d("startCaptureSession error " + e);
            mCallback.onMountError(e);
        } catch (CameraAccessException e) {
            Timber.tag(TAG).d("startCaptureSession error " + e);
            mCallback.onMountError(e);
        }
    }

    @Override
    public void resumePreview() {
        unlockFocus();
    }

    @Override
    public void pausePreview() {
        try {
            mCaptureSession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public Surface getPreviewSurface() {
        if (mPreviewSurface != null) {
            return mPreviewSurface;
        }
        return mPreview.getSurface();
    }

    @Override
    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        Timber.tag(TAG).d("setPreviewTexture 1 - " + surfaceTexture);
        if (surfaceTexture != null) {
            Surface previewSurface = new Surface(surfaceTexture);
            mPreviewSurface = previewSurface;
        } else {
            mPreviewSurface = null;
        }

        // it may be called from another thread, so make sure we're in main looper
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Timber.tag(TAG).d("setPreviewTexture 2 - " + mCaptureSession);

                if (mCaptureSession != null) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
                startCaptureSession();
            }
        });
    }

    @Override
    public Size getPreviewSize() {
        return new Size(mPreview.getWidth(), mPreview.getHeight());
    }

    /**
     * Chooses the optimal preview size based on {@link #mPreviewSizes} and the surface size.
     *
     * @return The picked size for camera preview.
     */
    private Size chooseOptimalSize() {
        int surfaceLonger, surfaceShorter;
        final int surfaceWidth = mPreview.getWidth();
        final int surfaceHeight = mPreview.getHeight();
        if (surfaceWidth < surfaceHeight) {
            surfaceLonger = surfaceHeight;
            surfaceShorter = surfaceWidth;
        } else {
            surfaceLonger = surfaceWidth;
            surfaceShorter = surfaceHeight;
        }
        SortedSet<Size> candidates = mPreviewSizes.sizes(mAspectRatio);

        // Pick the smallest of those big enough
        for (Size size : candidates) {
            if (size.getWidth() >= surfaceLonger && size.getHeight() >= surfaceShorter) {
                return size;
            }
        }
        // If no size is big enough, pick the largest one.
        return candidates.last();
    }

    /**
     * Updates the internal state of auto-focus to {@link #mAutoFocus}.
     */
    void updateAutoFocus() {
        Timber.tag(TAG).d("updateAutoFocus - " + mAutoFocus);
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("updateAutoFocus - mPreviewRequestBuilder is null");
            return;
        }

        if (mAutoFocus) {
            int[] modes = mCameraCharacteristics.get(
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            // Auto focus is not supported
            if (modes == null || modes.length == 0 ||
                    (modes.length == 1 && modes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF)) {
                Timber.tag(TAG).d("updateAutoFocus 2");
                mAutoFocus = false;
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF);
            } else {
                Timber.tag(TAG).d("updateAutoFocus 3");
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
        } else {
            Timber.tag(TAG).d("updateAutoFocus 4");
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF);
        }
    }

    /**
     * Updates the internal state of flash to {@link #mFlash}.
     */
    void updateFlash() {
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("updateFlash - mPreviewRequestBuilder is null");
            return;
        }

        switch (mFlash) {
            case Constants.FLASH_OFF:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
            case Constants.FLASH_ON:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
            case Constants.FLASH_TORCH:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_TORCH);
                break;
            case Constants.FLASH_AUTO:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
            case Constants.FLASH_RED_EYE:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    /**
     * Updates the internal state of focus depth to {@link #mFocusDepth}.
     */
    void updateFocusDepth() {
        if (mAutoFocus) {
            return;
        }
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("updateFocusDepth - mPreviewRequestBuilder is null");
            return;
        }

        Float minimumLens = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minimumLens == null) {
            throw new NullPointerException("Unexpected state: LENS_INFO_MINIMUM_FOCUS_DISTANCE null");
        }
        float value = mFocusDepth * minimumLens;
        mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, value);
    }

    public float getMaxZoom() {
        return mCameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
    }

    /**
     * Updates the internal state of zoom to {@link #mZoom}.
     */
    void updateZoom() {
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("updateZoom - mPreviewRequestBuilder is null");
            return;
        }

        float maxZoom = mCameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        float scaledZoom = mZoom * (maxZoom - 1.0f) + 1.0f;
        Rect currentPreview = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (currentPreview != null) {
            int currentWidth = currentPreview.width();
            int currentHeight = currentPreview.height();
            int zoomedWidth = (int) (currentWidth / scaledZoom);
            int zoomedHeight = (int) (currentHeight / scaledZoom);
            int widthOffset = (currentWidth - zoomedWidth) / 2;
            int heightOffset = (currentHeight - zoomedHeight) / 2;

            Rect zoomedPreview = new Rect(
                    currentPreview.left + widthOffset,
                    currentPreview.top + heightOffset,
                    currentPreview.right - widthOffset,
                    currentPreview.bottom - heightOffset
            );

            // ¯\_(ツ)_/¯ for some devices calculating the Rect for zoom=1 results in a bit different
            // Rect that device claims as its no-zoom crop region and the preview freezes
            if (scaledZoom != 1.0f) {
                mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomedPreview);
            } else {
                mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, mInitialCropRegion);
            }
        }
    }

    /**
     * Updates the internal state of white balance to {@link #mWhiteBalance}.
     */
    void updateWhiteBalance() {
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("updateWhiteBalance - mPreviewRequestBuilder is null");
            return;
        }

        switch (mWhiteBalance) {
            case Constants.WB_AUTO:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_AUTO);
                break;
            case Constants.WB_CLOUDY:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
                break;
            case Constants.WB_FLUORESCENT:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
                break;
            case Constants.WB_INCANDESCENT:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
                break;
            case Constants.WB_SHADOW:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_SHADE);
                break;
            case Constants.WB_SUNNY:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
                break;
        }
    }

    /**
     * Locks the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("locaFocus - mPreviewRequestBuilder is null");
            return;
        }

        Timber.tag(TAG).d("lockFocus - " + mCaptureCallback);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            Timber.tag(TAG).d("lockFocus 2");
            mCaptureCallback.setState(PictureCaptureCallback.STATE_LOCKING);
            Timber.tag(TAG).d("lockFocus 3 - " + mCaptureSession);
            if (mCaptureSession != null) mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
            Timber.tag(TAG).d("lockFocus 4");
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("Failed to lock focus.", e);
        }
    }


    /**
     * Auto focus on input coordinates
     */

    // Much credit - https://gist.github.com/royshil/8c760c2485257c85a11cafd958548482
    void setFocusArea(float x, float y) {
        Timber.tag(TAG).d("setFocusArea - " + x + "/" + y + ": " + mCaptureSession);
        if (mCaptureSession == null) {
            return;
        }
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("setFocusArea - mPreviewRequestBuilder is null");
            return;
        }

        CameraCaptureSession.CaptureCallback captureCallbackHandler = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                Timber.tag(TAG).d("onCaptureCompleted (from setFocusArea) 1");
                super.onCaptureCompleted(session, request, result);

                if (mPreviewRequestBuilder == null) {
                    Timber.tag(TAG).e("onCaptureCompleted - mPreviewRequestBuilder is null");
                    return;
                }

                Timber.tag(TAG).d("onCaptureCompleted (from setFocusArea) 2 - " + request.getTag());
                if (request.getTag() == "FOCUS_TAG") {
                    Timber.tag(TAG).d("onCaptureCompleted (from setFocusArea) 3");
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
                    try {
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                                null);
                        mCaptureCallback.setState(PictureCaptureCallback.STATE_PREVIEW);
                    } catch (Exception e) {
                        Timber.tag(TAG).e("Failed to manual focus.", e);
                    }
                }
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                Timber.tag(TAG).d("onCaptureFailed");
                super.onCaptureFailed(session, request, failure);
                Timber.tag(TAG).e("Manual AF failure: " + failure);
            }
        };

        Timber.tag(TAG).d("setFocusArea 2");
        try {
            mCaptureSession.stopRepeating();
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("Failed to manual focus.", e);
        }
        Timber.tag(TAG).d("setFocusArea 3");

        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        try {
            mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, null);
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("Failed to manual focus.", e);
        }
        Timber.tag(TAG).d("setFocusArea 4");

        if (isMeteringAreaAFSupported()) {
            Timber.tag(TAG).d("setFocusArea 5");
            MeteringRectangle focusAreaTouch = calculateFocusArea(x, y);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusAreaTouch});
        }
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
        mPreviewRequestBuilder.setTag("FOCUS_TAG");

        Timber.tag(TAG).d("setFocusArea 6");
        try {
            mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, null);
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("Failed to manual focus.", e);
        }
        Timber.tag(TAG).d("setFocusArea 7");
    }

    private boolean isMeteringAreaAFSupported() {
        return mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) >= 1;
    }

    private MeteringRectangle calculateFocusArea(float x, float y) {
        final Rect sensorArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        // Current iOS spec has a requirement on sensor orientation that doesn't change, spec followed here.
        final int xCoordinate = (int)(y  * (float)sensorArraySize.height());
        final int yCoordinate = (int)(x * (float)sensorArraySize.width());
        final int halfTouchWidth  = 150;  //TODO: this doesn't represent actual touch size in pixel. Values range in [3, 10]...
        final int halfTouchHeight = 150;
        MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(yCoordinate - halfTouchWidth,  0),
                Math.max(xCoordinate - halfTouchHeight, 0),
                halfTouchWidth  * 2,
                halfTouchHeight * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);

        return focusAreaTouch;
    }

    /**
     * Captures a still picture.
     */
    void captureStillPicture() {
        Timber.tag(TAG).d("captureStillPicture");
        try {
            if (mCamera == null) {
                Timber.tag(TAG).e("captureStillPicture - mCamera is null");
                return;
            }
            if (mPreviewRequestBuilder == null) {
                Timber.tag(TAG).e("captureStillPicture - mPreviewRequestBuilder is null");
                return;
            }
            if (mCaptureSession == null) {
                Timber.tag(TAG).e("captureStillPicture - mCaptureSession is null");
                return;
            }

            CaptureRequest.Builder captureRequestBuilder = mCamera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            if (mIsScanning) {
                mImageFormat = ImageFormat.JPEG;
                captureRequestBuilder.removeTarget(mScanImageReader.getSurface());
            }
            captureRequestBuilder.addTarget(mStillImageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE));
            switch (mFlash) {
                case Constants.FLASH_OFF:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                            CaptureRequest.FLASH_MODE_OFF);
                    break;
                case Constants.FLASH_ON:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    break;
                case Constants.FLASH_TORCH:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                            CaptureRequest.FLASH_MODE_TORCH);
                    break;
                case Constants.FLASH_AUTO:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    break;
                case Constants.FLASH_RED_EYE:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    break;
            }
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOutputRotation());


            if(mCaptureCallback.getOptions().hasKey("quality")){
                int quality = (int) (mCaptureCallback.getOptions().getDouble("quality") * 100);
                captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY, (byte)quality);
            }

            Timber.tag(TAG).d("captureStillPicture 2");
            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, mPreviewRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION));

            if (mCaptureSession == null) {
                Timber.tag(TAG).e("captureStillPicture - mCaptureSession is null (2)");
                return;
            }

            // Stop preview and capture a still picture.
            Timber.tag(TAG).d("captureStillPicture 3");
            if (mCaptureSession == null) {
                Timber.tag(TAG).e("captureStillPicture - mCaptureSession is null (3)");
                return;
            }

            mCaptureSession.stopRepeating();
            Timber.tag(TAG).d("captureStillPicture 4");

            if (mCaptureSession == null) {
                Timber.tag(TAG).e("captureStillPicture - mCaptureSession is null (4)");
                return;
            }

            mCaptureSession.capture(captureRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            Timber.tag(TAG).d("captureStillPicture - onCaptureCompleted");
                            if (mCaptureCallback.getOptions().hasKey("pauseAfterCapture")
                                    && !mCaptureCallback.getOptions().getBoolean("pauseAfterCapture")) {
                                unlockFocus();
                            }
                        }
                    }, null);
            Timber.tag(TAG).d("captureStillPicture 5");
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("Cannot capture a still picture.", e);
        } catch (IllegalStateException e) {
            Timber.tag(TAG).e("Cannot capture still picture - Camera is probably closed", e);
        }
    }

    private int getOutputRotation() {
        @SuppressWarnings("ConstantConditions")
        int sensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // updated and copied from Camera1
        if (mFacing == Constants.FACING_BACK) {
            return (sensorOrientation + mDeviceOrientation) % 360;
        } else {
            final int landscapeFlip = isLandscape(mDeviceOrientation) ? 180 : 0;
            return (sensorOrientation + mDeviceOrientation + landscapeFlip) % 360;
        }
    }

    /**
     * Test if the supplied orientation is in landscape.
     *
     * @param orientationDegrees Orientation in degrees (0,90,180,270)
     * @return True if in landscape, false if portrait
     */
    private boolean isLandscape(int orientationDegrees) {
        return (orientationDegrees == Constants.LANDSCAPE_90 ||
                orientationDegrees == Constants.LANDSCAPE_270);
    }

    private void setUpMediaRecorder(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
        mMediaRecorder = new MediaRecorder();

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        if (recordAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }

        mMediaRecorder.setOutputFile(path);
        mVideoPath = path;

        CamcorderProfile camProfile = profile;
        if (!CamcorderProfile.hasProfile(Integer.parseInt(mCameraId), profile.quality)) {
            camProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
        }
        camProfile.videoBitRate = profile.videoBitRate;
        setCamcorderProfile(camProfile, recordAudio);

        mMediaRecorder.setOrientationHint(getOutputRotation());

        if (maxDuration != -1) {
            mMediaRecorder.setMaxDuration(maxDuration);
        }
        if (maxFileSize != -1) {
            mMediaRecorder.setMaxFileSize(maxFileSize);
        }

        mMediaRecorder.setOnInfoListener(this);
        mMediaRecorder.setOnErrorListener(this);
    }

    private void setCamcorderProfile(CamcorderProfile profile, boolean recordAudio) {
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mMediaRecorder.setVideoEncoder(profile.videoCodec);
        if (recordAudio) {
            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
        }
    }

    private void stopMediaRecorder() {
        mIsRecording = false;
        try {
            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            mMediaRecorder.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        mMediaRecorder.reset();
        mMediaRecorder.release();
        mMediaRecorder = null;

        if (mVideoPath == null || !new File(mVideoPath).exists()) {
            // @TODO: implement videoOrientation and deviceOrientation calculation
            mCallback.onVideoRecorded(null, 0 , 0);
            return;
        }
        // @TODO: implement videoOrientation and deviceOrientation calculation
        mCallback.onVideoRecorded(mVideoPath, 0, 0);
        mVideoPath = null;
    }

    /**
     * Unlocks the auto-focus and restart camera preview. This is supposed to be called after
     * capturing a still picture.
     */
    void unlockFocus() {
        Timber.tag(TAG).d("unlockFocus");
        if (mPreviewRequestBuilder == null) {
            Timber.tag(TAG).e("unlockFocus - mPreviewRequestBuilder is null");
            return;
        }
        if (mCaptureSession == null) {
            Timber.tag(TAG).e("unlockFocus - mCaptureSession is null");
            return;
        }

        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        try {
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
            updateAutoFocus();
            updateFlash();
            if (mIsScanning) {
                Timber.tag(TAG).d("unlockFocus 2");
                mImageFormat = ImageFormat.YUV_420_888;
                startCaptureSession();
            } else {
                Timber.tag(TAG).d("unlockFocus 3");
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                        null);
                mCaptureCallback.setState(PictureCaptureCallback.STATE_PREVIEW);
            }
        } catch (CameraAccessException e) {
            Timber.tag(TAG).e("Failed to restart camera preview.", e);
        }
    }

    /**
     * Called when an something occurs while recording.
     */
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if ( what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            stopRecording();
        }
    }

    /**
     * Called when an error occurs while recording.
     */
    public void onError(MediaRecorder mr, int what, int extra) {
        stopRecording();
    }

    /**
     * A {@link CameraCaptureSession.CaptureCallback} for capturing a still picture.
     */
    private static abstract class PictureCaptureCallback
            extends CameraCaptureSession.CaptureCallback {

        static final int STATE_PREVIEW = 0;
        static final int STATE_LOCKING = 1;
        static final int STATE_LOCKED = 2;
        static final int STATE_PRECAPTURE = 3;
        static final int STATE_WAITING = 4;
        static final int STATE_CAPTURING = 5;

        private int mState;
        private ReadableMap mOptions = null;

        PictureCaptureCallback() {
        }

        void setState(int state) {
            mState = state;
        }

        void setOptions(ReadableMap options) { mOptions = options; }

        ReadableMap getOptions() { return mOptions; }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
        }

        private void process(@NonNull CaptureResult result) {
            Timber.tag(TAG).d("process: " + mState + ":" + result);
            switch (mState) {
                case STATE_LOCKING: {
                    Timber.tag(TAG).d("process: STATE_LOCKING 1");
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (af == null) {
                        break;
                    }
                    Timber.tag(TAG).d("process: STATE_LOCKING 2: " + af);
                    if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                            af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                        Timber.tag(TAG).d("process: STATE_LOCKING 3: " + ae);
                        if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            Timber.tag(TAG).d("process 4");
                            setState(STATE_CAPTURING);
                            onReady();
                        } else {
                            Timber.tag(TAG).d("process 5");
                            setState(STATE_LOCKED);
                            onPrecaptureRequired();
                        }
                    }
                    break;
                }
                case STATE_PRECAPTURE: {
                    Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                    Timber.tag(TAG).d("process: STATE_PRECAPTURE 1: " + ae);
                    if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            ae == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        Timber.tag(TAG).d("process: STATE_PRECAPTURE 2");
                        setState(STATE_WAITING);
                    }
                    break;
                }
                case STATE_WAITING: {
                    Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                    Timber.tag(TAG).d("process: STATE_WAITING 1: " + ae);
                    if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        Timber.tag(TAG).d("process: STATE_WAITING 2");
                        setState(STATE_CAPTURING);
                        onReady();
                    }
                    break;
                }
            }
        }

        /**
         * Called when it is ready to take a still picture.
         */
        public abstract void onReady();

        /**
         * Called when it is necessary to run the precapture sequence.
         */
        public abstract void onPrecaptureRequired();

    }

}