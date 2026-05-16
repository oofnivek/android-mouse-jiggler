package com.example.mousejiggler

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private var jigglerService: JigglerService? = null
    private var isBound = false

    private lateinit var btnToggle: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnReset: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvId: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected")
            val binder = service as JigglerService.LocalBinder
            jigglerService = binder.getService()
            isBound = true
            jigglerService?.setCallback(serviceCallback)
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected")
            jigglerService = null
            isBound = false
        }
    }

    private val serviceCallback = object : JigglerService.ServiceCallback {
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device=${device?.name}, state=$state")
            runOnUiThread { updateUI() }
        }

        override fun onAppStatusChanged(registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered")
            runOnUiThread {
                if (registered) {
                    tvStatus.text = "Bluetooth HID Registered.\nPair your MacBook with this device."
                } else {
                    tvStatus.text = "Failed to register Bluetooth HID."
                }
            }
        }

        override fun onJigglingStatusChanged(isJiggling: Boolean) {
            Log.d(TAG, "onJigglingStatusChanged: $isJiggling")
            runOnUiThread { updateUI() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnReset = findViewById(R.id.btnReset)
        tvStatus = findViewById(R.id.tvStatus)
        tvId = findViewById(R.id.tvId)

        btnToggle.setOnClickListener {
            val service = jigglerService ?: return@setOnClickListener
            if (service.isJiggling()) {
                service.stopJiggler()
            } else {
                service.startJiggler()
            }
        }

        btnDiscover.setOnClickListener {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
            }
            startActivity(discoverableIntent)
        }

        btnReset.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset Pairing?")
                .setMessage("This will unpair all bonded devices and reset the Bluetooth HID registration. Use this if you are unable to pair with your MacBook.")
                .setPositiveButton("Reset") { _, _ ->
                    jigglerService?.unpairAll()
                    tvStatus.text = "All bonds cleared. Registration reset."
                    updateUI()
                }
                .setNegativeButton("Cancel", null)
                .show()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, JigglerService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateUI() {
        val service = jigglerService ?: return
        val host = service.getHostDevice()
        val isJiggling = service.isJiggling()
        val currentId = service.getCurrentId()

        tvId.text = "ID: $currentId"

        val buttonColor = if (host != null && isJiggling) R.color.error else R.color.primary
        btnToggle.backgroundTintList = ColorStateList.valueOf(resources.getColor(buttonColor, theme))

        if (host != null) {
            tvStatus.text = "Connected to ${host.name ?: "Unknown Device"}!"
            btnToggle.isEnabled = true
            btnToggle.text = if (isJiggling) "Stop Jiggling" else "Start Jiggling"
        } else {
            tvStatus.text = "Disconnected. Pair/Connect to your MacBook."
            btnToggle.isEnabled = false
            btnToggle.text = "Start Jiggling"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAndBindService()
            } else {
                tvStatus.text = "Permissions required for Bluetooth HID and Notifications."
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            jigglerService?.setCallback(null)
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
