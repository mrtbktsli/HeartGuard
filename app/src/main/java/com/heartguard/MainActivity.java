package com.heartguard;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private EditText etParentPhone, etUpperLimit, etLowerLimit, etChildName;
    private TextView tvStatus, tvHeartRate, tvSelectedDevice;
    private Switch swService;
    private Button btnSelectDevice;
    private SharedPreferences prefs;
    private static final int PERM_REQUEST = 100;
    private final Map<String, String> foundDevices = new LinkedHashMap<>();
    private BluetoothLeScanner bleScanner;
    private Handler handler = new Handler(Looper.getMainLooper());
    private static final UUID HR_SERVICE = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("HeartGuard", MODE_PRIVATE);
        etParentPhone    = (EditText) findViewById(R.id.etParentPhone);
        etUpperLimit     = (EditText) findViewById(R.id.etUpperLimit);
        etLowerLimit     = (EditText) findViewById(R.id.etLowerLimit);
        etChildName      = (EditText) findViewById(R.id.etChildName);
        tvStatus         = (TextView) findViewById(R.id.tvStatus);
        tvHeartRate      = (TextView) findViewById(R.id.tvHeartRate);
        tvSelectedDevice = (TextView) findViewById(R.id.tvSelectedDevice);
        swService        = (Switch) findViewById(R.id.swService);
        btnSelectDevice  = (Button) findViewById(R.id.btnSelectDevice);

        loadSettings();
        updateSelectedDeviceLabel();
        requestAllPermissions();
        requestBatteryOptimizationExemption();

        ((Button) findViewById(R.id.btnSave)).setOnClickListener(v -> saveSettings());
        ((Button) findViewById(R.id.btnTestSms)).setOnClickListener(v -> testSms());
        btnSelectDevice.setOnClickListener(v -> startDeviceScan());
        swService.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) startMonitoring();
            else stopMonitoring();
        });
    }

    private void updateSelectedDeviceLabel() {
        String mac = prefs.getString("selectedDeviceMac", "");
        String name = prefs.getString("selectedDeviceName", "");
        if (!mac.isEmpty() && !name.isEmpty()) {
            tvSelectedDevice.setText("Secili: " + name);
        } else {
            tvSelectedDevice.setText("Cihaz secilmedi");
        }
    }

    // Bluetooth taraması direkt Activity'den yapılıyor
    private void startDeviceScan() {
        foundDevices.clear();
        tvStatus.setText("Taranıyor (10 sn)...");
        btnSelectDevice.setEnabled(false);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            tvStatus.setText("Bluetooth desteklenmiyor");
            btnSelectDevice.setEnabled(true);
            return;
        }
        BluetoothAdapter ba = bm.getAdapter();
        if (ba == null || !ba.isEnabled()) {
            tvStatus.setText("Bluetooth kapali! Acin ve tekrar deneyin.");
            btnSelectDevice.setEnabled(true);
            return;
        }

        bleScanner = ba.getBluetoothLeScanner();
        if (bleScanner == null) {
            tvStatus.setText("BLE tarayici alinamadi");
            btnSelectDevice.setEnabled(true);
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(new ParcelUuid(HR_SERVICE)).build();
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);

        // 10 saniye sonra durdur
        handler.postDelayed(() -> {
            if (bleScanner != null) {
                try { bleScanner.stopScan(scanCallback); } catch (Exception e) {}
                bleScanner = null;
            }
            btnSelectDevice.setEnabled(true);
            if (foundDevices.isEmpty()) {
                tvStatus.setText("Cihaz bulunamadi. Mi Band'i takin ve tekrar deneyin.");
            } else {
                showDeviceSelectionDialog();
            }
        }, 10000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String address = result.getDevice().getAddress();
            String name = result.getDevice().getName();
            if (name == null) name = "Bilinmeyen (" + address + ")";
            if (!foundDevices.containsKey(address)) {
                foundDevices.put(address, name);
                tvStatus.setText("Bulunan: " + foundDevices.size() + " cihaz");
            }
        }
    };

    private void showDeviceSelectionDialog() {
        int size = foundDevices.size();
        String[] names = new String[size + 1];
        String[] macs = new String[size + 1];

        int idx = 0;
        for (Map.Entry<String, String> entry : foundDevices.entrySet()) {
            macs[idx] = entry.getKey();
            names[idx] = entry.getValue();
            idx++;
        }
        names[size] = "Otomatik (ilk bulunan)";
        macs[size] = "";

        new AlertDialog.Builder(this)
            .setTitle("Cihaz Secin")
            .setItems(names, (dialog, which) -> {
                String mac = macs[which];
                String name = names[which];
                prefs.edit()
                    .putString("selectedDeviceMac", mac)
                    .putString("selectedDeviceName", name)
                    .apply();
                updateSelectedDeviceLabel();
                tvStatus.setText("Secildi: " + name);
            })
            .show();
    }

    private void loadSettings() {
        etParentPhone.setText(prefs.getString("parentPhone", ""));
        etUpperLimit.setText(String.valueOf(prefs.getInt("upperLimit", 160)));
        etLowerLimit.setText(String.valueOf(prefs.getInt("lowerLimit", 40)));
        etChildName.setText(prefs.getString("childName", ""));
        swService.setChecked(prefs.getBoolean("serviceRunning", false));
    }

    private void saveSettings() {
        String phone = etParentPhone.getText().toString().trim();
        String upper = etUpperLimit.getText().toString().trim();
        String lower = etLowerLimit.getText().toString().trim();
        String name  = etChildName.getText().toString().trim();
        if (phone.isEmpty() || upper.isEmpty() || lower.isEmpty()) {
            Toast.makeText(this, "Tum alanlari doldurun!", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
            .putString("parentPhone", phone)
            .putInt("upperLimit", Integer.parseInt(upper))
            .putInt("lowerLimit", Integer.parseInt(lower))
            .putString("childName", name)
            .apply();
        Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show();
    }

    private void testSms() {
        saveSettings();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.SEND_SMS}, PERM_REQUEST);
            return;
        }
        String phone = etParentPhone.getText().toString().trim();
        if (phone.isEmpty()) {
            Toast.makeText(this, "Telefon numarasi giriniz!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!phone.startsWith("+") && !phone.startsWith("00")) {
            if (phone.startsWith("0")) {
                phone = "+9" + phone;
            } else {
                phone = "+90" + phone;
            }
        }
        try {
            SmsManager sm;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sm = getSystemService(SmsManager.class);
            } else {
                sm = SmsManager.getDefault();
            }
            if (sm == null) return;
            String txt = "HEARTGUARD\nTEST MESAJI\nUygulama calisiyor";
            ArrayList<String> parts = sm.divideMessage(txt);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            tvStatus.setText("Test SMS gonderildi");
        } catch (Exception e) {
            tvStatus.setText("SMS HATASI: " + e.getMessage());
        }
    }

    private void startMonitoring() {
        saveSettings();
        prefs.edit().putBoolean("serviceRunning", true).apply();
        Intent i = new Intent(this, HeartMonitorService.class);
        i.setAction("START");
        ContextCompat.startForegroundService(this, i);
        tvStatus.setText("Izleme aktif");
    }

    private void stopMonitoring() {
        prefs.edit().putBoolean("serviceRunning", false).apply();
        Intent i = new Intent(this, HeartMonitorService.class);
        i.setAction("STOP");
        startService(i);
        tvStatus.setText("Izleme durduruldu");
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Pil Optimizasyonu")
                    .setMessage("HeartGuard arka planda calismak icin pil optimizasyonunu kapatmaniz gerekiyor.")
                    .setPositiveButton("Evet, Kapat", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Hayir", null)
                    .show();
            }
        }
    }

    private void requestAllPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        String[] perms = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeartMonitorService.setUiCallback((bpm, status) ->
            runOnUiThread(() -> {
                if (bpm > 0) tvHeartRate.setText("Nabiz: " + bpm + " bpm");
                tvStatus.setText(status);
            })
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        HeartMonitorService.setUiCallback(null);
        if (bleScanner != null) {
            try { bleScanner.stopScan(scanCallback); } catch (Exception e) {}
            bleScanner = null;
        }
    }
}
