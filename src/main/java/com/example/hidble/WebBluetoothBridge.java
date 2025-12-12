/*
 * Copyright (c) 2025 KwickPOS - Jimmy
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.example.hidble;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebBluetoothBridge - JavaScript Interface for Web Bluetooth API
 *
 * Implements Web Bluetooth API compatibility for Android WebView.
 * Bridges JavaScript calls to native Android Bluetooth LE APIs.
 *
 * Features:
 * - BLE device scanning and selection dialog
 * - GATT connection management
 * - Service and characteristic discovery
 * - Read/Write characteristic operations
 * - Notification subscriptions
 * - Automatic pairing handling
 *
 * JavaScript Interface Name: AndroidWebBluetooth
 */
public class WebBluetoothBridge {
    private static final String TAG = "WebBluetoothBridge";

    // Client Characteristic Configuration Descriptor UUID
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Activity activity;
    private WebView webView;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private Handler mainHandler;

    // Scan state - Thread-safe collections for concurrent access
    private List<BluetoothDevice> scannedDevices = new CopyOnWriteArrayList<>();
    private volatile boolean isScanning = false;
    private volatile String pendingScanCallback = null;
    private android.app.AlertDialog scanDialog = null;
    private android.widget.ArrayAdapter<String> deviceListAdapter = null;
    private Runnable scanTimeoutRunnable = null;

    // Connection state
    private volatile boolean isConnected = false;
    private volatile String connectedDeviceId = null;

    // Pending callbacks for async operations
    private volatile String pendingWriteCallback = null;
    private volatile String pendingNotifyCallback = null;

    // Pairing state
    private BluetoothDevice pendingPairingDevice = null;
    private android.content.BroadcastReceiver pairingReceiver = null;

    // Device name filter (customize for your devices)
    private DeviceNameFilter deviceNameFilter = null;

    /**
     * Interface for filtering BLE devices by name
     */
    public interface DeviceNameFilter {
        boolean isAllowedDevice(String deviceName);
    }

