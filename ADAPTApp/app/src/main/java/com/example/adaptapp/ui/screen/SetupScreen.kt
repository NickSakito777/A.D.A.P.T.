package com.example.adaptapp.ui.screen

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
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.controller.ArmController
import com.example.adaptapp.model.ArmPosition
import com.example.adaptapp.repository.PositionRepository
import com.example.adaptapp.ui.component.EmergencyStopButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Setup Mode 的步骤（对应 Python 工具选项 4 的流程）
enum class SetupStep {
    FOLDING,          // Step 1: 折叠到 torque closed
    DRAG_ARM,         // Step 2: 拖动机械臂到目标位置
    LOCKING,          // Step 3: 锁定中
    ADJUST_ROLL,      // Step 4: 手动调 Roll
    SAVE,             // Step 5: 输入名称并保存
    DONE              // Step 6: 完成
}

// Setup Mode 界面 — 引导用户完成位置设定流程
@Composable
fun SetupScreen(
    connection: ConnectionManager,
    controller: ArmController,
    repository: PositionRepository,
    onExit: () -> Unit
) {
    var step by remember { mutableStateOf(SetupStep.FOLDING) }
    var statusText by remember { mutableStateOf("") }
    var positionName by remember { mutableStateOf("") }
    var feedbackPosition by remember { mutableStateOf<ArmPosition?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 接收 ESP32 反馈
    var lastResponse by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        connection.setOnReceiveCallback { message ->
            lastResponse = message
        }
    }

    // Step 1: 自动开始折叠
    LaunchedEffect(Unit) {
        statusText = "Moving to fold position..."
        // 先移到 torque closed（T:120 直接模式）
        controller.moveDirectTo(
            ArmPosition("torque closed", b = 0.058, s = -0.060, e = 1.580, t = 3.137),
            speed = 600, acc = 20
        )
        // 等待机械臂到位
        delay(5000)
        // 松扭矩
        statusText = "Releasing torque..."
        controller.setTorque(false)
        delay(500)
        step = SetupStep.DRAG_ARM
        statusText = ""
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // === 标题 ===
            Text("Setup Mode", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Position Setup / 位置设定", fontSize = 14.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(12.dp))

            // === 步骤指示器 ===
            StepIndicator(step)
            Spacer(modifier = Modifier.height(16.dp))

            // === 主内容区（根据步骤显示不同内容）===
            Box(modifier = Modifier.weight(1f)) {
                when (step) {
                    SetupStep.FOLDING -> {
                        StepContent(
                            title = "Folding Arm...",
                            description = "Moving to safe fold position, then releasing torque.\n" +
                                    "先移动到安全折叠位置，再松开扭矩。",
                            showProgress = true
                        )
                    }

                    SetupStep.DRAG_ARM -> {
                        Column {
                            StepContent(
                                title = "Drag Arm to Position",
                                description = "Torque is OFF. Manually move the arm to your desired position.\n" +
                                        "扭矩已关闭。请手动拖动机械臂到目标位置。"
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    step = SetupStep.LOCKING
                                    scope.launch {
                                        // 锁定所有电机
                                        statusText = "Locking arm..."
                                        controller.setTorque(true)
                                        delay(500)
                                        // 单独解锁 Roll
                                        controller.setRollTorque(false)
                                        delay(300)
                                        step = SetupStep.ADJUST_ROLL
                                        statusText = ""
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Text(
                                    "Position Ready / 位置就绪",
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    SetupStep.LOCKING -> {
                        StepContent(
                            title = "Locking Arm...",
                            description = "Locking joints and releasing Roll for adjustment.\n" +
                                    "锁定关节，释放 Roll 以便调整。",
                            showProgress = true
                        )
                    }

                    SetupStep.ADJUST_ROLL -> {
                        Column {
                            StepContent(
                                title = "Adjust Phone Roll",
                                description = "Arm is LOCKED. Roll is FREE.\n" +
                                        "Rotate the phone to your desired orientation.\n\n" +
                                        "机械臂已锁定。Roll 已释放。\n" +
                                        "请手动旋转手机到需要的横竖屏方向。"
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { step = SetupStep.SAVE },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1565C0)
                                )
                            ) {
                                Text(
                                    "Roll Ready / Roll 就绪",
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    SetupStep.SAVE -> {
                        Column {
                            StepContent(
                                title = "Save Position",
                                description = "Enter a name for this position.\n" +
                                        "请输入位置名称。"
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = positionName,
                                onValueChange = { positionName = it; saveError = null },
                                label = { Text("Position Name / 位置名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            saveError?.let {
                                Text(it, color = Color.Red, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    val name = positionName.trim()
                                    if (name.isEmpty()) {
                                        saveError = "Name cannot be empty / 名称不能为空"
                                        return@Button
                                    }

                                    scope.launch {
                                        statusText = "Reading position..."
                                        // 读取当前位置
                                        lastResponse = ""
                                        controller.readFeedback()
                                        // 等待 ESP32 返回
                                        delay(1000)

                                        val parsed = ArmController.parseFeedback(lastResponse)
                                        if (parsed != null) {
                                            val position = parsed.copy(name = name)
                                            repository.save(position)
                                            feedbackPosition = position
                                            // 恢复 Roll 扭矩
                                            controller.setRollTorque(true)
                                            step = SetupStep.DONE
                                            statusText = ""
                                        } else {
                                            saveError = "Failed to read position / 读取位置失败，请重试"
                                            statusText = ""
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Text(
                                    "Save / 保存",
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    SetupStep.DONE -> {
                        Column {
                            StepContent(
                                title = "Position Saved!",
                                description = "\"${feedbackPosition?.name}\" has been saved successfully.\n" +
                                        "位置已保存成功。"
                            )

                            // 显示保存的角度值
                            feedbackPosition?.let { pos ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        AngleRow("Base", "%.4f rad".format(pos.b))
                                        AngleRow("Shoulder", "%.4f rad".format(pos.s))
                                        AngleRow("Elbow", "%.4f rad".format(pos.e))
                                        AngleRow("Hand", "%.4f rad".format(pos.t))
                                        pos.p?.let { AngleRow("Roll", "%.2f°".format(it)) }
                                        pos.tilt?.let { AngleRow("Tilt", "%.2f°".format(it)) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // 继续保存 or 退出
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = {
                                        // 重新进入 Setup 流程
                                        step = SetupStep.FOLDING
                                        positionName = ""
                                        feedbackPosition = null
                                        saveError = null
                                        scope.launch {
                                            statusText = "Moving to fold position..."
                                            controller.moveDirectTo(
                                                ArmPosition("torque closed",
                                                    b = 0.058, s = -0.060, e = 1.580, t = 3.137),
                                                speed = 600, acc = 20
                                            )
                                            delay(5000)
                                            statusText = "Releasing torque..."
                                            controller.setTorque(false)
                                            delay(500)
                                            step = SetupStep.DRAG_ARM
                                            statusText = ""
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Save Another\n再保存一个", fontSize = 12.sp, lineHeight = 15.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onExit,
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Done / 完成", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }

            // === 状态文字 ===
            if (statusText.isNotEmpty()) {
                Text(
                    statusText,
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // === 底部按钮 ===
            Row(modifier = Modifier.fillMaxWidth()) {
                // 取消/退出
                OutlinedButton(
                    onClick = {
                        // 恢复扭矩后退出
                        controller.setTorque(true)
                        controller.setRollTorque(true)
                        onExit()
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Cancel / 取消")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // === 急停（始终可见）===
            EmergencyStopButton(onStop = { controller.emergencyStop() })
        }
    }
}

// 步骤指示器
@Composable
fun StepIndicator(current: SetupStep) {
    val steps = listOf(
        "Fold" to SetupStep.FOLDING,
        "Drag" to SetupStep.DRAG_ARM,
        "Lock" to SetupStep.LOCKING,
        "Roll" to SetupStep.ADJUST_ROLL,
        "Save" to SetupStep.SAVE,
        "Done" to SetupStep.DONE
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        steps.forEach { (label, step) ->
            val isActive = current == step
            val isPast = current.ordinal > step.ordinal
            val color = when {
                isActive -> Color(0xFF1565C0)
                isPast -> Color(0xFF4CAF50)
                else -> Color(0xFFBDBDBD)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(color, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isPast) "✓" else "${step.ordinal + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(label, fontSize = 10.sp, color = color)
            }
        }
    }
}

// 步骤内容块
@Composable
fun StepContent(
    title: String,
    description: String,
    showProgress: Boolean = false
) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(description, fontSize = 14.sp, color = Color(0xFF616161), lineHeight = 20.sp)
        if (showProgress) {
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// 角度值显示行
@Composable
fun AngleRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = Color.Gray)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
