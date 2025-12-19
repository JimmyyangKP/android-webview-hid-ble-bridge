/*
 * Copyright (c) 2025 KwickPOS - Jimmy
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.example.hidble;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import static android.content.Context.RECEIVER_EXPORTED;

/**
 * WebHIDBridge - JavaScript Interface for Web HID API
 *
 * Implements Web HID API compatibility for Android WebView.
 * Bridges JavaScript calls to native Android USB HID APIs.
 *
 * Features:
 * - USB HID device discovery
 * - Permission request handling
 * - Device open/close operations
 * - Send/Receive HID reports
 * - Connect/Disconnect event notifications
 *
 * JavaScript Interface Name: AndroidWebHID
 */
public class WebHIDBridge {
    private static final String TAG = "WebHIDBridge";
    private static final String ACTION_USB_PERMISSION = "com.example.hidble.USB_PERMISSION";

    // Default vendor ID (customize for your devices)
    private static final int DEFAULT_VENDOR_ID = 0x0801;

    private Activity activity;
    private WebView webView;
    private UsbManager usbManager;
    private Handler mainHandler;

    // Device state
    private Map<String, UsbDevice> connectedDevices = new HashMap<>();
    private UsbDevice currentDevice = null;
    private UsbDeviceConnection currentConnection = null;
    private UsbInterface currentInterface = null;
    private UsbEndpoint inputEndpoint = null;
    private UsbEndpoint outputEndpoint = null;
    private boolean isDeviceOpen = false;

    // Thread for reading input reports
    private Thread inputReportThread = null;
    private volatile boolean shouldReadInput = false;

    // Pending callbacks
    private String pendingPermissionCallback = null;

    // Vendor ID filter (customize for your devices)
    private int vendorIdFilter = DEFAULT_VENDOR_ID;

    private PendingIntent mPermissionIntent;

    private Object mPermissionEvent;
    private boolean mPermissionGranted = false;

