/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
*/

package com.vagell.kv4pht.radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * Owns a long-lived Bluetooth LE GATT connection to a single paired PTT button. Re-binds
 * automatically after disconnects (e.g. cheap buttons that doze when idle), debounces
 * incoming press/release notifications, and dispatches to a listener on the main thread.
 *
 * Thread model: all GATT calls and listener dispatches happen on the main looper. The
 * {@link android.bluetooth.BluetoothGattCallback} runs on a binder thread, so we marshal
 * back to the main looper before touching state.
 */
public class BlePttManager {

    private static final String TAG = BlePttManager.class.getSimpleName();
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Min interval between accepted state transitions, to filter out chattering buttons. */
    private static final long DEBOUNCE_MS = 50L;
    /** Initial reconnect backoff. Doubles up to {@link #MAX_RECONNECT_DELAY_MS}. */
    private static final long INITIAL_RECONNECT_DELAY_MS = 1_000L;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    public interface Listener {
        @MainThread default void onPress() {}
        @MainThread default void onRelease() {}
        @MainThread default void onStateChanged(@NonNull State state) {}
    }

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener = new Listener() {};

    @Nullable private BlePttBinding binding;
    @Nullable private BluetoothGatt gatt;
    @NonNull private State state = State.DISCONNECTED;
    private boolean lastReportedPressed = false;
    private long lastDispatchMs = 0L;
    private long reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
    @Nullable private Runnable pendingReconnect;
    private boolean closed = false;

    public BlePttManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener != null ? listener : new Listener() {};
    }

    @NonNull
    public State getState() {
        return state;
    }

    @Nullable
    public BlePttBinding getBinding() {
        return binding;
    }

    /**
     * Replaces the active binding. Pass {@code null} to clear and disconnect. Safe to call
     * from any thread.
     */
    public void setBinding(@Nullable BlePttBinding newBinding) {
        handler.post(() -> {
            if (closed) return;
            boolean same = (binding != null && newBinding != null
                    && binding.getMac().equals(newBinding.getMac())
                    && binding.getCharacteristicUuid().equals(newBinding.getCharacteristicUuid())
                    && binding.getServiceUuid().equals(newBinding.getServiceUuid())
                    && binding.getPressedValue() == newBinding.getPressedValue()
                    && binding.getReleasedValue() == newBinding.getReleasedValue());
            if (same) return;
            disconnectInternal();
            binding = newBinding;
            if (binding != null) {
                connect();
            } else {
                updateState(State.DISCONNECTED);
            }
        });
    }

    /** Permanently shut down the manager. After this, the instance can't be reused. */
    public void close() {
        handler.post(() -> {
            closed = true;
            cancelPendingReconnect();
            disconnectInternal();
            updateState(State.DISCONNECTED);
        });
    }

    // ---------- internals ----------

    @MainThread
    @SuppressLint("MissingPermission")
    private void connect() {
        if (closed || binding == null) return;
        if (!hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission, cannot connect to PTT button");
            updateState(State.ERROR);
            return;
        }
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter unavailable; will retry");
            scheduleReconnect();
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(binding.getMac());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Bad MAC in PTT binding: " + binding.getMac(), e);
            updateState(State.ERROR);
            return;
        }
        updateState(State.CONNECTING);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(appContext, /*autoConnect=*/true, gattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(appContext, /*autoConnect=*/true, gattCallback);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException connecting GATT", e);
            updateState(State.ERROR);
        }
    }

    @MainThread
    @SuppressLint("MissingPermission")
    private void disconnectInternal() {
        cancelPendingReconnect();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, "Error closing GATT", t);
            }
            gatt = null;
        }
        lastReportedPressed = false;
    }

    @MainThread
    private void scheduleReconnect() {
        if (closed || binding == null) return;
        cancelPendingReconnect();
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(MAX_RECONNECT_DELAY_MS, reconnectDelayMs * 2);
        pendingReconnect = () -> {
            pendingReconnect = null;
            if (closed || binding == null) return;
            // Tear down any half-open connection before retrying so we don't leak GATT clients.
            disconnectInternal();
            connect();
        };
        handler.postDelayed(pendingReconnect, delay);
    }

    @MainThread
    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            handler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    @MainThread
    private void updateState(@NonNull State newState) {
        if (newState == state) return;
        state = newState;
        try {
            listener.onStateChanged(newState);
        } catch (Throwable t) {
            Log.w(TAG, "Listener onStateChanged threw", t);
        }
    }

    @MainThread
    private void dispatchPressedFromNotify(boolean pressed) {
        long now = android.os.SystemClock.uptimeMillis();
        if (pressed == lastReportedPressed) {
            return;
        }
        if (now - lastDispatchMs < DEBOUNCE_MS) {
            // Coalesce chatter: drop transitions arriving inside the debounce window.
            return;
        }
        lastReportedPressed = pressed;
        lastDispatchMs = now;
        try {
            if (pressed) listener.onPress();
            else listener.onRelease();
        } catch (Throwable t) {
            Log.w(TAG, "Listener press/release threw", t);
        }
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    private BluetoothAdapter getAdapter() {
        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (closed) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "BLE PTT connected, discovering services");
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
                    try {
                        g.discoverServices();
                    } catch (SecurityException e) {
                        Log.e(TAG, "discoverServices threw", e);
                        updateState(State.ERROR);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "BLE PTT disconnected, status=" + status);
                    // If we were holding PTT, force release on disconnect to avoid runaway TX.
                    if (lastReportedPressed) {
                        lastReportedPressed = false;
                        try {
                            listener.onRelease();
                        } catch (Throwable t) {
                            Log.w(TAG, "onRelease (forced on disconnect) threw", t);
                        }
                    }
                    disconnectInternal();
                    updateState(State.DISCONNECTED);
                    scheduleReconnect();
                }
            });
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (closed || binding == null) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed: " + status);
                    updateState(State.ERROR);
                    return;
                }
                BluetoothGattService svc = g.getService(binding.getServiceUuid());
                if (svc == null) {
                    Log.w(TAG, "Bound service not found on device");
                    updateState(State.ERROR);
                    return;
                }
                BluetoothGattCharacteristic ch = svc.getCharacteristic(binding.getCharacteristicUuid());
                if (ch == null) {
                    Log.w(TAG, "Bound characteristic not found on device");
                    updateState(State.ERROR);
                    return;
                }
                if (!enableNotifications(g, ch)) {
                    Log.w(TAG, "Failed to enable notifications");
                    updateState(State.ERROR);
                    return;
                }
                updateState(State.CONNECTED);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] value = ch.getValue();
            if (value == null || value.length == 0) return;
            int firstByte = value[0] & 0xFF;
            handler.post(() -> {
                if (closed || binding == null) return;
                if (!ch.getUuid().equals(binding.getCharacteristicUuid())) return;
                dispatchPressedFromNotify(binding.isPressed(firstByte));
            });
        }
    };

    @SuppressLint("MissingPermission")
    private boolean enableNotifications(@NonNull BluetoothGatt g,
                                        @NonNull BluetoothGattCharacteristic ch) {
        try {
            if (!g.setCharacteristicNotification(ch, true)) return false;
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                // Some buttons signal via notify without exposing CCCD; that's fine.
                return true;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return g.writeDescriptor(cccd);
        } catch (SecurityException e) {
            return false;
        }
    }
}
