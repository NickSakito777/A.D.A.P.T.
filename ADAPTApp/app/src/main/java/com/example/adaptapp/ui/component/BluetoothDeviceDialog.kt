package com.example.adaptapp.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.adaptapp.connection.BluetoothSppManager

// 搜索状态
private enum class ScanState {
    SCANNING,   // 扫描中
    FOUND,      // 找到 ADAPT_ARM
    NOT_FOUND,  // 超时未找到
    ERROR,      // 权限/定位错误
    BT_OFF      // 蓝牙未开启
}

// 蓝牙搜索对话框 — 只找 "ADAPT_ARM"，其他全过滤
@Composable
fun BluetoothDeviceDialog(
    btManager: BluetoothSppManager,
    onDeviceSelected: (address: String) -> Unit,
    onDismiss: () -> Unit
) {
    var scanState by remember { mutableStateOf(ScanState.SCANNING) }
    var foundAddress by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // 蓝牙是否可用
    val btEnabled = remember { btManager.isBluetoothEnabled() }
    if (!btEnabled) {
        scanState = ScanState.BT_OFF
    }

    // 扫描回调处理
    fun handleScanResult(name: String, address: String) {
        when {
            name == "[SCAN_DONE]" -> {
                // 扫描结束
                if (scanState == ScanState.SCANNING) {
                    scanState = ScanState.NOT_FOUND
                }
            }
            name.startsWith("[ERROR]") -> {
                // 权限或其他错误
                errorMessage = name.removePrefix("[ERROR] ")
                scanState = ScanState.ERROR
            }
            name == BluetoothSppManager.TARGET_DEVICE_NAME -> {
                // 找到目标
                foundAddress = address
                scanState = ScanState.FOUND
                btManager.stopDiscovery()
            }
            // 其他设备 → 忽略
        }
    }

    // 启动扫描的函数
    fun doStartScan() {
        scanState = ScanState.SCANNING
        errorMessage = ""
        btManager.startDiscovery { info ->
            handleScanResult(info.name, info.address)
        }
    }

    // 首次打开自动扫描
    DisposableEffect(Unit) {
        if (btEnabled) {
            btManager.startDiscovery { info ->
                handleScanResult(info.name, info.address)
            }
        }
        onDispose { btManager.stopDiscovery() }
    }

    Dialog(onDismissRequest = {
        btManager.stopDiscovery()
        onDismiss()
    }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (scanState) {
                    ScanState.BT_OFF -> {
                        Text("Bluetooth Off", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Please enable Bluetooth in system settings.\n蓝牙未开启，请在系统设置中开启。",
                            fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Close / 关闭")
                        }
                    }

                    ScanState.ERROR -> {
                        Text("Scan Failed", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                        Text("扫描失败", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            errorMessage,
                            fontSize = 14.sp, color = Color(0xFF616161), textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { doStartScan() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry / 重试")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel / 取消")
                        }
                    }

                    ScanState.SCANNING -> {
                        Text("Searching...", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("搜索中...", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(20.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = Color(0xFF1565C0)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Looking for \"${BluetoothSppManager.TARGET_DEVICE_NAME}\"...\n" +
                            "正在搜索 \"${BluetoothSppManager.TARGET_DEVICE_NAME}\"...",
                            fontSize = 14.sp, color = Color(0xFF616161), textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = { btManager.stopDiscovery(); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel / 取消") }
                    }

                    ScanState.FOUND -> {
                        Text(
                            "ADAPT_ARM Found!",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50)
                        )
                        Text("已找到 ADAPT_ARM!", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(foundAddress, fontSize = 12.sp, color = Color(0xFF9E9E9E))
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { onDeviceSelected(foundAddress) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                        ) {
                            Text("Connect / 连接", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel / 取消")
                        }
                    }

                    ScanState.NOT_FOUND -> {
                        Text("Not Found", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                        Text("未找到", fontSize = 13.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "\"${BluetoothSppManager.TARGET_DEVICE_NAME}\" was not found nearby.\n" +
                            "Make sure the arm is powered on.\n\n" +
                            "附近未找到 \"${BluetoothSppManager.TARGET_DEVICE_NAME}\"。\n" +
                            "请确认机械臂已开机。",
                            fontSize = 14.sp, color = Color(0xFF616161), textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { doStartScan() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry / 重试")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel / 取消")
                        }
                    }
                }
            }
        }
    }
}
