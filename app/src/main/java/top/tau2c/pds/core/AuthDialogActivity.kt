package top.tau2c.pds.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AuthDialogActivity : Activity() {

    companion object {
        private const val TAG = "AuthDialogActivity"

        const val ACTION_AUTHORIZE = "top.tau2c.pds.core.REQUEST_AUTHORIZATION"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_PERMISSIONS = "permissions"

        const val RESULT_CLIENT_ID = "client_id"
        const val RESULT_GRANTED_PERMISSIONS = "granted_permissions"
    }

    private var pdsService: IPdsService? = null
    private var isBound = false

    private val permissionCheckboxes = mutableMapOf<String, CheckBox>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            pdsService = IPdsService.Stub.asInterface(service)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pdsService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: "Unknown App"
        val publicKey = intent.getByteArrayExtra(EXTRA_PUBLIC_KEY)
        val requestedPerms = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: arrayOf(
            "ReadEntities", "WriteEntities", "ReadBlobs", "WriteBlobs", "ReadHistory"
        )

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(this).apply {
            text = "Authorization Request"
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(titleText)

        val descText = TextView(this).apply {
            text = "The application \"$clientName\" wants to access your Personal Data Store (PDS)."
            textSize = 14f
            setTextColor(0xFF424242.toInt())
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(descText)

        val permsLabel = TextView(this).apply {
            text = "Requested Permissions:"
            textSize = 14f
            paint.isFakeBoldText = true
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, 12)
        }
        rootLayout.addView(permsLabel)

        val permsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
        }

        for (perm in requestedPerms) {
            val cb = CheckBox(this).apply {
                text = perm
                isChecked = true
                textSize = 13f
                setTextColor(0xFF333333.toInt())
            }
            permissionCheckboxes[perm] = cb
            permsContainer.addView(cb)
        }
        rootLayout.addView(permsContainer)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val denyButton = Button(this).apply {
            text = "Deny"
            setTextColor(0xFFC62828.toInt())
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        buttonsRow.addView(denyButton)

        val allowButton = Button(this).apply {
            text = "Authorize"
            setTextColor(0xFF2E7D32.toInt())
            setOnClickListener {
                handleAuthorize(clientName, publicKey)
            }
        }
        buttonsRow.addView(allowButton)

        rootLayout.addView(buttonsRow)

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

    private fun handleAuthorize(clientName: String, publicKey: ByteArray?) {
        val service = pdsService
        if (service == null) {
            Log.e(TAG, "Authorization failed: PdsService is not connected")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val selectedPerms = permissionCheckboxes.filter { it.value.isChecked }.map { it.key }
        val permsJsonArray = org.json.JSONArray(selectedPerms)

        var clientId: String? = null
        if (publicKey != null && publicKey.size == 32) {
            Log.i(TAG, "Registering client: clientName=$clientName with public key")
            clientId = service.registerClient(clientName, publicKey)
            if (clientId.isNotEmpty()) {
                Log.i(TAG, "Approving client: clientId=$clientId, permissions=$selectedPerms")
                service.approveClient(clientId, permsJsonArray.toString())
            } else {
                Log.e(TAG, "Client registration returned empty clientId")
            }
        } else {
            Log.w(
                TAG,
                "No valid 32-byte public key provided in authorization request: publicKey=${publicKey?.size} bytes. Client will not be registered."
            )
        }

        val resultIntent = Intent().apply {
            if (clientId != null && clientId.isNotEmpty()) {
                putExtra(RESULT_CLIENT_ID, clientId)
            }
            putExtra(RESULT_GRANTED_PERMISSIONS, selectedPerms.toTypedArray())
        }
        Log.i(TAG, "Returning authorization result: clientId=$clientId, permissions=$selectedPerms")
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
