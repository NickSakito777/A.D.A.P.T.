package com.example.adaptapp.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.util.UUID

// 蓝牙设备信息（用于 UI 显示）
data class BtDeviceInfo(
    val name: String,
    val address: String,
    val paired: Boolean
)

// 蓝牙 SPP 连接实现（经典蓝牙串口，连接 ESP32 的 "ADAPT_ARM"）
class BluetoothSppManager(private val context: Context) : ConnectionManager {

    companion object {
        // SPP (Serial Port Profile) 标准 UUID
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val TARGET_DEVICE_NAME = "ADAPT_ARM"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var socket: BluetoothSocket? = null
    private var readThread: Thread? = null
    private var receiveCallback: ((String) -> Unit)? = null

    // 扫描相关
    private var discoveryReceiver: BroadcastReceiver? = null
    private var discoveryCallback: ((BtDeviceInfo) -> Unit)? = null

    // --- 蓝牙适配器 ---
    @SuppressLint("MissingPermission")
    private fun getAdapter(): BluetoothAdapter? {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return btManager?.adapter
    }

    // --- 获取已配对设备列表 ---
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BtDeviceInfo> {
        val adapter = getAdapter() ?: return emptyList()
        return adapter.bondedDevices
            ?.map { BtDeviceInfo(it.name ?: "Unknown", it.address, paired = true) }
            ?.sortedByDescending { it.name == TARGET_DEVICE_NAME } // ADAPT_ARM 置顶
            ?: emptyList()
    }

    // --- 检查蓝牙是否可用且开启 ---
    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        val adapter = getAdapter() ?: return false
        return adapter.isEnabled
    }

    // --- 自动连接（从已配对设备查找 ADAPT_ARM）---
    @SuppressLint("MissingPermission")
    override fun connect() {
        val paired = getPairedDevices()
        val target = paired.find { it.name == TARGET_DEVICE_NAME }
        if (target != null) {
            connectToDevice(target.address)
        } else {
            // 未找到目标设备，通知 UI 弹出设备选择
            _connectionState.value = ConnectionState.DISCONNECTED
            receiveCallback?.invoke("[NEED_DEVICE_PICKER]")
        }
    }

    // --- 连接到指定蓝牙设备（通过 MAC 地址）---
    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING

