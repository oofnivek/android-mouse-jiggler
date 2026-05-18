package com.example.mousejiggler

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class JigglerService : Service() {

    companion object {
        private const val TAG = "JigglerService"
        private const val CHANNEL_ID = "JigglerServiceChannel"
        private const val NOTIFICATION_ID = 1

        // HID Mouse report descriptor
        private val HID_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01,             // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02,             // Usage (Mouse)
            0xA1.toByte(), 0x01,             // Collection (Application)
            0x09.toByte(), 0x01,             //   Usage (Pointer)
            0xA1.toByte(), 0x00,             //   Collection (Physical)
            0x05.toByte(), 0x09,             //     Usage Page (Buttons)
            0x19.toByte(), 0x01,             //     Usage Minimum (1)
            0x29.toByte(), 0x03,             //     Usage Maximum (3)
            0x15.toByte(), 0x00,             //     Logical Minimum (0)
            0x25.toByte(), 0x01,             //     Logical Maximum (1)
            0x95.toByte(), 0x03,             //     Report Count (3)
            0x75.toByte(), 0x01,             //     Report Size (1)
            0x81.toByte(), 0x02,             //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01,             //     Report Count (1)
            0x75.toByte(), 0x05,             //     Report Size (5) - padding
            0x81.toByte(), 0x03,             //     Input (Constant)
            0x05.toByte(), 0x01,             //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30,             //     Usage (X)
            0x09.toByte(), 0x31,             //     Usage (Y)
            0x15.toByte(), 0x81.toByte(),    //     Logical Minimum (-127)
            0x25.toByte(), 0x7F,             //     Logical Maximum (127)
            0x75.toByte(), 0x08,             //     Report Size (8)
            0x95.toByte(), 0x02,             //     Report Count (2)
            0x81.toByte(), 0x06,             //     Input (Data, Variable, Relative)
            0xC0.toByte(),                   //   End Collection
            0xC0.toByte()                    // End Collection
        )
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private var isJiggling = false
    private var currentId: String = ""
    private val handler = Handler(Looper.getMainLooper())

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): JigglerService = this@JigglerService
    }

    interface ServiceCallback {
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
        fun onAppStatusChanged(registered: Boolean)
        fun onJigglingStatusChanged(isJiggling: Boolean)
    }

    private var callback: ServiceCallback? = null

    fun setCallback(callback: ServiceCallback?) {
        this.callback = callback
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered")
            callback?.onAppStatusChanged(registered)
            if (registered) {
                checkExistingConnections()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device=${device?.name}, state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    hostDevice = null
                    stopJiggler()
                }
            }
            callback?.onConnectionStateChanged(device, state)
            updateNotification()
        }
    }

    private fun checkExistingConnections() {
        val hid = hidDevice ?: return
        val connectedDevices = hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
        Log.d(TAG, "checkExistingConnections: found ${connectedDevices.size} connected devices")
        
        if (connectedDevices.isNotEmpty()) {
            hostDevice = connectedDevices[0]
            Log.d(TAG, "Restoring connection to: ${hostDevice?.name}")
            callback?.onConnectionStateChanged(hostDevice, BluetoothProfile.STATE_CONNECTED)
            updateNotification()
        } else {
            // Try to proactively connect to bonded devices
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                val state = hid.getConnectionState(device)
                Log.d(TAG, "Bonded device: ${device.name}, HID State: $state")
                if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Attempting to connect to ${device.name}...")
                    try {
                        // Attempt to initiate connection from our side
                        hid.connect(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to call connect()", e)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing Bluetooth HID..."))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Set initial Bluetooth name
        updateBluetoothName()

        bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                Log.d(TAG, "onServiceConnected: profile=$profile")
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerHidApp()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                Log.d(TAG, "onServiceDisconnected: profile=$profile")
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun updateBluetoothName(forceNew: Boolean = false) {
        val prefs = getSharedPreferences("jiggler_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("current_id", null)
        
        if (id == null || forceNew) {
            id = generateRandomId()
            prefs.edit().putString("current_id", id).apply()
        }
        
        currentId = id
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bluetoothAdapter?.name = "Mouse $currentId"
            }
        } catch (e: Exception) {
            // Fallback
        }
    }

    private fun generateRandomId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..5)
            .map { chars.random() }
            .joinToString("")
    }

    fun resetRegistration() {
        stopJiggler()
        hidDevice?.unregisterApp()
        handler.postDelayed({
            registerHidApp()
        }, 1000)
    }

    fun unpairAll() {
        val bondedDevices = bluetoothAdapter?.bondedDevices ?: return
        for (device in bondedDevices) {
            try {
                val method = device.javaClass.getMethod("removeBond")
                method.invoke(device)
            } catch (e: Exception) {
                // Handle or log failure
            }
        }
        // Generate a new ID to appear as a new device to the host
        updateBluetoothName(true)
        // After unpairing, it's good to reset registration
        resetRegistration()
    }

    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mouse Jiggler",
            "Android Bluetooth Mouse",
            "Google",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HID_DESCRIPTOR
        )
        hidDevice?.registerApp(sdpSettings, null, null, Executors.newSingleThreadExecutor(), hidCallback)
    }

    fun startJiggler() {
        isJiggling = true
        handler.post(jiggleRunnable)
        callback?.onJigglingStatusChanged(true)
        updateNotification()
    }

    fun stopJiggler() {
        isJiggling = false
        handler.removeCallbacks(jiggleRunnable)
        sendMouseReport(0, 0)
        callback?.onJigglingStatusChanged(false)
        updateNotification()
    }

    fun isJiggling(): Boolean = isJiggling

    fun getHostDevice(): BluetoothDevice? = hostDevice

    fun getCurrentId(): String = currentId

    private val jiggleRunnable = object : Runnable {
        private var moveRight = true
        override fun run() {
            if (!isJiggling) return
            if (moveRight) sendMouseReport(5, 0) else sendMouseReport(-5, 0)
            moveRight = !moveRight
            handler.postDelayed(this, 500)
        }
    }

    private fun sendMouseReport(dx: Int, dy: Int) {
        val device = hostDevice ?: return
        val report = byteArrayOf(0x00, dx.toByte(), dy.toByte())
        hidDevice?.sendReport(device, 0, report)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mouse Jiggler Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mouse Jiggler Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val status = when {
            hostDevice != null && isJiggling -> "Connected and Jiggling"
            hostDevice != null -> "Connected"
            else -> "Waiting for connection..."
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    override fun onDestroy() {
        stopJiggler()
        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        super.onDestroy()
    }
}
