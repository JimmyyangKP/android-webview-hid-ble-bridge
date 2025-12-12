/**
 * Web Bluetooth & Web HID API Polyfill for Android WebView
 *
 * Copyright (c) 2025 KwickPOS - Jimmy
 * Licensed under the MIT License.
 *
 * Provides Web Bluetooth and Web HID API compatibility by bridging
 * JavaScript calls to native Android implementations.
 *
 * This polyfill implements:
 * - navigator.bluetooth.requestDevice()
 * - navigator.bluetooth.getDevices()
 * - BluetoothDevice, BluetoothRemoteGATTServer, BluetoothRemoteGATTService, BluetoothRemoteGATTCharacteristic
 * - navigator.hid.requestDevice()
 * - navigator.hid.getDevices()
 * - HIDDevice with open(), close(), sendReport(), and input report events
 *
 * Usage: Inject this script BEFORE loading any code that uses Web Bluetooth or Web HID APIs.
 *
 * Required Android bridges:
 * - AndroidWebBluetooth (WebBluetoothBridge.java)
 * - AndroidWebHID (WebHIDBridge.java)
 * - AndroidLog (AndroidLogBridge.java) - optional, for debugging
 */

(function() {
  'use strict';

  // Check if Android bridges are available
  if (typeof AndroidWebBluetooth === 'undefined' || typeof AndroidWebHID === 'undefined') {
    if (typeof AndroidLog !== 'undefined') {
      AndroidLog.e('WebAPI-Polyfill', 'FATAL: Android bridges not found! JavascriptInterface not properly injected.');
    }
    console.error('WebAPI-Polyfill: Android bridges not found');
    return;
  }

  // Log successful initialization
  if (typeof AndroidLog !== 'undefined') {
    AndroidLog.d('WebAPI-Polyfill', 'Web Bluetooth & HID API polyfill initialized successfully');
  }

  // ==================== Event Emitter ====================
  // Simple event emitter for device events
  if (!window.EventEmitter) {
    window.EventEmitter = {
      _listeners: {},
      on: function(event, callback) {
        if (!this._listeners[event]) {
          this._listeners[event] = [];
        }
        this._listeners[event].push(callback);
      },
      off: function(event, callback) {
        if (this._listeners[event]) {
          const index = this._listeners[event].indexOf(callback);
          if (index > -1) {
            this._listeners[event].splice(index, 1);
          }
        }
      },
      emit: function(event, data) {
        if (this._listeners[event]) {
          this._listeners[event].forEach(callback => callback(data));
        }
      }
    };
  }

  // ==================== Web Bluetooth API Polyfill ====================

  // Store active Bluetooth device for disconnect event handling
  window._activeBluetoothDevice = null;

  // Callback manager to handle multiple concurrent requests
  let callbackId = 0;
  window._bleCallbacks = {};

  class BluetoothDevice {
    constructor(id, name) {
      this.id = id;
      this.name = name;
      this.gatt = new BluetoothRemoteGATTServer(this);
      this._eventListeners = {};
      this.watchingAdvertisements = false;
    }

    addEventListener(eventName, callback) {
      if (!this._eventListeners[eventName]) {
        this._eventListeners[eventName] = [];
      }
      this._eventListeners[eventName].push(callback);
    }

    removeEventListener(eventName, callback) {
      if (this._eventListeners[eventName]) {
        const index = this._eventListeners[eventName].indexOf(callback);
        if (index > -1) {
          this._eventListeners[eventName].splice(index, 1);
        }
      }
    }

    _dispatchEvent(eventName, data) {
      if (this._eventListeners[eventName]) {
        const event = { target: this, type: eventName, ...data };
        this._eventListeners[eventName].forEach(callback => callback(event));
      }
    }

    async watchAdvertisements(options) {
      if (typeof AndroidLog !== 'undefined') {
        AndroidLog.d('WebAPI-Polyfill', 'watchAdvertisements called (experimental feature)');
      }
      this.watchingAdvertisements = true;
      return Promise.resolve();
    }

    async unwatchAdvertisements() {
      if (typeof AndroidLog !== 'undefined') {
        AndroidLog.d('WebAPI-Polyfill', 'unwatchAdvertisements called');
      }
      this.watchingAdvertisements = false;
      return Promise.resolve();
    }
  }

  class BluetoothRemoteGATTServer {
    constructor(device) {
      this.device = device;
      this.connected = false;
    }

    connect() {
      return new Promise((resolve, reject) => {
        if (typeof AndroidLog !== 'undefined') {
          AndroidLog.d('WebAPI-Polyfill', 'gatt.connect() called for device: ' + this.device.id);
        }

        window._bleConnectCallback = (result) => {
          if (typeof AndroidLog !== 'undefined') {
            AndroidLog.d('WebAPI-Polyfill', '_bleConnectCallback called with result: ' + JSON.stringify(result));
          }

          if (result.success) {
            this.connected = true;
            if (typeof AndroidLog !== 'undefined') {
              AndroidLog.d('WebAPI-Polyfill', 'gatt.connect successful');
            }
            resolve(this);
          } else {
            if (typeof AndroidLog !== 'undefined') {
              AndroidLog.e('WebAPI-Polyfill', 'gatt.connect failed: ' + result.error);
            }
            reject(new Error(result.error || 'Connection failed'));
          }
        };

        AndroidWebBluetooth.connectGatt(this.device.id, '_bleConnectCallback');
      });
    }

    disconnect() {
      AndroidWebBluetooth.disconnect();
      this.connected = false;
    }

    getPrimaryService(serviceUuid) {
      if (!this.connected) {
        return Promise.reject(new Error('GATT Server is not connected'));
      }
      return Promise.resolve(new BluetoothRemoteGATTService(this, serviceUuid));
    }
  }

  class BluetoothRemoteGATTService {
    constructor(server, uuid) {
      this.device = server.device;
      this.uuid = uuid;
    }

    getCharacteristic(characteristicUuid) {
      return Promise.resolve(new BluetoothRemoteGATTCharacteristic(this, characteristicUuid));
    }
  }

  class BluetoothRemoteGATTCharacteristic {
    constructor(service, uuid) {
      this.service = service;
      this.uuid = uuid;
      this._notificationCallbacks = [];
    }

    writeValueWithResponse(data) {
      return new Promise((resolve, reject) => {
        let hexData = this._arrayBufferToHex(data);

        const cbId = `bleWrite_${++callbackId}`;
        window._bleCallbacks[cbId] = (success) => {
          delete window._bleCallbacks[cbId];
          if (success) {
            resolve();
          } else {
            reject(new Error('Write failed'));
          }
        };

        AndroidWebBluetooth.writeCharacteristic(
          this.service.uuid,
          this.uuid,
          hexData,
          cbId
        );
      });
    }

    startNotifications() {
      return new Promise((resolve, reject) => {
        const cbId = `bleNotify_${++callbackId}`;
        window._bleCallbacks[cbId] = (success) => {
          delete window._bleCallbacks[cbId];
          if (success) {
            resolve(this);
          } else {
            reject(new Error('Failed to start notifications'));
          }
        };

        if (!window._bleNotificationHandlers) {
          window._bleNotificationHandlers = {};
        }

        window._bleNotificationHandlers[this.uuid] = (hexData) => {
          const dataView = this._hexToDataView(hexData);
          const event = {
            target: { value: dataView }
          };
          this._notificationCallbacks.forEach(callback => callback(event));
        };

        AndroidWebBluetooth.startNotifications(
          this.service.uuid,
          this.uuid,
          cbId
        );
      });
    }

    stopNotifications() {
      this._notificationCallbacks = [];
      return Promise.resolve();
    }

    addEventListener(eventName, callback) {
      if (eventName === 'characteristicvaluechanged') {
        this._notificationCallbacks.push(callback);
      }
    }

    removeEventListener(eventName, callback) {
      if (eventName === 'characteristicvaluechanged') {
        const index = this._notificationCallbacks.indexOf(callback);
        if (index > -1) {
          this._notificationCallbacks.splice(index, 1);
        }
      }
    }

    _arrayBufferToHex(buffer) {
      const bytes = new Uint8Array(buffer);
      return Array.from(bytes)
        .map(b => b.toString(16).padStart(2, '0'))
        .join('')
        .toUpperCase();
    }

    _hexToDataView(hexString) {
      const bytes = [];
      for (let i = 0; i < hexString.length; i += 2) {
        bytes.push(parseInt(hexString.substr(i, 2), 16));
      }
      const arrayBuffer = new Uint8Array(bytes).buffer;
      return new DataView(arrayBuffer);
    }
  }

  // Override navigator.bluetooth
  if (!navigator.bluetooth) {
    navigator.bluetooth = {};
  }

  navigator.bluetooth.requestDevice = function(options) {
    return new Promise((resolve, reject) => {
      const filtersJson = JSON.stringify({
        services: options.filters && options.filters[0] && options.filters[0].services
          ? options.filters[0].services
          : []
      });

      if (typeof AndroidLog !== 'undefined') {
        AndroidLog.d('WebAPI-Polyfill', 'requestDevice called with filters: ' + filtersJson);
      }

      window._bleRequestDeviceCallback = (result) => {
        if (typeof AndroidLog !== 'undefined') {
          AndroidLog.d('WebAPI-Polyfill', 'requestDevice callback: ' + JSON.stringify(result));
        }

        if (result.success) {
          const deviceData = result.data;
          const device = new BluetoothDevice(deviceData.id, deviceData.name);
          window._activeBluetoothDevice = device;

          if (typeof AndroidLog !== 'undefined') {
            AndroidLog.d('WebAPI-Polyfill', 'BluetoothDevice created: ' + device.id);
          }

          resolve(device);
        } else {
          if (typeof AndroidLog !== 'undefined') {
            AndroidLog.e('WebAPI-Polyfill', 'requestDevice failed: ' + result.error);
          }
          reject(new Error(result.error || 'Device selection failed'));
        }
      };

      AndroidWebBluetooth.requestDevice(filtersJson, '_bleRequestDeviceCallback');
    });
  };

  navigator.bluetooth.getDevices = function() {
    return new Promise((resolve, reject) => {
      if (typeof AndroidLog !== 'undefined') {
        AndroidLog.d('WebAPI-Polyfill', 'getDevices called');
      }

      window._bleGetDevicesCallback = (result) => {
        if (typeof AndroidLog !== 'undefined') {
          AndroidLog.d('WebAPI-Polyfill', 'getDevices callback: ' + JSON.stringify(result));
        }

        if (result.success && result.devices) {
          const devices = result.devices.map(deviceData =>
            new BluetoothDevice(deviceData.id, deviceData.name)
          );
          resolve(devices);
        } else {
          resolve([]);
        }
      };

      AndroidWebBluetooth.getPairedDevices('_bleGetDevicesCallback');
    });
  };

  // ==================== Web HID API Polyfill ====================

  class HIDDevice {
    constructor(deviceInfo) {
      this.vendorId = deviceInfo.vendorId;
      this.productId = deviceInfo.productId;
      this.productName = deviceInfo.productName;
      this._deviceName = deviceInfo.name;
      this.opened = deviceInfo.opened || false;
      this.collections = [
        {
          outputReports: [
            {
              items: [
                { reportCount: 64 } // Default report size
              ]
            }
          ]
        }
      ];
      this._inputReportCallbacks = [];
    }

    open() {
      return new Promise((resolve, reject) => {
        window._hidOpenCallback = (result) => {
          if (result.success) {
            this.opened = true;

            window._hidInputReportHandler = (hexData) => {
              const dataView = this._hexToDataView(hexData);
              const event = {
                data: dataView,
                device: this,
                reportId: 0
              };
              this._inputReportCallbacks.forEach(callback => callback(event));
            };

            resolve();
          } else {
            reject(new Error(result.error || 'Failed to open device'));
          }
        };

        AndroidWebHID.openDevice(this._deviceName, '_hidOpenCallback');
      });
    }

    close() {
      if (typeof AndroidLog !== 'undefined') {
        AndroidLog.w('WebAPI-Polyfill', 'HID device.close() called');
      }
      this.opened = false;
      this._inputReportCallbacks = [];
      AndroidWebHID.closeDevice();
      return Promise.resolve();
    }

    sendReport(reportId, data) {
      if (!this.opened) {
        return Promise.reject(new Error('Device not opened'));
      }

      return new Promise((resolve, reject) => {
        const hexData = this._arrayBufferToHex(data);

        window._hidSendReportCallback = (result) => {
          if (result.success) {
            resolve();
          } else {
            reject(new Error(result.error || 'Send report failed'));
          }
        };

        AndroidWebHID.sendReport(reportId, hexData, '_hidSendReportCallback');
      });
    }

    addEventListener(eventName, callback) {
      if (eventName === 'inputreport') {
        this._inputReportCallbacks.push(callback);
      }
    }

    removeEventListener(eventName, callback) {
      if (eventName === 'inputreport') {
        const index = this._inputReportCallbacks.indexOf(callback);
        if (index > -1) {
          this._inputReportCallbacks.splice(index, 1);
        }
      }
    }

    _arrayBufferToHex(buffer) {
      const bytes = new Uint8Array(buffer);
      return Array.from(bytes)
        .map(b => b.toString(16).padStart(2, '0'))
        .join('')
        .toUpperCase();
    }

    _hexToDataView(hexString) {
      const bytes = [];
      for (let i = 0; i < hexString.length; i += 2) {
        bytes.push(parseInt(hexString.substr(i, 2), 16));
      }
      const arrayBuffer = new Uint8Array(bytes).buffer;
      return new DataView(arrayBuffer);
    }
  }

  // Override navigator.hid
  if (!navigator.hid) {
    navigator.hid = {};
  }

  navigator.hid.requestDevice = function(options) {
    return new Promise((resolve, reject) => {
      const filtersJson = JSON.stringify({
        vendorId: options.filters && options.filters[0] && options.filters[0].vendorId
          ? options.filters[0].vendorId
          : 0x0801 // Default vendor ID
      });

      window._hidRequestDeviceCallback = (result) => {
        if (result.success && result.devices && result.devices.length > 0) {
          const devices = result.devices.map(d => new HIDDevice(d));
          resolve(devices);
        } else {
          reject(new Error(result.error || 'No HID device selected'));
        }
      };

      AndroidWebHID.requestDevice(filtersJson, '_hidRequestDeviceCallback');
    });
  };

  navigator.hid.getDevices = function() {
    return new Promise((resolve, reject) => {
      window._hidGetDevicesCallback = (result) => {
        if (result.success) {
          const devices = result.devices.map(d => new HIDDevice(d));
          resolve(devices);
        } else {
          reject(new Error(result.error || 'Failed to get devices'));
        }
      };

      AndroidWebHID.getDevices('_hidGetDevicesCallback');
    });
  };

  // HID events (connect/disconnect)
  const hidEventListeners = {
    connect: [],
    disconnect: []
  };

  navigator.hid.addEventListener = function(eventName, callback) {
    if (hidEventListeners[eventName]) {
      hidEventListeners[eventName].push(callback);
    }
  };

  navigator.hid.removeEventListener = function(eventName, callback) {
    if (hidEventListeners[eventName]) {
      const index = hidEventListeners[eventName].indexOf(callback);
      if (index > -1) {
        hidEventListeners[eventName].splice(index, 1);
      }
    }
  };

  // Listen for device events from Android via EventEmitter
  window.EventEmitter.on('OnDeviceConnect', (data) => {
    hidEventListeners.connect.forEach(cb => cb({ device: data.device }));
  });

  window.EventEmitter.on('OnDeviceDisconnect', (data) => {
    hidEventListeners.disconnect.forEach(cb => cb({ device: data.device }));
  });

  // Listen for Bluetooth device disconnection events
  window.EventEmitter.on('OnDeviceClose', (data) => {
    if (window._activeBluetoothDevice && data.device && data.device.id) {
      if (window._activeBluetoothDevice.id === data.device.id) {
        window._activeBluetoothDevice._dispatchEvent('gattserverdisconnected', {});
        if (typeof AndroidLog !== 'undefined') {
          AndroidLog.d('WebAPI-Polyfill', 'BLE device disconnected: ' + data.device.id);
        }
      }
    }
  });

  // Export for module systems if available
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      BluetoothDevice,
      BluetoothRemoteGATTServer,
      BluetoothRemoteGATTService,
      BluetoothRemoteGATTCharacteristic,
      HIDDevice
    };
  }

})();
