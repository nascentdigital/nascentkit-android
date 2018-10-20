package com.nascentdigital.device;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;

import com.nascentdigital.graphics.ImageHelper;
import com.nascentdigital.widget.AspectTextureView;
import com.nascentdigital.widget.ContextHelper;
import com.nascentdigital.util.SizeComparator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.subjects.BehaviorSubject;


public class CameraFeed {

    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    private enum PhotoCaptureState {
        IDLE,
        FOCUSING,
        CAPTURE_INIT,
        CAPTURING,
        CAPTURED
    }

    private static final String TAG = "nascent/CameraFeed";
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final Activity _activity;
    private final Display _display;
    private final Object _stateLock;
    private final BehaviorSubject<State> _state$;
    private State _state;

    private final CameraManager _cameraManager;
    private final Semaphore _cameraBindingLock = new Semaphore(1);
    private String _cameraId;
    private CameraCharacteristics _cameraCharacteristics;
    private StreamConfigurationMap _cameraConfigurationMap;
    private CameraDevice _camera;
    private boolean _canFlash;
    private CameraCaptureSession _cameraSession;
    private HandlerThread _captureThread;
    private Handler _captureHandler;
    private PhotoCaptureState _captureState;
    private final PhotoPrecaptureCallback photoPrecaptureCallback;
    private final PhotoCaptureCallback _photoCaptureCallback;
    private final PreviewCaptureCallback _previewCaptureCallback;
    private int _cameraOrientation;
    private CaptureRequest.Builder _cameraRequestBuilder;
    private ImageReader _cameraPhotoReader;
    private Size _cameraPhotoSize;
    private int _photoOrientation;
    private SingleEmitter<Bitmap> _photoEmitter;
    private CaptureRequest _cameraPreviewRequest;
    private Size _cameraPreviewSize;


    CameraFeed(Context context) {

        // initialize instance variables
        _activity = ContextHelper.getActivity(context);
        _display = _activity.getWindowManager().getDefaultDisplay();
        _stateLock = new Object();
        _state$ = BehaviorSubject.createDefault(_state = State.UNINITIALIZED);
        _cameraManager = (CameraManager) _activity
            .getSystemService(Context.CAMERA_SERVICE);
        _captureState = PhotoCaptureState.IDLE;
        photoPrecaptureCallback = new PhotoPrecaptureCallback();
        _photoCaptureCallback = new PhotoCaptureCallback();
        _previewCaptureCallback = new PreviewCaptureCallback();
    }

    public State getState() {
        synchronized (_stateLock) {
            return _state;
        }
    }

    private void setState(State state) {
        synchronized (_stateLock) {
            _state = state;
            _state$.onNext(state);
        }
    }

    private void setState(Exception error) {
        synchronized (_stateLock) {
            _state = State.ERROR;
            _state$.onError(error);
        }
    }

    public Observable<State> observeState() {
        synchronized (_stateLock) {
            return _state$.distinctUntilChanged();
        }
    }

