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
    private TextView tvStatus, tvHeartRate, tvSelectedDevice;
    private Switch swService;
    private Button btnSelectDevice;
    private SharedPreferences prefs;
    private static final int PERM_REQUEST = 100;

    // Taranan cihazlar: MAC -> İsim
    private final Map<String, String> foundDevices = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("HeartGuard", MODE_PRIVATE);
        etParentPhone   = findViewById(R.id.etParentPhone);
        etUpperLimit    = findViewById(R.id.etUpperLimit);
        etLowerLimit    = findViewById(R.id.etLowerLimit);
        etChildName     = findViewById(R.id.etChildName);
        tvStatus        = findViewById(R.id.tvStatus);
        tvHeartRate     = findViewById(R.id.tvHeartRate);
        tvSelectedDevice = findViewById(R.id.tvSelectedDevice);
        swService       = findViewById(R.id.swService);
        btnSelectDevice = findViewById(R.id.btnSelectDevice);

        loadSettings();
        updateSelectedDeviceLabel();
        requestAllPermissions();
        requestBatteryOptimizationExemption();

        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnTestSms).setOnClickListener(v -> testSms());
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
            tvSelectedDevice.setText("Seçili cihaz: " + name);
        } else {
            tvSelectedDevice.setText("Cihaz seçilmedi — ilk bulunan cihaza bağlanır");
        }
    }

    // Cihaz tarama başlat
    private void startDeviceScan() {
        foundDevices.clear();
        tvStatus.setText("Cihazlar taranıyor (10 sn)...");
        btnSelectDevice.setEnabled(false);

        HeartMonitorService.setDeviceScanCallback(new HeartMonitorService.DeviceScanCallback() {
            @Override
            public void onDeviceFound(String name, String address) {
                foundDevices.put(address, name);
                tvStatus.setText("Bulunan: " + foundDevices.size() + " cihaz...");
            }

            @Override
            public void onScanFinished() {
                btnSelectDevice.setEnabled(true);
                if (foundDevices.isEmpty()) {
                    tvStatus.setText("Cihaz bulunamadı. Mi Band'i takın ve tekrar deneyin.");
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
        // Son seçenek: otomatik
        names[idx] = "Otomatik (ilk bulunan)";
        macs[idx] = "";

        new AlertDialog.Builder(this)
            .setTitle("Cihaz Seçin")
            .setItems(names, (dialog, which) -> {
                String selectedMac = macs[which];
                String selectedName = which < foundDevices.size()
                    ? foundDevices.get(selectedMac)
                    : "Otomatik";

                prefs.edit()
                    .putString("selectedDeviceMac", selectedMac)
                    .putString("selectedDeviceName", selectedName)
                    .apply();

                updateSelectedDeviceLabel();
                tvStatus.setText("Cihaz seçildi: " + selectedName);
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
            Toast.makeText(this, "Tüm alanları doldurun!", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
            .putString("parentPhone", phone)
            .putInt("upperLimit", Integer.parseInt(upper))
            .putInt("lowerLimit", Integer.parseInt(lower))
            .putString("childName", name)
            .apply();
        Toast.makeText(this, "Kaydedildi ✓", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Telefon numarası giriniz!", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "SmsManager alınamadı!", Toast.LENGTH_LONG).show();
                return;
            }
            String txt = "HEARTGUARD\nTEST MESAJI\nUygulama calisiyor";
            ArrayList<String> parts = sm.divideMessage(txt);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            tvStatus.setText("Test SMS gönderildi -> " + phone);
            Toast.makeText(this, "Test SMS gönderildi!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            tvStatus.setText("SMS HATASI: " + e.getMessage());
            Toast.makeText(this, "SMS HATASI: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startMonitoring() {
        saveSettings();
        prefs.edit().putBoolean("serviceRunning", true).apply();
        Intent i = new Intent(this, HeartMonitorService.class);
        i.setAction("START");
        ContextCompat.startForegroundService(this, i);
        tvStatus.setText("İzleme aktif ✓");
    }

    private void stopMonitoring() {
        prefs.edit().putBoolean("serviceRunning", false).apply();
        Intent i = new Intent(this, HeartMonitorService.class);
        i.setAction("STOP");
        startService(i);
        tvStatus.setText("İzleme durduruldu");
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Pil Optimizasyonu")
                    .setMessage("HeartGuard'ın arka planda sürekli çalışabilmesi için pil optimizasyonunu kapatmanız gerekiyor.")
                    .setPositiveButton("Evet, Kapat", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Hayır", null)
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERM_REQUEST) {
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.SEND_SMS)
                        && results[i] != PackageManager.PERMISSION_GRANTED) {
                    new AlertDialog.Builder(this)
                        .setTitle("SMS İzni Gerekli")
                        .setMessage("HeartGuard SMS gönderebilmek için SMS iznine ihtiyaç duyuyor.")
                        .setPositiveButton("Ayarlara Git", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("İptal", null)
                        .show();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeartMonitorService.setUiCallback((bpm, status) ->
            runOnUiThread(() -> {
                if (bpm > 0) tvHeartRate.setText("Nabız: " + bpm + " bpm");
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