    public WebHIDBridge(Activity activity, WebView webView) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity cannot be null");
        }
        if (webView == null) {
            throw new IllegalArgumentException("WebView cannot be null");
        }

        this.activity = activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);

        if (usbManager == null) {
            Log.w(TAG, "UsbManager not available on this device");
        }

        if (android.os.Build.VERSION.SDK_INT >= 34)
        {
            Log.i(TAG, "*** Android 14 or higher");
            Intent explicitIntent = new Intent(ACTION_USB_PERMISSION);
            explicitIntent.setPackage(activity.getPackageName());
            mPermissionIntent = PendingIntent.getBroadcast(activity, 0, explicitIntent, PendingIntent.FLAG_MUTABLE);
        }
        else if (android.os.Build.VERSION.SDK_INT > 30)
        {
            mPermissionIntent = PendingIntent.getBroadcast(activity, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
        }
        else
        {
            mPermissionIntent = PendingIntent.getBroadcast(activity, 0, new Intent(ACTION_USB_PERMISSION), 0);
        }

        // Register USB permission receiver
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 34)
        {
            activity.registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED);
        }
        else
        {
            activity.registerReceiver(usbReceiver, filter);
        }
    }

    /**
     * Set the vendor ID to filter devices
     */
    public void setVendorIdFilter(int vendorId) {
        this.vendorIdFilter = vendorId;
    }

    /**
     * Get list of already-permitted HID devices - mimics navigator.hid.getDevices()
     *
     * @param callbackName JavaScript callback function name
     */
    @JavascriptInterface
    public void getDevices(String callbackName) {
        Log.d(TAG, "getDevices called");

        if (usbManager == null) {
            executeCallback(callbackName, createErrorResult("USB Manager not available"));
            return;
        }

        try {
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            JSONArray devices = new JSONArray();

            for (UsbDevice device : deviceList.values()) {
                if (isHIDDevice(device)) {
                    connectedDevices.put(device.getDeviceName(), device);
                    devices.put(createDeviceJson(device));
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("devices", devices);
            executeCallback(callbackName, result.toString());

        } catch (JSONException e) {
            Log.e(TAG, "Error creating device list JSON", e);
            executeCallback(callbackName, createErrorResult("Failed to get devices: " + e.getMessage()));
        }
    }

    /**
     * Request HID device - mimics navigator.hid.requestDevice()
     *
     * @param filtersJson JSON string containing device filters (vendorId, productId)
     * @param callbackName JavaScript callback function name
     */
    @JavascriptInterface
    public void requestDevice(String filtersJson, String callbackName) {
        Log.d(TAG, "requestDevice called with filters: " + filtersJson);

        if (usbManager == null) {
            executeCallback(callbackName, createErrorResult("USB Manager not available"));
            return;
        }

        try {
            JSONObject filters = new JSONObject(filtersJson);
            int vendorId = filters.optInt("vendorId", vendorIdFilter);

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            UsbDevice matchedDevice = null;

            for (UsbDevice device : deviceList.values()) {
                if (device.getVendorId() == vendorId && isHIDDevice(device)) {
                    matchedDevice = device;
                    break;
                }
            }

            if (matchedDevice == null) {
                executeCallback(callbackName, createErrorResult("No matching HID device found"));
                return;
            }

            if (usbManager.hasPermission(matchedDevice)) {
                connectedDevices.put(matchedDevice.getDeviceName(), matchedDevice);
                JSONArray deviceArray = new JSONArray();
                deviceArray.put(createDeviceJson(matchedDevice));

                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("devices", deviceArray);
                executeCallback(callbackName, result.toString());

            } else {
                pendingPermissionCallback = callbackName;
                PendingIntent permissionIntent = PendingIntent.getBroadcast(
                        activity,
                        0,
                        new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                usbManager.requestPermission(matchedDevice, permissionIntent);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing filters JSON", e);
            executeCallback(callbackName, createErrorResult("Invalid filters format: " + e.getMessage()));
        }
    }

    /**
     * Open HID device - mimics device.open()
     *
     * @param deviceName Device name (from getDevices or requestDevice)
     * @param callbackName JavaScript callback function name
     */
    @JavascriptInterface
    public void openDevice(String deviceName, String callbackName) {
        Log.i(TAG, "openDevice called for: " + deviceName);

        if (usbManager == null) {
            Log.e(TAG, "openDevice FAILED - USB Manager not available");
            executeCallback(callbackName, createErrorResult("USB Manager not available"));
            return;
        }

        UsbDevice device = connectedDevices.get(deviceName);
        if (device == null) {
            Log.e(TAG, "openDevice FAILED - device not found: " + deviceName);
            executeCallback(callbackName, createErrorResult("Device not found: " + deviceName));
            return;
        }

        if (!usbManager.hasPermission(device)) {
            mPermissionEvent = new Object();
            mPermissionGranted = false;
            usbManager.requestPermission(device, mPermissionIntent);
            synchronized (mPermissionEvent)
            {
                try
                {
                    mPermissionEvent.wait(30000);
                }
                catch (Exception ex)
                {

                }
                if (!mPermissionGranted)
                {
                    Log.e(TAG, "openDevice FAILED - no permission");
                    executeCallback(callbackName, createErrorResult("No permission for device"));
                    return;
                }
            }
        }

        try {
            currentConnection = usbManager.openDevice(device);
            if (currentConnection == null) {
                Log.e(TAG, "openDevice FAILED - cannot open connection");
                executeCallback(callbackName, createErrorResult("Failed to open device connection"));
                return;
            }

            currentInterface = findHIDInterface(device);
            if (currentInterface == null) {
                currentConnection.close();
                currentConnection = null;
                Log.e(TAG, "openDevice FAILED - no HID interface");
                executeCallback(callbackName, createErrorResult("No HID interface found"));
                return;
            }

            boolean claimed = currentConnection.claimInterface(currentInterface, true);
            if (!claimed) {
                currentConnection.close();
                currentConnection = null;
                Log.e(TAG, "openDevice FAILED - cannot claim interface");
                executeCallback(callbackName, createErrorResult("Failed to claim interface"));
                return;
            }

            // Find endpoints
            for (int i = 0; i < currentInterface.getEndpointCount(); i++) {
                UsbEndpoint endpoint = currentInterface.getEndpoint(i);
                if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                        inputEndpoint = endpoint;
                    } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                        outputEndpoint = endpoint;
                    }
                }
            }

            if (inputEndpoint == null || outputEndpoint == null) {
                currentConnection.releaseInterface(currentInterface);
                currentConnection.close();
                currentConnection = null;
                Log.e(TAG, "openDevice FAILED - no endpoints found");
                executeCallback(callbackName, createErrorResult("Failed to find input/output endpoints"));
                return;
            }

            currentDevice = device;
            isDeviceOpen = true;

            Log.i(TAG, "openDevice SUCCESS - starting input thread");

            startInputReportThread();

            executeCallback(callbackName, createSuccessResult("{\"opened\": true}"));

            // Notify JavaScript of device open
            executeJavaScript("if(window.EventEmitter) { window.EventEmitter.emit('OnDeviceOpen', {device: " + createDeviceJson(device).toString() + "}); }");

        } catch (Exception e) {
            Log.e(TAG, "openDevice EXCEPTION: " + e.getMessage(), e);
            cleanupConnection();
            executeCallback(callbackName, createErrorResult("Open error: " + e.getMessage()));
        }
    }

    /**
     * Send HID output report - mimics device.sendReport()
     *
     * @param reportId Report ID (usually 0)
     * @param hexData Hex string data to send
     * @param callbackName JavaScript callback function name
     */
    @JavascriptInterface
    public void sendReport(int reportId, String hexData, String callbackName) {
        Log.i(TAG, "sendReport called - deviceOpen:" + isDeviceOpen);
        if (!isDeviceOpen || currentConnection == null || outputEndpoint == null) {
            Log.e(TAG, "sendReport FAILED - device not open");
            executeCallback(callbackName, createErrorResult("Device not open"));
            return;
        }

        // Run on background thread to avoid blocking UI
        final UsbDeviceConnection conn = currentConnection;
        final UsbEndpoint endpoint = outputEndpoint;
        final int ifaceNum = currentInterface.getId();

        new Thread(() -> {
            try {
                byte[] data = hexToBytes(hexData);

                byte[] report;
                if (reportId == 0) {
                    report = data;
                } else {
                    report = new byte[data.length + 1];
                    report[0] = (byte) reportId;
                    System.arraycopy(data, 0, report, 1, data.length);
                }

                int timeout = 5000;

                // Try controlTransfer first (HID SET_REPORT)
                // requestType: 0x21 = host to device, class, interface
                // request: 0x09 = SET_REPORT
                // value: (reportType << 8) | reportId, reportType 2 = output
                int requestType = 0x21;
                int request = 0x09;  // SET_REPORT
                int value = (2 << 8) | reportId;  // Output report
                int index = ifaceNum;

                int transferred = conn.controlTransfer(requestType, request, value, index, report, report.length, timeout);

                if (transferred >= 0) {
                    executeCallback(callbackName, createSuccessResult("{}"));
                } else {
                    executeCallback(callbackName, createErrorResult("Failed to send report: " + transferred));
                }

            } catch (Exception e) {
                Log.e(TAG, "Error sending report", e);
                executeCallback(callbackName, createErrorResult("Send error: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * Close HID device - mimics device.close()
     */
    @JavascriptInterface
    public void closeDevice() {
        Log.d(TAG, "closeDevice called");

        stopInputReportThread();
        cleanupConnection();

        if (currentDevice != null) {
            String deviceName = currentDevice.getDeviceName();
            executeJavaScript("if(window.EventEmitter) { window.EventEmitter.emit('OnDeviceClose', {device: {name: '" + deviceName + "'}}); }");
        }

        currentDevice = null;
        isDeviceOpen = false;
    }

    /**
     * Cleanup resources - MUST be called when Activity is destroyed
     */
    public void cleanup() {
        try {
            if (activity != null) {
                activity.unregisterReceiver(usbReceiver);
            }
        } catch (Exception e) {
            // Receiver may not be registered
        }
        closeDevice();

        activity = null;
        webView = null;
        usbManager = null;
        mainHandler = null;
        connectedDevices.clear();
    }

    // ============ Private Helper Methods ============

    private boolean isHIDDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                return true;
            }
        }
        return false;
    }

    private UsbInterface findHIDInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                return iface;
            }
        }
        return null;
    }

    private JSONObject createDeviceJson(UsbDevice device) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", device.getDeviceName());
        json.put("vendorId", device.getVendorId());
        json.put("productId", device.getProductId());
        json.put("productName", device.getProductName() != null ? device.getProductName() : "Unknown");
        json.put("opened", isDeviceOpen && device.equals(currentDevice));
        return json;
    }

    private void startInputReportThread() {
        shouldReadInput = true;
        Log.i(TAG, "Starting input report thread");
        inputReportThread = new Thread(() -> {
            byte[] buffer = new byte[inputEndpoint.getMaxPacketSize()];
            int consecutiveErrors = 0;
            final int MAX_CONSECUTIVE_ERRORS = 10;
            long lastLogTime = 0;
            long readCount = 0;

            Log.i(TAG, "Input thread running, buffer size: " + buffer.length);

            while (shouldReadInput && currentConnection != null && inputEndpoint != null) {
                try {
                    int bytesRead = currentConnection.bulkTransfer(inputEndpoint, buffer, buffer.length, 100);
                    readCount++;

                    if (bytesRead > 0) {
                        consecutiveErrors = 0;
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        String hexData = bytesToHex(data);

                        Log.i(TAG, "Input report received (" + bytesRead + " bytes): " + hexData.substring(0, Math.min(40, hexData.length())) + "...");

                        final Handler handler = mainHandler;
                        if (handler != null) {
                            handler.post(() -> {
                                String jsCode = String.format(
                                    "if(window._hidInputReportHandler) { " +
                                    "  window._hidInputReportHandler('%s'); " +
                                    "}",
                                    hexData
                                );
                                executeJavaScript(jsCode);
                            });
                        }
                    } else if (bytesRead < 0) {
                        consecutiveErrors = 0;
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 30000) {
                        Log.i(TAG, "Input thread heartbeat - reads: " + readCount);
                        lastLogTime = now;
                    }

                } catch (Exception e) {
                    consecutiveErrors++;
                    if (shouldReadInput) {
                        Log.w(TAG, "Error reading input report (attempt " + consecutiveErrors + "): " + e.getMessage());
                    }

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.e(TAG, "Too many consecutive errors, stopping input thread");
                        break;
                    }

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }

            Log.i(TAG, "Input report thread STOPPED");
        });

        inputReportThread.start();
    }

    private void stopInputReportThread() {
        shouldReadInput = false;
        if (inputReportThread != null) {
            inputReportThread.interrupt();
            try {
                inputReportThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            inputReportThread = null;
        }
    }

    private void cleanupConnection() {
        if (currentConnection != null) {
            if (currentInterface != null) {
                currentConnection.releaseInterface(currentInterface);
                currentInterface = null;
            }
            currentConnection.close();
            currentConnection = null;
        }
        inputEndpoint = null;
        outputEndpoint = null;
    }

    protected void notifyPermissionResult(boolean result)
    {
        if (mPermissionEvent != null)
        {
            synchronized (mPermissionEvent)
            {
                mPermissionGranted = result;
                mPermissionEvent.notifyAll();
            }
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d(TAG, "USB permission granted for: " + device.getDeviceName());
                            connectedDevices.put(device.getDeviceName(), device);

                            notifyPermissionResult(true);

                            if (pendingPermissionCallback != null) {
                                try {
                                    JSONArray deviceArray = new JSONArray();
                                    deviceArray.put(createDeviceJson(device));

                                    JSONObject result = new JSONObject();
                                    result.put("success", true);
                                    result.put("devices", deviceArray);
                                    executeCallback(pendingPermissionCallback, result.toString());
                                } catch (JSONException e) {
                                    executeCallback(pendingPermissionCallback, createErrorResult("Error creating device JSON"));
                                }
                                pendingPermissionCallback = null;
                            }
                        }
                    } else {
                        Log.d(TAG, "USB permission denied");
                        notifyPermissionResult(false);
                        if (pendingPermissionCallback != null) {
                            executeCallback(pendingPermissionCallback, createErrorResult("USB permission denied by user"));
                            pendingPermissionCallback = null;
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isHIDDevice(device)) {
                    Log.d(TAG, "USB device attached: " + device.getDeviceName());

                    if (!isDeviceOpen || currentDevice == null || !device.equals(currentDevice)) {
                        executeJavaScript("if(window.EventEmitter) { window.EventEmitter.emit('OnDeviceConnect', {device: {name: '" + device.getDeviceName() + "'}}); }");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    Log.d(TAG, "USB device detached: " + device.getDeviceName());
                    connectedDevices.remove(device.getDeviceName());

                    if (device.equals(currentDevice)) {
                        cleanupConnection();
                        currentDevice = null;
                        isDeviceOpen = false;
                    }

                    executeJavaScript("if(window.EventEmitter) { window.EventEmitter.emit('OnDeviceDisconnect', {device: {name: '" + device.getDeviceName() + "'}}); }");
                }
            }
        }
    };

    private void executeCallback(String callbackName, String resultJson) {
        if (callbackName == null || callbackName.isEmpty()) return;

        String jsCode = String.format("if(typeof window.%s === 'function') { window.%s(%s); }",
                callbackName, callbackName, resultJson);
        executeJavaScript(jsCode);
    }

    private void executeJavaScript(String jsCode) {
        final Handler handler = mainHandler;
        final WebView wv = webView;

        if (handler != null && wv != null) {
            handler.post(() -> wv.evaluateJavascript(jsCode, null));
        }
    }

    private String createSuccessResult(String data) {
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("data", new JSONObject(data));
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\": true, \"data\": {}}";
        }
    }

    private String createErrorResult(String error) {
        try {
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("error", error);
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\": false, \"error\": \"Unknown error\"}";
        }
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
