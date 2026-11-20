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

        val clientSectionTitle = TextView(this).apply {
            text = "Registered Client Applications"
            textSize = 18f
            paint.isFakeBoldText = true
            setPadding(0, 48, 0, 16)
        }
        rootLayout.addView(clientSectionTitle)

        clientsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(clientsContainer)

        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
        }

        setContentView(scrollView)
    }

    private lateinit var clientsContainer: LinearLayout

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

        loadClients()
    }

    private fun loadClients() {
        clientsContainer.removeAllViews()
        val service = pdsService ?: return

        val clientsJson = try {
            service.listClients()
        } catch (e: Exception) {
            "[]"
        }

        try {
            val jsonArray = org.json.JSONArray(clientsJson)
            if (jsonArray.length() == 0) {
                val emptyView = TextView(this).apply {
                    text = "No applications registered yet."
                    textSize = 14f
                    setTextColor(0xFF757575.toInt())
                    setPadding(0, 16, 0, 16)
                }
                clientsContainer.addView(emptyView)
                return
            }

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val clientId = item.optString("client_id")
                val name = item.optString("name")
                val status = item.optString("status")
                val permsArray = item.optJSONArray("permissions")
                val permsList = mutableListOf<String>()
                if (permsArray != null) {
                    for (j in 0 until permsArray.length()) {
                        permsList.add(permsArray.getString(j))
                    }
                }

                val cardLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 24, 24, 24)
                    setBackgroundColor(0xFFEEEEEE.toInt())
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 12, 0, 12)
                    }
                    layoutParams = params
                }

                val appHeader = TextView(this).apply {
                    text = "$name ($status)"
                    textSize = 16f
                    paint.isFakeBoldText = true
                    setTextColor(
                        when (status) {
                            "Active" -> 0xFF2E7D32.toInt()
                            "Pending" -> 0xFFE65100.toInt()
                            else -> 0xFFC62828.toInt()
                        }
                    )
                }
                cardLayout.addView(appHeader)

                val detailsText = TextView(this).apply {
                    text = "ID: $clientId\nPermissions: ${if (permsList.isEmpty()) "None" else permsList.joinToString(", ")}"
                    textSize = 13f
                    setPadding(0, 8, 0, 16)
                }
                cardLayout.addView(detailsText)

                val buttonRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                if (status != "Active") {
                    val approveButton = Button(this).apply {
                        text = "Approve All"
                        setOnClickListener {
                            val defaultPerms = """["ReadEntities","WriteEntities","ReadBlobs","WriteBlobs","ReadHistory"]"""
                            try {
                                service.approveClient(clientId, defaultPerms)
                                updateStatus()
                            } catch (_: Exception) {}
                        }
                    }
                    buttonRow.addView(approveButton)
                }

                if (status == "Active") {
                    val revokeButton = Button(this).apply {
                        text = "Revoke"
                        setOnClickListener {
                            try {
                                service.revokeClient(clientId)
                                updateStatus()
                            } catch (_: Exception) {}
                        }
                    }
                    buttonRow.addView(revokeButton)
                }

                val removeButton = Button(this).apply {
                    text = "Remove"
                    setOnClickListener {
                        try {
                            service.removeClient(clientId)
                            updateStatus()
                        } catch (_: Exception) {}
                    }
                }
                buttonRow.addView(removeButton)

                cardLayout.addView(buttonRow)
                clientsContainer.addView(cardLayout)
            }
        } catch (_: Exception) {
            val errorView = TextView(this).apply {
                text = "Error parsing clients list."
                textSize = 14f
                setTextColor(0xFFC62828.toInt())
            }
            clientsContainer.addView(errorView)
        }
    }
}
