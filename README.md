# Android WebView HID & BLE Bridge

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-17%2B-brightgreen.svg)](https://developer.android.com/about/versions/android-4.2)

A production-ready Android library that brings **Web Bluetooth API** and **Web HID API** support to Android WebView applications. This bridge enables seamless communication between web applications and Bluetooth Low Energy (BLE) devices or USB HID devices using standard W3C Web APIs.

## Overview

Modern web applications increasingly rely on Web Bluetooth and Web HID APIs for hardware integration. However, Android WebView does not natively support these APIs. This library provides a complete polyfill solution that bridges JavaScript calls to native Android implementations, enabling your hybrid apps to leverage these powerful capabilities.

### Key Features

**Web Bluetooth API**
- Device discovery with customizable filters
- GATT server connection management
- Service and characteristic operations
- Real-time notifications via CCCD
- Automatic pairing and bonding
- Connection state monitoring

**Web HID API**
- USB HID device enumeration
- Runtime permission handling
- Bidirectional report communication
- Hot-plug event notifications
- Thread-safe input report handling

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Web Application                           │
│         (navigator.bluetooth / navigator.hid)                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   webapi_polyfill.js                         │
│          W3C-compliant API Implementation Layer              │
└─────────────────────────────────────────────────────────────┘
                              │
                     JavascriptInterface
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Native Android Bridges                       │
│  ┌─────────────────────┐    ┌─────────────────────┐         │
│  │ WebBluetoothBridge  │    │    WebHIDBridge     │         │
│  │   (BLE Operations)  │    │  (USB HID Control)  │         │
│  └─────────────────────┘    └─────────────────────┘         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Android System APIs                        │
│      BluetoothLeScanner, BluetoothGatt, UsbManager          │
└─────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Add Source Files

Copy the bridge classes to your project:

```
src/main/java/your/package/
├── WebBluetoothBridge.java
├── WebHIDBridge.java
└── AndroidLogBridge.java

src/main/assets/
└── webapi_polyfill.js
```

### 2. Configure Permissions

Add to `AndroidManifest.xml`:

```xml
<!-- Bluetooth (Android 12+) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- USB HID -->
<uses-permission android:name="android.permission.USB_PERMISSION" />

<!-- Hardware Features -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

### 3. Initialize in Activity

```java
public class MainActivity extends Activity {
    private WebView webView;
    private WebBluetoothBridge bleBridge;
    private WebHIDBridge hidBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Initialize bridges
        bleBridge = new WebBluetoothBridge(this, webView);
        hidBridge = new WebHIDBridge(this, webView);

        webView.addJavascriptInterface(bleBridge, "AndroidWebBluetooth");
        webView.addJavascriptInterface(hidBridge, "AndroidWebHID");
        webView.addJavascriptInterface(new AndroidLogBridge(), "AndroidLog");

        // Inject polyfill on page load
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                injectPolyfill(view);
            }
        });

        setContentView(webView);
        webView.loadUrl("https://your-app.com");
    }

    private void injectPolyfill(WebView view) {
        try {
            InputStream is = getAssets().open("webapi_polyfill.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            view.evaluateJavascript(new String(buffer, "UTF-8"), null);
        } catch (IOException e) {
            Log.e("MainActivity", "Polyfill injection failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleBridge != null) bleBridge.cleanup();
        if (hidBridge != null) hidBridge.cleanup();
    }
}
```

## JavaScript API Usage

### Web Bluetooth

```javascript
// Scan and connect to BLE device
async function connectBLE() {
    const device = await navigator.bluetooth.requestDevice({
        filters: [{ services: ['battery_service'] }]
    });

    const server = await device.gatt.connect();
    const service = await server.getPrimaryService('battery_service');
    const characteristic = await service.getCharacteristic('battery_level');

    // Enable notifications
    await characteristic.startNotifications();
    characteristic.addEventListener('characteristicvaluechanged', (e) => {
        console.log('Battery:', e.target.value.getUint8(0) + '%');
    });

    // Handle disconnect
    device.addEventListener('gattserverdisconnected', () => {
        console.log('Device disconnected');
    });
}
```

### Web HID

```javascript
// Connect to USB HID device
async function connectHID() {
    const devices = await navigator.hid.requestDevice({
        filters: [{ vendorId: 0x0801 }]
    });

    const device = devices[0];
    await device.open();

    // Receive input reports
    device.addEventListener('inputreport', (e) => {
        const data = new Uint8Array(e.data.buffer);
        console.log('Received:', data);
    });

    // Send output report
    await device.sendReport(0, new Uint8Array([0x01, 0x02, 0x03]));
}

// Monitor device connections
navigator.hid.addEventListener('connect', (e) => console.log('Connected:', e.device));
navigator.hid.addEventListener('disconnect', (e) => console.log('Disconnected:', e.device));
```

## API Reference

### WebBluetoothBridge

| Method | Parameters | Description |
|--------|------------|-------------|
| `requestDevice` | filtersJson, callback | Initiates device scan with filter criteria |
| `connectGatt` | deviceId, callback | Establishes GATT connection |
| `writeCharacteristic` | serviceUuid, charUuid, hexData, callback | Writes data to characteristic |
| `startNotifications` | serviceUuid, charUuid, callback | Enables characteristic notifications |
| `disconnect` | - | Terminates GATT connection |
| `getPairedDevices` | callback | Returns bonded BLE devices |
| `cleanup` | - | Releases all resources |

### WebHIDBridge

| Method | Parameters | Description |
|--------|------------|-------------|
| `getDevices` | callback | Lists permitted HID devices |
| `requestDevice` | filtersJson, callback | Requests device access with permission dialog |
| `openDevice` | deviceName, callback | Opens device for I/O operations |
| `sendReport` | reportId, hexData, callback | Transmits HID output report |
| `closeDevice` | - | Closes device connection |
| `cleanup` | - | Releases all resources |

## Configuration Options

### BLE Device Filtering

```java
bleBridge.setDeviceNameFilter(name -> {
    return name.startsWith("MyDevice") || name.contains("Sensor");
});
```

### HID Vendor ID Filtering

```java
hidBridge.setVendorIdFilter(0x0801);  // Your vendor ID
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| No BLE devices found | Verify Bluetooth is enabled; check BLUETOOTH_SCAN and location permissions |
| USB permission denied | User must grant permission via system dialog on each connection |
| Polyfill not working | Ensure injection occurs in `onPageStarted`, before app JavaScript executes |
| Memory leaks | Always call `cleanup()` in `onDestroy()` |

## System Requirements

- **Minimum SDK**: API 17 (Android 4.2)
- **Target SDK**: API 35 (Android 15)
- **Java Version**: 1.8+
- **BLE Support**: Bluetooth 4.0+ hardware
- **USB HID Support**: USB Host mode capable device

## Project Structure

```
android-webview-hid-ble-bridge/
├── src/main/
│   ├── java/com/example/hidble/
│   │   ├── WebBluetoothBridge.java    # BLE implementation
│   │   ├── WebHIDBridge.java          # USB HID implementation
│   │   └── AndroidLogBridge.java      # Debug logging utility
│   └── assets/
│       └── webapi_polyfill.js         # JavaScript API polyfill
├── sample/
│   ├── SampleActivity.java            # Integration example
│   └── index.html                     # Demo web page
├── docs/
│   └── AndroidManifest_permissions.xml
├── README.md
└── LICENSE
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author

**Jimmy @ KwickPOS**

Developed for KwickPOS POS system hardware integration requirements.

---

For questions or support, please open an issue in this repository.
