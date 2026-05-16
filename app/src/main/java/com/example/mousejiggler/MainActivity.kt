package com.example.mousejiggler

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        
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
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var btnToggle: Button
    private lateinit var btnDiscover: Button
    private lateinit var tvStatus: TextView

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread {
                if (registered) {
                    tvStatus.text = "Bluetooth HID Registered.\nPair your MacBook with this device."
                } else {
                    tvStatus.text = "Failed to register Bluetooth HID."
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        hostDevice = device
                        tvStatus.text = "Connected to ${device?.name ?: "Unknown Device"}!"
                        btnToggle.isEnabled = true
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        hostDevice = null
                        tvStatus.text = "Disconnected. Pair/Connect to your MacBook."
                        btnToggle.isEnabled = false
                        stopJiggler()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        btnDiscover = findViewById(R.id.btnDiscover)
        tvStatus = findViewById(R.id.tvStatus)

        btnToggle.isEnabled = false
        btnToggle.setOnClickListener {
            if (isJiggling) stopJiggler() else startJiggler()
        }

        btnDiscover.setOnClickListener {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivity(discoverableIntent)
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            tvStatus.text = "Bluetooth not supported on this device."
            return
        }

        checkPermissionsAndInit()
    }

    private fun checkPermissionsAndInit() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initBluetoothHid()
        }
    }

    private fun initBluetoothHid() {
        if (!bluetoothAdapter!!.isEnabled) {
            tvStatus.text = "Please enable Bluetooth."
            // Optionally request to enable bluetooth
            return
        }

        // Set the Bluetooth name to be easily identifiable
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bluetoothAdapter?.name = "Mouse Jiggler (Android)"
            }
        } catch (e: Exception) {
            // Fallback if naming fails
        }

        bluetoothAdapter!!.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerHidApp()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Mouse Jiggler",
            "Android Bluetooth Mouse",
            "Google",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HID_DESCRIPTOR
        )

        hidDevice?.registerApp(
            sdpSettings,
            null,
            null,
            Executors.newSingleThreadExecutor(),
            hidCallback
        )
    }

    private fun sendMouseReport(dx: Int, dy: Int) {
        val device = hostDevice ?: return
        val report = byteArrayOf(0x00, dx.toByte(), dy.toByte())
        hidDevice?.sendReport(device, 0, report)
    }

    private val jiggleRunnable = object : Runnable {
        private var moveRight = true

        override fun run() {
            if (!isJiggling) return
            if (moveRight) {
                sendMouseReport(5, 0)
            } else {
                sendMouseReport(-5, 0)
            }
            moveRight = !moveRight
            handler.postDelayed(this, 1000)
        }
    }

    private fun startJiggler() {
        isJiggling = true
        btnToggle.text = "Stop Jiggling"
        tvStatus.text = "Jiggling... (←5px / →5px every 1s)"
        handler.post(jiggleRunnable)
    }

    private fun stopJiggler() {
        isJiggling = false
        btnToggle.text = "Start Jiggling"
        tvStatus.text = "Connected. Jiggler stopped."
        handler.removeCallbacks(jiggleRunnable)
        sendMouseReport(0, 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initBluetoothHid()
            } else {
                tvStatus.text = "Permissions required for Bluetooth HID."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopJiggler()
        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
    }
}
