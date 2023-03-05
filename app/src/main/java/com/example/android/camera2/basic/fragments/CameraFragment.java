package com.example.android.camera2.basic.fragments;

import static android.util.Log.d;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import com.example.android.camera2.basic.AutoFitSurfaceView;
import com.example.android.camera2.basic.CameraActivity;
import com.example.android.camera2.basic.OrientationLiveData;
import com.example.android.camera2.basic.R;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Collectors;

import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.Dispatchers;

public class CameraFragment extends Fragment {
    String cameraId;
    int pixelFormat;

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private CameraManager cameraManager;
    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private CameraCharacteristics characteristics;
    /** Readers used as buffers for camera still shots */
    private ImageReader imageReader;
    /** [HandlerThread] where all camera operations run */
    private HandlerThread cameraThread;
    /** [Handler] corresponding to [cameraThread] */
    private Handler cameraHandler;
    /** Performs recording animation of flashing screen */
    private Runnable animationTask;
    /** [HandlerThread] where all buffer reading operations run */
    private HandlerThread imageReaderThread;
    /** [Handler] corresponding to [imageReaderThread] */
    private Handler imageReaderHandler;
    /** The [CameraDevice] that will be opened in this fragment */
    private CameraDevice camera = null;
    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private CameraCaptureSession session = null;
    /** Live data listener for changes in the device orientation relative to the camera */
    private OrientationLiveData relativeOrientation;

    public CameraFragment(String acameraId, int format) {
        cameraId = acameraId;
        pixelFormat = format;
    }

    public static CameraFragment newInstance(String cameraId, int format) {
        return new CameraFragment(cameraId, format);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = requireContext().getApplicationContext();
        cameraManager = (CameraManager)context.getSystemService(Context.CAMERA_SERVICE);

        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
        }
        catch(CameraAccessException e) {
            throw new RuntimeException(e);
        }

        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        animationTask = () -> {
            // Flash white animation
            final View viewTakeAnim = getActivity().findViewById(R.id.overlay);
            viewTakeAnim.setBackgroundColor(Color.argb(150, 255, 255, 255));
            // Wait for ANIMATION_FAST_MILLIS
            viewTakeAnim.postDelayed(new Runnable() {
                @Override
                public void run() {
                    viewTakeAnim.setBackground(null);
                }
            }, CameraActivity.ANIMATION_FAST_MILLIS);
        };

