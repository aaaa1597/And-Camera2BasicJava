package com.example.android.camera2.basic.fragments;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.example.android.camera2.basic.R;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import kotlin.jvm.functions.Function3;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ImageViewerFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ImageViewerFragment extends Fragment {
    private final String mAbsolutePath;
    private final int mOrientation;
    private final boolean mIsDepth;
    private BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
    /** Bitmap transformation derived from passed arguments */
    private Matrix bitmapTransformation = new Matrix();
    /** Flag indicating that there is depth data available for this image */
    private boolean isDepth;
    /** Data backing our Bitmap viewpager */
    private List<Bitmap> bitmapList = new ArrayList<Bitmap>();

    public ImageViewerFragment(String absolutePath, int orientation, boolean isdepth) {
        mAbsolutePath   = absolutePath;
        mOrientation    = orientation;
        mIsDepth        = isdepth;

        bitmapOptions.inJustDecodeBounds = false;

        // Keep Bitmaps at less than 1 MP
        if(Math.max(bitmapOptions.outHeight, bitmapOptions.outWidth) > DOWNSAMPLE_SIZE) {
            int scaleFactorX = bitmapOptions.outWidth / DOWNSAMPLE_SIZE + 1;
            int scaleFactorY = bitmapOptions.outHeight/ DOWNSAMPLE_SIZE + 1;
            bitmapOptions.inSampleSize = Math.max(scaleFactorX, scaleFactorY);
        }

        bitmapTransformation = decodeExifOrientation(orientation);

        isDepth = isdepth;
    }

    public static Fragment newInstance(String absolutePath, int orientation, boolean isdepth) {
        return new ImageViewerFragment(absolutePath, orientation, isdepth);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_image_viewer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Load input image file
        Bitmap bmp = BitmapFactory.decodeFile(mAbsolutePath, bitmapOptions);

        // Load the main JPEG image
        ((ImageView)view.findViewById(R.id.ivw_pictureviewer)).setImageBitmap(bmp);

        // If we have depth data attached, attempt to load it
        if (isDepth) {
//            try {
//                val depthStart = findNextJpegEndMarker(inputBuffer, 2)
//                addItemToViewPager(view, decodeBitmap(
//                        inputBuffer, depthStart, inputBuffer.size - depthStart))
//
//                val confidenceStart = findNextJpegEndMarker(inputBuffer, depthStart)
//                addItemToViewPager(view, decodeBitmap(
//                        inputBuffer, confidenceStart, inputBuffer.size - confidenceStart))
//
//            } catch (exc: RuntimeException) {
//                Log.e(TAG, "Invalid start marker for depth or confidence data")
//            }
        }
    }

    /** Maximum size of [Bitmap] decoded */
    private static int DOWNSAMPLE_SIZE = 1024;  // 1MP
    /** These are the magic numbers used to separate the different JPG data chunks */
    private List<Integer> JPEG_DELIMITER_BYTES = Arrays.asList(-1, -39);

    private Matrix decodeExifOrientation(int exifOrientation) {
        Matrix matrix = new Matrix();

        // Apply transformation corresponding to declared EXIF orientation
        switch(exifOrientation) {
            case ExifInterface.ORIENTATION_NORMAL:          matrix.reset(); break;
            case ExifInterface.ORIENTATION_UNDEFINED:       matrix.reset(); break;
            case ExifInterface.ORIENTATION_ROTATE_90:       matrix.postRotate(90F);
            case ExifInterface.ORIENTATION_ROTATE_180:      matrix.postRotate(180F);
            case ExifInterface.ORIENTATION_ROTATE_270:      matrix.postRotate(270F);
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1F, 1F);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:   matrix.postScale(1F, -1F);
            case ExifInterface.ORIENTATION_TRANSPOSE: {     matrix.postScale(-1F, 1F);
                                                            matrix.postRotate(270F); }
            case ExifInterface.ORIENTATION_TRANSVERSE: {    matrix.postScale(-1F, 1F);
                                                            matrix.postRotate(90F); }
            // Error out if the EXIF orientation is invalid
            default: Log.e("aaaaa", "Invalid orientation: $exifOrientation");
        }

        // Return the resulting matrix
        return matrix;
    }

    private class BitmapListAdapter extends RecyclerView.Adapter<BitmapListAdapter.BitmapListViewHolder> {
        private class BitmapListViewHolder extends RecyclerView.ViewHolder {
            View view;
            public BitmapListViewHolder(@NonNull View itemView) {
                super(itemView);
                view = itemView;
            }
        }

        private List<Bitmap> dataset;
        private int itemLayoutId = -1;
        private Function<View, Void> itemViewFactory = null;
        private Function3<View, Bitmap, Integer, Void> onBind;

        public BitmapListAdapter(List<Bitmap> adataset, int aitemLayoutId, Function<View, Void> aitemViewFactory, Function3<View, Bitmap, Integer, Void> aonBind) {
            dataset = adataset;
            itemLayoutId = aitemLayoutId;
            itemViewFactory = aitemViewFactory;
            onBind = aonBind;
        }
        @NonNull
        @Override
        public BitmapListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if(itemViewFactory != null) itemViewFactory.apply(parent);
            if(itemLayoutId != -1) {
                View view = LayoutInflater.from(parent.getContext()).inflate(itemLayoutId, parent, false);
                return new BitmapListViewHolder(view);
            }
            else {
                throw new IllegalStateException("Either the layout ID or the view factory need to be non-null");
            }
        }

        @Override
        public void onBindViewHolder(@NonNull BitmapListViewHolder holder, int position) {
            if (position < 0 || position > dataset.size()) return;
            onBind.invoke(holder.view, dataset.get(position), position);
        }

        @Override
        public int getItemCount() {
            return 0;
        }
    }
}