    @SuppressLint("MissingPermission")
    public void start(CameraPosition cameraPosition, AspectTextureView previewView)
        throws DeviceAccessException, DeviceDiscoveryException,
               DeviceNotFoundException {

        Log.d(TAG, "starting camera feed");

        // start capture thread / handler
        _captureThread = new HandlerThread("CameraFeed Capture");
        _captureThread.start();
        _captureHandler = new Handler(_captureThread.getLooper());

        Log.v(TAG, "verifying camera permission");

        // ensure permissions are available
        if (ActivityCompat
            .checkSelfPermission(_activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {

            throw new DeviceAccessException(
                "Camera permission must be granted before starting a CameraFeed.");
        }

        // update state
        setState(State.INITIALIZING);

        // find first camera matching position
        try {
            resolveCamera(cameraPosition);
        }

        // rethrow if the device isn't found
        catch (DeviceNotFoundException e) {

            // update state with error
            setState(e);

            // re-throw
            throw e;
        }

        // throw a discovery exception
        catch (Exception e) {

            // update state with error
            DeviceDiscoveryException error = new DeviceDiscoveryException(
                "Unable to enumerate connected cameras.", e);
            setState(error);

            // throw
            throw error;
        }

        // prepare outputs
        prepareOutputs(previewView);
        updatePreviewTransform(previewView);

        // open camera connection
        try {

            Log.v(TAG, "obtaining camera binding lock");

            // obtain lock (throw on timeout)
            if (!_cameraBindingLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new DeviceAccessException("Time out waiting on camera binding lock.");
            }

            Log.v(TAG, "opening connection to camera");

            // attempt to connect to camera
            _cameraManager
                .openCamera(_cameraId, new CameraObserver(previewView),
                    _captureHandler);

            // mark as connecting
            setState(State.CONNECTING);
        }
        catch (CameraAccessException e) {

            // update state with error
            DeviceAccessException error = new DeviceAccessException(
                "Unable to access camera.", e);
            setState(error);

            // throw
            throw error;
        }
        catch (InterruptedException e) {

            // update state with error
            DeviceAccessException error = new DeviceAccessException(
                "Interrupted while accessing camera.", e);
            setState(error);

            // throw
            throw error;
        }
    }

    public void stop() throws DeviceAccessException {

        Log.d(TAG, "releasing camera resources");

        // try to release camera resources
        try {

            Log.v(TAG, "attempting to close camera connection");

            // acquire binding lock
            _cameraBindingLock.acquire();

            // release session
            if (_cameraSession != null) {
                Log.v(TAG, "closing camera session");
                _cameraSession.close();
                _cameraSession = null;
            }

            // release device
            if (_camera != null) {
                Log.v(TAG, "closing camera");
                _camera.close();
                _camera = null;
                _cameraId = null;
            }

            // release photo reader
            if (_cameraPhotoReader != null) {
                _cameraPhotoReader.close();
                _cameraPhotoReader = null;
            }

            // mark disconnected
            setState(State.DISCONNECTED);
        }

        // handle interruption during lock acquisition
        catch (InterruptedException e) {

            // update state with error
            DeviceAccessException error = new DeviceAccessException(
                "Interrupted while trying to acquire camera binding lock.", e);
            setState(error);

            // throw
            throw error;
        }

        // ensure lock is released
        finally {
            Log.d(TAG, "Disposed camera feed.");
            _cameraBindingLock.release();
        }

        if (_captureThread != null) {

            Log.d(TAG, "stopping camera feed thread");

            // request thread to stop
            _captureThread.quitSafely();

            // wait for thread
            try {
                _captureThread.join();
                _captureThread = null;
                _captureHandler = null;
            }

            // handle exceptions
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public Single<Bitmap> takePhoto() {

        // fail immediately if a photo is already in flight
        if (_photoEmitter != null) {
            return Single.error(new IllegalStateException(
                "Attempt to take concurrent photos."));
        }

        // initiate photo
        return Single.create(single -> {

            // track emitter and add cleanup
            _photoEmitter = single;
            _photoEmitter.setDisposable(Disposables.fromAction(
                () -> _photoEmitter = null));

            // try to lock camera focus
            try {

                // build request
                _cameraRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);

                // Tell #mCaptureCallback to wait for the lock.
                _captureState = PhotoCaptureState.FOCUSING;
                _cameraSession.capture(_cameraRequestBuilder.build(),
                    photoPrecaptureCallback, _captureHandler);
            }

            // handle exception
            catch (CameraAccessException e) {
                _photoEmitter.onError(
                    new DeviceAccessException("Camera device is unavailable.", e));
            }
        });
    }

    public void updatePreviewTransform(AspectTextureView previewView) {

        // capture existing sizing inputs
        final int previewWidth = previewView.getWidth();
        final int previewHeight = previewView.getHeight();
        final int imageWidth = _cameraPreviewSize.getWidth();
        final int imageHeight = _cameraPreviewSize.getHeight();
        final RectF previewRect = new RectF(0, 0, previewWidth, previewHeight);
        final RectF imageRect = new RectF(0, 0, imageHeight, imageWidth);
        final float centerX = previewRect.centerX();
        final float centerY = previewRect.centerY();

        Log.v(TAG, "updating preview transform ("
            + imageWidth + ", " + imageHeight + ") -> ("
            + previewWidth + ", " + previewHeight + ")");

        // setup rotate and fit if rotated perpendicularly
        final Matrix matrix = new Matrix();
        final int rotation = _display.getRotation();
        if (Surface.ROTATION_90 == rotation
            || Surface.ROTATION_270 == rotation) {

            // center image
            imageRect.offset(centerX - imageRect.centerX(),
                centerY - imageRect.centerY());
            matrix
                .setRectToRect(previewRect, imageRect, Matrix.ScaleToFit.FILL);

            // scale
            float scale = Math.max(
                (float) previewHeight / imageHeight,
                (float) previewWidth / imageWidth);
            matrix.postScale(scale, scale, centerX, centerY);

            // apply rotation
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);

            Log.v(TAG, "rotating 90 degress and scaling by " + scale);
        }

        // or just apply rotation if upside down
        else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);

            Log.v(TAG, "rotating 180 degress");
        }

        // update preview transformation using matrix
        previewView.setTransform(matrix);
    }

