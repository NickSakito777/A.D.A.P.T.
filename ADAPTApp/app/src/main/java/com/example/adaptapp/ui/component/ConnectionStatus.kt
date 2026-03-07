package com.example.adaptapp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.adaptapp.connection.ConnectionManager
import com.example.adaptapp.connection.ConnectionState

// 连接状态栏 + 连接/断开按钮
@Composable
fun ConnectionStatusBar(
    connection: ConnectionManager,
    onLog: (String) -> Unit = {}
) {
    val state by connection.connectionState.collectAsState()

    val (color, text) = when (state) {
        ConnectionState.DISCONNECTED -> Color.Red to "Disconnected"
        ConnectionState.CONNECTING -> Color(0xFFFFC107) to "Connecting..."
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) to "Connected"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 状态指示灯 + 文字
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, RoundedCornerShape(5.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("USB: $text", fontSize = 14.sp)
        }

        // 连接/断开按钮
        if (state == ConnectionState.DISCONNECTED) {
            TextButton(onClick = {
                onLog("--- Connecting USB...")
                connection.connect()
            }) {
                Text("Connect")
            }
        } else if (state == ConnectionState.CONNECTED) {
            TextButton(onClick = {
                connection.disconnect()
                onLog("--- Disconnected")
            }) {
                Text("Disconnect")
            }
        }
    }
}
