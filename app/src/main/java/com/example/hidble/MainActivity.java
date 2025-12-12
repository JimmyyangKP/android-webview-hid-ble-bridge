/*
 * Copyright (c) 2025 KwickPOS - Jimmy
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.example.hidble;

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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo Activity for WebView HID & BLE Bridge
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
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
                injectPolyfill(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page loaded: " + url);
            }
        });

        // Load MagTek demo page
        webView.loadUrl("https://rms.magensa.net/Test/demo/mmsdemo.html");
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private void initializeBridges() {
        // Initialize Android Log Bridge
        androidLogBridge = new AndroidLogBridge();
        webView.addJavascriptInterface(androidLogBridge, "AndroidLog");
        Log.d(TAG, "AndroidLog bridge initialized");

        // Initialize Web Bluetooth Bridge
        webBluetoothBridge = new WebBluetoothBridge(this, webView);
        webView.addJavascriptInterface(webBluetoothBridge, "AndroidWebBluetooth");
        Log.d(TAG, "WebBluetooth bridge initialized");

        // Initialize Web HID Bridge
        webHIDBridge = new WebHIDBridge(this, webView);
        webView.addJavascriptInterface(webHIDBridge, "AndroidWebHID");
        Log.d(TAG, "WebHID bridge initialized");
    }

    private void injectPolyfill(WebView view) {
        try {
            InputStream is = getAssets().open("webapi_polyfill.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            String polyfill = new String(buffer, "UTF-8");
            view.evaluateJavascript(polyfill, null);
            Log.d(TAG, "Polyfill injected successfully");

        } catch (IOException e) {
            Log.e(TAG, "Failed to inject polyfill", e);
        }
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };

            List<String> permissionsToRequest = new ArrayList<>();
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            }

            if (!permissionsToRequest.isEmpty()) {
                requestPermissions(permissionsToRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_CODE);
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
                Log.w(TAG, "Some permissions were denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (webBluetoothBridge != null) {
            webBluetoothBridge.cleanup();
            webBluetoothBridge = null;
        }

        if (webHIDBridge != null) {
            webHIDBridge.cleanup();
            webHIDBridge = null;
        }

        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
