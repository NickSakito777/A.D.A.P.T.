package com.example.adaptapp.connection

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.Executors

// USB 有线连接实现（通过 USB OTG 连接 ESP32，115200 baud）
class UsbSerialManager(private val context: Context) : ConnectionManager {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.adaptapp.USB_PERMISSION"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var receiveCallback: ((String) -> Unit)? = null

    // 接收缓冲区（ESP32 可能分多次发送一条 JSON）
    private val receiveBuffer = StringBuilder()

    // USB 权限请求结果广播接收器
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                if (granted && device != null) {
                    receiveCallback?.invoke("[INFO] USB 权限已授予，正在连接...")
                    openConnection(device)
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    receiveCallback?.invoke("[ERROR] USB 权限被拒绝")
                }
            }
        }
    }

    private var receiverRegistered = false

    override fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED) return

        _connectionState.value = ConnectionState.CONNECTING

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            receiveCallback?.invoke("[ERROR] 未找到 USB 设备，请检查 USB 连接")
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (usbManager.hasPermission(device)) {
            // 已有权限，直接连接
            openConnection(device)
        } else {
            // 请求 USB 权限（系统会弹出授权对话框）
            receiveCallback?.invoke("[INFO] 正在请求 USB 权限...")
            registerReceiver()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    // 拿到权限后打开串口连接
    private fun openConnection(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = availableDrivers.find { it.device == device } ?: run {
                _connectionState.value = ConnectionState.DISCONNECTED
                receiveCallback?.invoke("[ERROR] USB 设备丢失")
                return
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                receiveCallback?.invoke("[ERROR] 无法打开 USB 设备")
                return
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // 设置 DTR 和 RTS（部分 ESP32 开发板需要）
            port.dtr = true
            port.rts = true

            serialPort = port

            // 启动后台读取线程
            val listener = object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val text = String(data)
                    receiveBuffer.append(text)

                    // 按换行符分割完整的 JSON 消息
                    val content = receiveBuffer.toString()
                    val lines = content.split("\n")

                    // 最后一段可能不完整，保留在缓冲区
                    receiveBuffer.clear()
                    receiveBuffer.append(lines.last())

                    // 分发完整的行
                    for (i in 0 until lines.size - 1) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty()) {
                            receiveCallback?.invoke(line)
                        }
                    }
                }

                override fun onRunError(e: Exception) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    receiveCallback?.invoke("[ERROR] USB 连接断开: ${e.message}")
                }
            }

            ioManager = SerialInputOutputManager(port, listener).also {
                Executors.newSingleThreadExecutor().submit(it)
            }

            _connectionState.value = ConnectionState.CONNECTED
            receiveCallback?.invoke("[INFO] USB 已连接: ${device.deviceName}")

        } catch (e: IOException) {
            _connectionState.value = ConnectionState.DISCONNECTED
            receiveCallback?.invoke("[ERROR] USB 连接失败: ${e.message}")
        }
    }

    override fun disconnect() {
        try {
            ioManager?.listener = null
            ioManager?.stop()
            ioManager = null

            serialPort?.close()
            serialPort = null

            receiveBuffer.clear()
            unregisterReceiver()
            _connectionState.value = ConnectionState.DISCONNECTED
            receiveCallback?.invoke("[INFO] USB 已断开")
        } catch (e: IOException) {
            // 关闭时忽略异常
        }
    }

    override fun send(json: String) {
        val port = serialPort
        if (port == null || _connectionState.value != ConnectionState.CONNECTED) {
            receiveCallback?.invoke("[ERROR] 未连接，无法发送")
            return
        }

        try {
            // ESP32 串口以换行符作为消息结束符
            val data = (json + "\n").toByteArray()
            port.write(data, 1000) // 1 秒超时
        } catch (e: IOException) {
            receiveCallback?.invoke("[ERROR] 发送失败: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun setOnReceiveCallback(callback: (String) -> Unit) {
        receiveCallback = callback
    }

    private fun registerReceiver() {
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbPermissionReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
    }
}
