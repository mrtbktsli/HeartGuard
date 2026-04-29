package com.heartguard;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private EditText etParentPhone, etUpperLimit, etLowerLimit, etChildName;
    private TextView tvStatus, tvHeartRate;
    private Switch swService;
    private SharedPreferences prefs;
    private static final int PERM_REQUEST = 100;

    private final Map<String, String> foundDevices = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("HeartGuard", MODE_PRIVATE);
        etParentPhone = findViewById(R.id.etParentPhone);
        etUpperLimit  = findViewById(R.id.etUpperLimit);
        etLowerLimit  = findViewById(R.id.etLowerLimit);
        etChildName   = findViewById(R.id.etChildName);
        tvStatus      = findViewById(R.id.tvStatus);
        tvHeartRate   = findViewById(R.id.tvHeartRate);
        swService     = findViewById(R.id.swService);

        loadSettings();
        requestAllPermissions();
        requestBatteryOptimizationExemption();

        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnTestSms).setOnClickListener(v -> testSms());
        findViewById(R.id.btnSelectDevice).setOnClickListener(v -> startDeviceScan());

        swService.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) startMonitoring();
            else stopMonitoring();
        });

        updateSelectedDeviceLabel();
    }

    private void updateSelectedDeviceLabel() {
        String mac = prefs.getString("selectedDeviceMac", "");
        String name = prefs.getString("selectedDeviceName", "");
        TextView tv = findViewById(R.id.tvSelectedDevice);
        if (tv == null) return;
        if (!mac.isEmpty() && !name.isEmpty()) {
            tv.setText("Secili cihaz: " + name);
        } else {
            tv.setText("Cihaz secilmedi - ilk bulunan cihaza baglanir");
        }
    }

    private void startDeviceScan() {
        foundDevices.clear();
        tvStatus.setText("Cihazlar taranıyor (10 sn)...");

        Button btn = findViewById(R.id.btnSelectDevice);
        if (btn != null) btn.setEnabled(false);

        HeartMonitorService.setDeviceScanCallback(new HeartMonitorService.DeviceScanCallback() {
            @Override
            public void onDeviceFound(String name, String address) {
                foundDevices.put(address, name);
                tvStatus.setText("Bulunan: " + foundDevices.size() + " cihaz...");
            }

            @Override
            public void onScanFinished() {
                Button b = findViewById(R.id.btnSelectDevice);
                if (b != null) b.setEnabled(true);
                if (foundDevices.isEmpty()) {
                    tvStatus.setText("Cihaz bulunamadi. Mi Band'i takin ve tekrar deneyin.");
                } else {
                    showDeviceSelectionDialog();
                }
            }
        });

        Intent i = new Intent(this, HeartMonitorService.class);
        i.setAction("SCAN_DEVICES");
        ContextCompat.startForegroundService(this, i);
    }

    private void showDeviceSelectionDialog() {
        String[] names = new String[foundDevices.size() + 1];
        String[] macs = new String[foundDevices.size() + 1];

        int idx = 0;
        for (Map.Entry<String, String> entry : foundDevices.entrySet()) {
            macs[idx] = entry.getKey();
            names[idx] = entry.getValue() + "\n" + entry.getKey();
            idx++;
        }
        names[idx] = "Otomatik (ilk bulunan)";
        macs[idx] = "";

        new AlertDialog.Builder(this)
            .setTitle("Cihaz Secin")
            .setItems(names, (dialog, which) -> {
                String selectedMac = macs[which];
                String selectedName = which < foundDevices.size()
                    ? foundDevices.get(selectedMac) : "Otomatik";

                prefs.edit()
                    .putString("selectedDeviceMac", selectedMac)
                    .putString("selectedDeviceName", selectedName)
                    .apply();

                updateSelectedDeviceLabel();
                tvStatus.setText("Cihaz secildi: " + selectedName);
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
            Toast.makeText(this, "SMS izni yok!", Toast.LENGTH_LONG).show();
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
            if (sm == null) {
                Toast.makeText(this, "SmsManager alinamadi!", Toast.LENGTH_LONG).show();
                return;
            }
            String txt = "HEARTGUARD\nTEST MESAJI\nUygulama calisiyor";
            ArrayList<String> parts = sm.divideMessage(txt);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            tvStatus.setText("Test SMS gonderildi -> " + phone);
            Toast.makeText(this, "Test SMS gonderildi!", Toast.LENGTH_LONG).show();
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
                    .setMessage("HeartGuard'in arka planda surekli calisabilmesi icin pil optimizasyonunu kapatmaniz gerekiyor.")
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
    }
}
