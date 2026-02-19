package com.fourdo.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Utility class for managing device orientation and determining optimal orientations
 * based on device type and screen size.
 */
public class DeviceOrientationManager {
    
    private static final float TABLET_DP_THRESHOLD = 600f; // dp threshold for tablet detection
    
    /**
     * Determines the optimal orientation for the main activity based on device type.
     * @param context The application context
     * @return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT for phones, 
     *         ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE for tablets
     */
    public static int getOptimalMainActivityOrientation(Context context) {
        if (isTablet(context)) {
            return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }
    
    /**
     * Determines the optimal orientation for the emulator activity.
     * @param context The application context
     * @return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE for tablets,
     *         ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED for phones (allow rotation)
     */
    public static int getOptimalEmulatorOrientation(Context context) {
        if (isTablet(context)) {
            return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }
    
    /**
     * Forces the activity to the optimal orientation for the main activity.
     * @param activity The activity to orient
     */
    public static void setOptimalMainActivityOrientation(Activity activity) {
        int orientation = getOptimalMainActivityOrientation(activity);
        activity.setRequestedOrientation(orientation);
    }
    
    /**
     * Forces the activity to the optimal orientation for the emulator activity.
     * @param activity The activity to orient
     */
    public static void setOptimalEmulatorOrientation(Activity activity) {
        int orientation = getOptimalEmulatorOrientation(activity);
        activity.setRequestedOrientation(orientation);
    }
    
    /**
     * Forces the activity to portrait orientation (for controller mapping).
     * @param activity The activity to orient
     */
    public static void setPortraitOrientation(Activity activity) {
        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }
    
    /**
     * Forces the activity to landscape orientation.
     * @param activity The activity to orient
     */
    public static void setLandscapeOrientation(Activity activity) {
        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }
    
    /**
     * Checks if the current device is a tablet based on screen size.
     * @param context The application context
     * @return true if device is considered a tablet, false otherwise
     */
    public static boolean isTablet(Context context) {
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        float widthDp = displayMetrics.widthPixels / displayMetrics.density;
        float heightDp = displayMetrics.heightPixels / displayMetrics.density;
        float smallestWidthDp = Math.min(widthDp, heightDp);
        
        return smallestWidthDp >= TABLET_DP_THRESHOLD;
    }
    
    /**
     * Gets the current orientation of the device.
     * @param context The application context
     * @return Configuration.ORIENTATION_LANDSCAPE or Configuration.ORIENTATION_PORTRAIT
     */
    public static int getCurrentOrientation(Context context) {
        return context.getResources().getConfiguration().orientation;
    }
    
    /**
     * Checks if the current orientation is landscape.
     * @param context The application context
     * @return true if landscape, false if portrait
     */
    public static boolean isLandscape(Context context) {
        return getCurrentOrientation(context) == Configuration.ORIENTATION_LANDSCAPE;
    }
    
    /**
     * Checks if the current orientation is portrait.
     * @param context The application context
     * @return true if portrait, false if landscape
     */
    public static boolean isPortrait(Context context) {
        return getCurrentOrientation(context) == Configuration.ORIENTATION_PORTRAIT;
    }
    
    /**
     * Gets the rotation angle for the current orientation.
     * @param activity The activity
     * @return 0, 90, 180, or 270 degrees
     */
    public static int getRotationAngle(Activity activity) {
        WindowManager windowManager = activity.getWindowManager();
        int rotation = windowManager.getDefaultDisplay().getRotation();
        
        switch (rotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }
    
    /**
     * Handles orientation change for emulator activity.
     * This method should be called when orientation changes to ensure proper rendering.
     * @param activity The emulator activity
     */
    public static void handleEmulatorOrientationChange(Activity activity) {
        // For phones, allow orientation changes
        if (!isTablet(activity)) {
            int currentOrientation = getCurrentOrientation(activity);
            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setLandscapeOrientation(activity);
            } else {
                setPortraitOrientation(activity);
            }
        }
        // For tablets, maintain landscape orientation
        else {
            setLandscapeOrientation(activity);
        }
    }
}