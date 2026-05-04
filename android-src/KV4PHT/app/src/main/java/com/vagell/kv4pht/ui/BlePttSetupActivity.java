/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
*/

package com.vagell.kv4pht.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.radio.BlePttBinding;
import com.vagell.kv4pht.radio.BlePttProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Three-tier setup UI for an external Bluetooth LE PTT button:
 *  - Tier 1: scan, automatically badge devices whose advertised service UUID matches one
 *    of {@link BlePttProfiles#PROFILES}; one tap pairs them.
 *  - Tier 2: auto-detect wizard. Pick any BLE device, the wizard enables notifications on
 *    every NOTIFY characteristic and waits for a press, then a release.
 *  - Tier 3: manual entry hidden behind an "Advanced" expander.
 */
public class BlePttSetupActivity extends AppCompatActivity {

    private static final String TAG = BlePttSetupActivity.class.getSimpleName();
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long WIZARD_PRESS_TIMEOUT_MS = 10_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService threadPoolExecutor = Executors.newSingleThreadExecutor();
    private MainViewModel viewModel;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private final Map<String, ScanRow> deviceRows = new LinkedHashMap<>();
    private DeviceListAdapter listAdapter;

    // Wizard state
    private @Nullable BluetoothGatt wizardGatt;
    private @Nullable BluetoothDevice wizardDevice;
    private final List<BluetoothGattCharacteristic> wizardCandidates = new ArrayList<>();
    private final Map<String, Integer> wizardFirstByteSeen = new HashMap<>();
    private @Nullable Runnable wizardTimeoutRunnable;
    private enum WizardPhase { IDLE, CONNECTING, WAITING_PRESS, WAITING_RELEASE, DONE }
    private WizardPhase wizardPhase = WizardPhase.IDLE;
    private @Nullable UUID wizardPressService;
    private @Nullable UUID wizardPressChar;
    private int wizardPressedValue = -1;
    private int wizardReleasedValue = -1;

    // Views
    private TextView statusView;
    private ListView listView;
    private View wizardPanel;
    private TextView wizardPrompt;
    private View advancedPanel;
    private EditText advServiceUuid;
    private EditText advCharUuid;
    private CheckBox advInvert;

    private final androidx.activity.result.ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    grants -> {
                        boolean ok = true;
                        for (Boolean v : grants.values()) {
                            if (Boolean.FALSE.equals(v)) { ok = false; break; }
                        }
                        if (ok) {
                            startScan();
                        } else {
                            Toast.makeText(this,
                                    "Bluetooth permissions are required to pair a PTT button.",
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_ptt_setup);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        statusView = findViewById(R.id.blePttStatus);
        listView = findViewById(R.id.blePttDeviceList);
        wizardPanel = findViewById(R.id.blePttWizardPanel);
        wizardPrompt = findViewById(R.id.blePttWizardPrompt);
        advancedPanel = findViewById(R.id.blePttAdvancedPanel);
        advServiceUuid = findViewById(R.id.blePttAdvService);
        advCharUuid = findViewById(R.id.blePttAdvCharacteristic);
        advInvert = findViewById(R.id.blePttAdvInvert);

        listAdapter = new DeviceListAdapter();
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onDeviceClicked(position));

        findViewById(R.id.blePttDoneButton).setOnClickListener(v -> finish());
        findViewById(R.id.blePttForgetButton).setOnClickListener(v -> forgetBinding());
        findViewById(R.id.blePttCancelWizardButton).setOnClickListener(v -> cancelWizard("Cancelled."));
        findViewById(R.id.blePttAdvancedToggle).setOnClickListener(v ->
                advancedPanel.setVisibility(advancedPanel.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        findViewById(R.id.blePttAdvSaveButton).setOnClickListener(v -> saveAdvancedBinding());
        findViewById(R.id.blePttRescanButton).setOnClickListener(v -> startScan());

        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null) {
            statusView.setText("This device has no Bluetooth.");
            return;
        }

        showCurrentBinding();
        ensurePermissionsThenScan();
    }

    private void ensurePermissionsThenScan() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Pre-12: location permission is required for BLE scan results.
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) startScan();
        else permissionLauncher.launch(missing.toArray(new String[0]));
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            statusView.setText("Turn on Bluetooth to scan for PTT buttons.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            statusView.setText("BLE scanner unavailable.");
            return;
        }
        if (scanning) return;
        deviceRows.clear();
        listAdapter.notifyDataSetChanged();
        statusView.setText("Scanning for nearby Bluetooth LE devices…");
        // Scan unfiltered: we want both compatible (Tier 1) and unknown (Tier 2) devices.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(/*filters=*/null, settings, scanCallback);
            scanning = true;
            // Stop scan after a while to save battery; user can rescan.
            handler.postDelayed(this::stopScan, 15_000L);
        } catch (SecurityException e) {
            statusView.setText("Permission denied for BLE scan.");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning || scanner == null) return;
        try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) {}
        scanning = false;
        if (deviceRows.isEmpty()) {
            statusView.setText("No devices found. Make sure the button is on, then tap Rescan.");
        } else {
            statusView.setText("Tap your button to pair, or pick \"My button isn't listed\".");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handler.post(() -> handleScanResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            handler.post(() -> { for (ScanResult r : results) handleScanResult(r); });
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> statusView.setText("Scan failed (code " + errorCode + ")."));
        }
    };

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult r) {
        if (r == null || r.getDevice() == null) return;
        BluetoothDevice d = r.getDevice();
        String mac = d.getAddress();
        if (mac == null) return;

        String name;
        try {
            name = d.getName();
        } catch (SecurityException e) {
            name = null;
        }
        if (TextUtils.isEmpty(name) && r.getScanRecord() != null) {
            name = r.getScanRecord().getDeviceName();
        }
        if (TextUtils.isEmpty(name)) name = "(unnamed)";

        // Match against built-in profiles by advertised service UUIDs.
        BlePttProfiles.Profile match = null;
        if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) {
            for (ParcelUuid pu : r.getScanRecord().getServiceUuids()) {
                BlePttProfiles.Profile m = BlePttProfiles.matchByServiceUuid(pu.getUuid()).orElse(null);
                if (m != null) { match = m; break; }
            }
        }

        ScanRow row = deviceRows.get(mac);
        if (row == null) {
            row = new ScanRow(d, name, r.getRssi(), match);
            deviceRows.put(mac, row);
        } else {
            row.name = name;
            row.rssi = r.getRssi();
            if (row.compatible == null) row.compatible = match;
        }
        listAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void onDeviceClicked(int position) {
        List<ScanRow> rows = new ArrayList<>(deviceRows.values());
        if (position < 0 || position >= rows.size()) return;
        ScanRow row = rows.get(position);
        stopScan();

        if (row.compatible != null) {
            // Tier 1: instant pair.
            BlePttBinding binding = new BlePttBinding(
                    row.device.getAddress(),
                    row.name,
                    row.compatible.serviceUuid,
                    row.compatible.characteristicUuid,
                    row.compatible.pressedValue,
                    row.compatible.releasedValue);
            persistBindingAndFinish(binding);
            return;
        }

        // Tier 2: confirm and start the auto-detect wizard.
        new AlertDialog.Builder(this)
                .setTitle("Set up button")
                .setMessage("This device isn't a known PTT button. Set it up by pressing it on cue?")
                .setPositiveButton("Set up", (d, w) -> startWizard(row.device, row.name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- Tier 2 wizard ----------

    @SuppressLint("MissingPermission")
    private void startWizard(@NonNull BluetoothDevice device, @NonNull String name) {
        cancelWizardConnections();
        wizardDevice = device;
        wizardPhase = WizardPhase.CONNECTING;
        wizardPanel.setVisibility(View.VISIBLE);
        wizardPrompt.setText("Connecting to " + name + "…");
        try {
            wizardGatt = device.connectGatt(this, /*autoConnect=*/false, wizardCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            cancelWizard("Permission denied.");
        }
    }

    private void armWizardTimeout(@NonNull String message) {
        cancelWizardTimeout();
        wizardTimeoutRunnable = () -> cancelWizard(message);
        handler.postDelayed(wizardTimeoutRunnable, WIZARD_PRESS_TIMEOUT_MS);
    }

    private void cancelWizardTimeout() {
        if (wizardTimeoutRunnable != null) {
            handler.removeCallbacks(wizardTimeoutRunnable);
            wizardTimeoutRunnable = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void cancelWizardConnections() {
        cancelWizardTimeout();
        if (wizardGatt != null) {
            try { wizardGatt.disconnect(); wizardGatt.close(); } catch (Exception ignored) {}
            wizardGatt = null;
        }
        wizardCandidates.clear();
        wizardFirstByteSeen.clear();
        wizardPressService = null;
        wizardPressChar = null;
        wizardPressedValue = -1;
        wizardReleasedValue = -1;
    }

    private void cancelWizard(@Nullable String message) {
        wizardPhase = WizardPhase.IDLE;
        cancelWizardConnections();
        wizardPanel.setVisibility(View.GONE);
        if (message != null) statusView.setText(message);
    }

    private final BluetoothGattCallback wizardCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.discoverServices(); } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                    && wizardPhase != WizardPhase.DONE) {
                handler.post(() -> cancelWizard("Disconnected before button was identified."));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    cancelWizard("Service discovery failed.");
                    return;
                }
                wizardCandidates.clear();
                for (BluetoothGattService svc : g.getServices()) {
                    if (BlePttProfiles.IGNORED_SERVICES.contains(svc.getUuid())) continue;
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        if ((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            wizardCandidates.add(ch);
                        }
                    }
                }
                if (wizardCandidates.isEmpty()) {
                    cancelWizard("No notify characteristics found on this device.");
                    return;
                }
                // Enable notifications on every candidate so the first one to fire wins.
                for (BluetoothGattCharacteristic ch : wizardCandidates) {
                    try {
                        g.setCharacteristicNotification(ch, true);
                        BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
                        if (cccd != null) {
                            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(cccd);
                        }
                    } catch (SecurityException ignored) {}
                }
                wizardPhase = WizardPhase.WAITING_PRESS;
                wizardPrompt.setText("Press and hold your PTT button.");
                armWizardTimeout("No signal detected. Make sure the button is on and try again.");
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] value = ch.getValue();
            if (value == null || value.length == 0) return;
            int firstByte = value[0] & 0xFF;
            handler.post(() -> handleWizardNotify(ch, firstByte));
        }
    };

    private void handleWizardNotify(@NonNull BluetoothGattCharacteristic ch, int firstByte) {
        if (wizardPhase == WizardPhase.WAITING_PRESS) {
            // First notify wins as the press characteristic.
            cancelWizardTimeout();
            wizardPressService = ch.getService().getUuid();
            wizardPressChar = ch.getUuid();
            wizardPressedValue = firstByte;
            wizardFirstByteSeen.put(ch.getUuid().toString(), firstByte);
            wizardPhase = WizardPhase.WAITING_RELEASE;
            wizardPrompt.setText("Now release the button.");
            armWizardTimeout("No release detected. Try again.");
        } else if (wizardPhase == WizardPhase.WAITING_RELEASE) {
            if (wizardPressChar == null || !ch.getUuid().equals(wizardPressChar)) return;
            if (firstByte == wizardPressedValue) {
                // Same byte (could be a repeated press notify); keep waiting.
                return;
            }
            cancelWizardTimeout();
            wizardReleasedValue = firstByte;
            wizardPhase = WizardPhase.DONE;
            String name;
            try {
                name = wizardDevice != null ? wizardDevice.getName() : "BLE PTT";
            } catch (SecurityException e) {
                name = "BLE PTT";
            }
            if (TextUtils.isEmpty(name)) name = "BLE PTT";
            BlePttBinding binding = new BlePttBinding(
                    wizardDevice.getAddress(),
                    name,
                    wizardPressService,
                    wizardPressChar,
                    wizardPressedValue,
                    wizardReleasedValue);
            cancelWizardConnections();
            wizardPanel.setVisibility(View.GONE);
            persistBindingAndFinish(binding);
        }
    }

    // ---------- Tier 3 advanced ----------

    @SuppressLint("MissingPermission")
    private void saveAdvancedBinding() {
        // The user must have selected a device from the scan list before opening Advanced; we
        // pick whichever one is currently highlighted, falling back to the first scan result.
        ScanRow chosen = deviceRows.values().stream().findFirst().orElse(null);
        if (chosen == null) {
            Toast.makeText(this, "Pick a device from the scan list first.", Toast.LENGTH_LONG).show();
            return;
        }
        UUID svc;
        UUID ch;
        try {
            svc = UUID.fromString(advServiceUuid.getText().toString().trim());
            ch = UUID.fromString(advCharUuid.getText().toString().trim());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Service / characteristic UUID is invalid.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean inverted = advInvert.isChecked();
        int pressed = inverted ? 0x00 : 0x01;
        int released = inverted ? 0x01 : 0x00;
        BlePttBinding binding = new BlePttBinding(
                chosen.device.getAddress(), chosen.name, svc, ch, pressed, released);
        persistBindingAndFinish(binding);
    }

    // ---------- persistence ----------

    private void persistBindingAndFinish(@NonNull BlePttBinding binding) {
        threadPoolExecutor.execute(() -> {
            viewModel.getAppDb().saveAppSetting(
                    AppSetting.SETTING_BLE_PTT_BINDING, binding.serialize());
            runOnUiThread(() -> {
                Toast.makeText(this, "Paired: " + binding.getName(), Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_OK);
                finish();
            });
        });
    }

    private void forgetBinding() {
        threadPoolExecutor.execute(() -> {
            viewModel.getAppDb().saveAppSetting(AppSetting.SETTING_BLE_PTT_BINDING, "");
            runOnUiThread(() -> {
                Toast.makeText(this, "Button forgotten.", Toast.LENGTH_SHORT).show();
                showCurrentBinding();
                setResult(Activity.RESULT_OK);
            });
        });
    }

    private void showCurrentBinding() {
        threadPoolExecutor.execute(() -> {
            AppSetting s = viewModel.getAppDb().appSettingDao().getByName(
                    AppSetting.SETTING_BLE_PTT_BINDING);
            BlePttBinding b = s != null ? BlePttBinding.deserialize(s.getValue()) : null;
            runOnUiThread(() -> {
                TextView paired = findViewById(R.id.blePttCurrentPairing);
                Button forget = findViewById(R.id.blePttForgetButton);
                if (b != null) {
                    paired.setText("Paired: " + b.getName() + " (" + b.getMac() + ")");
                    forget.setVisibility(View.VISIBLE);
                } else {
                    paired.setText("No PTT button paired.");
                    forget.setVisibility(View.GONE);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        cancelWizardConnections();
        threadPoolExecutor.shutdown();
    }

    // ---------- adapter ----------

    private static final class ScanRow {
        final BluetoothDevice device;
        String name;
        int rssi;
        @Nullable BlePttProfiles.Profile compatible;

        ScanRow(BluetoothDevice device, String name, int rssi,
                @Nullable BlePttProfiles.Profile compatible) {
            this.device = device;
            this.name = name;
            this.rssi = rssi;
            this.compatible = compatible;
        }
    }

    private final class DeviceListAdapter extends ArrayAdapter<ScanRow> {
        DeviceListAdapter() {
            super(BlePttSetupActivity.this, R.layout.ble_device_row);
        }

        @Override public int getCount() { return deviceRows.size(); }

        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext())
                            .inflate(R.layout.ble_device_row, parent, false);
            List<ScanRow> rows = new ArrayList<>(deviceRows.values());
            ScanRow row = rows.get(position);
            ((TextView) v.findViewById(R.id.bleRowName)).setText(row.name);
            ((TextView) v.findViewById(R.id.bleRowMac))
                    .setText(row.device.getAddress() + "   " + row.rssi + " dBm");
            TextView badge = v.findViewById(R.id.bleRowBadge);
            if (row.compatible != null) {
                badge.setVisibility(View.VISIBLE);
                badge.setText("Compatible · " + row.compatible.displayName);
            } else {
                badge.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
