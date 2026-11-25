// SPDX-License-Identifier: MIT
package dev.glassworks.stockprobe;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only BLE GATT peripheral that serves the GWSB stock bundle to Kiln over Web Bluetooth,
 * for a Mirror with no usable Wi-Fi. Protocol: see ../../../../README.md ("BLE GATT protocol").
 *
 * Every Bluetooth call is guarded: a missing adapter, Bluetooth off, or a stack error only logs
 * and never takes down ProbeServerService. If Bluetooth is off at start, the server waits for
 * ACTION_STATE_CHANGED and starts when it turns on. All state lives on one HandlerThread.
 */
public final class BleProbeServer {
    private static final String TAG = "GlassworksStockProbe";

    public static final UUID SERVICE = UUID.fromString("67770001-6b69-6c6e-7374-6f636b70726f");
    public static final UUID META = UUID.fromString("67770002-6b69-6c6e-7374-6f636b70726f");
    public static final UUID CTRL = UUID.fromString("67770003-6b69-6c6e-7374-6f636b70726f");
    public static final UUID DATA = UUID.fromString("67770004-6b69-6c6e-7374-6f636b70726f");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int OP_STREAM = 0x01;
    public static final int OP_RESEND = 0x02;
    public static final int OP_ABORT = 0x03;

    /** DATA frames with these indexes carry stream info, not bundle bytes. */
    public static final long IDX_INFO = 0xFFFFFFFFL;
    public static final long IDX_END = 0xFFFFFFFEL;

    private static final int DEFAULT_MTU = 23;
    private static final int MAX_ATTR = 512;
    private static final long SENT_WATCHDOG_MS = 1500L;
    private static final int MAX_NOTIFY_RETRIES = 200;
    private static final int GATT_INVALID_OFFSET = 7;

    private final Context ctx;
    private final HandlerThread thread;
    private final Handler h;

    // Everything below is touched only on the handler thread.
    private BluetoothManager manager;
    private BluetoothGattServer gatt;
    private BluetoothGattCharacteristic dataChar;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertising;
    private boolean triedWithoutName;
    private boolean receiverRegistered;
    private boolean stopped;

    private final Map<String, Integer> mtus = new HashMap<>();
    private final Map<String, byte[]> metaSnapshots = new HashMap<>();
    private final Map<String, ByteArrayOutputStream> preparedWrites = new HashMap<>();
    private final Map<String, Boolean> subscribed = new HashMap<>();

    // The one active stream (the characteristic value is shared, so one stream at a time).
    private BluetoothDevice streamDevice;
    private StockBundle streamBundle;
    private int chunkSize;
    private long totalChunks;
    private final ArrayDeque<long[]> queue = new ArrayDeque<>(); // {from, to} ranges, or {IDX_*, 0}
    private boolean awaitingSent;
    private int notifyRetries;
    private long sentInStream;
    private final Runnable sentWatchdog = () -> {
        if (awaitingSent) {
            Log.w(TAG, "BLE: onNotificationSent did not arrive; continuing");
            awaitingSent = false;
            pump();
        }
    };

