package com.example.android.camera2.basic.fragments;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.example.android.camera2.basic.R;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import kotlin.jvm.functions.Function1;

public class SelectorFragment extends Fragment {
    List<FormatItem> cameraList;
    public static Fragment newInstance() {
        return new SelectorFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_selector, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView v = (RecyclerView)view.findViewById(R.id.rvw_selector);
        v.setLayoutManager(new LinearLayoutManager(getContext()));

        CameraManager cameraManager = (CameraManager)requireContext().getSystemService(Context.CAMERA_SERVICE);
        cameraList = enumerateCameras(cameraManager);

        v.setAdapter(new SelectorAdapter());
    }

    private List<FormatItem> enumerateCameras(CameraManager cameraManager) {
        List<FormatItem> availableCameras =  new ArrayList<>();

        String[] cameraIdList = null;
        try { cameraIdList = cameraManager.getCameraIdList(); }
        catch(CameraAccessException e) { throw new RuntimeException(e); }

        // Get list of all compatible cameras
        Arrays.stream(cameraIdList)
            .filter(id -> {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean is = Ints.asList(capabilities).contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE);
                    return is;
                }
                catch(CameraAccessException e) { throw new RuntimeException(e); }
            })
            .forEach(id -> {
                CameraCharacteristics characteristics = null;
                try { characteristics = cameraManager.getCameraCharacteristics(id); }
                catch(CameraAccessException e) { throw new RuntimeException(e); }

                Function1<Integer, String> lensOrientationString = val -> {
                    switch(val) {
                        case CameraCharacteristics.LENS_FACING_BACK:    return "Back";
                        case CameraCharacteristics.LENS_FACING_FRONT:   return "Front";
                        case CameraCharacteristics.LENS_FACING_EXTERNAL:return "External";
                        default:                                        return "Unknown";
                    }};
                String orientation = lensOrientationString.invoke(characteristics.get(CameraCharacteristics.LENS_FACING));

                // Query the available capabilities and output formats
                int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                int[] outputFormats= characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputFormats();

                // All cameras *must* support JPEG output so we don't need to check characteristics
                availableCameras.add(new FormatItem(String.format(Locale.JAPAN, "%s JPEG (%s)", orientation, id), id, ImageFormat.JPEG));

                // Return cameras that support RAW capability
                if(Ints.asList(capabilities).contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                   Ints.asList(outputFormats).contains(ImageFormat.RAW_SENSOR)) {
                    availableCameras.add(new FormatItem(String.format(Locale.JAPAN, "%s RAW (%s)", orientation, id), id, ImageFormat.RAW_SENSOR));
                }

                // Return cameras that support JPEG DEPTH capability
                if(Ints.asList(capabilities).contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) &&
                   Ints.asList(outputFormats).contains(ImageFormat.DEPTH_JPEG)) {
                    availableCameras.add(new FormatItem(String.format(Locale.JAPAN, "%s DEPTH (%s)", orientation, id), id, ImageFormat.DEPTH_JPEG));
                }
            });

        return availableCameras;
    }

    static class FormatItem {
        String title;
        String cameraId;
        int format;
        public FormatItem(String t, String c, int f){title=t;cameraId=c;format=f;}
    }

    private class SelectorAdapter extends RecyclerView.Adapter<SelectorAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView mTxtItem;
            public ViewHolder(@NonNull View v) {
                super(v);
                mTxtItem = v.findViewById(R.id.txt_item);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_selector, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FormatItem item = cameraList.get(position);
            holder.mTxtItem.setText(item.title);
            holder.mTxtItem.setOnClickListener(v1 -> {
                getActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, CameraFragment.newInstance()).commit();
            });
       }

        @Override
        public int getItemCount() {
            return cameraList.size();
        }
    }
}