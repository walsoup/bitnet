package com.bluenet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException

class L2capClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val psm: Int,
    private val onConnected: (BluetoothSocket) -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: BluetoothSocket? = null
    private var connectionThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun connect(compatMode: Boolean = false) {
        connectionThread = Thread({
            var connectedSocket: BluetoothSocket? = null
            val attemptLogs = mutableListOf<String>()

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
            try {
                if (adapter?.isDiscovering == true) {
                    adapter.cancelDiscovery()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error canceling Bluetooth discovery", e)
            }

            if (!compatMode && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && psm > 1) {
                // Attempt 1: Insecure L2CAP CoC (API 29+)
                var s: BluetoothSocket? = null
                try {
                    Log.d(TAG, "[Attempt 1/5] Insecure L2CAP connection to ${device.address} on PSM $psm")
                    s = device.createInsecureL2capChannel(psm)
                    s.connect()
                    connectedSocket = s
                    Log.d(TAG, "Insecure L2CAP socket connected successfully")
                } catch (e: Exception) {
                    val errMsg = e.message ?: e.toString()
                    attemptLogs.add("1. Insecure L2CAP (PSM $psm): $errMsg")
                    Log.w(TAG, "Insecure L2CAP CoC connection failed: $errMsg", e)
                    try { s?.close() } catch (_: Exception) {}
                }

                // Attempt 2: Secure L2CAP CoC (API 29+)
                if (connectedSocket == null) {
                    var s2: BluetoothSocket? = null
                    try {
                        Log.d(TAG, "[Attempt 2/5] Secure L2CAP connection to ${device.address} on PSM $psm")
                        s2 = device.createL2capChannel(psm)
                        s2.connect()
                        connectedSocket = s2
                        Log.d(TAG, "Secure L2CAP socket connected successfully")
                    } catch (e: Exception) {
                        val errMsg = e.message ?: e.toString()
                        attemptLogs.add("2. Secure L2CAP (PSM $psm): $errMsg")
                        Log.w(TAG, "Secure L2CAP CoC connection failed: $errMsg", e)
                        try { s2?.close() } catch (_: Exception) {}
                    }
                }
            } else if (compatMode) {
                attemptLogs.add("Info: Compatibility Mode Active (Bypassed L2CAP CoC)")
            }

            // Attempt 3: Insecure RFCOMM via Service UUID
            if (connectedSocket == null) {
                var s: BluetoothSocket? = null
                try {
                    Log.d(TAG, "[Attempt 3/5] Insecure RFCOMM connection via UUID ${L2capServer.SERVICE_UUID}")
                    s = device.createInsecureRfcommSocketToServiceRecord(L2capServer.SERVICE_UUID)
                    s.connect()
                    connectedSocket = s
                    Log.d(TAG, "Insecure RFCOMM socket connected successfully")
                } catch (e: Exception) {
                    val errMsg = e.message ?: e.toString()
                    attemptLogs.add("3. Insecure RFCOMM: $errMsg")
                    Log.w(TAG, "Insecure RFCOMM UUID socket failed: $errMsg", e)
                    try { s?.close() } catch (_: Exception) {}
                }
            }

            // Attempt 4: Secure RFCOMM via Service UUID
            if (connectedSocket == null) {
                var s: BluetoothSocket? = null
                try {
                    Log.d(TAG, "[Attempt 4/5] Secure RFCOMM connection via UUID ${L2capServer.SERVICE_UUID}")
                    s = device.createRfcommSocketToServiceRecord(L2capServer.SERVICE_UUID)
                    s.connect()
                    connectedSocket = s
                    Log.d(TAG, "Secure RFCOMM socket connected successfully")
                } catch (e: Exception) {
                    val errMsg = e.message ?: e.toString()
                    attemptLogs.add("4. Secure RFCOMM: $errMsg")
                    Log.w(TAG, "Secure RFCOMM UUID socket failed: $errMsg", e)
                    try { s?.close() } catch (_: Exception) {}
                }
            }

            // Attempt 5: Reflection RFCOMM channel 1
            if (connectedSocket == null) {
                var s: BluetoothSocket? = null
                try {
                    Log.d(TAG, "[Attempt 5/5] Reflection RFCOMM channel 1 to ${device.address}")
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    s = m.invoke(device, 1) as BluetoothSocket
                    s.connect()
                    connectedSocket = s
                    Log.d(TAG, "Reflection RFCOMM channel 1 connected successfully")
                } catch (e: Exception) {
                    val errMsg = e.message ?: e.toString()
                    attemptLogs.add("5. Reflection RFCOMM: $errMsg")
                    Log.e(TAG, "All Bluetooth connection attempts failed", e)
                    try { s?.close() } catch (_: Exception) {}

                    val verboseError = "Bluetooth Connection Failed:\n" + attemptLogs.joinToString("\n")
                    onError(verboseError)
                    disconnect()
                    return@Thread
                }
            }

            socket = connectedSocket
            onConnected(connectedSocket)
        }, "L2capClientConnectThread").apply { start() }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing client L2CAP socket", e)
        }
        socket = null
        connectionThread?.interrupt()
        connectionThread = null
    }

    companion object {
        private const val TAG = "L2capClient"
    }
}
