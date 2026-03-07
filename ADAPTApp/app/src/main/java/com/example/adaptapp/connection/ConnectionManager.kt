package com.example.adaptapp.connection

import kotlinx.coroutines.flow.StateFlow

// 连接状态
enum class ConnectionState {
    DISCONNECTED,  // 未连接
    CONNECTING,    // 连接中
    CONNECTED      // 已连接
}

// 连接模式：USB 有线 / 蓝牙
enum class ConnectionMode {
    USB,
    BLUETOOTH
}

// 统一连接接口 — USB 和蓝牙各自实现
interface ConnectionManager {

    // 当前连接状态（可被 Compose UI 观察）
    val connectionState: StateFlow<ConnectionState>

    // 连接到 ESP32
    fun connect()

    // 断开连接
    fun disconnect()

    // 发送 JSON 命令（如 {"T":105}）
    fun send(json: String)

    // 设置数据接收回调（ESP32 返回的 JSON 响应）
    fun setOnReceiveCallback(callback: (String) -> Unit)
}