    public BleProbeServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        thread = new HandlerThread("stock-probe-ble");
        thread.start();
        h = new Handler(thread.getLooper());
    }

    public void start() {
        h.post(() -> {
            try {
                if (!receiverRegistered) {
                    ctx.registerReceiver(stateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, h);
                    receiverRegistered = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "BLE: could not watch the adapter state", t);
            }
            bringUp();
        });
    }

    public void stop() {
        h.post(() -> {
            stopped = true;
            tearDown();
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(stateReceiver); } catch (Throwable ignored) {}
                receiverRegistered = false;
            }
            thread.quitSafely();
        });
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) bringUp();
            else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) tearDown();
        }
    };

    private void bringUp() {
        if (stopped || gatt != null) return;
        try {
            manager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
            if (adapter == null) { Log.i(TAG, "BLE: no Bluetooth adapter; BLE probe off"); return; }
            if (!adapter.isEnabled()) { Log.i(TAG, "BLE: Bluetooth is off; waiting for it to turn on"); return; }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { Log.i(TAG, "BLE: no LE advertiser (multiAdv off?); BLE probe off"); return; }
            gatt = manager.openGattServer(ctx, callback);
            if (gatt == null) { Log.w(TAG, "BLE: openGattServer returned null"); return; }

            BluetoothGattService svc = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(new BluetoothGattCharacteristic(META,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
            svc.addCharacteristic(new BluetoothGattCharacteristic(CTRL,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE));
            dataChar = new BluetoothGattCharacteristic(DATA,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
            dataChar.addDescriptor(new BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
            svc.addCharacteristic(dataChar);
            // Advertising starts in onServiceAdded, once the service is really there.
            if (!gatt.addService(svc)) { Log.w(TAG, "BLE: addService refused"); tearDown(); }
        } catch (Throwable t) {
            Log.w(TAG, "BLE: bring-up failed; BLE probe off", t);
            tearDown();
        }
    }

    private void tearDown() {
        clearStream();
        stopAdvertising();
        if (gatt != null) {
            try { gatt.close(); } catch (Throwable ignored) {}
        }
        gatt = null;
        dataChar = null;
        advertiser = null;
        mtus.clear();
        metaSnapshots.clear();
        preparedWrites.clear();
        subscribed.clear();
    }

    private void startAdvertising() {
        if (advertiser == null || advertising || stopped) return;
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            // flags (3) + 128-bit service UUID (18) = 21 of 31 bytes; the name goes in the scan response.
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(SERVICE))
                    .build();
            AdvertiseData scan = new AdvertiseData.Builder()
                    .setIncludeDeviceName(!triedWithoutName)
                    .setIncludeTxPowerLevel(false)
                    .build();
            advertiser.startAdvertising(settings, data, scan, advertiseCallback);
            advertising = true;
        } catch (Throwable t) {
            Log.w(TAG, "BLE: startAdvertising failed", t);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertising) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Throwable ignored) {}
        }
        advertising = false;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings s) {
            Log.i(TAG, "BLE: advertising service " + SERVICE);
        }

        @Override
        public void onStartFailure(int errorCode) {
            h.post(() -> {
                advertising = false;
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) { advertising = true; return; }
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !triedWithoutName) {
                    // The adapter name did not fit the scan response; advertise without it.
                    triedWithoutName = true;
                    startAdvertising();
                    return;
                }
                Log.w(TAG, "BLE: advertising failed, error " + errorCode);
            });
        }
    };

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            h.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising();
                else Log.w(TAG, "BLE: service add failed, status " + status);
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            h.post(() -> {
                String a = device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "BLE: central connected");
                    if (!mtus.containsKey(a)) mtus.put(a, DEFAULT_MTU);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "BLE: central disconnected");
                    mtus.remove(a);
                    metaSnapshots.remove(a);
                    preparedWrites.remove(a);
                    subscribed.remove(a);
                    if (streamDevice != null && a.equals(streamDevice.getAddress())) clearStream();
                    // Some stacks stop a connectable advertisement on connect; make sure it runs again.
                    stopAdvertising();
                    startAdvertising();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            h.post(() -> {
                mtus.put(device.getAddress(), mtu);
                Log.i(TAG, "BLE: MTU " + mtu);
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic c) {
            h.post(() -> {
                if (META.equals(c.getUuid())) readMeta(device, requestId, offset);
                else respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
            });
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic c,
                                                 boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            final byte[] v = value != null ? value.clone() : new byte[0];
            h.post(() -> {
                if (!CTRL.equals(c.getUuid())) {
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null);
                    return;
                }
                if (preparedWrite) {
                    ByteArrayOutputStream buf = preparedWrites.get(device.getAddress());
                    if (buf == null) { buf = new ByteArrayOutputStream(); preparedWrites.put(device.getAddress(), buf); }
                    if (offset != buf.size()) {
                        if (responseNeeded) respond(device, requestId, GATT_INVALID_OFFSET, offset, null);
                        return;
                    }
                    buf.write(v, 0, v.length);
                    if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
                    return;
                }
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                command(device, v);
            });
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            h.post(() -> {
                ByteArrayOutputStream buf = preparedWrites.remove(device.getAddress());
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                if (execute && buf != null) command(device, buf.toByteArray());
            });
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor d) {
            h.post(() -> {
                boolean on = Boolean.TRUE.equals(subscribed.get(device.getAddress()));
                byte[] v = on ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
            });
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor d,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            final byte[] v = value != null ? value.clone() : new byte[0];
            h.post(() -> {
                if (CCCD.equals(d.getUuid())) {
                    boolean on = v.length >= 1 && (v[0] & 0x01) != 0;
                    subscribed.put(device.getAddress(), on);
                    if (!on && streamDevice != null && device.getAddress().equals(streamDevice.getAddress())) clearStream();
                }
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v);
            });
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            h.post(() -> {
                if (!awaitingSent) return;
                awaitingSent = false;
                h.removeCallbacks(sentWatchdog);
                pump();
            });
        }
    };

    private void respond(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        try {
            if (gatt != null) gatt.sendResponse(device, requestId, status, offset, value);
        } catch (Throwable t) {
            Log.w(TAG, "BLE: sendResponse failed", t);
        }
    }

    private int mtu(BluetoothDevice d) {
        Integer m = mtus.get(d.getAddress());
        return m != null ? m : DEFAULT_MTU;
    }

    private static int chunkFor(int mtu) {
        // 3 bytes ATT notification header + 4 bytes chunk index; a value stays within 512 bytes.
        return Math.min(MAX_ATTR - 4, Math.max(16, mtu - 7));
    }

    private boolean isStreaming(BluetoothDevice d) {
        return streamDevice != null && streamDevice.getAddress().equals(d.getAddress());
    }

    /** META long read: snapshot at offset 0, serve slices of that snapshot for offset > 0. */
    private void readMeta(BluetoothDevice device, int requestId, int offset) {
        String a = device.getAddress();
        byte[] meta = metaSnapshots.get(a);
        if (offset == 0 || meta == null) {
            try {
                StockBundle b = isStreaming(device) ? streamBundle : StockBundle.current();
                int cs = isStreaming(device) ? chunkSize : chunkFor(mtu(device));
                meta = b.bleMetaJson(cs, isStreaming(device)).getBytes(StandardCharsets.UTF_8);
            } catch (Throwable t) {
                meta = ("{\"version\":" + StockBundle.VERSION + ",\"error\":"
                        + StockBundle.jsonStr(String.valueOf(t.getMessage())) + "}").getBytes(StandardCharsets.UTF_8);
            }
            metaSnapshots.put(a, meta);
        }
        if (offset > meta.length) {
            respond(device, requestId, GATT_INVALID_OFFSET, offset, null);
            return;
        }
        int n = Math.min(meta.length - offset, mtu(device) - 1);
        byte[] slice = new byte[n];
        System.arraycopy(meta, offset, slice, 0, n);
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
    }

    private void command(BluetoothDevice device, byte[] v) {
        if (v.length == 0) return;
        int op = v[0] & 0xff;
        try {
            if (op == OP_STREAM) {
                long start = v.length >= 5 ? le32(v, 1) : 0;
                long count = v.length >= 9 ? le32(v, 5) : 0;
                StockBundle b = StockBundle.current();
                clearStream();
                streamDevice = device;
                streamBundle = b;
                chunkSize = chunkFor(mtu(device));
                totalChunks = (b.size() + chunkSize - 1) / chunkSize;
                long end = count == 0 ? totalChunks : Math.min(totalChunks, start + count);
                metaSnapshots.remove(device.getAddress());
                Log.i(TAG, "BLE: stream " + start + ".." + end + " of " + totalChunks + " chunks of " + chunkSize);
                queue.add(new long[] {IDX_INFO, 0});
                if (start < end) queue.add(new long[] {start, end});
                queue.add(new long[] {IDX_END, 0});
                sentInStream = 0;
                pump();
            } else if (op == OP_RESEND) {
                if (!isStreaming(device) && streamDevice != null) return; // another central owns the stream
                if (streamBundle == null) return; // no locked chunk size yet: send 0x01 first
                streamDevice = device;
                int n = v.length >= 3 ? ((v[1] & 0xff) | (v[2] & 0xff) << 8) : 0;
                Log.i(TAG, "BLE: resend " + n + " chunks");
                boolean wasIdle = queue.isEmpty() && !awaitingSent;
                // Drop a pending END so the resent chunks land before it.
                if (!queue.isEmpty() && queue.peekLast()[0] == IDX_END) queue.pollLast();
                for (int i = 0; i < n && 3 + 4 * i + 4 <= v.length; i++) {
                    long idx = le32(v, 3 + 4 * i);
                    if (idx < totalChunks) queue.add(new long[] {idx, idx + 1});
                }
                queue.add(new long[] {IDX_END, 0});
                sentInStream = 0;
                if (wasIdle) pump();
            } else if (op == OP_ABORT) {
                if (isStreaming(device)) {
                    queue.clear();
                    Log.i(TAG, "BLE: stream aborted");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "BLE: command 0x" + Integer.toHexString(op) + " failed", t);
        }
    }

    /** Sends the next queued frame; the next one follows onNotificationSent (or the watchdog). */
    private void pump() {
        if (awaitingSent || gatt == null || dataChar == null || streamDevice == null) return;
        long[] r = queue.peekFirst();
        if (r == null) return;
        long idx = r[0];
        byte[] frame;
        if (idx == IDX_INFO) {
            frame = new byte[16];
            StockBundle.putLe32(frame, 0, IDX_INFO);
            StockBundle.putLe32(frame, 4, chunkSize);
            StockBundle.putLe32(frame, 8, streamBundle.size());
            StockBundle.putLe32(frame, 12, totalChunks);
        } else if (idx == IDX_END) {
            frame = new byte[8];
            StockBundle.putLe32(frame, 0, IDX_END);
            StockBundle.putLe32(frame, 4, sentInStream);
        } else {
            long off = idx * chunkSize;
            int n = (int) Math.min(chunkSize, streamBundle.size() - off);
            frame = new byte[4 + n];
            StockBundle.putLe32(frame, 0, idx);
            streamBundle.read(off, frame, 4, n);
        }
        boolean ok;
        try {
            dataChar.setValue(frame);
            ok = gatt.notifyCharacteristicChanged(streamDevice, dataChar, false);
        } catch (Throwable t) {
            Log.w(TAG, "BLE: notify failed", t);
            ok = false;
        }
        if (!ok) {
            // The stack's queue is busy (or the link is going away): try again shortly, then give up.
            if (++notifyRetries > MAX_NOTIFY_RETRIES) {
                Log.w(TAG, "BLE: notifications keep failing; stopping the stream");
                clearStream();
                return;
            }
            h.postDelayed(this::pump, 10L);
            return;
        }
        notifyRetries = 0;
        if (idx < IDX_END) sentInStream++;
        // Advance the queue only after the stack took the frame.
        if (idx >= IDX_END || r[0] + 1 >= r[1]) queue.pollFirst();
        else r[0] = idx + 1;
        awaitingSent = true;
        h.postDelayed(sentWatchdog, SENT_WATCHDOG_MS);
    }

    private void clearStream() {
        queue.clear();
        awaitingSent = false;
        notifyRetries = 0;
        h.removeCallbacks(sentWatchdog);
        streamDevice = null;
        streamBundle = null;
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8 | (b[off + 2] & 0xffL) << 16 | (b[off + 3] & 0xffL) << 24;
    }
}
