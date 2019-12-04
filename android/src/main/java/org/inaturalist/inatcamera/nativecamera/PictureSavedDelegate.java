package org.inaturalist.inatcamera.nativecamera;

import com.facebook.react.bridge.WritableMap;

public interface PictureSavedDelegate {
    void onPictureSaved(WritableMap response);
}