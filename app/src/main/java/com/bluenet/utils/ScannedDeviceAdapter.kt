package com.bluenet.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bluenet.databinding.ItemScannedDeviceBinding

data class ScannedDevice(
    val device: BluetoothDevice,
    val rssi: Int
)

class ScannedDeviceAdapter(
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<ScannedDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<ScannedDevice>()

    @SuppressLint("MissingPermission")
    fun addOrUpdateDevice(device: BluetoothDevice, rssi: Int) {
        val index = devices.indexOfFirst { it.device.address == device.address }
        if (index >= 0) {
            devices[index] = ScannedDevice(device, rssi)
            notifyItemChanged(index)
        } else {
            devices.add(ScannedDevice(device, rssi))
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clear() {
        devices.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScannedDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(private val binding: ItemScannedDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("MissingPermission")
        fun bind(scannedDevice: ScannedDevice) {
            val dev = scannedDevice.device
            binding.tvDeviceName.text = dev.name ?: "Unknown Bluetooth Device"
            binding.tvDeviceAddress.text = dev.address
            binding.tvRssi.text = "${scannedDevice.rssi} dBm"

            binding.btnSelectDevice.setOnClickListener {
                onDeviceSelected(dev)
            }
            binding.root.setOnClickListener {
                onDeviceSelected(dev)
            }
        }
    }
}
