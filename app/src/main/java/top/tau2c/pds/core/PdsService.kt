package top.tau2c.pds.core

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class PdsService : Service() {

    companion object {
        private const val TAG = "PdsService"
        const val PERMISSION_ACCESS_PDS = "top.tau2c.pds.permission.ACCESS_PDS"
    }

    private var isInitialized = false

    private val callbackList = RemoteCallbackList<IPdsChangeListener>()
    private val listenerFilters = ConcurrentHashMap<IBinder, String>()

    private val binder = object : IPdsService.Stub() {
        override fun executeRequest(requestJson: String?): String {
            enforceCallingPermission()
            if (requestJson == null) {
                return """{"status":"Error","payload":"Empty request payload"}"""
            }
            val callingUid = Binder.getCallingUid()
            val responseJson = NativePds.handleIpcRequestJson(requestJson, callingUid)

            // If request is a modifying operation (put/delete), notify subscribers
            notifyChangeIfModifying(requestJson, responseJson)

            return responseJson
        }

        override fun registerClient(clientName: String?, publicKey: ByteArray?): String {
            enforceCallingPermission()
            if (clientName == null || publicKey == null || publicKey.size != 32) {
                return ""
            }
            return NativePds.registerClient(clientName, publicKey)
        }

        override fun approveClient(clientId: String?, permissionsJson: String?): Boolean {
            enforceCallingPermission()
            if (clientId == null || permissionsJson == null) {
                return false
            }
            return NativePds.approveClient(clientId, permissionsJson)
        }

        override fun isReady(): Boolean {
            return isInitialized
        }

        override fun registerChangeListener(filterJson: String?, listener: IPdsChangeListener?) {
            enforceCallingPermission()
            if (listener == null) return
            val filter = filterJson ?: "{}"
            listenerFilters[listener.asBinder()] = filter
            callbackList.register(listener)
            Log.d(TAG, "Registered change listener with filter: $filter")
        }

        override fun unregisterChangeListener(listener: IPdsChangeListener?) {
            enforceCallingPermission()
            if (listener == null) return
            listenerFilters.remove(listener.asBinder())
            callbackList.unregister(listener)
            Log.d(TAG, "Unregistered change listener")
        }

        override fun listClients(): String {
            enforceCallingPermission()
            return NativePds.listClientsJson()
        }

        override fun revokeClient(clientId: String?): Boolean {
            enforceCallingPermission()
            if (clientId == null) return false
            return NativePds.revokeClient(clientId)
        }

        override fun removeClient(clientId: String?): Boolean {
            enforceCallingPermission()
            if (clientId == null) return false
            return NativePds.removeClient(clientId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val dataDir = filesDir.resolve("pds_store").absolutePath
        val nodeName = "android-${android.os.Build.MODEL}"
        Log.i(TAG, "Initializing PDS Core engine at: $dataDir")
        isInitialized = NativePds.init(dataDir, nodeName)
        if (!isInitialized) {
            Log.e(TAG, "Failed to initialize PDS Core native storage engine")
        } else {
            Log.i(TAG, "PDS Core native engine initialized successfully")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun enforceCallingPermission() {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        if (callingUid == android.os.Process.myUid()) {
            return
        }
        val permissionResult = checkPermission(PERMISSION_ACCESS_PDS, callingPid, callingUid)
        if (permissionResult != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Calling app (UID: $callingUid) does not have permission $PERMISSION_ACCESS_PDS")
        }
    }

    private fun notifyChangeIfModifying(requestJson: String, responseJson: String) {
        try {
            val req = JSONObject(requestJson)
            val action = req.optString("action", "")
            if (action != "put" && action != "delete" && action != "put_blob") {
                return
            }

            val resp = JSONObject(responseJson)
            if (resp.optString("status") != "Success" && resp.optString("status") != "success") {
                return
            }

            val entityType = req.optString("entity_type", if (action == "put_blob") "blob" else "*")
            val recordId = resp.optString("payload", "")

            val event = JSONObject().apply {
                put("action", action)
                put("entity_type", entityType)
                put("id", recordId)
                put("timestamp_ms", System.currentTimeMillis())
            }
            val eventsArray = """[${event}]"""

            dispatchChangeEvents(entityType, eventsArray)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process change notification", e)
        }
    }

    private fun dispatchChangeEvents(entityType: String, eventsJson: String) {
        val count = callbackList.beginBroadcast()
        try {
            for (i in 0 until count) {
                val listener = callbackList.getBroadcastItem(i)
                val filter = listenerFilters[listener.asBinder()] ?: "{}"
                if (matchesFilter(filter, entityType)) {
                    try {
                        listener.onChanges(eventsJson)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to deliver change event to listener", e)
                    }
                }
            }
        } finally {
            callbackList.finishBroadcast()
        }
    }

    private fun matchesFilter(filterJson: String, entityType: String): Boolean {
        return try {
            val filter = JSONObject(filterJson)
            val targetType = filter.optString("entity_type", "*")
            targetType == "*" || targetType == entityType
        } catch (e: Exception) {
            true
        }
    }
}
