package com.example.adaptapp.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.adaptapp.connection.BluetoothSppManager
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionMode
import com.example.adaptapp.connection.ConnectionState
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.ConnectionStatusBar
import com.example.adaptapp.ui.component.EmergencyStopButton

// Recall Mode 主界面 — 位置列表 + 召回 + 急停
@Composable
fun RecallScreen(
    connection: ConnectionManager,
    controller: ArmController,
    repository: PositionRepository,
    currentMode: ConnectionMode = ConnectionMode.USB,
    btManager: BluetoothSppManager? = null,
    onSwitchMode: ((ConnectionMode) -> Unit)? = null,
    onEnterSetup: () -> Unit,
    onOpenDebug: () -> Unit
) {
    val connectionState by connection.connectionState.collectAsState()

    // 从 Repository 加载位置列表
    var positions by remember { mutableStateOf(repository.getAll()) }

    // 删除确认对话框
    var deleteTarget by remember { mutableStateOf<ArmPosition?>(null) }

    // 当前正在移动的位置名（用于 UI 反馈）
    var movingTo by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // === 标题 ===
            Text(
                text = "A.D.A.P.T.",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // === 连接状态栏（含 USB/BT 切换）===
            ConnectionStatusBar(
                connection = connection,
                currentMode = currentMode,
                btManager = btManager,
                onSwitchMode = onSwitchMode
            )
            Spacer(modifier = Modifier.height(12.dp))

            // === 位置列表标题 ===
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Saved Positions", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${positions.size} positions", fontSize = 14.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // === 位置列表 ===
            if (positions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No saved positions\nEnter Setup Mode to create one",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(positions, key = { it.name }) { position ->
                        PositionCard(
                            position = position,
                            isConnected = connectionState == ConnectionState.CONNECTED,
                            isMoving = movingTo == position.name,
                            onGo = {
                                movingTo = position.name
                                controller.moveTo(position)
                                // 简单延时清除 moving 状态（实际应等反馈）
                                movingTo = null
                            },
                            onDelete = {
                                deleteTarget = position
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === 功能按钮 ===
            Button(
                onClick = onEnterSetup,
                enabled = connectionState == ConnectionState.CONNECTED,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Enter Setup Mode", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 调试入口（小按钮）
            OutlinedButton(
                onClick = onOpenDebug,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Debug Console", fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // === 急停 ===
            EmergencyStopButton(onStop = { controller.emergencyStop() })
        }
    }

    // === 删除确认对话框 ===
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Position") },
            text = { Text("Delete \"${target.name}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        repository.delete(target.name)
                        positions = repository.getAll()
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// 位置卡片 — 显示名称 + Go 按钮 + 删除
@Composable
fun PositionCard(
    position: ArmPosition,
    isConnected: Boolean,
    isMoving: Boolean,
    onGo: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "Position ${position.name}" },
        colors = CardDefaults.cardColors(
            containerColor = if (isMoving) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 位置名称
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    position.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                // 简要显示角度值
                Text(
                    buildString {
                        append("b:%.2f s:%.2f e:%.2f".format(position.b, position.s, position.e))
                        position.tilt?.let { append(" tilt:%.0f°".format(it)) }
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // Go 按钮
            Button(
                onClick = onGo,
                enabled = isConnected,
                modifier = Modifier
                    .height(44.dp)
                    .semantics { contentDescription = "Go to ${position.name}" }
            ) {
                Text("Go", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 删除按钮
            TextButton(onClick = onDelete) {
                Text("X", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
            }
        }
    }
}
