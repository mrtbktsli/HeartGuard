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
    private long lastAlert = 0;
    private static final long COOLDOWN = 300000L;
    private static UiCallback uiCallback;

    public interface UiCallback {
        void onUpdate(int bpm, String status);
    }

    public static void setUiCallback(UiCallback cb) {
        uiCallback = cb;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("HeartGuard", MODE_PRIVATE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = "START";
        if (intent != null && intent.getAction() != null) {
            action = intent.getAction();
        }
        if ("STOP".equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if ("TEST_SMS".equals(action)) {
            startForeground(NOTIF_ID, buildNotif("Test..."));
            sendSms(99, "TEST - Deneme mesaji");
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotif("Watch aranıyor..."));
        startScan();
        return START_STICKY;
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
            handler.postDelayed(new Runnable() {
                @Override public void run() { startScan(); }
            }, 30000);
            return;
        }
        bleScanner = ba.getBluetoothLeScanner();
        if (bleScanner == null) return;
        ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(new android.os.ParcelUuid(HR_SERVICE)).build();
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        updateStatus(0, "Watch aranıyor...");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (scanning && !connected) {
                    stopScan();
                    handler.postDelayed(new Runnable() {
                        @Override public void run() { startScan(); }
                    }, 5000);
                }
            }
        }, 30000);
    }

    private void stopScan() {
        if (bleScanner != null && scanning) {
            try { bleScanner.stopScan(scanCallback); } catch (Exception e) { Log.e(TAG, "stopScan: " + e.getMessage()); }
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
            handler.postDelayed(new Runnable() {
                @Override public void run() { startScan(); }
            }, 30000);
        }
    };

    private void connectDevice(BluetoothDevice device) {
        updateStatus(0, "Baglanıyor...");
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
                updateStatus(0, "Baglandi");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                disconnectGatt();
                handler.postDelayed(new Runnable() {
                    @Override public void run() { startScan(); }
                }, 5000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(HR_SERVICE);
            if (svc == null) return;
            BluetoothGattCharacteristic ch = svc.getCharacteristic(HR_CHAR);
            if (ch == null) return;
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(desc);
            }
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
        String status;
        boolean alert = false;
        String alertMsg = "";
        if (bpm > upper) {
            status = "YUKSEK: " + bpm + " bpm";
            alert = true;
            alertMsg = "YUKSEK NABIZ: " + name + " = " + bpm + " bpm (Limit: " + upper + ")";
        } else if (bpm < lower && bpm > 0) {
            status = "DUSUK: " + bpm + " bpm";
            alert = true;
            alertMsg = "DUSUK NABIZ: " + name + " = " + bpm + " bpm (Limit: " + lower + ")";
        } else {
            status = "Normal: " + bpm + " bpm";
        }
        updateStatus(bpm, status);
        updateNotif(status);
        long now = System.currentTimeMillis();
        if (alert && now - lastAlert > COOLDOWN) {
            lastAlert = now;
            sendSms(bpm, alertMsg);
        }
    }

    private void sendSms(int bpm, String reason) {
        String phone = prefs.getString("parentPhone", "");
        if (phone == null || phone.length() == 0) return;
        String time = new SimpleDateFormat("HH:mm:ss", new Locale("tr")).format(new Date());
        String txt = "HEARTGUARD\n" + reason + "\nSaat: " + time;
        try {
            SmsManager sm = SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(txt);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            updateStatus(bpm, "SMS gonderildi");
        } catch (Exception e) {
            Log.e(TAG, "SMS hatasi: " + e.getMessage());
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
            handler.post(new Runnable() {
                @Override public void run() {
                    uiCallback.onUpdate(bpm, status);
                }
            });
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
