package org.inaturalist.inatcamera.nativecamera;

import android.annotation.SuppressLint;
import android.util.Log;

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

public class INatCameraModule extends ReactContextBaseJavaModule {
    private static final String TAG = "INatCameraModule";

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
}

