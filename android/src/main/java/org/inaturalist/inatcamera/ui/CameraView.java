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
import org.inaturalist.inatcamera.R;
import android.view.TextureView;
import android.os.Handler;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.content.res.Resources;
import android.view.Gravity;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.ParcelableCompat;
import androidx.core.os.ParcelableCompatCreatorCallbacks;
import androidx.core.view.ViewCompat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.graphics.SurfaceTexture;
import android.view.View.OnTouchListener;
import android.util.Log;
import android.app.Activity;

import com.facebook.react.bridge.ReadableMap;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import android.widget.RelativeLayout;
import android.widget.LinearLayout;
import android.widget.FrameLayout;

public class CameraView extends FrameLayout implements OnTouchListener {

    private static final String TAG = "CameraView";

    /** The camera device faces the opposite direction as the device's screen. */
    public static final int FACING_BACK = Constants.FACING_BACK;

    /** The camera device faces the same direction as the device's screen. */
    public static final int FACING_FRONT = Constants.FACING_FRONT;

    /** Direction the camera faces relative to device screen. */
    @IntDef({FACING_BACK, FACING_FRONT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Facing {
    }

    /** Flash will not be fired. */
    public static final int FLASH_OFF = Constants.FLASH_OFF;

    /** Flash will always be fired during snapshot. */
    public static final int FLASH_ON = Constants.FLASH_ON;

    /** Constant emission of light during preview, auto-focus and snapshot. */
    public static final int FLASH_TORCH = Constants.FLASH_TORCH;

    /** Flash will be fired automatically when required. */
    public static final int FLASH_AUTO = Constants.FLASH_AUTO;

    /** Flash will be fired in red-eye reduction mode. */
    public static final int FLASH_RED_EYE = Constants.FLASH_RED_EYE;

    /** The mode for for the camera device's flash control */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FLASH_OFF, FLASH_ON, FLASH_TORCH, FLASH_AUTO, FLASH_RED_EYE})
    public @interface Flash {
    }

    protected CameraViewImpl mImpl;

    private final CallbackBridge mCallbacks;

    private boolean mAdjustViewBounds;

    private Context mContext;

    private final DisplayOrientationDetector mDisplayOrientationDetector;

    private View mFocusAreaView;

    protected HandlerThread mBgThread;
    protected Handler mBgHandler;

    protected float mPreviousFingerSpacing = 0;

    public CameraView(Context context, boolean fallbackToOldApi) {
        this(context, null, fallbackToOldApi);
    }

    public CameraView(Context context, AttributeSet attrs, boolean fallbackToOldApi) {
        this(context, attrs, 0, fallbackToOldApi);
    }

    @SuppressWarnings("WrongConstant")
    public CameraView(Context context, AttributeSet attrs, int defStyleAttr, boolean fallbackToOldApi) {
        super(context, attrs, defStyleAttr);

        // bg hanadler for non UI heavy work
        mBgThread = new HandlerThread("RNCamera-Handler-Thread");
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());


        if (isInEditMode()){
            mCallbacks = null;
            mDisplayOrientationDetector = null;
            return;
        }
        mAdjustViewBounds = true;
        mContext = context;

        // Internal setup
        final PreviewImpl preview = createPreviewImpl(context);
        mCallbacks = new CallbackBridge();
        Timber.tag(TAG).d("CameraView ctor - " + preview);
        if (Build.VERSION.SDK_INT < 23) {
            mImpl = new Camera2(mCallbacks, preview, context, mBgHandler);
        } else {
            mImpl = new Camera2Api23(mCallbacks, preview, context, mBgHandler);
        }

        // Display orientation detector
        mDisplayOrientationDetector = new DisplayOrientationDetector(context) {
            @Override
            public void onDisplayOrientationChanged(int displayOrientation, int deviceOrientation) {
                mImpl.setDisplayOrientation(displayOrientation);
                mImpl.setDeviceOrientation(deviceOrientation);
            }
        };