    private void resolveCamera(CameraPosition cameraPosition)
        throws CameraAccessException, DeviceNotFoundException {

        Log.v(TAG, "searching for " + cameraPosition + " camera.");

        // search for camera using the registered devices
        String cameraId = null;
        for (String id : _cameraManager.getCameraIdList()) {

            // get device details (skip if details are missing)
            CameraCharacteristics characteristics = _cameraManager
                .getCameraCharacteristics(id);
            Integer lensFacing = characteristics
                .get(CameraCharacteristics.LENS_FACING);
            if (lensFacing == null) {
                continue;
            }

            // determine camera position
            CameraPosition position;
            switch (lensFacing) {
                case CameraMetadata.LENS_FACING_FRONT:
                    position = CameraPosition.FRONT;
                    break;
                case CameraMetadata.LENS_FACING_BACK:
                    position = CameraPosition.BACK;
                    break;
                default:
                    position = CameraPosition.EXTERNAL;
                    break;
            }

            // use camera if matching
            if (position == cameraPosition) {

                // don't use camera if there isn't a map
                StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                Log.v(TAG, "Found camera: " + id);

                // assign camera
                _cameraId = id;
                _cameraCharacteristics = characteristics;
                _cameraConfigurationMap = map;

                // capture capabilities
                Boolean canFlash = characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE);
                _canFlash = canFlash == null ? false : canFlash;

                // stop searching
                break;
            }
        }

        // throw if there was no matching device
        if (_cameraId == null) {
            throw new DeviceNotFoundException(
                "Unable to find connected camera in " + cameraPosition
                    + " position");
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void prepareOutputs(AspectTextureView previewView) {

        Log.v(TAG, "preparing photo output");

        // create image reader for capturing images (capture largest size available)
        _cameraPhotoSize = Collections.max(
            Arrays.asList(_cameraConfigurationMap
                .getOutputSizes(ImageFormat.JPEG)),
            new SizeComparator());
        _cameraPhotoReader = ImageReader.newInstance(
            _cameraPhotoSize.getWidth(), _cameraPhotoSize.getHeight(),
            ImageFormat.JPEG, 2);
        _cameraPhotoReader.setOnImageAvailableListener(
            new ImageProcessor(), _captureHandler);

        // determine camera orientation
        _cameraOrientation = _cameraCharacteristics
            .get(CameraCharacteristics.SENSOR_ORIENTATION);

        // figure out if we need to flip display axis to match camera
        boolean flipAxis = false;
        switch (_display.getRotation()) {

            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                if (_cameraOrientation == 90 || _cameraOrientation == 270) {
                    flipAxis = true;
                }
                break;

            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                if (_cameraOrientation == 0 || _cameraOrientation == 180) {
                    flipAxis = true;
                }
                break;

            default:
                Log.e(TAG, "display rotation is invalid: "
                    + _display.getRotation());
                break;
        }

        Size previewSize = previewView.getSize();
        Log.v(TAG, "preparing preview output for " + previewSize);

        // determine preview dimension variables
        Point displaySize = new Point();
        _display.getSize(displaySize);
        int adjustedWidth, adjustedHeight;
        int maxWidth, maxHeight;
        if (flipAxis) {
            adjustedWidth = previewSize.getHeight();
            adjustedHeight = previewSize.getWidth();
            maxWidth = displaySize.y;
            maxHeight = displaySize.x;
        }
        else {
            adjustedWidth = previewSize.getWidth();
            adjustedHeight = previewSize.getHeight();
            maxWidth = displaySize.x;
            maxHeight = displaySize.y;
        }

        // clamp max dimensions
        if (maxWidth > MAX_PREVIEW_WIDTH) {
            maxWidth = MAX_PREVIEW_WIDTH;
        }
        if (maxHeight > MAX_PREVIEW_HEIGHT) {
            maxHeight = MAX_PREVIEW_HEIGHT;
        }

        // determine the best preview size
        _cameraPreviewSize = chooseOptimalPreviewSize(
            _cameraConfigurationMap.getOutputSizes(SurfaceTexture.class),
            adjustedWidth, adjustedHeight, maxWidth, maxHeight,
            _cameraPhotoSize);

        // adjust preview aspect ratio to match image output
        int orientation = _activity.getResources()
            .getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            previewView.setAspectRatio(_cameraPreviewSize.getWidth(),
                _cameraPreviewSize.getHeight());
        }
        else {
            previewView.setAspectRatio(_cameraPreviewSize.getHeight(),
                _cameraPreviewSize.getWidth());
        }
    }

