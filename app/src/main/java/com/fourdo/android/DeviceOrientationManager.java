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
     * Uses natural screen dimensions (independent of current orientation).
     * @param context The application context
     * @return true if device is considered a tablet, false otherwise
     */
    public static boolean isTablet(Context context) {
        // Get natural screen dimensions (independent of current rotation)
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        
        // Get real display metrics (includes system bars, more accurate)
        DisplayMetrics realMetrics = new DisplayMetrics();
        display.getRealMetrics(realMetrics);
        
        // Natural width/height (these are in the device's natural orientation)
        int naturalWidth = realMetrics.widthPixels;
        int naturalHeight = realMetrics.heightPixels;
        
        // Account for current rotation to get natural dimensions
        int rotation = display.getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // Device is in landscape, swap to get natural portrait dimensions
            int temp = naturalWidth;
            naturalWidth = naturalHeight;
            naturalHeight = temp;
        }
        
        // Calculate smallest width dp based on natural dimensions
        float smallestWidthDp = Math.min(naturalWidth, naturalHeight) / displayMetrics.density;
        
        // Check for flip tablets like Retroid Pocket Flip
        String deviceModel = android.os.Build.MODEL.toLowerCase();
        boolean isFlipTablet = deviceModel.contains("retroid") || 
                               deviceModel.contains("flip") ||
                               deviceModel.contains("pocket");
        
        // For flip tablets, use a lower threshold since they're smaller but should be treated as tablets
        if (isFlipTablet) {
            return smallestWidthDp >= 480f; // Lower threshold for flip tablets
        }
        
        return smallestWidthDp >= TABLET_DP_THRESHOLD;
    }
    
    /**
     * Gets the natural orientation of the device (portrait or landscape).
     * This is independent of the current rotation.
     * @param context The application context
     * @return Configuration.ORIENTATION_PORTRAIT or Configuration.ORIENTATION_LANDSCAPE
     */
    public static int getNaturalOrientation(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        
        DisplayMetrics realMetrics = new DisplayMetrics();
        display.getRealMetrics(realMetrics);
        
        int naturalWidth = realMetrics.widthPixels;
        int naturalHeight = realMetrics.heightPixels;
        
        // Account for current rotation to determine natural orientation
        int rotation = display.getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // Device is rotated 90 or 270, swap dimensions
            int temp = naturalWidth;
            naturalWidth = naturalHeight;
            naturalHeight = temp;
        }
        
        // Natural orientation is based on which dimension is larger
        if (naturalWidth > naturalHeight) {
            return Configuration.ORIENTATION_LANDSCAPE;
        } else {
            return Configuration.ORIENTATION_PORTRAIT;
        }
    }
    
    /**
     * Gets the current device rotation from natural orientation.
     * @param context The application context
     * @return Surface.ROTATION_0, ROTATION_90, ROTATION_180, or ROTATION_270
     */
    public static int getCurrentRotation(Context context) {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return windowManager.getDefaultDisplay().getRotation();
    }
    
    /**
     * Locks the splash screen to the current orientation without forcing a change.
     * This preserves whatever orientation the device already has.
     * @param activity The activity to orient
     */
    public static void setOptimalSplashOrientation(Activity activity) {
        // Get current orientation and lock to it
        int currentOrientation = activity.getResources().getConfiguration().orientation;
        
        // Lock to whatever orientation the device currently has
        // NOSENSOR keeps the current orientation without using the sensor
        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
    }
    
    /**
     * Sets the activity to follow sensor orientation (allows all 4 rotations).
     * @param activity The activity to orient
     */
    public static void setSensorOrientation(Activity activity) {
        activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
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