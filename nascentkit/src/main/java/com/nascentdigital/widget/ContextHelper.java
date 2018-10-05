package com.nascentdigital.widget;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;


public final class ContextHelper {

    private ContextHelper() {
    }

    public static Activity getActivity(Context context) {

        // stop processing if context is null
        if (context == null) {
            return null;
        }

        // handle any context base
        else if (context instanceof ContextWrapper) {

            // return activity when reached
            if (context instanceof Activity) {
                return (Activity) context;
            }

            // or recursively walk up hierarchy
            else {
                return getActivity(((ContextWrapper) context).getBaseContext());
            }
        }

        // or fail immediately
        return null;
    }

    public static Application getApplication(Context context) {

        // stop processing if context is null
        if (context == null) {
            return null;
        }

        // handle any context base
        else if (context instanceof ContextWrapper) {

            // return application if reached
            if (context instanceof Application) {
                return (Application)context;
            }

            // return activity when reached
            if (context instanceof Activity) {
                return ((Activity)context).getApplication();
            }

            // or recursively walk up hierarchy
            else {
                return getApplication(((ContextWrapper)context).getBaseContext());
            }
        }

        // or fail immediately
        return null;
    }
}