        imageReaderThread = new HandlerThread("imageReaderThread");
        imageReaderThread.start();
        imageReaderHandler = new Handler(imageReaderThread.getLooper());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.capture_button).setOnApplyWindowInsetsListener((v, insets) -> {
            v.setTranslationX(-insets.getSystemWindowInsetRight());
            v.setTranslationY(-insets.getSystemWindowInsetBottom());
            insets.consumeSystemWindowInsets();
            return insets;
        });

        ((SurfaceView)view.findViewById(R.id.view_finder)).getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                // Selects appropriate preview size and configures view finder
                Size previewSize = getPreviewOutputSize(view.findViewById(R.id.view_finder).getDisplay(), characteristics, SurfaceHolder.class, null);
                d("aaaaa", String.format("View finder size: %d x %d", view.findViewById(R.id.view_finder).getWidth(), view.findViewById(R.id.view_finder).getHeight()));
                d("aaaaa", String.format("Selected preview size: %s", previewSize));
                ((AutoFitSurfaceView)view.findViewById(R.id.view_finder)).setAspectRatio(previewSize.getWidth(), previewSize.getHeight());

                // To ensure that size is set, initialize camera in the view's thread
                view.post(() -> initializeCamera(view));
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            }
        });

        // Used to rotate the output media to match device orientation
        relativeOrientation = new OrientationLiveData(requireContext(), characteristics);
        relativeOrientation.observe(getViewLifecycleOwner(), (Observer)o -> d("aaaaa", "Orientation changed:" + o));
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private void initializeCamera(@NonNull View view) {
        // Open the selected camera
        openCamera(cameraManager, cameraId, cameraHandler);
        while(camera == null) {
            try { Thread.sleep(10); }
            catch(InterruptedException e) { }
        }

        // Initialize an image reader which will be used to capture still photos
        Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(pixelFormat);
        Size size = Arrays.stream(sizes).max((o1, o2) -> o2.getWidth()*o2.getHeight()-o1.getWidth()*o1.getHeight()).get();
        imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), pixelFormat, IMAGE_BUFFER_SIZE);

        // Creates list of Surfaces where the camera will output frames
        List<Surface> targets = Arrays.asList(((SurfaceView)view.findViewById(R.id.view_finder)).getHolder().getSurface(), imageReader.getSurface());

        // Start a capture session using our open camera and list of Surfaces where frames will go
        createCaptureSession(camera, targets, cameraHandler);
        while(session == null) {
            try { Thread.sleep(10); }
            catch(InterruptedException e) { }
        }

        CaptureRequest.Builder captureRequest = null;
        try { captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); }
        catch(CameraAccessException e) { throw new RuntimeException(e); }
        captureRequest.addTarget(((SurfaceView)view.findViewById(R.id.view_finder)).getHolder().getSurface());

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        try { session.setRepeatingRequest(captureRequest.build(), null, cameraHandler); }
        catch(CameraAccessException e) { throw new RuntimeException(e); }

        // Listen to the capture button
        view.findViewById(R.id.capture_button).setOnClickListener(v -> {
                                                                            // Disable click listener to prevent multiple requests simultaneously in flight
                                                                            v.setEnabled(false);

                                                                            // Perform I/O heavy operations in a different scope
                                                                            CombinedCaptureResult result = takePhoto(view);

                                                                            Log.d("aaaaa", String.format("aaaaa", "Result received: %s",result));

                                                                            // Save the result to disk
                                                                            File output = saveResult(result);
                                                                            Log.d("aaaaa", String.format("Image saved: %s", output.getAbsolutePath()));

                                                                            // If the result is a JPEG file, update EXIF metadata with orientation info
                                                                            String extension = output.getAbsolutePath().substring(output.getAbsolutePath().lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                                                                            if(extension.equals("jpg")) {
                                                                                try {
                                                                                    ExifInterface exif = new ExifInterface(output.getAbsolutePath());
                                                                                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, ""+result.orientation);
                                                                                    exif.saveAttributes();
                                                                                    Log.d("aaaaa", String.format("EXIF metadata saved: %s", output.getAbsolutePath()));
                                                                                }
                                                                                catch(IOException e) {
                                                                                    throw new RuntimeException(e);
                                                                                }
                                                                            }

                                                                            // Display the photo taken to user
                                                                            getActivity().getSupportFragmentManager().beginTransaction()
                                                                                    .replace(R.id.fragment_container, ImageViewerFragment.newInstance(output.getAbsolutePath(), result.orientation, result.format==ImageFormat.DEPTH_JPEG))
                                                                                    .commit();

                                                                            // Re-enable click listener after photo is taken
                                                                            v.post(() -> {v.setEnabled(true);});
                                                                    });
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    private void openCamera(CameraManager manager, String cameraId, Handler handler) {
        if(ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw new RuntimeException("error!! camera permission deneied!!");
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice acamera) {
                            camera = acamera;
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            Log.w("aaaaa", String.format("Camera %s has been disconnected", cameraId));
                            requireActivity().finish();
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            Function1<Integer, String> getMessage = (what) -> {
                                switch(what) {
                                    case ERROR_CAMERA_DEVICE:      return  "Fatal (device)";
                                    case ERROR_CAMERA_DISABLED:    return "Device policy";
                                    case ERROR_CAMERA_IN_USE:      return "Camera in use";
                                    case ERROR_CAMERA_SERVICE:     return "Fatal (service)";
                                    case ERROR_MAX_CAMERAS_IN_USE: return "Maximum cameras in use";
                                    default: return "Unknown";
                                }
                            };
                            String msg = getMessage.invoke(error);
                            RuntimeException exc = new RuntimeException(String.format(Locale.JAPAN, "Camera %s error: %d %s", cameraId, error, msg));
                            Log.e("aaaaa", exc.getMessage(), exc);
                            getActivity().finish();
                        }
                    }, handler);
        }
        catch(CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private void createCaptureSession(CameraDevice device, List<Surface> targets, Handler handler) {
        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        try {
            device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                                                        @Override
                                                        public void onConfigured(@NonNull CameraCaptureSession asession) {
                                                            session = asession;
                                                        }

                                                        @Override
                                                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                                            RuntimeException exc = new RuntimeException(String.format(Locale.JAPAN, "Camera %s session configuration failed", device.getId()));
                                                            Log.e("aaaaa", exc.getMessage(), exc);
                                                            getActivity().finish();
                                                        }
                                                    }, handler);
        }
        catch(CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     *
     * @return
     */
    private CombinedCaptureResult takePhoto(View view) {
        // Flush any images left in the image reader
        while(imageReader.acquireNextImage() != null) {}

        // Start a new image queue
        ArrayBlockingQueue imageQueue = new ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                                                                    Image image = reader.acquireNextImage();
                                                                    d("aaaaa", String.format("Image available in queue: %d", image.getTimestamp()));
                                                                    imageQueue.add(image);
                                                                }
            }, imageReaderHandler);

        CaptureRequest.Builder captureRequest = null;
        try {
            captureRequest = session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequest.addTarget(imageReader.getSurface());
        }
        catch(CameraAccessException e) {
            throw new RuntimeException(e);
        }

        final CombinedCaptureResult[] ret = {null};
        try {
            session.capture(captureRequest.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);
                            view.findViewById(R.id.view_finder).post(animationTask);
                        }
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                            super.onCaptureCompleted(session, request, result);
                            Long resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                            d("aaaaa", String.format("Capture result received: %d",resultTimestamp));

                            // Set a timeout in case image captured is dropped from the pipeline
                            RuntimeException exc = new RuntimeException("Image dequeuing took too long");
                            Runnable timeoutRunnable = () -> { throw exc; };
                            imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS);

                            // Loop in the coroutine's context until an image with matching timestamp comes
                            // We need to launch the coroutine context again because the callback is done in
                            //  the handler provided to the `capture` method, not in our coroutine context
                            while (true) {
                                // Dequeue images while timestamps don't match
                                Image image = null;
                                try {
                                    image = (Image)imageQueue.take();
                                }
                                catch(InterruptedException e) { throw new RuntimeException(e); }
                                // TODO(owahltinez): b/142011420
                                // if (image.timestamp != resultTimestamp) continue
                                if (image.getFormat() != ImageFormat.DEPTH_JPEG && image.getTimestamp() != resultTimestamp)
                                    continue;
                                Log.d("aaaaa", String.format("Matching image dequeued: %d", image.getTimestamp()));

                                // Unset the image reader listener
                                imageReaderHandler.removeCallbacks(timeoutRunnable);
                                imageReader.setOnImageAvailableListener(null, null);

                                // Clear the queue of images, if there are left
                                while (imageQueue.size() > 0) {
                                    try {
                                        ((Image)imageQueue.take()).close();
                                    }
                                    catch(InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }

                        // Compute EXIF orientation metadata
                                int rotation = /*relativeOrientation.getValue()*/0;
                                Log.d("aaaaa", String.format("aaaaa rotation = %d", rotation));
                                boolean mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
                                int exifOrientation = computeExifOrientation(rotation, mirrored);

                        // Build the result and resume progress
                                ret[0] = new CombinedCaptureResult(image, result, exifOrientation, imageReader.getImageFormat());

                        // There is no need to break out of the loop, this coroutine will suspend
                            }
                        }
                    }, cameraHandler);
        }
        catch(CameraAccessException e) {
            throw new RuntimeException(e);
        }

        while(ret[0] == null) {
            try { Thread.sleep(10); }
            catch(InterruptedException e) { throw new RuntimeException(e); }
        }

        return ret[0];
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private File saveResult(CombinedCaptureResult result) {
        switch (result.format) {

            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            case ImageFormat.JPEG: case ImageFormat.DEPTH_JPEG: {
                ByteBuffer buffer = result.image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try {
                    File output = createFile(requireContext(), "jpg");
                    FileOutputStream stream = new FileOutputStream(output);
                    stream.write(bytes);
                    return output;
                } catch (IOException exc) {
                    Log.e("aaaaa", "Unable to write JPEG image to file", exc);
                    throw new RuntimeException(exc);
                }
            }

            // When the format is RAW we use the DngCreator utility library
            case ImageFormat.RAW_SENSOR: {
                DngCreator dngCreator = new DngCreator(characteristics, result.metadata);
                try {
                    File output = createFile(requireContext(), "dng");
                    FileOutputStream stream = new FileOutputStream(output);
                    dngCreator.writeImage(stream, result.image);
                    return output;
                } catch (IOException exc) {
                    Log.e("aaaaa", "Unable to write DNG image to file", exc);
                    throw new RuntimeException(exc);
                }
            }

            // No other formats are supported by this sample
            default: {
                RuntimeException exc = new RuntimeException(String.format("Unknown image format: %s", result.image.getFormat()));
                Log.e("aaaaa", exc.getMessage(), exc);
                throw new RuntimeException(exc);
            }
        }
    }

        /** Maximum number of images that will be held in the reader's buffer */
        private static final int IMAGE_BUFFER_SIZE = 3;

        /** Maximum time allowed to wait for the result of an image capture */
        private static final int IMAGE_CAPTURE_TIMEOUT_MILLIS = 5000;

        /** Helper data class used to hold capture metadata with their associated image */
    private static class CombinedCaptureResult implements Closeable {
        Image image;
        CaptureResult metadata;
        int orientation;
        int format;
        public CombinedCaptureResult(Image aimage, CaptureResult ametadata, int aorientation, int aimageFormat) {
            image       = aimage;
            metadata    = ametadata;
            orientation = aorientation;
            format      = aimageFormat;
        }

        @Override
        public void close() throws IOException {
            image.close();
        }
    }


        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
    private File createFile(Context context, String extension) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US);
        return new File(context.getFilesDir(), String.format("IMG_%s.%s", sdf.format(new Date()), extension));
    }

    private int computeExifOrientation(int rotationDegrees, boolean mirrored) {
             if(rotationDegrees == 0   && !mirrored) return ExifInterface.ORIENTATION_NORMAL;
        else if(rotationDegrees == 0   &&  mirrored) return ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
        else if(rotationDegrees == 180 && !mirrored) return ExifInterface.ORIENTATION_ROTATE_180;
        else if(rotationDegrees == 180 &&  mirrored) return ExifInterface.ORIENTATION_FLIP_VERTICAL;
        else if(rotationDegrees == 90  && !mirrored) return ExifInterface.ORIENTATION_ROTATE_90;
        else if(rotationDegrees == 90  &&  mirrored) return ExifInterface.ORIENTATION_TRANSPOSE;
        else if(rotationDegrees == 270 &&  mirrored) return ExifInterface.ORIENTATION_ROTATE_270;
        else if(rotationDegrees == 270 && !mirrored) return ExifInterface.ORIENTATION_TRANSVERSE;
        else return ExifInterface.ORIENTATION_UNDEFINED;
    }

    /** Helper class used to pre-compute shortest and longest sides of a [Size] */
    static class SmartSize {
        Size size;
        int mlong;
        int mshort;

        public SmartSize(int width, int height) {
            size = new Size(width, height);
            mlong = Math.max(size.getWidth(), size.getHeight());
            mshort = Math.min(size.getWidth(), size.getHeight());
        }
        public SmartSize(Size lsize) {
            size = lsize;
            mlong = Math.max(size.getWidth(), size.getHeight());
            mshort = Math.min(size.getWidth(), size.getHeight());
        }
        @NonNull @Override
        public String toString() {
            return String.format(Locale.JAPAN, "SmartSize(%dx%d)", mlong, mshort);
        }
    }

    /** Standard High Definition size for pictures and video */
    SmartSize SIZE_1080P = new SmartSize(1920, 1080);

    /** Returns a [SmartSize] object for the given [Display] */
     private SmartSize getDisplaySmartSize(Display display) {
         Point outPoint = new Point();
        display.getRealSize(outPoint);
        return new SmartSize(outPoint.x, outPoint.y);
    }

    /**
     * Returns the largest available PREVIEW size. For more information, see:
     * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
     * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
     */
    private <T> Size getPreviewOutputSize(Display display, CameraCharacteristics characteristics, Class<T> targetClass, Integer format) {
        // Find which is smaller: screen or 1080p
        SmartSize screenSize = getDisplaySmartSize(display);
        boolean hdScreen = screenSize.mlong >= SIZE_1080P.mlong || screenSize.mshort >= SIZE_1080P.mshort;
        SmartSize maxSize = (hdScreen) ? SIZE_1080P : screenSize;

        // If image format is provided, use it to determine supported sizes; else use target class
        StreamConfigurationMap config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (format == null)
            assert(StreamConfigurationMap.isOutputSupportedFor(targetClass));
        else
            assert(config.isOutputSupportedFor(format));
        Size[] allSizes = (format == null) ? config.getOutputSizes(targetClass) : config.getOutputSizes(format);

        // Get available sizes and sort them by area from largest to smallest
        List<Size> validSizes = Arrays.stream(allSizes).sorted((o1, o2) -> {
            return (o2.getWidth()*o2.getHeight()) - (o1.getWidth()*o1.getHeight());
        }).collect(Collectors.toList());

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.stream().filter(it -> {
                                            SmartSize smartSize = new SmartSize(it);
                                            return smartSize.mlong <= maxSize.mlong && smartSize.mshort <= maxSize.mshort;
                                        }).findFirst().get();
    }
}