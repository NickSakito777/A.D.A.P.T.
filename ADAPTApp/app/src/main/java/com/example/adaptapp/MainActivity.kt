package com.example.adaptapp

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.adaptapp.ui.theme.ADAPTAppTheme
import java.util.*

data class ArmPosition(
    var name: String,
    var b: Double = 0.0,
    var s: Double = 0.0,
    var e: Double = 0.0,
    var t: Double = 0.0
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开启全屏显示模式（边到边设计）
        enableEdgeToEdge()

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            0
        )

        val defaultPositions = listOf(
            ArmPosition("High", 0.5, 0.5, 0.7, 3.14),
            ArmPosition("Low", 1.5, -0.1, 1.6, 3.14),
            ArmPosition("Init", -0.1, -0.33, 2.78, 2.87)
        )

        setContent {
            ADAPTAppTheme {
                MyPositionsScreen(defaultPositions.toMutableStateList())
            }
        }
    }
}

@Composable
fun MyPositionsScreen(positions: MutableList<ArmPosition>) {

    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedPosition by remember { mutableStateOf<ArmPosition?>(null) }
    var emergencyStopActivated by remember { mutableStateOf(false) }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
    }

    DisposableEffect(Unit) {

        speechRecognizer.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()?.lowercase() ?: return

                when {
                    spokenText.startsWith("move to") -> {
                        val name = spokenText.removePrefix("move to").trim()
                        positions.find { it.name.lowercase() == name }
                            ?.let {
                                // TODO: call arm move
                            }
                    }

                    spokenText.startsWith("delete") -> {
                        val name = spokenText.removePrefix("delete").trim()
                        positions.find { it.name.lowercase() == name }
                            ?.let {
                                positions.remove(it)
                            }
                    }

                    spokenText.startsWith("add position") -> {
                        val name = spokenText.removePrefix("add position").trim()
                        if (name.isNotBlank()) {
                            positions.add(ArmPosition(name))
                        }
                    }

                    spokenText.contains("stop") -> {
                        emergencyStopActivated = true
                        // TODO: call emergency stop
                    }
                }

                speechRecognizer.startListening(recognizerIntent)
            }

            override fun onError(error: Int) {
                speechRecognizer.startListening(recognizerIntent)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(recognizerIntent)

        onDispose {
            speechRecognizer.destroy()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {

            Text(
                text = "My Positions",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .semantics { contentDescription = "My saved arm positions" }
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(positions) { position ->
                    PositionRow(
                        position = position,
                        onMove = {
                            // TODO: move arm
                        },
                        onEdit = {
                            showAddDialog = true
                            selectedPosition = position
                        },
                        onDelete = {
                            positions.remove(position)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    showAddDialog = true
                    selectedPosition = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .semantics { contentDescription = "Add new arm position" }
            ) {
                Text("Add Position", fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    emergencyStopActivated = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .semantics { contentDescription = "Emergency stop button" }
            ) {
                Text(
                    "EMERGENCY STOP",
                    fontSize = 24.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showAddDialog) {

        var nameInput by remember { mutableStateOf(selectedPosition?.name ?: "") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(if (selectedPosition == null) "Add Position" else "Edit Position")
            },
            text = {
                Column {
                    Text("Enter position name:")
                    TextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text("Position Name") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (selectedPosition == null) {
                        positions.add(ArmPosition(nameInput))
                    } else {
                        selectedPosition!!.name = nameInput
                    }
                    showAddDialog = false
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                Button(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PositionRow(
    position: ArmPosition,
    onMove: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFFEFEFEF), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .semantics {
                contentDescription = "Position ${position.name}"
            }
    ) {
        Text(position.name, fontSize = 20.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onMove) { Text("Move") }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}