    private void bindOutputs(AspectTextureView previewView)
        throws CameraAccessException {

        // initialize preview texture to create surface
        SurfaceTexture previewTexture = previewView.getSurfaceTexture();
        assert previewTexture != null;
        previewTexture.setDefaultBufferSize(_cameraPreviewSize.getWidth(),
            _cameraPreviewSize.getHeight());
        Surface previewSurface = new Surface(previewTexture);

        // create a reusable request builder
        _cameraRequestBuilder
            = _camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        _cameraRequestBuilder.addTarget(previewSurface);

        // start the capture session
        _camera.createCaptureSession(
            Arrays.asList(previewSurface, _cameraPhotoReader.getSurface()),
            new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {

                    // TODO: make sure we clear this when disposed
                    // abort if camera is feed is already disposed
                    if (_camera == null) {
                        return;
                    }

                    // track session
                    _cameraSession = session;

                    // configure session
                    try {

                        // enable auto-focus
                        _cameraRequestBuilder
                            .set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                        // enable auto-flash
                        configureCameraRequest(_cameraRequestBuilder);

                        // create the preview request and set it to repeat
                        _cameraPreviewRequest = _cameraRequestBuilder.build();
                        _cameraSession.setRepeatingRequest(
                            _cameraPreviewRequest, _previewCaptureCallback,
                            _captureHandler);
                    }

                    // handle any exceptions
                    catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "failed to configure camera session");
                }
            },
            null);
    }

    private void configureCameraRequest(CaptureRequest.Builder requestBuilder) {

        // enable auto-flash (if possible)
        if (_canFlash) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    private static Size chooseOptimalPreviewSize(Size[] choices,
                                                 int textureViewWidth, int textureViewHeight,
                                                 int maxWidth, int maxHeight, Size aspectRatio) {

        // determine the sizes that can be used
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {

            // process sizes that aren't too large and match the aspect ratio
            final int width = option.getWidth();
            final int height = option.getHeight();
            if (width <= maxWidth
                && height <= maxHeight
                && height == width * h / w) {

                // track sizes are at least the size of the texture
                if (width >= textureViewWidth &&
                    height >= textureViewHeight) {
                    bigEnough.add(option);
                }

                // or keep the other options
                else {
                    notBigEnough.add(option);
                }
            }
        }

        // use the smallest of the "large enough" sizes
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new SizeComparator());
        }

        // or use the largest of the "not large enough" sizes
        else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new SizeComparator());
        }

        // otherwise, just pick any size (not ideal)
        else {
            Log.w(TAG, "couldn't find any suitable preview size");
            return choices[0];
        }
    }


    private class CameraObserver extends CameraDevice.StateCallback {

        private final AspectTextureView _previewView;


        public CameraObserver(AspectTextureView previewView) {
            _previewView = previewView;
        }

        @Override
        public void onOpened(@NonNull CameraDevice camera) {

            Log.d(TAG, "Connected to camera " + camera.getId());

            // capture camera
            _camera = camera;

            // release lock
            _cameraBindingLock.release();

            // update state
            setState(State.CONNECTED);

            Log.v(TAG, "preparing camera outputs");

            // prepare outputs
            try {
                bindOutputs(_previewView);
            }

            // or raise exception (stops feed)
            catch (CameraAccessException e) {
                setState(new DeviceAccessException(
                    "Failed to bind camera feed to outputs: " + e.getMessage()));
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {

            Log.d(TAG, "Disconnected from camera " + camera.getId());

            // dispose of camera
            _camera.close();
            _camera = null;

            // release lock
            _cameraBindingLock.release();

            // update state
            setState(State.DISCONNECTED);

            // TODO: stop feed
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {

            Log.d(TAG,
                "Error from camera " + camera.getId() + " code " + error);

            // dispose of camera
            _camera.close();
            _camera = null;

            // release lock
            _cameraBindingLock.release();

            // raise exception (stops feed)
            setState(new DeviceAccessException(
                "Unexpected error from camera: " + error));
        }
    }

    private class PhotoPrecaptureCallback
        extends CameraCaptureSession.CaptureCallback {

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

        private void process(CaptureResult result) {
            switch (_captureState) {
                case IDLE:
                    break;

                case FOCUSING:

                    // take picture if auto-focus is not present
                    Integer focusState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (focusState == null) {
                        capture();
                    }

                    // or handle case where focus is complete
                    else if (focusState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || focusState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {

                        // take picture if auto-exposure is complete (or not available)
                        Integer exposureState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (exposureState == null
                            || exposureState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            capture();
                        }

                        // or start auto-exposure
                        else {
                            initializeCapture();
                        }
                    }
                    break;

                case CAPTURE_INIT: {

                    // move to capturing if exposure is ready
                    Integer exposureState = result
                        .get(CaptureResult.CONTROL_AE_STATE);
                    if (exposureState == null
                        || exposureState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                        || exposureState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        _captureState = PhotoCaptureState.CAPTURING;
                    }
                    break;
                }

                case CAPTURING:

                    // move to captured if exposure is complete
                    Integer exposureState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (exposureState == null
                        || exposureState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        capture();
                    }
                    break;
            }
        }

        private void initializeCapture() {

            // initiate a photo
            try {

                // create request
                _cameraRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

                // update session
                _captureState = PhotoCaptureState.CAPTURE_INIT;
                _cameraSession.capture(_cameraRequestBuilder.build(),
                    this, _captureHandler);
            }

            // handle exceptions
            catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        private void capture() {

            // ensure maked as captured
            _captureState = PhotoCaptureState.CAPTURED;

            // initiate a photo
            try {

                // create new builder
                final CaptureRequest.Builder captureBuilder =
                    _camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(_cameraPhotoReader.getSurface());

                // create request
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                configureCameraRequest(captureBuilder);

                // set image orientation
                int rotation = _display.getRotation();
                _photoOrientation = (ORIENTATIONS.get(rotation)
                    + _cameraOrientation + 270) % 360;
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION,
                    _photoOrientation);

                // update session
                _cameraSession.stopRepeating();
                _cameraSession.abortCaptures();
                _cameraSession.capture(captureBuilder.build(),
                    _photoCaptureCallback,null);
            }

            // handle exceptions
            catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private class PhotoCaptureCallback
        extends CameraCaptureSession.CaptureCallback {

        @Override
        public void onCaptureCompleted(
            @NonNull CameraCaptureSession session,
            @NonNull CaptureRequest request,
            @NonNull TotalCaptureResult result) {

            // reset to preview state
            try {

                // unlock focus immediately
                _cameraRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                configureCameraRequest(_cameraRequestBuilder);
                _cameraSession.capture(_cameraRequestBuilder.build(),
                    _previewCaptureCallback, _captureHandler);

                // reset to preview loop
                _captureState = PhotoCaptureState.IDLE;
                _cameraSession.setRepeatingRequest(_cameraPreviewRequest,
                    _previewCaptureCallback, _captureHandler);
            }

            // handle exceptions
            catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private class PreviewCaptureCallback
        extends CameraCaptureSession.CaptureCallback {
    }

    private class ImageProcessor
        implements ImageReader.OnImageAvailableListener {

        @Override
        public void onImageAvailable(ImageReader reader) {

            // process image
            try {

                // convert image to bitmap
                Bitmap bitmap;
                try (Image image = reader.acquireNextImage()) {

                    Log.d(TAG, "received image: " + image);

                    // convert image to bitmap
                    bitmap = ImageHelper.createBitmap(image);
                }

                // scale bitmap if required
                if (_photoOrientation != 0) {

                    // create transform matrix
                    Matrix matrix = new Matrix();
                    matrix.postRotate(_photoOrientation);

                    // create transformed bitmap
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                }

                // emit bitmap
                _photoEmitter.onSuccess(bitmap);
            }
            catch (Exception e) {
                _photoEmitter.onError(e);
            }
        }
    }
}
