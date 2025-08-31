
package cl.dga.aforo.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class BluetoothClient {
    private val sppUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

    val lines = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)

    fun getPairedDevices(): Set<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptySet()
        return adapter.bondedDevices ?: emptySet()
    }

    suspend fun connectTo(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        disconnect()
        val sock = device.createRfcommSocketToServiceRecord(sppUUID)
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        sock.connect()
        socket = sock
        val reader = BufferedReader(InputStreamReader(sock.inputStream))
        kotlin.runCatching {
            var line: String?
            while (sock.isConnected && reader.readLine().also { line = it } != null) {
                line?.let { lines.emit(it) }
            }
        }
    }

    suspend fun send(cmd: String) = withContext(Dispatchers.IO) {
        socket?.outputStream?.write((cmd + "
").toByteArray())
        socket?.outputStream?.flush()
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    fun disconnect() { kotlin.runCatching { socket?.close() }; socket = null }
}
