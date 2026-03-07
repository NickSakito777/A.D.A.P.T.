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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.connection.UsbSerialManager
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.screen.RecallScreen
import com.example.adaptapp.ui.theme.ADAPTAppTheme

// 当前显示哪个页面
enum class Screen { RECALL, DEBUG }

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbSerialManager
    private lateinit var armController: ArmController
    private lateinit var positionRepository: PositionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            0
        )

        usbManager = UsbSerialManager(this)
        armController = ArmController(usbManager)
        positionRepository = PositionRepository(this)

        setContent {
            ADAPTAppTheme {
                var currentScreen by remember { mutableStateOf(Screen.RECALL) }

                when (currentScreen) {
                    Screen.RECALL -> RecallScreen(
                        connection = usbManager,
                        controller = armController,
                        repository = positionRepository,
                        onEnterSetup = {
                            // TODO: Step 4 — Setup Mode
                        },
                        onOpenDebug = { currentScreen = Screen.DEBUG }
                    )
                    Screen.DEBUG -> DebugConsoleScreen(
                        connection = usbManager,
                        onBack = { currentScreen = Screen.RECALL }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.disconnect()
    }
}

// === Debug Console（原测试界面，保留作为调试工具）===
@Composable
fun DebugConsoleScreen(
    connection: ConnectionManager,
    onBack: () -> Unit
) {
    val connectionState by connection.connectionState.collectAsState()
    val logMessages = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var customCommand by remember { mutableStateOf("{\"T\":105}") }

    LaunchedEffect(Unit) {
        connection.setOnReceiveCallback { message ->
            logMessages.add("<<< $message")
        }
    }

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
            // 返回按钮 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("< Back") }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Debug Console", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 连接状态
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                val (color, text) = when (connectionState) {
                    ConnectionState.DISCONNECTED -> Color.Red to "Disconnected"
                    ConnectionState.CONNECTING -> Color(0xFFFFC107) to "Connecting..."
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
                }
                Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text, fontSize = 14.sp)
            }

            // 连接/断开
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { logMessages.add("--- Connecting USB..."); connection.connect() },
                    enabled = connectionState == ConnectionState.DISCONNECTED,
                    modifier = Modifier.weight(1f)
                ) { Text("Connect") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { connection.disconnect(); logMessages.add("--- Disconnected") },
                    enabled = connectionState == ConnectionState.CONNECTED,
                    modifier = Modifier.weight(1f)
                ) { Text("Disconnect") }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 快捷命令
            Row(modifier = Modifier.fillMaxWidth()) {
                QuickBtn("T:105\nFeedback", "{\"T\":105}", connection, logMessages, Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                QuickBtn("T:210\nTorque ON", "{\"T\":210,\"cmd\":1}", connection, logMessages, Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                QuickBtn("T:210\nTorque OFF", "{\"T\":210,\"cmd\":0}", connection, logMessages, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 自定义命令
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    label = { Text("JSON") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { logMessages.add(">>> $customCommand"); connection.send(customCommand) },
                    enabled = connectionState == ConnectionState.CONNECTED
                ) { Text("Send") }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 日志
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(logMessages) { msg ->
                    val c = when {
                        msg.startsWith(">>>") -> Color(0xFF82AAFF)
                        msg.startsWith("<<<") -> Color(0xFFC3E88D)
                        msg.contains("[ERROR]") -> Color(0xFFFF5370)
                        else -> Color(0xFFB0BEC5)
                    }
                    Text(msg, color = c, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 急停
            Button(
                onClick = { logMessages.add(">>> {\"T\":0} [EMERGENCY STOP]"); connection.send("{\"T\":0}") },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .semantics { contentDescription = "Emergency stop button" }
            ) {
                Text("EMERGENCY STOP", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun QuickBtn(label: String, cmd: String, conn: ConnectionManager, log: MutableList<String>, modifier: Modifier) {
    val state by conn.connectionState.collectAsState()
    OutlinedButton(
        onClick = { log.add(">>> $cmd"); conn.send(cmd) },
        enabled = state == ConnectionState.CONNECTED,
        modifier = modifier.height(48.dp)
    ) { Text(label, fontSize = 10.sp, lineHeight = 13.sp) }
}
