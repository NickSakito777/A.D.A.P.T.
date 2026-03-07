package com.example.adaptapp

import android.Manifest
import android.os.Build
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
import com.example.adaptapp.connection.BluetoothSppManager
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionMode
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.connection.UsbSerialManager
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.screen.RecallScreen
import com.example.adaptapp.ui.screen.SetupScreen
import com.example.adaptapp.ui.theme.ADAPTAppTheme

// 当前显示哪个页面
enum class Screen { RECALL, SETUP, DEBUG }

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbSerialManager
    private lateinit var btManager: BluetoothSppManager
    private lateinit var armController: ArmController
    private lateinit var positionRepository: PositionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求权限：录音 + 蓝牙 + 位置（蓝牙扫描需要）
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION  // BT 扫描需要（所有 Android 版本）
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)

        usbManager = UsbSerialManager(this)
        btManager = BluetoothSppManager(this)
        armController = ArmController(usbManager) // 默认 USB
        positionRepository = PositionRepository(this)

        setContent {
            ADAPTAppTheme {
                var currentScreen by remember { mutableStateOf(Screen.RECALL) }
                var activeMode by remember { mutableStateOf(ConnectionMode.USB) }

                // 活跃连接（根据模式切换）
                val activeConnection: ConnectionManager = when (activeMode) {
                    ConnectionMode.USB -> usbManager
                    ConnectionMode.BLUETOOTH -> btManager
                }

                // 模式切换时更新 ArmController 的连接引用
                LaunchedEffect(activeMode) {
                    armController.connection = activeConnection
                }

                // 模式切换回调
                val onSwitchMode: (ConnectionMode) -> Unit = { newMode ->
                    activeMode = newMode
                }

                when (currentScreen) {
                    Screen.RECALL -> RecallScreen(
                        connection = activeConnection,
                        controller = armController,
                        repository = positionRepository,
                        currentMode = activeMode,
                        btManager = btManager,
                        onSwitchMode = onSwitchMode,
                        onEnterSetup = { currentScreen = Screen.SETUP },
                        onOpenDebug = { currentScreen = Screen.DEBUG }
                    )
                    Screen.SETUP -> SetupScreen(
                        connection = activeConnection,
                        controller = armController,
                        repository = positionRepository,
                        onExit = { currentScreen = Screen.RECALL }
                    )
                    Screen.DEBUG -> DebugConsoleScreen(
                        connection = activeConnection,
                        btManager = btManager,
                        currentMode = activeMode,
                        onSwitchMode = onSwitchMode,
                        onBack = { currentScreen = Screen.RECALL }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbManager.disconnect()
        btManager.disconnect()
    }
}

// === Debug Console（对齐 Python 工具菜单结构）===
@Composable
fun DebugConsoleScreen(
    connection: ConnectionManager,
    btManager: BluetoothSppManager? = null,
    currentMode: ConnectionMode = ConnectionMode.USB,
    onSwitchMode: ((ConnectionMode) -> Unit)? = null,
    onBack: () -> Unit
) {
    val connectionState by connection.connectionState.collectAsState()
    val logMessages = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var customCommand by remember { mutableStateOf("{\"T\":105}") }
    val connected = connectionState == ConnectionState.CONNECTED
    var showBtPicker by remember { mutableStateOf(false) }

    // 发送命令的辅助函数
    fun sendCmd(label: String, json: String) {
        logMessages.add(">>> [$label] $json")
        connection.send(json)
    }

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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // === 顶部：返回 + 标题 + 连接状态 ===
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("< Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Debug Console", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                val (color, text) = when (connectionState) {
                    ConnectionState.DISCONNECTED -> Color.Red to "OFF"
                    ConnectionState.CONNECTING -> Color(0xFFFFC107) to "..."
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "ON"
                }
                Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text, fontSize = 12.sp)
            }

            // === 模式切换 + 连接/断开 ===
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                // 模式切换按钮
                if (onSwitchMode != null) {
                    OutlinedButton(
                        onClick = {
                            val newMode = if (currentMode == ConnectionMode.USB)
                                ConnectionMode.BLUETOOTH else ConnectionMode.USB
                            if (connected) {
                                connection.disconnect()
                                logMessages.add("--- Disconnected for mode switch")
                            }
                            onSwitchMode(newMode)
                            logMessages.add("--- Mode: ${if (newMode == ConnectionMode.USB) "USB" else "Bluetooth"}")
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            if (currentMode == ConnectionMode.USB) "USB" else "BT",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Button(
                    onClick = {
                        if (currentMode == ConnectionMode.BLUETOOTH && btManager != null) {
                            // 检查是否已配对 ADAPT_ARM
                            val paired = btManager.getPairedDevices()
                            val target = paired.find { it.name == BluetoothSppManager.TARGET_DEVICE_NAME }
                            if (target != null) {
                                logMessages.add("--- Connecting Bluetooth...")
                                btManager.connectToDevice(target.address)
                            } else {
                                showBtPicker = true
                            }
                        } else {
                            logMessages.add("--- Connecting USB...")
                            connection.connect()
                        }
                    },
                    enabled = connectionState == ConnectionState.DISCONNECTED,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text("Connect", fontSize = 13.sp) }
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { connection.disconnect(); logMessages.add("--- Disconnected") },
                    enabled = connected,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text("Disconnect", fontSize = 13.sp) }
            }

            // === 滚动命令区域 ===
            LazyColumn(modifier = Modifier.weight(1f), state = listState) {

                // --- 臂控制 / Arm Control ---
                item {
                    SectionHeader("Arm Control")
                    CmdRow {
                        CmdBtn("Read Pos\nT:105", connected) { sendCmd("Feedback", "{\"T\":105}") }
                        CmdBtn("Torque ON\nT:210", connected) { sendCmd("Torque ON", "{\"T\":210,\"cmd\":1}") }
                        CmdBtn("Torque OFF\nT:210", connected) { sendCmd("Torque OFF", "{\"T\":210,\"cmd\":0}") }
                    }
                    CmdRow {
                        CmdBtn("Init Pos\nT:100", connected) { sendCmd("Init", "{\"T\":100}") }
                        CmdBtn("E-Stop\nT:0", connected, color = Color(0xFFE53935)) { sendCmd("STOP", "{\"T\":0}") }
                    }
                }

                // --- Phone Roll / 手机横竖旋转 ---
                item {
                    SectionHeader("Phone Roll (ID16)")
                    CmdRow {
                        CmdBtn("0° Portrait", connected) { sendCmd("Roll 0°", "{\"T\":700,\"angle\":0}") }
                        CmdBtn("90° Land", connected) { sendCmd("Roll 90°", "{\"T\":700,\"angle\":90}") }
                        CmdBtn("180° Inv", connected) { sendCmd("Roll 180°", "{\"T\":700,\"angle\":180}") }
                        CmdBtn("270° Inv", connected) { sendCmd("Roll 270°", "{\"T\":700,\"angle\":270}") }
                    }
                    CmdRow {
                        CmdBtn("Roll\nUnlock", connected) { sendCmd("Roll OFF", "{\"T\":702,\"cmd\":0}") }
                        CmdBtn("Roll\nLock", connected) { sendCmd("Roll ON", "{\"T\":702,\"cmd\":1}") }
                    }
                }

                // --- Phone Tilt / 手机俯仰 ---
                item {
                    SectionHeader("Phone Tilt (ID17) safe: 0-106 / 284-360")
                    CmdRow {
                        CmdBtn("Tilt 50°", connected) { sendCmd("Tilt 50", "{\"T\":703,\"angle\":50}") }
                        CmdBtn("Tilt 0°", connected) { sendCmd("Tilt 0", "{\"T\":703,\"angle\":0}") }
                        CmdBtn("Tilt 340°", connected) { sendCmd("Tilt 340", "{\"T\":703,\"angle\":340}") }
                    }
                    CmdRow {
                        CmdBtn("Tilt\nUnlock", connected) { sendCmd("Tilt OFF", "{\"T\":704,\"cmd\":0}") }
                        CmdBtn("Tilt\nLock", connected) { sendCmd("Tilt ON", "{\"T\":704,\"cmd\":1}") }
                    }
                }

                // --- 自定义命令 ---
                item {
                    SectionHeader("Custom Command")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        OutlinedTextField(
                            value = customCommand,
                            onValueChange = { customCommand = it },
                            label = { Text("JSON") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { sendCmd("Custom", customCommand) },
                            enabled = connected
                        ) { Text("Send") }
                    }
                }

                // --- 日志区域 ---
                item {
                    SectionHeader("Log")
                }
                items(logMessages) { msg ->
                    val c = when {
                        msg.startsWith(">>>") -> Color(0xFF82AAFF)
                        msg.startsWith("<<<") -> Color(0xFFC3E88D)
                        msg.contains("[ERROR]") -> Color(0xFFFF5370)
                        else -> Color(0xFFB0BEC5)
                    }
                    Text(
                        msg, color = c, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // === 急停（固定在底部） ===
            Button(
                onClick = { sendCmd("EMERGENCY STOP", "{\"T\":0}") },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .semantics { contentDescription = "Emergency stop button" }
            ) {
                Text("EMERGENCY STOP", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 蓝牙设备选择对话框
    if (showBtPicker && btManager != null) {
        com.example.adaptapp.ui.component.BluetoothDeviceDialog(
            btManager = btManager,
            onDeviceSelected = { address ->
                showBtPicker = false
                logMessages.add("--- Connecting Bluetooth...")
                btManager.connectToDevice(address)
            },
            onDismiss = { showBtPicker = false }
        )
    }
}

// === 辅助组件 ===

// 分区标题
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1565C0),
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

// 命令按钮行
@Composable
fun CmdRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

// 单个命令按钮
@Composable
fun RowScope.CmdBtn(
    label: String,
    enabled: Boolean,
    color: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (color != Color.Unspecified)
            ButtonDefaults.outlinedButtonColors(contentColor = color)
        else ButtonDefaults.outlinedButtonColors(),
        modifier = Modifier.weight(1f).height(44.dp)
    ) {
        Text(label, fontSize = 10.sp, lineHeight = 13.sp)
    }
}
