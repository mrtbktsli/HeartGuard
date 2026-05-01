package com.heartguard;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import android.telephony.SmsManager;
import android.util.Log;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class HeartMonitorService extends Service {

    private static final String TAG = "HeartGuard";
    private static final String MI_BAND_MAC = "D0:62:2C:0C:F8:50";
    private static final String AUTH_KEY_HEX = "7249aebe0799b254c4b9d099a4a7cdf8";private static final UUID XIAOMI_SERVICE_UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    private static final UUID XIAOMI_AUTH_UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_CONTROL_UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGatt bluetoothGatt;
    private BluetoothAdapter bluetoothAdapter;
    private Handler handler = new Handler(Looper.getMainLooper());

    private String phoneNumber = "";
    private int upperLimit = 120;
    private int lowerLimit = 50;
    private boolean alertSent = false;
    private boolean isConnected = false;
    private boolean isAuthenticated = false;
    private int authStep = 0;
    private byte[] authRandomNumber = new byte[16];

    private static final String CHANNEL_ID = "HeartGuardChannel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_HEART_RATE_UPDATE = "com.heartguard.HEART_RATE_UPDATE";
    public static final String ACTION_CONNECTION_STATUS = "com.heartguard.CONNECTION_STATUS";
    public static final String EXTRA_HEART_RATE = "heart_rate";
    public static final String EXTRA_STATUS = "status";@Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("HeartGuard baslatiliyor..."));
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) bluetoothAdapter = bm.getAdapter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            phoneNumber = intent.getStringExtra("phoneNumber") != null ? intent.getStringExtra("phoneNumber") : "";
            upperLimit = intent.getIntExtra("upperLimit", 120);
            lowerLimit = intent.getIntExtra("lowerLimit", 50);
        }
        connectToBand();
        return START_STICKY;
    }

    private void connectToBand() {
        if (bluetoothAdapter == null) return;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(MI_BAND_MAC);
            updateNotification("Mi Band 8 baglaniliyor...");
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            Log.e(TAG, "Baglanti hatasi: " + e.getMessage());
            handler.postDelayed(this::connectToBand, 5000);
        }
    }private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                broadcastStatus("Baglandi");
                updateNotification("Mi Band 8 baglandi, dogrulaniyor...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                isAuthenticated = false;
                authStep = 0;
                broadcastStatus("Baglanti Kesildi");
                updateNotification("Baglanti kesildi, yeniden deneniyor...");
                handler.postDelayed(() -> connectToBand(), 5000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handler.postDelayed(() -> startAuthentication(gatt), 500);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;
            String uuid = characteristic.getUuid().toString();
            if (uuid.contains("0051")) {
                handleAuthResponse(gatt, data);
            } else if (uuid.contains("2a37") && data.length > 1) {
                int hr = data[1] & 0xFF;
                if (hr > 0) processHeartRate(hr);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Descriptor yazildi: " + status);
        }
    };private void startAuthentication(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(XIAOMI_SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "Xiaomi servisi yok, HR deneniyor...");
            startHeartRateMonitoring(gatt);
            return;
        }
        BluetoothGattCharacteristic authChar = service.getCharacteristic(XIAOMI_AUTH_UUID);
        if (authChar == null) return;

        gatt.setCharacteristicNotification(authChar, true);
        BluetoothGattDescriptor desc = authChar.getDescriptor(CCCD_UUID);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
        }

        handler.postDelayed(() -> {
            authStep = 1;
            new SecureRandom().nextBytes(authRandomNumber);
            byte[] cmd = new byte[18];
            cmd[0] = 0x01;
            cmd[1] = 0x00;
            System.arraycopy(authRandomNumber, 0, cmd, 2, 16);
            authChar.setValue(cmd);
            gatt.writeCharacteristic(authChar);
            Log.d(TAG, "Auth adim 1 gonderildi");
        }, 600);
    }

    private void handleAuthResponse(BluetoothGatt gatt, byte[] data) {
        Log.d(TAG, "Auth yaniti: " + bytesToHex(data));
        BluetoothGattService service = gatt.getService(XIAOMI_SERVICE_UUID);
        if (service == null) return;
        BluetoothGattCharacteristic authChar = service.getCharacteristic(XIAOMI_AUTH_UUID);
        if (authChar == null) return;

        if (authStep == 1 && data.length >= 3 && data[0] == 0x10 && data[1] == 0x01 && data[2] == 0x01) {
            authStep = 2;
            try {
                byte[] encrypted = encryptAES(authRandomNumber, hexToBytes(AUTH_KEY_HEX));
                byte[] cmd = new byte[18];
                cmd[0] = 0x03;
                cmd[1] = 0x00;
                System.arraycopy(encrypted, 0, cmd, 2, 16);
                authChar.setValue(cmd);
                gatt.writeCharacteristic(authChar);
                Log.d(TAG, "Auth adim 2 gonderildi");
            } catch (Exception e) {
                Log.e(TAG, "Sifreleme hatasi: " + e.getMessage());
            }
        } else if (authStep == 2 && data.length >= 3 && data[0] == 0x10 && data[1] == 0x03 && data[2] == 0x01) {
            isAuthenticated = true;
            authStep = 0;
            Log.d(TAG, "AUTH BASARILI!");
            updateNotification("Dogrulama basarili! Nabiz izleniyor...");
            handler.postDelayed(() -> startHeartRateMonitoring(gatt), 500);
        } else if (data.length >= 3 && data[2] == 0x04) {
            Log.e(TAG, "Auth basarisiz - yanlis key");
        }
    }private void startHeartRateMonitoring(BluetoothGatt gatt) {
        BluetoothGattService hrService = gatt.getService(HR_SERVICE_UUID);
        if (hrService == null) {
            Log.e(TAG, "HR servisi bulunamadi");
            updateNotification("Nabiz servisi bulunamadi, yeniden baglaniliyor...");
            handler.postDelayed(() -> gatt.disconnect(), 3000);
            return;
        }

        BluetoothGattCharacteristic hrControl = hrService.getCharacteristic(HR_CONTROL_UUID);
        if (hrControl != null) {
            hrControl.setValue(new byte[]{0x15, 0x02, 0x01});
            gatt.writeCharacteristic(hrControl);
        }

        BluetoothGattCharacteristic hrMeasure = hrService.getCharacteristic(HR_MEASUREMENT_UUID);
        if (hrMeasure != null) {
            gatt.setCharacteristicNotification(hrMeasure, true);
            BluetoothGattDescriptor desc = hrMeasure.getDescriptor(CCCD_UUID);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(desc);
            }
        }

        updateNotification("Nabiz izleniyor...");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected && hrControl != null) {
                    hrControl.setValue(new byte[]{0x16});
                    gatt.writeCharacteristic(hrControl);
                }
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void processHeartRate(int hr) {
        Log.d(TAG, "Nabiz: " + hr);
        broadcastHeartRate(hr);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        if (hr > upperLimit) {
            updateNotification("Yuksek nabiz: " + hr + " bpm");
            if (!alertSent) { alertSent = true; sendSms("UYARI: Nabiz cok yuksek! " + hr + " bpm (" + time + ")"); }
        } else if (hr < lowerLimit) {
            updateNotification("Dusuk nabiz: " + hr + " bpm");
            if (!alertSent) { alertSent = true; sendSms("UYARI: Nabiz cok dusuk! " + hr + " bpm (" + time + ")"); }
        } else {
            updateNotification("Nabiz: " + hr + " bpm - Normal");
            alertSent = false;
        }
    }

    private void sendSms(String msg) {
        if (phoneNumber == null || phoneNumber.isEmpty()) return;
        try {
            SmsManager sm = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
                getSystemService(SmsManager.class) : SmsManager.getDefault();
            if (sm != null) sm.sendTextMessage(phoneNumber, null, msg, null, null);
        } catch (Exception e) { Log.e(TAG, "SMS hatasi: " + e.getMessage()); }
    }private byte[] encryptAES(byte[] data, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    private byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return b;
    }

    private String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private void broadcastHeartRate(int hr) {
        Intent i = new Intent(ACTION_HEART_RATE_UPDATE);
        i.putExtra(EXTRA_HEART_RATE, hr);
        sendBroadcast(i);
    }

    private void broadcastStatus(String s) {
        Intent i = new Intent(ACTION_CONNECTION_STATUS);
        i.putExtra(EXTRA_STATUS, s);
        sendBroadcast(i);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HeartGuard").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi).build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "HeartGuard", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Override
    public void setUiCallback(Object callback) {}

    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) { bluetoothGatt.close(); bluetoothGatt = null; }
        handler.removeCallbacksAndMessages(null);
    }
}