    public WebBluetoothBridge(Activity activity, WebView webView) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity cannot be null");
        }
        if (webView == null) {
            throw new IllegalArgumentException("WebView cannot be null");
        }

        this.activity = activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();

            if (bluetoothAdapter != null) {
                this.bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        } else {
            Log.w(TAG, "BluetoothManager not available on this device");
        }

        registerPairingReceiver();
    }

    /**
     * Set a custom device name filter to control which devices appear in the scan dialog
     */
    public void setDeviceNameFilter(DeviceNameFilter filter) {
        this.deviceNameFilter = filter;
    }

    private void registerPairingReceiver() {
        pairingReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();

                if (android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                    android.bluetooth.BluetoothDevice device = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                    int pairingType = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);

                    Log.d(TAG, "Pairing request received for: " + device.getAddress() + ", type: " + pairingType);

                    // PAIRING_VARIANT_CONSENT = 3 (Just Works - auto-confirm)
                    if (pairingType == 3) {
                        Log.d(TAG, "Auto-confirming Just Works pairing");
                        try {
                            device.setPairingConfirmation(true);
                            abortBroadcast();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to confirm pairing", e);
                        }
                    }
                }
                else if (android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    android.bluetooth.BluetoothDevice device = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                    int newState = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1);
                    int prevState = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

                    Log.d(TAG, "Bond state changed: " + prevState + " -> " + newState + " for device: " + device.getAddress());
                }
            }
        };

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.setPriority(android.content.IntentFilter.SYSTEM_HIGH_PRIORITY);

        activity.registerReceiver(pairingReceiver, filter);
        Log.d(TAG, "Pairing receiver registered");
    }

    /**
     * Cleanup resources - MUST be called when Activity is destroyed
     */
    public void cleanup() {
        // Stop any ongoing BLE scan
        if (isScanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
                Log.d(TAG, "BLE scan stopped during cleanup");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping BLE scan", e);
            }
            isScanning = false;
        }

        // Cancel any pending scan timeout
        if (scanTimeoutRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }

        // Dismiss scan dialog if showing
        if (scanDialog != null) {
            try {
                if (scanDialog.isShowing()) {
                    scanDialog.dismiss();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing scan dialog", e);
            }
            scanDialog = null;
            deviceListAdapter = null;
        }

        // Clear scan state
        scannedDevices.clear();
        pendingScanCallback = null;

        // Unregister pairing receiver
        if (pairingReceiver != null) {
            try {
                activity.unregisterReceiver(pairingReceiver);
                Log.d(TAG, "Pairing receiver unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering pairing receiver", e);
            }
            pairingReceiver = null;
        }

        // Close GATT connection
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
                Log.d(TAG, "GATT connection closed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing GATT", e);
            }
            bluetoothGatt = null;
        }

        // Clear connection state
        isConnected = false;
        connectedDeviceId = null;
        pendingWriteCallback = null;
        pendingNotifyCallback = null;
        pendingPairingDevice = null;

        // Clear references to prevent memory leak
        activity = null;
        webView = null;
        bluetoothAdapter = null;
        bluetoothLeScanner = null;
        mainHandler = null;

        Log.d(TAG, "WebBluetoothBridge cleanup completed");
    }

    /**
     * Request Bluetooth device - mimics navigator.bluetooth.requestDevice()
     *
     * @param filtersJson JSON string containing service UUID filters
     * @param callbackName JavaScript callback function name
     */
    @JavascriptInterface
    public void requestDevice(String filtersJson, String callbackName) {
        Log.d(TAG, "requestDevice called with filters: " + filtersJson);

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            executeCallback(callbackName, createErrorResult("Bluetooth not available or disabled"));
            return;
        }

        try {
            JSONObject filters = new JSONObject(filtersJson);
            JSONArray services = filters.optJSONArray("services");

            if (services == null || services.length() == 0) {
                executeCallback(callbackName, createErrorResult("No service filters provided"));
                return;
            }

            String serviceUuid = services.getString(0);
            Log.d(TAG, "Scanning for service UUID: " + serviceUuid);

            startBleScan(serviceUuid, callbackName);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing filters JSON", e);
            executeCallback(callbackName, createErrorResult("Invalid filters format: " + e.getMessage()));
        }
    }

    /**
     * Connect to GATT server - mimics device.gatt.connect()
     *
     * @param deviceId Bluetooth device address
     * @param callbackName JavaScript callback function name
     */
    @JavascriptInterface
    public void connectGatt(String deviceId, String callbackName) {
        Log.d(TAG, "connectGatt called for device: " + deviceId);

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceId);
        if (device == null) {
            executeCallback(callbackName, createErrorResult("Device not found"));
            return;
        }

        // Close existing GATT connection before creating new one
        if (bluetoothGatt != null) {
            Log.w(TAG, "Closing existing GATT connection before connecting to new device");
            try {
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing existing GATT", e);
            }
            bluetoothGatt = null;
        }

        bluetoothGatt = device.connectGatt(activity, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server, status: " + status);
                    isConnected = true;
                    connectedDeviceId = deviceId;

                    boolean discoverStarted = gatt.discoverServices();
                    if (!discoverStarted) {
                        Log.e(TAG, "Failed to start service discovery");
                        executeCallback(callbackName, createErrorResult("Failed to start service discovery"));
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from GATT server, status: " + status);
                    isConnected = false;
                    connectedDeviceId = null;

                    if (gatt != null) {
                        Log.d(TAG, "Closing GATT connection to clean up resources");
                        gatt.close();
                        bluetoothGatt = null;
                    }

                    // Notify JavaScript of disconnection
                    executeJavaScript("if(window.EventEmitter) { window.EventEmitter.emit('OnDeviceClose', {device: {id: '" + deviceId + "'}}); }");

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Connection failed with status: " + status);
                        executeCallback(callbackName, createErrorResult("Connection failed: " + status));
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered successfully");
                    executeCallback(callbackName, createSuccessResult("{\"connected\": true, \"deviceId\": \"" + deviceId + "\"}"));
                } else {
                    Log.e(TAG, "Service discovery failed with status: " + status);
                    executeCallback(callbackName, createErrorResult("Service discovery failed"));
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                String charUuid = characteristic.getUuid().toString();
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Characteristic write successful: " + charUuid);
                    if (pendingWriteCallback != null) {
                        executeJavaScript(String.format("if(window._bleCallbacks && window._bleCallbacks['%s']) { window._bleCallbacks['%s'](true); }",
                            pendingWriteCallback, pendingWriteCallback));
                        pendingWriteCallback = null;
                    }
                } else {
                    Log.e(TAG, "Characteristic write failed: " + charUuid + " status: " + status);
                    if (pendingWriteCallback != null) {
                        executeJavaScript(String.format("if(window._bleCallbacks && window._bleCallbacks['%s']) { window._bleCallbacks['%s'](false); }",
                            pendingWriteCallback, pendingWriteCallback));
                        pendingWriteCallback = null;
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                byte[] data = characteristic.getValue();
                String hexData = bytesToHex(data);
                String charUuid = characteristic.getUuid().toString();

                Log.d(TAG, "Characteristic changed: " + charUuid + " data: " + hexData);

                String jsCode = String.format(
                    "if(window._bleNotificationHandlers && window._bleNotificationHandlers['%s']) { " +
                    "  window._bleNotificationHandlers['%s']('%s'); " +
                    "}",
                    charUuid, charUuid, hexData
                );
                executeJavaScript(jsCode);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor write successful (notifications enabled)");
                    if (pendingNotifyCallback != null) {
                        invokeCallback(pendingNotifyCallback, true);
                        pendingNotifyCallback = null;
                    }
                } else {
                    Log.e(TAG, "Descriptor write failed: " + status);
                    if (pendingNotifyCallback != null) {
                        invokeCallback(pendingNotifyCallback, false);
                        pendingNotifyCallback = null;
                    }
                }
            }
        });
    }

    /**
     * Write characteristic value - mimics characteristic.writeValueWithResponse()
     */
    @JavascriptInterface
    public void writeCharacteristic(String serviceUuid, String characteristicUuid, String hexData, String callbackName) {
        if (bluetoothGatt == null || !isConnected) {
            invokeCallback(callbackName, false);
            return;
        }

        try {
            BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(serviceUuid));
            if (service == null) {
                Log.e(TAG, "Service not found: " + serviceUuid);
                invokeCallback(callbackName, false);
                return;
            }

            BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid));
            if (characteristic == null) {
                Log.e(TAG, "Characteristic not found: " + characteristicUuid);
                invokeCallback(callbackName, false);
                return;
            }

            byte[] data = hexToBytes(hexData);
            characteristic.setValue(data);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            pendingWriteCallback = callbackName;

            boolean success = bluetoothGatt.writeCharacteristic(characteristic);
            if (!success) {
                pendingWriteCallback = null;
                invokeCallback(callbackName, false);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error writing characteristic", e);
            pendingWriteCallback = null;
            invokeCallback(callbackName, false);
        }
    }

    /**
     * Start notifications - mimics characteristic.startNotifications()
     */
    @JavascriptInterface
    public void startNotifications(String serviceUuid, String characteristicUuid, String callbackName) {
        if (bluetoothGatt == null || !isConnected) {
            invokeCallback(callbackName, false);
            return;
        }

        try {
            BluetoothGattService service = bluetoothGatt.getService(UUID.fromString(serviceUuid));
            if (service == null) {
                Log.e(TAG, "Service not found");
                invokeCallback(callbackName, false);
                return;
            }

            BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid));
            if (characteristic == null) {
                Log.e(TAG, "Characteristic not found");
                invokeCallback(callbackName, false);
                return;
            }

            Log.d(TAG, "Enabling local notifications for characteristic: " + characteristicUuid);
            boolean success = bluetoothGatt.setCharacteristicNotification(characteristic, true);
            if (!success) {
                Log.e(TAG, "Failed to enable local notifications");
                invokeCallback(callbackName, false);
                return;
            }

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor == null) {
                Log.e(TAG, "CCCD descriptor not found");
                invokeCallback(callbackName, false);
                return;
            }

            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            pendingNotifyCallback = callbackName;

            success = bluetoothGatt.writeDescriptor(descriptor);
            if (!success) {
                Log.e(TAG, "Failed to write CCCD descriptor");
                pendingNotifyCallback = null;
                invokeCallback(callbackName, false);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error starting notifications", e);
            pendingNotifyCallback = null;
            invokeCallback(callbackName, false);
        }
    }

    /**
     * Disconnect from GATT server
     */
    @JavascriptInterface
    public void disconnect() {
        if (bluetoothGatt != null) {
            Log.d(TAG, "Initiating GATT disconnect");
            bluetoothGatt.disconnect();
        }
    }

    /**
     * Get paired/bonded Bluetooth devices
     */
    @JavascriptInterface
    public void getPairedDevices(String callbackName) {
        Log.d(TAG, "getPairedDevices called");

        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            JSONArray devicesArray = new JSONArray();

            if (pairedDevices != null && !pairedDevices.isEmpty()) {
                for (BluetoothDevice device : pairedDevices) {
                    if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE ||
                        device.getType() == BluetoothDevice.DEVICE_TYPE_DUAL) {

                        JSONObject deviceObj = new JSONObject();
                        deviceObj.put("id", device.getAddress());
                        deviceObj.put("name", device.getName() != null ? device.getName() : "Unknown");
                        devicesArray.put(deviceObj);
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("devices", devicesArray);
            executeCallback(callbackName, result.toString());

        } catch (Exception e) {
            Log.e(TAG, "Error getting paired devices", e);
            executeCallback(callbackName, createErrorResult("Failed to get paired devices: " + e.getMessage()));
        }
    }

    // ============ Private Helper Methods ============

    private void startBleScan(String serviceUuid, String callbackName) {
        if (bluetoothLeScanner == null || mainHandler == null) {
            executeCallback(callbackName, createErrorResult("Bluetooth scanner not available"));
            return;
        }

        if (isScanning) {
            executeCallback(callbackName, createErrorResult("Scan already in progress"));
            return;
        }

        if (scanDialog != null && scanDialog.isShowing()) {
            Log.w(TAG, "Scan dialog already showing");
            return;
        }

        scannedDevices.clear();
        isScanning = true;
        pendingScanCallback = callbackName;

        showDeviceSelectionDialog();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // Scan without filters - filter by device name in callback
        bluetoothLeScanner.startScan(null, settings, scanCallback);

        scanTimeoutRunnable = () -> stopBleScan();
        mainHandler.postDelayed(scanTimeoutRunnable, 5000);
    }

    private void stopBleScan() {
        if (!isScanning) return;

        isScanning = false;

        if (bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping BLE scan", e);
            }
        }

        if (scanTimeoutRunnable != null && mainHandler != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }

        final Activity act = activity;
        if (act != null) {
            act.runOnUiThread(() -> {
                if (scanDialog != null) {
                    if (scannedDevices.isEmpty()) {
                        scanDialog.setTitle("No Devices Found");
                    } else {
                        scanDialog.setTitle("Select Bluetooth Device (" + scannedDevices.size() + " found)");
                    }
                } else if (scannedDevices.isEmpty() && pendingScanCallback != null) {
                    executeCallback(pendingScanCallback, createErrorResult("No devices found"));
                    pendingScanCallback = null;
                }
            });
        }
    }

    private void showDeviceSelectionDialog() {
        final Activity act = activity;
        if (act == null) {
            Log.w(TAG, "Activity is null, cannot show device selection dialog");
            return;
        }

        act.runOnUiThread(() -> {
            if (scanDialog != null && scanDialog.isShowing()) {
                updateDeviceListAdapter();
                return;
            }

            deviceListAdapter = new android.widget.ArrayAdapter<>(
                act,
                android.R.layout.simple_list_item_1
            );

            updateDeviceListAdapter();

            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(act);
            builder.setTitle(isScanning ? "Scanning for Devices..." : "Select Bluetooth Device");
            builder.setAdapter(deviceListAdapter, (dialog, which) -> {
                if (scannedDevices.isEmpty() || which >= scannedDevices.size()) {
                    Log.w(TAG, "Invalid device selection");
                    return;
                }

                BluetoothDevice selectedDevice = scannedDevices.get(which);
                Log.d(TAG, "User selected device: " + selectedDevice.getName());

                if (isScanning) {
                    isScanning = false;
                    if (bluetoothLeScanner != null) {
                        try {
                            bluetoothLeScanner.stopScan(scanCallback);
                        } catch (Exception e) {
                            Log.e(TAG, "Error stopping scan", e);
                        }
                    }
                    if (scanTimeoutRunnable != null && mainHandler != null) {
                        mainHandler.removeCallbacks(scanTimeoutRunnable);
                        scanTimeoutRunnable = null;
                    }
                }

                scanDialog = null;
                deviceListAdapter = null;

                if (selectedDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "Device not bonded, initiating pairing");
                    pendingPairingDevice = selectedDevice;
                    pairDevice(selectedDevice);
                } else {
                    Log.d(TAG, "Device already bonded");
                    returnSelectedDevice(selectedDevice);
                }
            });

            builder.setNegativeButton("Cancel", (dialog, which) -> {
                stopScanAndCleanup();
            });

            builder.setOnCancelListener(dialog -> {
                stopScanAndCleanup();
            });

            scanDialog = builder.create();
            scanDialog.show();
        });
    }

    private void stopScanAndCleanup() {
        if (isScanning) {
            isScanning = false;
            if (bluetoothLeScanner != null) {
                try {
                    bluetoothLeScanner.stopScan(scanCallback);
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping scan", e);
                }
            }
            if (scanTimeoutRunnable != null && mainHandler != null) {
                mainHandler.removeCallbacks(scanTimeoutRunnable);
                scanTimeoutRunnable = null;
            }
        }

        scanDialog = null;
        deviceListAdapter = null;

        if (pendingScanCallback != null) {
            executeCallback(pendingScanCallback, createErrorResult("User cancelled device selection"));
            pendingScanCallback = null;
        }
    }

    private void updateDeviceListAdapter() {
        if (deviceListAdapter == null) return;

        deviceListAdapter.clear();
        if (scannedDevices.isEmpty()) {
            deviceListAdapter.add("Scanning...");
        } else {
            for (BluetoothDevice device : scannedDevices) {
                String name = device.getName() != null ? device.getName() : "Unknown";
                String bondState = device.getBondState() == BluetoothDevice.BOND_BONDED ? " [Paired]" : "";
                deviceListAdapter.add(name + bondState + "\n" + device.getAddress());
            }
        }
        deviceListAdapter.notifyDataSetChanged();
    }

    private void pairDevice(BluetoothDevice device) {
        try {
            Log.d(TAG, "Creating bond with device: " + device.getAddress());
            boolean pairingStarted = device.createBond();
            if (!pairingStarted) {
                Log.e(TAG, "Failed to start pairing");
                if (pendingScanCallback != null) {
                    executeCallback(pendingScanCallback, createErrorResult("Failed to start pairing"));
                    pendingScanCallback = null;
                }
            } else {
                Log.d(TAG, "Pairing initiated");
                waitForPairingComplete(device);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during pairing", e);
            if (pendingScanCallback != null) {
                executeCallback(pendingScanCallback, createErrorResult("Pairing error: " + e.getMessage()));
                pendingScanCallback = null;
            }
        }
    }

    private void waitForPairingComplete(BluetoothDevice device) {
        final Handler handler = mainHandler;
        if (handler == null) {
            if (pendingScanCallback != null) {
                executeCallback(pendingScanCallback, createErrorResult("Handler not available"));
                pendingScanCallback = null;
            }
            return;
        }

        final int[] attempts = {0};
        final int maxAttempts = 60; // 30 seconds

        final Runnable checkBondState = new Runnable() {
            @Override
            public void run() {
                int bondState = device.getBondState();
                attempts[0]++;

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "Device paired successfully!");
                    returnSelectedDevice(device);
                } else if (bondState == BluetoothDevice.BOND_NONE) {
                    Log.e(TAG, "Pairing failed or cancelled");
                    if (pendingScanCallback != null) {
                        executeCallback(pendingScanCallback, createErrorResult("Pairing failed or cancelled"));
                        pendingScanCallback = null;
                    }
                } else if (attempts[0] < maxAttempts) {
                    if (handler != null) {
                        handler.postDelayed(this, 500);
                    }
                } else {
                    Log.e(TAG, "Pairing timeout");
                    if (pendingScanCallback != null) {
                        executeCallback(pendingScanCallback, createErrorResult("Pairing timeout"));
                        pendingScanCallback = null;
                    }
                }
            }
        };

        handler.postDelayed(checkBondState, 500);
    }

    private void returnSelectedDevice(BluetoothDevice device) {
        if (pendingScanCallback != null) {
            String result = String.format("{\"id\": \"%s\", \"name\": \"%s\"}",
                    device.getAddress(),
                    device.getName() != null ? device.getName() : "Unknown");
            executeCallback(pendingScanCallback, createSuccessResult(result));
            pendingScanCallback = null;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();

            if (deviceName == null) {
                return;
            }

            // Apply custom filter if set
            if (deviceNameFilter != null && !deviceNameFilter.isAllowedDevice(deviceName)) {
                return;
            }

            if (!scannedDevices.contains(device)) {
                scannedDevices.add(device);
                Log.d(TAG, "Device found: " + deviceName + " (" + device.getAddress() + ")");

                final Activity act = activity;
                if (act != null) {
                    act.runOnUiThread(() -> updateDeviceListAdapter());
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed with error code: " + errorCode);
            isScanning = false;

            if (scanTimeoutRunnable != null) {
                mainHandler.removeCallbacks(scanTimeoutRunnable);
                scanTimeoutRunnable = null;
            }

            final Activity act = activity;
            if (act != null) {
                act.runOnUiThread(() -> {
                    if (scanDialog != null) {
                        try {
                            if (scanDialog.isShowing()) {
                                scanDialog.dismiss();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error dismissing dialog", e);
                        }
                        scanDialog = null;
                        deviceListAdapter = null;
                    }
                });
            }

            if (pendingScanCallback != null) {
                executeCallback(pendingScanCallback, createErrorResult("Scan failed: " + errorCode));
                pendingScanCallback = null;
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

    private void invokeCallback(String callbackId, boolean success) {
        if (callbackId == null || callbackId.isEmpty()) return;

        String jsCode = String.format("if(window._bleCallbacks && window._bleCallbacks['%s']) { window._bleCallbacks['%s'](%s); }",
                callbackId, callbackId, success);
        executeJavaScript(jsCode);
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
