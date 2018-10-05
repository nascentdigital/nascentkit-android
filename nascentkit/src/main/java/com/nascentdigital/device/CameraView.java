package com.nascentdigital.device;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.widget.FrameLayout;

import com.nascentdigital.widget.AspectTextureView;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;


public class CameraView extends FrameLayout {

    private static final String TAG = "nascent/CameraView";

    private final AspectTextureView _cameraPreview;
    private final CompositeDisposable _cameraPreviewSubscriptions;
    private CameraFeed _cameraFeed;
    private Disposable _cameraFeedSubscription;
    private boolean _active;


    public CameraView(@NonNull Context context) {
        this(context, null);
    }

    public CameraView(@NonNull Context context, @Nullable AttributeSet attributes) {
        this(context, attributes, 0);
    }

    public CameraView(@NonNull Context context, @Nullable AttributeSet attributes, int style) {

        // call base constructor
        super(context, attributes, style);

        Log.d(TAG, "creating camera view");

        // initialize instance variables
        _cameraPreview = new AspectTextureView(context);
        _cameraPreviewSubscriptions = new CompositeDisposable();

        // initialize subviews
        _cameraPreview
            .setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        addView(_cameraPreview);
    }

    public Bitmap getPreviewBitmap() {
        Bitmap bitmap = _cameraPreview.getBitmap();
        return bitmap;
    }

    public Single<Bitmap> getPhoto() {

        // fail if feed isn't active
        if (_cameraFeed == null) {
            return Single.error(new IllegalStateException(
                "CameraPhoto can not be taken until started."));
        }
        else {
            return _cameraFeed.takePhoto();
        }
    }

    public void start() {

        // skip if there's an active feed already
        if (_cameraFeed != null) {
            Log.d(TAG, "ignoring start() - already active");
            return;
        }

        Log.d(TAG, "starting CameraView");

        // mark active
        _active = true;

        // skip if the preview is ready
        if (!_cameraPreview.isAvailable()) {
            Log.d(TAG, "deferring start() when preview isn't active");
            return;
        }

        // create feed
        _cameraFeed = new CameraFeed(getContext());

        Log.d(TAG, "subscribing to CameraFeed events");

        // monitor camera feed changes (ensure callbacks occur on UI thread)
        _cameraFeedSubscription = _cameraFeed.observeState()
            .subscribeOn(AndroidSchedulers.mainThread())
            .subscribe(this::onCameraFeedChanged);

        Log.d(TAG, "starting CameraFeed");
        try {
            _cameraFeed.start(CameraPosition.BACK, _cameraPreview);
        }
        catch (DeviceAccessException e) {
            e.printStackTrace();
        }
        catch (DeviceDiscoveryException e) {
            e.printStackTrace();
        }
        catch (DeviceNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void stop() {

        // skip if there's no active feed
        if (_cameraFeed == null) {

            Log.d(TAG, "ignoring stop() - not active");

            // ensure active flag is cleared
            _active = false;

            // stop processing
            return;
        }

        Log.d(TAG, "stopping CameraView");

        // mark as inactive
        _active = false;

        Log.d(TAG, "unsubscribing from CameraFeed events");

        // clear subscriptions
        _cameraFeedSubscription.dispose();
        _cameraFeedSubscription = null;

        Log.d(TAG, "stopping CameraFeed");

        try {
            _cameraFeed.stop();
            _cameraFeed = null;
        }
        catch (DeviceAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onAttachedToWindow() {

        Log.d(TAG, "subscribing to preview TextureView events");

        // subscribe to camera preview events
        _cameraPreviewSubscriptions.addAll(

            // notify when preview availability / size changes
            _cameraPreview.observeAvailable()
                .subscribe(this::onCameraPreviewAvailableChanged),

            // notify when preview size changes
            _cameraPreview.observeSize()
                .subscribe(this::onCameraPreviewSizeChanged)
        );

        // call base implementation
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {

        Log.d(TAG, "unsubscribing from preview TextureView events");

        // unsubscribe from camera preview events
        _cameraPreviewSubscriptions.clear();

        // call base implementation
        super.onDetachedFromWindow();
    }

    private void onCameraPreviewAvailableChanged(Boolean available) {

        Log.d(TAG, "camera preview available: " + available);

        // ignore if not active
        if (!_active) {
            return;
        }

        // start if available
        if (available) {
            start();
        }

        // or handle case where camera preview is disposed, but feed is active
        else if (_cameraFeed != null) {
            Log.w(TAG, "camera preview texture destroyed before feed");
        }
    }

    private void onCameraPreviewSizeChanged(Size size) {

        Log.d(TAG, "camera size changed: " + size);

        // update preview transform if feed is active
        if (_cameraFeed != null) {
            _cameraFeed.updatePreviewTransform(_cameraPreview);
        }
    }

    private void onCameraFeedChanged(CameraFeed.State state) {

        // handle changes to camera feed state
        switch (state) {

            case CONNECTED:
                Log.d(TAG, "CameraFeed connected");
                break;

            default:
                Log.d(TAG, "Unhandled CameraFeed state: " + state);
                break;
        }
    }
}
