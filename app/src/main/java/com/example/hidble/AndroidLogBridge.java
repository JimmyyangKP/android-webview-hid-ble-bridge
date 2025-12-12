/*
 * Copyright (c) 2025 KwickPOS - Jimmy
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.example.hidble;

import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * AndroidLogBridge - JavaScript Interface for Android Logcat
 *
 * Provides a bridge for JavaScript code to log messages to Android's Logcat.
 * Useful for debugging WebView JavaScript code since console.log output
 * is not always visible in Android.
 *
 * JavaScript Interface Name: AndroidLog
 *
 * Usage in JavaScript:
 *   AndroidLog.d('MyTag', 'Debug message');
 *   AndroidLog.i('MyTag', 'Info message');
 *   AndroidLog.w('MyTag', 'Warning message');
 *   AndroidLog.e('MyTag', 'Error message');
 *
 *   // Shorthand without tag:
 *   AndroidLog.log('Debug message');
 *   AndroidLog.warn('Warning message');
 *   AndroidLog.error('Error message');
 */
public class AndroidLogBridge {
    private static final String TAG = "WebView-JS";

    /**
     * Log debug message with custom tag
     */
    @JavascriptInterface
    public void d(String tag, String message) {
        Log.d(tag != null ? tag : TAG, message != null ? message : "");
    }

    /**
     * Log info message with custom tag
     */
    @JavascriptInterface
    public void i(String tag, String message) {
        Log.i(tag != null ? tag : TAG, message != null ? message : "");
    }

    /**
     * Log warning message with custom tag
     */
    @JavascriptInterface
    public void w(String tag, String message) {
        Log.w(tag != null ? tag : TAG, message != null ? message : "");
    }

    /**
     * Log error message with custom tag
     */
    @JavascriptInterface
    public void e(String tag, String message) {
        Log.e(tag != null ? tag : TAG, message != null ? message : "");
    }

    /**
     * Log debug message with default tag (shorthand)
     */
    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, message != null ? message : "");
    }

    /**
     * Log warning message with default tag (shorthand)
     */
    @JavascriptInterface
    public void warn(String message) {
        Log.w(TAG, message != null ? message : "");
    }

    /**
     * Log error message with default tag (shorthand)
     */
    @JavascriptInterface
    public void error(String message) {
        Log.e(TAG, message != null ? message : "");
    }
}
