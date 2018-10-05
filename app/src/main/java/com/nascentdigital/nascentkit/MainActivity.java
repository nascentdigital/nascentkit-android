package com.nascentdigital.nascentkit;

import android.Manifest;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;

import com.nascentdigital.device.CameraView;
import com.nascentdigital.services.PermissionState;
import com.nascentdigital.services.Permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "nascent/MainActivity";

    private Permissions _permissionService;
    private CameraView _camera;
    private ImageView _preview;
    private Disposable _cameraPreviewSubscription;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // call base implementation
        super.onCreate(savedInstanceState);

        // resolve services
        _permissionService = new Permissions(this);

        // bind to view
        setContentView(R.layout.activity_main);
        _camera = findViewById(R.id.camera);
        _preview = findViewById(R.id.preview);

        // bind button event
        findViewById(R.id.take_photo)
            .setOnClickListener(v -> {
                _camera.getPhoto()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        this::onCameraPhoto,
                        this::onCameraError
                    );
            });
    }

    @Override
    protected void onResume() {

        // call base implementation
        super.onResume();

        // start camera
        startCamera();

        // poll
        _cameraPreviewSubscription = Observable.interval(1, TimeUnit.SECONDS)
            .map(tick -> _camera.getPreviewBitmap())
            .subscribe(
                this::onCameraSample,
                this::onCameraError
            );
    }

    @Override
    protected void onPause() {

        // call base implementation
        super.onPause();

        // pause camera
        stopCamera();

        // stop preview if done
        if (_cameraPreviewSubscription != null) {
            _cameraPreviewSubscription.dispose();
            _cameraPreviewSubscription = null;
        }
    }

    @Override
    protected void onDestroy() {

        // call base implementation
        super.onDestroy();

        // finalize capture
        stopCamera();
    }

    private void startCamera() {

        // get permissions
        List<String> permissions = new ArrayList<>();
        for (Map.Entry<String, PermissionState> entry : _permissionService.getPermissionStates().entrySet()) {
            if (entry.getValue() != PermissionState.GRANTED) {
                permissions.add(entry.getKey());
            }
        }

        // ask for any permissions that are required
        if (permissions.size() > 0) {

            Log.d(TAG, "requesting app permissions");

            // request permission using service
            _permissionService.requestPermissions(permissions);
        }

        // start camera
        else {
            Log.d(TAG, "starting CameraView");
            _camera.start();
        }
    }

    private void stopCamera() {

        Log.d(TAG, "stopping CameraView");

        _camera.stop();
    }

    private void onCameraSample(Bitmap bitmap) {
        Log.d(TAG, "received camera sample: " + bitmap);
    }

    private void onCameraPhoto(Bitmap bitmap) {
        Log.d(TAG, "received camera photo: " + bitmap);

        // update image
        _preview.setImageBitmap(bitmap);
    }

    private void onCameraError(Throwable t) {
        t.printStackTrace();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        // call base implementation
        super
            .onRequestPermissionsResult(requestCode, permissions, grantResults);

        // start camera if permission is granted
        if (_permissionService.getPermissionState(Manifest.permission.CAMERA)
            == PermissionState.GRANTED) {
            Log.d(TAG, "camera permissions granted");
        }

        // otherwise, prompt the user to enable the permission
        else {

            Log.d(TAG, "prompting user to retry camera permission");

            // TODO: show call-to-action to grant permission to camera
        }
    }
}
