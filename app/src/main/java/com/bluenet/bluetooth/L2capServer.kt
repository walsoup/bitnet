package com.bluenet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

import java.util.UUID

class L2capServer(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onClientConnected: (BluetoothSocket) -> Unit,
    private val onError: (String) -> Unit
) {
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var rfcommServerSocket: BluetoothServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var l2capWorkerThread: Thread? = null
    private var rfcommWorkerThread: Thread? = null

    var psm: Int = -1
        private set

    var isUsingRfcommFallback: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            onError("Bluetooth is disabled")
            return false
        }

        var startedAny = false

        // Attempt 1: Native L2CAP CoC (API 29+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                l2capServerSocket = bluetoothAdapter.listenUsingInsecureL2capChannel()
                psm = l2capServerSocket?.psm ?: -1
                startedAny = true
                Log.d(TAG, "L2CAP Server started listening on PSM: $psm")
            } catch (e: Exception) {
                Log.w(TAG, "Insecure L2CAP CoC listen failed, trying secure L2CAP", e)
                try {
                    l2capServerSocket = bluetoothAdapter.listenUsingL2capChannel()
                    psm = l2capServerSocket?.psm ?: -1
                    startedAny = true
                    Log.d(TAG, "Secure L2CAP Server started listening on PSM: $psm")
                } catch (e2: Exception) {
                    Log.w(TAG, "L2CAP CoC listen not supported, relying on RFCOMM listener", e2)
                    l2capServerSocket = null
                }
            }
        }

        // Attempt 2: RFCOMM Fallback socket (Runs concurrently so any client protocol succeeds)
        try {
            rfcommServerSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            startedAny = true
            isUsingRfcommFallback = (l2capServerSocket == null)
            Log.d(TAG, "RFCOMM Server started listening with UUID $SERVICE_UUID")
        } catch (e: Exception) {
            Log.w(TAG, "Insecure RFCOMM listen failed, trying secure RFCOMM", e)
            try {
                rfcommServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                startedAny = true
                isUsingRfcommFallback = (l2capServerSocket == null)
                Log.d(TAG, "Secure RFCOMM Server started listening with UUID $SERVICE_UUID")
            } catch (e2: Exception) {
                Log.e(TAG, "RFCOMM server socket setup failed", e2)
                rfcommServerSocket = null
            }
        }

        if (!startedAny) {
            onError("Failed to start Bluetooth server listener")
            return false
        }

        isRunning.set(true)

        if (l2capServerSocket != null) {
            l2capWorkerThread = Thread({ listenLoop(l2capServerSocket!!, "L2CAP") }, "L2capServerThread").apply { start() }
        }
        if (rfcommServerSocket != null) {
            rfcommWorkerThread = Thread({ listenLoop(rfcommServerSocket!!, "RFCOMM") }, "RfcommServerThread").apply { start() }
        }

        return true
    }

    private fun listenLoop(serverSocket: BluetoothServerSocket, modeName: String) {
        while (isRunning.get()) {
            try {
                Log.d(TAG, "Waiting for $modeName client connection...")
                val socket = serverSocket.accept()
                if (socket != null && isRunning.get()) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            Log.d(TAG, "$modeName socket MaxTxPacketSize: ${socket.maxTransmitPacketSize}, MaxRxPacketSize: ${socket.maxReceivePacketSize}")
                        }
                    } catch (_: Exception) {}
                    Log.d(TAG, "$modeName Client connected successfully from: ${socket.remoteDevice.address}")
                    onClientConnected(socket)
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting $modeName connection", e)
                }
                break
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            l2capServerSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing L2CAP server socket", e)
        }
        try {
            rfcommServerSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing RFCOMM server socket", e)
        }
        l2capServerSocket = null
        rfcommServerSocket = null
        l2capWorkerThread?.interrupt()
        rfcommWorkerThread?.interrupt()
        l2capWorkerThread = null
        rfcommWorkerThread = null
        psm = -1
        Log.d(TAG, "L2CAP Server stopped")
    }

    companion object {
        private const val TAG = "L2capServer"
        const val SERVICE_NAME = "BlueNetTether"
        val SERVICE_UUID: UUID = UUID.fromString("8ce255c0-223a-11ee-be56-0242ac120002")
    }
}
