package com.example.adaptapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.connection.UsbSerialManager
import com.example.adaptapp.ui.theme.ADAPTAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbSerialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            0
        )

        usbManager = UsbSerialManager(this)

        setContent {
            ADAPTAppTheme {
                ConnectionTestScreen(usbManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.disconnect()
    }
}

// 连接测试界面 — Step 1 临时界面，验证 USB 通信链路
@Composable
fun ConnectionTestScreen(connection: ConnectionManager) {
    val context = LocalContext.current
    val connectionState by connection.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    // 日志列表（显示发送/接收的消息）
    val logMessages = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    // 自定义命令输入
    var customCommand by remember { mutableStateOf("{\"T\":105}") }

    // 设置接收回调
    LaunchedEffect(Unit) {
        connection.setOnReceiveCallback { message ->
            logMessages.add("<<< $message")
        }
    }

    // 日志更新时自动滚到底部
    LaunchedEffect(logMessages.size) {
        if (logMessages.isNotEmpty()) {
            listState.animateScrollToItem(logMessages.size - 1)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // === 标题 + 连接状态 ===
            Text(
                text = "A.D.A.P.T.",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "USB Connection Test",
                fontSize = 16.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // === 连接状态指示器 ===
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                val (statusColor, statusText) = when (connectionState) {
                    ConnectionState.DISCONNECTED -> Color.Red to "Disconnected"
                    ConnectionState.CONNECTING -> Color.Yellow to "Connecting..."
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(statusText, fontSize = 16.sp)
            }

            // === 连接/断开按钮 ===
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        logMessages.add("--- Connecting USB...")
                        connection.connect()
                    },
                    enabled = connectionState == ConnectionState.DISCONNECTED,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Connect USB")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        connection.disconnect()
                        logMessages.add("--- Disconnected")
                    },
                    enabled = connectionState == ConnectionState.CONNECTED,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === 快捷命令按钮 ===
            Text("Quick Commands", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                QuickCommandButton("Feedback\nT:105", "{\"T\":105}", connection, logMessages,
                    Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                QuickCommandButton("Torque ON\nT:210", "{\"T\":210,\"cmd\":1}", connection, logMessages,
                    Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                QuickCommandButton("Torque OFF\nT:210", "{\"T\":210,\"cmd\":0}", connection, logMessages,
                    Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === 自定义命令输入 ===
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    label = { Text("JSON Command") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (customCommand.isNotBlank()) {
                            logMessages.add(">>> $customCommand")
                            connection.send(customCommand)
                        }
                    },
                    enabled = connectionState == ConnectionState.CONNECTED
                ) {
                    Text("Send")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === 日志区域 ===
            Text("Log", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(logMessages) { msg ->
                    val textColor = when {
                        msg.startsWith(">>>") -> Color(0xFF82AAFF)   // 发送 = 蓝色
                        msg.startsWith("<<<") -> Color(0xFFC3E88D)   // 接收 = 绿色
                        msg.contains("[ERROR]") -> Color(0xFFFF5370) // 错误 = 红色
                        else -> Color(0xFFB0BEC5)                    // 其他 = 灰色
                    }
                    Text(
                        text = msg,
                        color = textColor,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === 急停按钮（始终可见） ===
            Button(
                onClick = {
                    logMessages.add(">>> {\"T\":0}  [EMERGENCY STOP]")
                    connection.send("{\"T\":0}")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .semantics { contentDescription = "Emergency stop button" }
            ) {
                Text(
                    "EMERGENCY STOP",
                    fontSize = 22.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// 快捷命令按钮组件
@Composable
fun QuickCommandButton(
    label: String,
    command: String,
    connection: ConnectionManager,
    log: MutableList<String>,
    modifier: Modifier = Modifier
) {
    val connectionState by connection.connectionState.collectAsState()

    OutlinedButton(
        onClick = {
            log.add(">>> $command")
            connection.send(command)
        },
        enabled = connectionState == ConnectionState.CONNECTED,
        modifier = modifier.height(56.dp)
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
