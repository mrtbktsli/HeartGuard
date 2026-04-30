package com.heartguard;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import android.telephony.SmsManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.*;

public class HeartMonitorService extends Service {

    private static final String TAG = "HeartGuard";
    private static final String CHANNEL_ID = "HeartGuardChannel";
    private static final int NOTIF_ID = 1;
    private static final UUID HR_SERVICE = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_CHAR    = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private SharedPreferences prefs;
    private Handler handler;
    private boolean scanning = false;
    private boolean connected = false;
    private boolean wasHigh = false;
    private boolean wasLow = false;

    private static UiCallback uiCallback;
    private static DeviceScanCallback deviceScanCallback;

    public interface UiCallback {
        void onUpdate(int bpm, String status);
    }

    public interface DeviceScanCallback {
        void onDeviceFound(String name, String address);
        void onScanFinished();
    }

    public static void setUiCallback(UiCallback cb) { uiCallback = cb; }
    public static void setDeviceScanCallback(DeviceScanCallback cb) { deviceScanCallback = cb; }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("HeartGuard", MODE_PRIVATE);
        createChannel();
        // Android 14 için onCreate'de startForeground çağır
        startForeground(NOTIF_ID, buildNotif("HeartGuard basliyor..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = "START";
        if (intent != null && intent.getAction() != null) {
            action = intent.getAction();
        }
        switch (action) {
            case "STOP":
                stopAll();
                return START_NOT_STICKY;
            case "TEST_SMS":
                updateNotif("Test SMS...");
                sendSms("TEST - HeartGuard calisiyor", 0);
                return START_NOT_STICKY;
            default:
                updateNotif("Cihaz aranıyor...");
                startScan();
                return START_STICKY;
        }
    }

    private void stopAll() {
        stopScan();
        disconnectGatt();
        stopForeground(true);
        stopSelf();
    }

    private void startScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm == null) return;
        BluetoothAdapter ba = bm.getAdapter();
        if (ba == null || !ba.isEnabled()) {
            updateStatus(0, "Bluetooth kapali!");
            handler.postDelayed(this::startScan, 30000);
            return;
        }
        bleScanner = ba.getBluetoothLeScanner();
        if (bleScanner == null) return;

        String selectedMac = prefs.getString("selectedDeviceMac", "");
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        scanning = true;

        if (!selectedMac.isEmpty()) {
            ScanFilter filter = new ScanFilter.Builder()
                .setDeviceAddress(selectedMac).build();
            bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } else {
            ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new android.os.ParcelUuid(HR_SERVICE)).build();
            bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        }

        updateStatus(0, "Cihaz aranıyor...");

        handler.postDelayed(() -> {
            if (scanning && !connected) {
                stopScan();
                handler.postDelayed(this::startScan, 5000);
            }
        }, 30000);
    }

    private void stopScan() {
        if (bleScanner != null && scanning) {
            try { bleScanner.stopScan(scanCallback); } catch (Exception e) { }
            scanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            stopScan();
            connectDevice(result.getDevice());
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.postDelayed(HeartMonitorService.this::startScan, 30000);
        }
    };

    private void connectDevice(BluetoothDevice device) {
        String name = device.getName() != null ? device.getName() : device.getAddress();
        updateStatus(0, "Baglanıyor: " + name);
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnectGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        connected = false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                g.discoverServices();
                updateStatus(0, "Baglandi!");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                disconnectGatt();
                handler.postDelayed(HeartMonitorService.this::startScan, 5000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(HR_SERVICE);
            if (svc != null) {
                BluetoothGattCharacteristic ch = svc.getCharacteristic(HR_CHAR);
                if (ch != null) {
                    g.setCharacteristicNotification(ch, true);
                    BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
                    if (desc != null) {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(desc);
                    }
                    updateStatus(0, "Nabiz izleniyor...");
                    return;
                }
            }
            updateStatus(0, "HR servisi bulunamadi");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (!HR_CHAR.equals(ch.getUuid())) return;
            int flag = ch.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
            int bpm;
            if ((flag & 1) != 0) {
                bpm = ch.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1);
            } else {
                bpm = ch.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1);
            }
            onHeartRate(bpm);
        }
    };

    private void onHeartRate(int bpm) {
        int upper = prefs.getInt("upperLimit", 160);
        int lower = prefs.getInt("lowerLimit", 40);
        String name = prefs.getString("childName", "Cocuk");

        if (bpm > upper) {
            String status = "YUKSEK: " + bpm + " bpm";
            updateStatus(bpm, status);
            updateNotif(status);
            if (!wasHigh) {
                wasHigh = true;
                wasLow = false;
                sendSms("YUKSEK NABIZ\n" + name + ": " + bpm + " bpm\nUst limit: " + upper + " bpm", bpm);
            }
        } else if (bpm < lower && bpm > 0) {
            String status = "DUSUK: " + bpm + " bpm";
            updateStatus(bpm, status);
            updateNotif(status);
            if (!wasLow) {
                wasLow = true;
                wasHigh = false;
                sendSms("DUSUK NABIZ\n" + name + ": " + bpm + " bpm\nAlt limit: " + lower + " bpm", bpm);
            }
        } else if (bpm > 0) {
            String status = "Normal: " + bpm + " bpm";
            updateStatus(bpm, status);
            updateNotif(status);
            if (wasHigh) {
                wasHigh = false;
                sendSms("NORMALE DONDU\n" + name + ": " + bpm + " bpm\n(Yuksek nabiz gecti)", bpm);
            } else if (wasLow) {
                wasLow = false;
                sendSms("NORMALE DONDU\n" + name + ": " + bpm + " bpm\n(Dusuk nabiz gecti)", bpm);
            }
        }
    }

    private void sendSms(String reason, int bpm) {
        String phone = prefs.getString("parentPhone", "").trim();
        if (phone.isEmpty()) {
            updateStatus(bpm, "HATA: Telefon numarasi girilmemis!");
            return;
        }
        if (!phone.startsWith("+") && !phone.startsWith("00")) {
            if (phone.startsWith("0")) {
                phone = "+9" + phone;
            } else {
                phone = "+90" + phone;
            }
        }
        String time = new SimpleDateFormat("HH:mm:ss", new Locale("tr")).format(new Date());
        String txt = "HEARTGUARD\n" + reason + "\nSaat: " + time;
        try {
            SmsManager sm;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sm = getSystemService(SmsManager.class);
            } else {
                sm = SmsManager.getDefault();
            }
            if (sm == null) return;
            ArrayList<String> parts = sm.divideMessage(txt);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            updateStatus(bpm, "SMS gonderildi");
        } catch (Exception e) {
            updateStatus(bpm, "SMS HATASI: " + e.getMessage());
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "HeartGuard", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HeartGuard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void updateNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif(text));
    }

    private void updateStatus(final int bpm, final String status) {
        if (uiCallback != null) {
            handler.post(() -> uiCallback.onUpdate(bpm, status));
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopScan();
        disconnectGatt();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
