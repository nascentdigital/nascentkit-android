package com.nascentdigital.services;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;


public class Permissions {

    private static final String TAG = "nascent/Permissions";
    private static final int PERMISSION_CODE = 1;

    private final Activity _activity;


    public Permissions(@NonNull Activity activity) {

        // initialize instance variables
        _activity = activity;
    }

    public Map<String, PermissionState> getPermissionStates() {

        // fetch permissions
        Map<String, PermissionState> permissionStates = new HashMap<>();
        try {

            // get package information
            PackageManager packageManager = _activity.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageInfo(_activity.getPackageName(),
                    PackageManager.GET_PERMISSIONS);

            // fetch all permission states
            if (packageInfo.requestedPermissions != null) {
                for (String permissionId : packageInfo.requestedPermissions) {
                    permissionStates.put(permissionId, getPermissionState(permissionId));
                }
            }
        }

        // handle error
        catch (Exception e) {
            Log.e(TAG, "Unable to fetch permissions: " + e.getMessage());
        }

        // return permission
        return permissionStates;
    }

    public PermissionState getPermissionState(String permissionId) {

        // determine permission state
        PermissionState state;
        if (ContextCompat.checkSelfPermission(_activity, permissionId)
                == PackageManager.PERMISSION_GRANTED) {
            state = PermissionState.GRANTED;
        }
        else if (ActivityCompat.shouldShowRequestPermissionRationale(_activity, permissionId)) {
            state = PermissionState.DENIED;
        }
        else {
            state = PermissionState.NOT_GRANTED;
        }

        // return state
        return state;
    }

    public void requestPermissions(String ...permissionIds) {
        this.requestPermissions(Arrays.asList(permissionIds));
    }

    public void requestPermissions(Collection<String> permissionIds) {

        // fetch all permissions that aren't granted if no permissions are specified
        if (permissionIds.size() == 0) {

            // get all permissions that haven't been granted
            ArrayList<String> permissions = new ArrayList<>();
            for (Entry<String, PermissionState> entry : getPermissionStates().entrySet()) {
                if (entry.getValue() != PermissionState.GRANTED) {
                    permissions.add(entry.getKey());
                }
            }

            // update permissions
            permissionIds = permissions;
        }

        // request permissions if there are any to ask for
        int permissionCount = permissionIds.size();
        if (permissionCount > 0) {
            String[] permissions = new String[permissionCount];
            ActivityCompat.requestPermissions(_activity,
                permissionIds.toArray(permissions), PERMISSION_CODE);
        }
    }

    public Map<String, PermissionState> parsePermissionResults(String[] permissionIds, int[] results) {

        // parse permissions
        Map<String, PermissionState> permissionStates = new HashMap<>();
        for (int i = 0; i < permissionIds.length; ++i) {
            PermissionState state = results[i] == PERMISSION_CODE
                    ? PermissionState.GRANTED
                    : PermissionState.DENIED;
            permissionStates.put(permissionIds[i], state);
        }

        // return permissions
        return permissionStates;
    }
}