        mFocusAreaView = preview.getFocusAreaView();
        mFocusAreaView.setVisibility(View.INVISIBLE);

        this.setOnTouchListener(this);
    }

    public void cleanup(){
        if(mBgThread != null){
            mBgThread.quitSafely();
            mBgThread = null;
        }
    }

    @NonNull
    private PreviewImpl createPreviewImpl(Context context) {
        PreviewImpl preview = new TextureViewPreview(context, this);
        return preview;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isInEditMode()) {
            mDisplayOrientationDetector.enable(ViewCompat.getDisplay(this));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!isInEditMode()) {
            mDisplayOrientationDetector.disable();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode2 = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode2 = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        Timber.tag(TAG).d("onMeasure1 " + widthMode2 + ":" + widthSize + " / " + heightMode2 + ":" + heightSize);
        Timber.tag(TAG).d("onMeasure1b " + MeasureSpec.EXACTLY + "/" + MeasureSpec.AT_MOST);

        if (isInEditMode()){
            Timber.tag(TAG).d("onMeasure - exit " + widthMeasureSpec + "/" + heightMeasureSpec);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Handle android:adjustViewBounds
        if (mAdjustViewBounds) {
            Timber.tag(TAG).d("onMeasure 2 " + widthMeasureSpec + "/" + heightMeasureSpec);

            final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            if (widthMode == MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
                final AspectRatio ratio = getAspectRatio();
                assert ratio != null;
                int height = (int) (MeasureSpec.getSize(widthMeasureSpec) * ratio.toFloat());
                if (heightMode == MeasureSpec.AT_MOST) {
                    height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
                }
                Timber.tag(TAG).d("onMeasure 3" + widthMeasureSpec + "/" + heightMeasureSpec);
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            } else if (widthMode != MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
                final AspectRatio ratio = getAspectRatio();
                assert ratio != null;
                int width = (int) (MeasureSpec.getSize(heightMeasureSpec) * ratio.toFloat());
                if (widthMode == MeasureSpec.AT_MOST) {
                    width = Math.min(width, MeasureSpec.getSize(widthMeasureSpec));
                }
                Timber.tag(TAG).d("onMeasure 4" + widthMeasureSpec + "/" + heightMeasureSpec);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        heightMeasureSpec);
            } else {
                Timber.tag(TAG).d("onMeasure 5" + widthMeasureSpec + "/" + heightMeasureSpec);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        } else {
            Timber.tag(TAG).d("onMeasure 6" + widthMeasureSpec + "/" + heightMeasureSpec);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
        // Measure the TextureView
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        AspectRatio ratio = getAspectRatio();
        if (mDisplayOrientationDetector.getLastKnownDisplayOrientation() % 180 == 0) {
            ratio = ratio.inverse();
        }
        assert ratio != null;
        Timber.tag(TAG).d("onMeasure 7" + widthMeasureSpec + "/" + heightMeasureSpec);
        if (height < width * ratio.getY() / ratio.getX()) {
            mImpl.getView().measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(width * ratio.getY() / ratio.getX(),
                            MeasureSpec.EXACTLY));
        } else {
            mImpl.getView().measure(
                    MeasureSpec.makeMeasureSpec(height * ratio.getX() / ratio.getY(),
                            MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState());
        state.facing = getFacing();
        state.cameraId = getCameraId();
        state.ratio = getAspectRatio();
        state.autoFocus = getAutoFocus();
        state.flash = getFlash();
        state.exposure = getExposureCompensation();
        state.focusDepth = getFocusDepth();
        state.zoom = getZoom();
        state.whiteBalance = getWhiteBalance();
        state.scanning = getScanning();
        state.pictureSize = getPictureSize();
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        setFacing(ss.facing);
        setCameraId(ss.cameraId);
        setAspectRatio(ss.ratio);
        setAutoFocus(ss.autoFocus);
        setFlash(ss.flash);
        setExposureCompensation(ss.exposure);
        setFocusDepth(ss.focusDepth);
        setZoom(ss.zoom);
        setWhiteBalance(ss.whiteBalance);
        setScanning(ss.scanning);
        setPictureSize(ss.pictureSize);
    }

    /**
     * Open a camera device and start showing camera preview. This is typically called from
     * {@link Activity#onResume()}.
     */
    public void start() {
        Timber.tag(TAG).d("CameraView - start 1");

        if (!mImpl.start()) {
            Timber.tag(TAG).d("CameraView - start 2");
            if (mImpl.getView() != null) {
                Handler mainHandler = new Handler(mContext.getMainLooper());
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        CameraView.this.removeView(mImpl.getView());
                    }
                };
                mainHandler.post(runnable);
            }
        }
    }

    /**
     * Stop camera preview and close the device. This is typically called from
     * {@link Activity#onPause()}.
     */
    public void stop() {
        Timber.tag(TAG).d("CameraView - stop 1");
        mImpl.stop();
    }

    /**
     * @return {@code true} if the camera is opened.
     */
    public boolean isCameraOpened() {
        return mImpl.isCameraOpened();
    }

    /**
     * Add a new callback.
     *
     * @param callback The {@link Callback} to add.
     * @see #removeCallback(Callback)
     */
    public void addCallback(@NonNull Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Remove a callback.
     *
     * @param callback The {@link Callback} to remove.
     * @see #addCallback(Callback)
     */
    public void removeCallback(@NonNull Callback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * @param adjustViewBounds {@code true} if you want the CameraView to adjust its bounds to
     *                         preserve the aspect ratio of camera.
     * @see #getAdjustViewBounds()
     */
    public void setAdjustViewBounds(boolean adjustViewBounds) {
        if (mAdjustViewBounds != adjustViewBounds) {
            mAdjustViewBounds = adjustViewBounds;
            requestLayout();
        }
    }

    /**
     * @return True when this CameraView is adjusting its bounds to preserve the aspect ratio of
     * camera.
     * @see #setAdjustViewBounds(boolean)
     */
    public boolean getAdjustViewBounds() {
        return mAdjustViewBounds;
    }

    public View getView() {
        if (mImpl != null) {
            return mImpl.getView();
        }
        return null;
    }

    public TextureView getTextureView() {
        if (mImpl != null) {
            return mImpl.getTextureView();
        }
        return null;
    }


    /**
     * Chooses camera by the direction it faces.
     *
     * @param facing The camera facing. Must be either {@link #FACING_BACK} or
     *               {@link #FACING_FRONT}.
     */
    public void setFacing(@Facing int facing) {
        mImpl.setFacing(facing);
    }

    /**
     * Gets the direction that the current camera faces.
     *
     * @return The camera facing.
     */
    @Facing
    public int getFacing() {
        //noinspection WrongConstant
        return mImpl.getFacing();
    }

    /**
     * Chooses camera by its camera iD
     *
     * @param id The camera ID
     */
    public void setCameraId(String id) {
        mImpl.setCameraId(id);
    }

    /**
     * Gets the currently set camera ID
     *
     * @return The camera facing.
     */
    public String getCameraId() {
        return mImpl.getCameraId();
    }

    /**
     * Gets all the aspect ratios supported by the current camera.
     */
    public Set<AspectRatio> getSupportedAspectRatios() {
        return mImpl.getSupportedAspectRatios();
    }

    /**
     * Gets all the camera IDs supported by the phone as a String
     */
    public List<Properties> getCameraIds() {
        return mImpl.getCameraIds();
    }

    /**
     * Sets the aspect ratio of camera.
     *
     * @param ratio The {@link AspectRatio} to be set.
     */
    public void setAspectRatio(@NonNull AspectRatio ratio) {
        if (mImpl.setAspectRatio(ratio)) {
            requestLayout();
        }
    }

    /**
     * Gets the current aspect ratio of camera.
     *
     * @return The current {@link AspectRatio}. Can be {@code null} if no camera is opened yet.
     */
    @Nullable
    public AspectRatio getAspectRatio() {
        return mImpl.getAspectRatio();
    }

    /**
     * Gets all the picture sizes for particular ratio supported by the current camera.
     *
     * @param ratio {@link AspectRatio} for which the available image sizes will be returned.
     */
    public SortedSet<Size> getAvailablePictureSizes(@NonNull AspectRatio ratio) {
        return mImpl.getAvailablePictureSizes(ratio);
    }

    /**
     * Sets the size of taken pictures.
     *
     * @param size The {@link Size} to be set.
     */
    public void setPictureSize(@NonNull Size size) {
        mImpl.setPictureSize(size);
    }

    /**
     * Gets the size of pictures that will be taken.
     */
    public Size getPictureSize() {
        return mImpl.getPictureSize();
    }

    /**
     * Enables or disables the continuous auto-focus mode. When the current camera doesn't support
     * auto-focus, calling this method will be ignored.
     *
     * @param autoFocus {@code true} to enable continuous auto-focus mode. {@code false} to
     *                  disable it.
     */
    public void setAutoFocus(boolean autoFocus) {
        mImpl.setAutoFocus(autoFocus);
    }

    /**
     * Returns whether the continuous auto-focus mode is enabled.
     *
     * @return {@code true} if the continuous auto-focus mode is enabled. {@code false} if it is
     * disabled, or if it is not supported by the current camera.
     */
    public boolean getAutoFocus() {
        return mImpl.getAutoFocus();
    }

    /**
     * Sets the flash mode.
     *
     * @param flash The desired flash mode.
     */
    public void setFlash(@Flash int flash) {
        mImpl.setFlash(flash);
    }

    /**
     * Gets the current flash mode.
     *
     * @return The current flash mode.
     */
    @Flash
    public int getFlash() {
        //noinspection WrongConstant
        return mImpl.getFlash();
    }

    public void setExposureCompensation(float exposure) {
        mImpl.setExposureCompensation(exposure);
    }

    public float getExposureCompensation() {
        return mImpl.getExposureCompensation();
    }


    /**
     * Gets the camera orientation relative to the devices native orientation.
     *
     * @return The orientation of the camera.
     */
    public int getCameraOrientation() {
        return mImpl.getCameraOrientation();
    }

    /**
     * Sets the auto focus point.
     *
     * @param x sets the x coordinate for camera auto focus
     * @param y sets the y coordinate for camera auto focus
     */
    public void setAutoFocusPointOfInterest(float x, float y) {
        mImpl.setFocusArea(x, y);
    }

    public void setFocusDepth(float value) {
        mImpl.setFocusDepth(value);
    }

    public float getFocusDepth() { return mImpl.getFocusDepth(); }

    public void setZoom(float zoom) {
        mImpl.setZoom(zoom);
    }

    public float getZoom() {
        return mImpl.getZoom();
    }

    public void setWhiteBalance(int whiteBalance) {
        mImpl.setWhiteBalance(whiteBalance);
    }

    public int getWhiteBalance() {
        return mImpl.getWhiteBalance();
    }

    public void setScanning(boolean isScanning) { mImpl.setScanning(isScanning);}

    public boolean getScanning() { return mImpl.getScanning(); }

    /**
     * Take a picture. The result will be returned to
     * {@link Callback#onPictureTaken(CameraView, byte[], int)}.
     */
    public void takePicture(ReadableMap options) {
        mImpl.takePicture(options);
    }

    /**
     * Record a video and save it to file. The result will be returned to
     * {@link Callback#onVideoRecorded(CameraView, String, int, int)}.
     * @param path Path to file that video will be saved to.
     * @param maxDuration Maximum duration of the recording, in seconds.
     * @param maxFileSize Maximum recording file size, in bytes.
     * @param profile Quality profile of the recording.
     */
    public boolean record(String path, int maxDuration, int maxFileSize,
                          boolean recordAudio, CamcorderProfile profile, int orientation) {
        return mImpl.record(path, maxDuration, maxFileSize, recordAudio, profile, orientation);
    }

    public void stopRecording() {
        mImpl.stopRecording();
    }

    public void resumePreview() {
        mImpl.resumePreview();
    }

    public void pausePreview() {
        mImpl.pausePreview();
    }

    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        mImpl.setPreviewTexture(surfaceTexture);
    }

    public Size getPreviewSize() {
        return mImpl.getPreviewSize();
    }

    private class CallbackBridge implements CameraViewImpl.Callback {

        private final ArrayList<Callback> mCallbacks = new ArrayList<>();

        private boolean mRequestLayoutOnOpen;

        CallbackBridge() {
        }

        public void add(Callback callback) {
            mCallbacks.add(callback);
        }

        public void remove(Callback callback) {
            mCallbacks.remove(callback);
        }

        @Override
        public void onCameraOpened() {
            if (mRequestLayoutOnOpen) {
                mRequestLayoutOnOpen = false;
                requestLayout();
            }
            for (Callback callback : mCallbacks) {
                callback.onCameraOpened(CameraView.this);
            }
        }

        @Override
        public void onCameraClosed() {
            for (Callback callback : mCallbacks) {
                callback.onCameraClosed(CameraView.this);
            }
        }

        @Override
        public void onPictureTaken(byte[] data, int deviceOrientation) {
            for (Callback callback : mCallbacks) {
                callback.onPictureTaken(CameraView.this, data, deviceOrientation);
            }
        }

        @Override
        public void onVideoRecorded(String path, int videoOrientation, int deviceOrientation) {
            for (Callback callback : mCallbacks) {
                callback.onVideoRecorded(CameraView.this, path, videoOrientation, deviceOrientation);
            }
        }

        @Override
        public void onFramePreview(byte[] data, int width, int height, int orientation) {
            for (Callback callback : mCallbacks) {
                callback.onFramePreview(CameraView.this, data, width, height, orientation);
            }
        }

        @Override
        public void onMountError(Exception exc) {
            for (Callback callback : mCallbacks) {
                callback.onMountError(CameraView.this, exc);
            }
        }

        public void reserveRequestLayoutOnOpen() {
            mRequestLayoutOnOpen = true;
        }
    }

    protected static class SavedState extends BaseSavedState {

        @Facing
        int facing;

        String cameraId;

        AspectRatio ratio;

        boolean autoFocus;

        @Flash
        int flash;

        float exposure;

        float focusDepth;

        float zoom;

        int whiteBalance;

        boolean scanning;

        Size pictureSize;

        @SuppressWarnings("WrongConstant")
        public SavedState(Parcel source, ClassLoader loader) {
            super(source);
            facing = source.readInt();
            cameraId = source.readString();
            ratio = source.readParcelable(loader);
            autoFocus = source.readByte() != 0;
            flash = source.readInt();
            exposure = source.readFloat();
            focusDepth = source.readFloat();
            zoom = source.readFloat();
            whiteBalance = source.readInt();
            scanning = source.readByte() != 0;
            pictureSize = source.readParcelable(loader);
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(facing);
            out.writeString(cameraId);
            out.writeParcelable(ratio, 0);
            out.writeByte((byte) (autoFocus ? 1 : 0));
            out.writeInt(flash);
            out.writeFloat(exposure);
            out.writeFloat(focusDepth);
            out.writeFloat(zoom);
            out.writeInt(whiteBalance);
            out.writeByte((byte) (scanning ? 1 : 0));
            out.writeParcelable(pictureSize, flags);
        }

        public static final Creator<SavedState> CREATOR
                = ParcelableCompat.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {

            @Override
            public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                return new SavedState(in, loader);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }

        });

    }

    /**
     * Callback for monitoring events about {@link CameraView}.
     */
    @SuppressWarnings("UnusedParameters")
    public abstract static class Callback {

        /**
         * Called when camera is opened.
         *
         * @param cameraView The associated {@link CameraView}.
         */
        public void onCameraOpened(CameraView cameraView) {
        }

        /**
         * Called when camera is closed.
         *
         * @param cameraView The associated {@link CameraView}.
         */
        public void onCameraClosed(CameraView cameraView) {
        }

        /**
         * Called when a picture is taken.
         *
         * @param cameraView The associated {@link CameraView}.
         * @param data       JPEG data.
         */
        public void onPictureTaken(CameraView cameraView, byte[] data, int deviceOrientation) {
        }

        /**
         * Called when a video is recorded.
         *
         * @param cameraView The associated {@link CameraView}.
         * @param path       Path to recoredd video file.
         */
        public void onVideoRecorded(CameraView cameraView, String path, int videoOrientation, int deviceOrientation) {
        }

        public void onFramePreview(CameraView cameraView, byte[] data, int width, int height, int orientation) {
        }

        public void onMountError(CameraView cameraView, Exception exc) {}
    }

    private float getFingerSpacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        try {
            float currentFingerSpacing;
            float zoomLevel = mImpl.getZoom();
            float maximumZoomLevel = mImpl.getMaxZoom();

            if (event.getPointerCount() == 2) { // Multi touch
                currentFingerSpacing = getFingerSpacing(event);
                float delta = 0.03f; // Control this value to control the zooming sensibility

                if (Math.abs(currentFingerSpacing - mPreviousFingerSpacing) < 4) {
                    mPreviousFingerSpacing = currentFingerSpacing;
                    return true;
                }

                if (mPreviousFingerSpacing != 0) {
                    if (currentFingerSpacing > mPreviousFingerSpacing) { // Don't over zoom-in
                        zoomLevel = Math.min(zoomLevel + delta, maximumZoomLevel);
                    } else if (currentFingerSpacing < mPreviousFingerSpacing){ // Don't over zoom-out
                        zoomLevel = Math.max(zoomLevel - delta, 0);
                    }
                    mImpl.setZoom(zoomLevel);
                }
                mPreviousFingerSpacing = currentFingerSpacing;

            } else {
                // Single touch point, needs to return true in order to detect one more touch point
                mPreviousFingerSpacing = 0;

                final int actionMasked = event.getActionMasked();
                if (actionMasked != MotionEvent.ACTION_UP) {
                    return true;
                }

                // Tap to focus

                float pageX = event.getX();
                float pageY = event.getY();

                float top = this.getView().getTop();
                float left = this.getView().getLeft();
                float width = this.getView().getWidth();
                float height = this.getView().getHeight();

                // compensate for top/left changes
                float pageX2 = pageX - left;
                float pageY2 = pageY - top;

                // normalize coords as described by https://gist.github.com/Craigtut/6632a9ac7cfff55e74fb561862bc4edb
                float x0 = pageX2 / width;
                float y0 = pageY2 / height;
                float x = x0;
                float y = y0;

                x = y0;
                y = -x0 + 1;

                Timber.tag(TAG).d("Tap to focus - " + x + "/" + y);

                // Set auto focus area for camera
                setAutoFocusPointOfInterest(x, y);

                // Show auto focus square
                mFocusAreaView.setVisibility(View.VISIBLE);

                mFocusAreaView.setY(pageY2 - dpToPx(50));
                mFocusAreaView.setX(pageX2 - dpToPx(50));

                mFocusAreaView.setAlpha(1.0f);
                mFocusAreaView.animate()
                        .alpha(0f)
                        .setDuration(1300)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                mFocusAreaView.setVisibility(View.INVISIBLE);
                            }
                        })
                        .start();
            }
            return true;
        } catch (final Exception e) {
            return true;
        }
    }

    private int pxToDp(int px) {
        return (int) (px / Resources.getSystem().getDisplayMetrics().density);
    }

    private float dpToPx(int dp) {
        Resources r = mContext.getResources();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, r.getDisplayMetrics());
    }

}