        val adapter = getAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ConnectionState.DISCONNECTED
            receiveCallback?.invoke("[ERROR] Bluetooth is not available or not enabled / 蓝牙未开启")
            return
        }

        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        // 在后台线程中建立连接（BluetoothSocket.connect() 是阻塞调用）
        Thread {
            try {
                val deviceName = device.name ?: address
                receiveCallback?.invoke("[INFO] Connecting to $deviceName...")

                // 停止扫描以加快连接
                stopDiscovery()

                val btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket.connect()

                socket = btSocket
                _connectionState.value = ConnectionState.CONNECTED
                receiveCallback?.invoke("[INFO] Bluetooth connected: $deviceName")

                // 启动后台读取线程
                startReadThread(btSocket.inputStream)

            } catch (e: IOException) {
                _connectionState.value = ConnectionState.DISCONNECTED
                receiveCallback?.invoke("[ERROR] Bluetooth connection failed: ${e.message}")
            }
        }.start()
    }

    // --- 检查扫描所需权限 ---
    private fun checkScanPermissions(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: 需要 BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                return "[ERROR] BLUETOOTH_SCAN permission not granted.\n" +
                       "请在系统设置中授予蓝牙扫描权限。"
            }
        } else {
            // Android ≤11: 需要 ACCESS_FINE_LOCATION + 定位服务开启
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                return "[ERROR] Location permission not granted.\n" +
                       "蓝牙扫描需要位置权限，请在系统设置中授予。"
            }
            // 检查定位服务是否开启
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                                  locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!locationEnabled) {
                return "[ERROR] Location services are OFF.\n" +
                       "Android ≤11 蓝牙扫描需要开启定位服务。\n" +
                       "请打开手机的定位/GPS。"
            }
        }
        return null // 权限 OK
    }

    // --- 开始扫描附近蓝牙设备 ---
    @SuppressLint("MissingPermission")
    fun startDiscovery(onDeviceFound: (BtDeviceInfo) -> Unit) {
        val adapter = getAdapter()
        if (adapter == null || !adapter.isEnabled) {
            onDeviceFound(BtDeviceInfo("[ERROR] Bluetooth not enabled", "", false))
            onDeviceFound(BtDeviceInfo("[SCAN_DONE]", "", false))
            return
        }

        // 检查权限
        val permError = checkScanPermissions()
        if (permError != null) {
            onDeviceFound(BtDeviceInfo(permError, "", false))
            onDeviceFound(BtDeviceInfo("[SCAN_DONE]", "", false))
            return
        }

        discoveryCallback = onDeviceFound

        // 注册广播接收器
        if (discoveryReceiver == null) {
            discoveryReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            device?.let {
                                val name = it.name ?: return@let  // 忽略无名设备
                                discoveryCallback?.invoke(
                                    BtDeviceInfo(name, it.address, paired = false)
                                )
                            }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            discoveryCallback?.invoke(
                                BtDeviceInfo("[SCAN_DONE]", "", paired = false)
                            )
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            // ACTION_FOUND 是系统广播，必须用 RECEIVER_EXPORTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
        }

        // 如果正在扫描，先停止
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        val started = adapter.startDiscovery()
        if (!started) {
            onDeviceFound(BtDeviceInfo(
                "[ERROR] startDiscovery() failed. Check permissions & location.",
                "", false
            ))
            onDeviceFound(BtDeviceInfo("[SCAN_DONE]", "", false))
        }
    }

    // --- 停止扫描 ---
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        getAdapter()?.cancelDiscovery()
        discoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        discoveryReceiver = null
        discoveryCallback = null
    }

    override fun disconnect() {
        try {
            readThread?.interrupt()
            readThread = null

            socket?.close()
            socket = null

            _connectionState.value = ConnectionState.DISCONNECTED
            receiveCallback?.invoke("[INFO] Bluetooth disconnected")
        } catch (_: IOException) {
            // 关闭时忽略异常
        }
    }

    override fun send(json: String) {
        val s = socket
        if (s == null || _connectionState.value != ConnectionState.CONNECTED) {
            receiveCallback?.invoke("[ERROR] Not connected, cannot send / 未连接，无法发送")
            return
        }

        try {
            // ESP32 串口以换行符作为消息结束符
            s.outputStream.write((json + "\n").toByteArray())
            s.outputStream.flush()
        } catch (e: IOException) {
            receiveCallback?.invoke("[ERROR] Send failed: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun setOnReceiveCallback(callback: (String) -> Unit) {
        receiveCallback = callback
    }

    // 后台读取蓝牙数据，按换行符拆分 JSON 消息
    private fun startReadThread(inputStream: InputStream) {
        readThread = Thread {
            val buffer = ByteArray(1024)
            val lineBuffer = StringBuilder()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val bytes = inputStream.read(buffer)
                    if (bytes == -1) break

                    val text = String(buffer, 0, bytes)
                    lineBuffer.append(text)

                    // 按换行符分割完整的 JSON 消息
                    val content = lineBuffer.toString()
                    val lines = content.split("\n")

                    // 最后一段可能不完整，保留在缓冲区
                    lineBuffer.clear()
                    lineBuffer.append(lines.last())

                    // 分发完整的行
                    for (i in 0 until lines.size - 1) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty()) {
                            receiveCallback?.invoke(line)
                        }
                    }
                }
            } catch (e: IOException) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    receiveCallback?.invoke("[ERROR] Bluetooth disconnected: ${e.message}")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
}
