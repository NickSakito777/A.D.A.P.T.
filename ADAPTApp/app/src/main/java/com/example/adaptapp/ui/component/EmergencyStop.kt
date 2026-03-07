package com.example.adaptapp.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 急停按钮 — 始终可见，最高优先级
@Composable
fun EmergencyStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
        modifier = modifier
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
