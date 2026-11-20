package top.tau2c.pds.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private var pdsService: IPdsService? = null
    private var isBound = false

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            pdsService = IPdsService.Stub.asInterface(service)
            isBound = true
            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pdsService = null
            isBound = false
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Personal Data Store (PDS) Core"
            textSize = 22f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 32)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(title)

        statusText = TextView(this).apply {
            text = "Connecting to PDS Core service..."
            textSize = 16f
            setPadding(0, 0, 0, 24)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(statusText)

        infoText = TextView(this).apply {
            text = "Service Details:\n- Storage Engine: Rust FFI\n- Package: top.tau2c.pds.core\n- IPC: Binder (AIDL)"
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(infoText)

        val refreshButton = Button(this).apply {
            text = "Refresh Status"
            setOnClickListener { updateStatus() }
        }
        rootLayout.addView(refreshButton)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }

        setContentView(scrollView)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PdsService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            pdsService = null
        }
    }

    private fun updateStatus() {
        val ready = try {
            pdsService?.isReady == true
        } catch (_: Exception) {
            false
        }

        if (ready) {
            statusText.text = "Status: Online (Storage Engine Ready)"
            statusText.setTextColor(0xFF2E7D32.toInt())
        } else {
            statusText.text = "Status: Initializing or Disconnected"
            statusText.setTextColor(0xFFC62828.toInt())
        }
    }
}
