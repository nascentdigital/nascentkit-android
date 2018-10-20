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

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;


public class CameraView extends FrameLayout {

    public enum State {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        ERROR
    }

    private static final String TAG = "nascent/CameraView";

    private final AspectTextureView _cameraPreview;
    private final CompositeDisposable _cameraPreviewSubscriptions;
    private CameraPosition _cameraPosition;
    private CameraFeed _cameraFeed;
    private Disposable _cameraFeedSubscription;

    private final Object _stateLock;
    private final BehaviorSubject<State> _state$;
    private State _state;


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
        _stateLock = new Object();
        _state$ = BehaviorSubject.createDefault(_state = State.STOPPED);

        // initialize subviews
        _cameraPreview
            .setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        addView(_cameraPreview);
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

    public void start(CameraPosition cameraPosition) {

        // skip if there's an active feed already
        if (_cameraFeed != null) {
            Log.d(TAG, "ignoring start() - already active");
            return;
        }

        Log.d(TAG, "starting CameraView");

        // update state
        setState(State.STARTING);

        // capture camera position
        _cameraPosition = cameraPosition;

        // skip if the preview is ready
        if (!_cameraPreview.isAvailable()) {
            Log.d(TAG, "deferring start() when preview isn't active");
            return;
        }

        // bind to camera feed
        bindToFeed();
    }

    public void stop() {

        // skip if there's no active feed
        if (_cameraFeed == null) {

            Log.d(TAG, "ignoring stop() - not active");

            // update state
            setState(State.STOPPED);

            // stop processing
            return;
        }

        Log.d(TAG, "stopping CameraView");

        // update state
        setState(State.STOPPING);

        Log.d(TAG, "unsubscribing from CameraFeed events");

        // clear subscriptions
        _cameraFeedSubscription.dispose();
        _cameraFeedSubscription = null;

        Log.d(TAG, "stopping CameraFeed");

        // stop camera feed
        try {

            // stop feed
            _cameraFeed.stop();
            _cameraFeed = null;

            // update state
            setState(State.STOPPED);
        }

        // handler error
        catch (DeviceAccessException e) {

            // print stack trace
            e.printStackTrace();

            // update state
            setState(e);
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

    private void bindToFeed() {

        Log.d(TAG, "subscribing to CameraFeed events");

        // create feed
        _cameraFeed = new CameraFeed(getContext());

        // monitor camera feed changes (ensure callbacks occur on UI thread)
        _cameraFeedSubscription = _cameraFeed.observeState()
            .subscribeOn(AndroidSchedulers.mainThread())
            .subscribe(this::onCameraFeedChanged);

        // start feed
        try {

            Log.d(TAG, "starting CameraFeed");
            _cameraFeed.start(_cameraPosition, _cameraPreview);
        }

        // handle error
        catch (Exception e) {

            // print stack
            e.printStackTrace();

            // update state
            setState(e);
        }
    }

    private void onCameraPreviewAvailableChanged(Boolean available) {

        Log.d(TAG, "camera preview available: " + available);

        // ignore if not active
        if (getState() != State.STARTING) {
            return;
        }

        // start if available
        if (available) {
            bindToFeed();
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

            // update state when connected
            case CONNECTED:
                Log.d(TAG, "CameraFeed connected");
                setState(State.STARTED);
                break;

            default:
                Log.d(TAG, "Unhandled CameraFeed state: " + state);
                break;
        }
    }
}
