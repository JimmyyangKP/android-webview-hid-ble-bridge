/*
 * Copyright (c) 2025 KwickPOS - Jimmy
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.example.hidble.sample;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.hidble.AndroidLogBridge;
import com.example.hidble.WebBluetoothBridge;
import com.example.hidble.WebHIDBridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample Activity demonstrating WebView HID & BLE Bridge integration
 *
 * This example shows how to:
 * 1. Set up a WebView with JavaScript enabled
 * 2. Initialize the HID and BLE bridges
 * 3. Inject the polyfill JavaScript
 * 4. Handle runtime permissions
 * 5. Properly cleanup resources
 */
public class SampleActivity extends Activity {
    private static final String TAG = "SampleActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private WebView webView;
    private WebBluetoothBridge webBluetoothBridge;
    private WebHIDBridge webHIDBridge;
    private AndroidLogBridge androidLogBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request permissions first
        requestRequiredPermissions();

        // Initialize WebView
        webView = new WebView(this);
        setContentView(webView);

        // Configure WebView settings
        configureWebView();

        // Initialize JavaScript bridges
        initializeBridges();

        // Set up WebViewClient to inject polyfill
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Inject polyfill at the start of every page load
                injectPolyfill(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page loaded: " + url);
            }
        });

        // Load your web application
        // Option 1: Load from URL
        // webView.loadUrl("https://your-webapp.com");

        // Option 2: Load from local assets
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        // Enable JavaScript (required)
        settings.setJavaScriptEnabled(true);

        // Enable DOM storage for web apps
        settings.setDomStorageEnabled(true);

        // Allow file access (for loading from assets)
        settings.setAllowFileAccess(true);

        // Enable debugging in development
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private void initializeBridges() {
        // 1. Initialize Android Log Bridge (optional but recommended for debugging)
        androidLogBridge = new AndroidLogBridge();
        webView.addJavascriptInterface(androidLogBridge, "AndroidLog");
        Log.d(TAG, "AndroidLog bridge initialized");

        // 2. Initialize Web Bluetooth Bridge
        webBluetoothBridge = new WebBluetoothBridge(this, webView);
        webView.addJavascriptInterface(webBluetoothBridge, "AndroidWebBluetooth");
        Log.d(TAG, "WebBluetooth bridge initialized");

        // Optional: Set custom device name filter
        // This filters which devices appear in the BLE scan dialog
        webBluetoothBridge.setDeviceNameFilter(deviceName -> {
            // Example: Only show devices with specific prefixes
            // Return true to show the device, false to hide it
            return deviceName != null && (
                deviceName.startsWith("MyDevice") ||
                deviceName.startsWith("Sensor") ||
                deviceName.contains("BLE")
            );
            // To show all devices, simply return: true
        });

        // 3. Initialize Web HID Bridge
        webHIDBridge = new WebHIDBridge(this, webView);
        webView.addJavascriptInterface(webHIDBridge, "AndroidWebHID");
        Log.d(TAG, "WebHID bridge initialized");

        // Optional: Set vendor ID filter for HID devices
        // webHIDBridge.setVendorIdFilter(0x0801); // Your device's vendor ID
    }

    private void injectPolyfill(WebView view) {
        try {
            // Read polyfill from assets
            InputStream is = getAssets().open("webapi_polyfill.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            String polyfill = new String(buffer, "UTF-8");

            // Inject the polyfill
            view.evaluateJavascript(polyfill, null);
            Log.d(TAG, "Polyfill injected successfully");

        } catch (IOException e) {
            Log.e(TAG, "Failed to inject polyfill", e);
        }
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires new Bluetooth permissions
            String[] permissions = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };

            List<String> permissionsToRequest = new ArrayList<>();
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            }

            if (!permissionsToRequest.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-11 requires location permission for BLE
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "All permissions granted");
            } else {
                Log.w(TAG, "Some permissions were denied - BLE features may not work");
                // Optionally show a dialog explaining why permissions are needed
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // IMPORTANT: Cleanup bridges to prevent memory leaks
        if (webBluetoothBridge != null) {
            webBluetoothBridge.cleanup();
            webBluetoothBridge = null;
            Log.d(TAG, "WebBluetooth bridge cleaned up");
        }

        if (webHIDBridge != null) {
            webHIDBridge.cleanup();
            webHIDBridge = null;
            Log.d(TAG, "WebHID bridge cleaned up");
        }

        // Destroy WebView
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void onBackPressed() {
        // Handle back button in WebView
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
