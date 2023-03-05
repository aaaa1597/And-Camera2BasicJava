package com.example.android.camera2.basic;

import androidx.lifecycle.ViewModel;

public class CameraViewModel extends ViewModel {
    private String mCameraId = "";
    public String getCameraId() {
        return mCameraId;
    }
    public void setCameraId(String cameraId) {
        mCameraId = cameraId;
    }

    private int mPixelFormat = -1;
    public int getPixelFormat() {
        return mPixelFormat;
    }
    public void setPixelFormat(int format) {
        this.mPixelFormat = format;
    }
}
