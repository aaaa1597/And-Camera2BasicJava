package com.example.android.camera2.basic;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;
import java.util.function.IntConsumer;

import kotlin.jvm.functions.Function1;

public class OrientationLiveData extends LiveData<Integer> {
    final Context mcontext;
    final CameraCharacteristics mcharacteristics;
    final private OrientationEventListener listener;
    public OrientationLiveData(Context acontext, CameraCharacteristics acharacteristics) {
        mcontext = acontext;
        mcharacteristics = acharacteristics;
        listener = new OrientationEventListener(mcontext.getApplicationContext()) {
            @Override
            public void onOrientationChanged(int orientation) {
                Function1<Integer, Integer> when = (rotate) -> {
                    if     (rotate <= 45)  return Surface.ROTATION_0;
                    else if(rotate <= 135) return Surface.ROTATION_90;
                    else if(rotate <= 225) return Surface.ROTATION_180;
                    else if(rotate <= 315) return Surface.ROTATION_270;
                    else return Surface.ROTATION_0;
                };
                int rotation = when.invoke(orientation);
                int relative = computeRelativeRotation(mcharacteristics, rotation);
                if (getValue() == null) postValue(relative);
                else if (relative != getValue()) postValue(relative);
            }
        };
    }

    @Override
    protected void onActive() {
        super.onActive();
        listener.enable();
    }

    @Override
    protected void onInactive() {
        super.onInactive();
        listener.disable();
    }

    private static int computeRelativeRotation(CameraCharacteristics characteristics, int surfaceRotation) {
        int sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Function1<Integer, Integer> when = (surfaceOrientation) -> {
            switch(surfaceOrientation) {
                case Surface.ROTATION_0:   return 0;
                case Surface.ROTATION_90:  return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default:                   return 0;
            }
        };
        int deviceOrientationDegrees = when.invoke(surfaceRotation);

        // Reverse device orientation for front-facing cameras
        int sign = (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) ? 1 : -1;

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        return (sensorOrientationDegrees - (deviceOrientationDegrees * sign) + 360) % 360;
    }
}
