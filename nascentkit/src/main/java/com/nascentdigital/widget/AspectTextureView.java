package com.nascentdigital.widget;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;


public class AspectTextureView extends TextureView
    implements TextureView.SurfaceTextureListener {

    private static final String TAG = "nascent/AspectTexture";

    private final BehaviorSubject<Boolean> _available$;
    private final BehaviorSubject<Size> _size$;

    private int _aspectWidth = 0;
    private int _aspectHeight = 0;


    public AspectTextureView(Context context) {
        this(context, null);
    }

    public AspectTextureView(Context context, AttributeSet attributes) {
        this(context, attributes, 0);
    }

    public AspectTextureView(Context context, AttributeSet attributes, int style) {

        // call base constructor
        super(context, attributes, style);

        // initialize instance variables
        _available$ = BehaviorSubject.createDefault(false);
        _size$ = BehaviorSubject.create();

        // observe underlying texture events
        setSurfaceTextureListener(this);
    }

    public Observable<Boolean> observeAvailable() {
        return _available$.distinctUntilChanged();
    }

    public Size getSize() {
        return new Size(getWidth(), getHeight());
    }

    public Observable<Size> observeSize() {
        return _size$.distinctUntilChanged();
    }

    public void setAspectRatio(int width, int height) {

        // verify aspect ratio
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(
                "Aspect ratio width / height cannot be negative.");
        }

        Log.d(TAG, "updated aspect ratio (" + width + ", " + height + ")");

        // capture ratio
        _aspectWidth = width;
        _aspectHeight = height;

        // force layout
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        // call base implementation
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // resolve actual width / height
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);

        Log.v(TAG, "updating measure for (" + width + ", " + height
            + ") and aspect " + _aspectWidth + ":" + _aspectHeight);

        // use width / height directly if either aspect size is unspecified
        if (_aspectWidth == 0 || _aspectHeight == 0) {

            Log.v(TAG, "using full width / height");

            setMeasuredDimension(width, height);
        }

        // fit to width (scale to aspect-fit)
        else if (width < height * _aspectWidth / _aspectHeight) {

            Log.v(TAG, "fitting width");

            setMeasuredDimension(width, width * _aspectHeight / _aspectWidth);
        }

        // or fit to height (scale to aspect-fit)
        else {

            Log.v(TAG, "fitting height");

            setMeasuredDimension(height * _aspectWidth / _aspectHeight, height);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {

        // raise events
        _available$.onNext(true);
        _size$.onNext(new Size(width, height));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        // raise event
        _size$.onNext(new Size(width, height));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {

        // raise event
        _available$.onNext(false);

        // stop rendering (auto-releases underlying Texture)
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
