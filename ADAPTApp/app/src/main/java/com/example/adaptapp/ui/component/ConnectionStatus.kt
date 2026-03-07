package com.example.adaptapp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptapp.connection.BluetoothSppManager
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionMode
import com.example.adaptapp.connection.ConnectionState

// 连接状态栏 + USB/BT 切换 + 连接/断开按钮
@Composable
fun ConnectionStatusBar(
    connection: ConnectionManager,
    currentMode: ConnectionMode = ConnectionMode.USB,
    btManager: BluetoothSppManager? = null,
    onSwitchMode: ((ConnectionMode) -> Unit)? = null,
    onLog: (String) -> Unit = {}
) {
    val state by connection.connectionState.collectAsState()

    // 切换模式确认对话框
    var pendingMode by remember { mutableStateOf<ConnectionMode?>(null) }

    // 蓝牙搜索对话框（只在未配对时弹出）
    var showBtSearch by remember { mutableStateOf(false) }

    val (color, text) = when (state) {
        ConnectionState.DISCONNECTED -> Color.Red to "Disconnected"
        ConnectionState.CONNECTING -> Color(0xFFFFC107) to "Connecting..."
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Row 1: 模式切换 + 连接状态
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 左侧：USB/BT 模式切换
            if (onSwitchMode != null) {
                Row {
                    ModeButton(
                        label = "USB",
                        selected = currentMode == ConnectionMode.USB,
                        onClick = {
                            if (currentMode != ConnectionMode.USB) {
                                if (state == ConnectionState.CONNECTED) {
                                    pendingMode = ConnectionMode.USB
                                } else {
                                    onSwitchMode(ConnectionMode.USB)
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    ModeButton(
                        label = "BT",
                        selected = currentMode == ConnectionMode.BLUETOOTH,
                        onClick = {
                            if (currentMode != ConnectionMode.BLUETOOTH) {
                                if (state == ConnectionState.CONNECTED) {
                                    pendingMode = ConnectionMode.BLUETOOTH
                                } else {
                                    onSwitchMode(ConnectionMode.BLUETOOTH)
                                }
                            }
                        }
                    )
                }
            } else {
                // 不可切换时，只显示模式文字
                Text(
                    if (currentMode == ConnectionMode.USB) "USB" else "Bluetooth",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }

            // 右侧：状态指示灯 + 文字
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(5.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: 连接/断开按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (state == ConnectionState.DISCONNECTED) {
                Button(
                    onClick = {
                        if (currentMode == ConnectionMode.BLUETOOTH && btManager != null) {
                            // BT 模式：检查是否已配对 ADAPT_ARM
                            val paired = btManager.getPairedDevices()
                            val target = paired.find { it.name == BluetoothSppManager.TARGET_DEVICE_NAME }
                            if (target != null) {
                                // 已配对 → 直接连接
                                onLog("--- Connecting Bluetooth...")
                                btManager.connectToDevice(target.address)
                            } else {
                                // 未配对 → 弹搜索框
                                showBtSearch = true
                            }
                        } else {
                            // USB 模式：直接连接
                            onLog("--- Connecting USB...")
                            connection.connect()
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("Connect", fontSize = 13.sp)
                }
            } else if (state == ConnectionState.CONNECTED) {
                OutlinedButton(
                    onClick = {
                        connection.disconnect()
                        onLog("--- Disconnected")
                    },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("Disconnect", fontSize = 13.sp)
                }
            }
        }
    }

    // 切换确认对话框（当前已连接时弹出）
    pendingMode?.let { newMode ->
        val modeName = if (newMode == ConnectionMode.USB) "USB" else "Bluetooth"
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("Switch to $modeName?") },
            text = { Text("Current connection will be disconnected.\n当前连接将断开。") },
            confirmButton = {
                Button(onClick = {
                    connection.disconnect()
                    onSwitchMode?.invoke(newMode)
                    pendingMode = null
                }) { Text("Switch / 切换") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingMode = null }) {
                    Text("Cancel / 取消")
                }
            }
        )
    }

    // 蓝牙搜索对话框（只找 ADAPT_ARM）
    if (showBtSearch && btManager != null) {
        BluetoothDeviceDialog(
            btManager = btManager,
            onDeviceSelected = { address ->
                showBtSearch = false
                onLog("--- Connecting Bluetooth...")
                btManager.connectToDevice(address)
            },
            onDismiss = { showBtSearch = false }
        )
    }
}

// 模式切换按钮
@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 14.dp)
        ) {
            Text(label, fontSize = 12.sp)
        }
    }
}
