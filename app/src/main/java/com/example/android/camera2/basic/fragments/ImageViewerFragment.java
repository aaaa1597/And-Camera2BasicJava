package com.example.android.camera2.basic.fragments;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.example.android.camera2.basic.R;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ImageViewerFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ImageViewerFragment extends Fragment {
    private final String mAbsolutePath;
    private final int mOrientation;
    private final boolean mIsDepth;

    public ImageViewerFragment(String absolutePath, int orientation, boolean isdepth) {
        mAbsolutePath   = absolutePath;
        mOrientation    = orientation;
        mIsDepth        = isdepth;
    }

    public static Fragment newInstance(String absolutePath, int orientation, boolean isdepth) {
        return new ImageViewerFragment(absolutePath, orientation, isdepth);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_image_viewer, container, false);
    }
}