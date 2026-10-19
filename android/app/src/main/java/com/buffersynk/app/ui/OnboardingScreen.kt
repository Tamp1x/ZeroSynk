package com.buffersynk.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buffersynk.app.net.PairingCtl
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val status by PairingCtl.status.collectAsState()
    val scope = rememberCoroutineScope()
    var showManual by rememberSaveable { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(status) {
        if (status is PairingCtl.Status.Success) {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            delay(900)
            onPaired()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("⇄", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Text("ZeroSynk", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Буфер обмена и файлы между ПК и телефоном\nв одной локальной сети",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        when (val s = status) {
            is PairingCtl.Status.Working -> {
                CircularProgressIndicator(Modifier.size(34.dp))
                Spacer(Modifier.height(12.dp))
                Text("Подключение к ПК…", style = MaterialTheme.typography.bodyMedium)
            }

            is PairingCtl.Status.Success -> {
                Text("✓", fontSize = 40.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Подключено", style = MaterialTheme.typography.titleMedium)
            }

            is PairingCtl.Status.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { PairingCtl.reset(); showManual = false }) {
                    Text("Попробовать снова")
                }
                Spacer(Modifier.height(8.dp))
            }

            else -> Unit
        }

        if (status is PairingCtl.Status.Idle || status is PairingCtl.Status.Error) {
            SectionCard(modifier = Modifier.padding(top = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("1. На ПК откройте ZeroSynk", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "На вкладке «Главная» показан QR-код и 6-значный PIN-код.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("2. Подключите телефон", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))

                    if (!showManual) {
                        Button(
                            onClick = {
                                val activity = findActivity(context) ?: return@Button
                                IntentIntegrator(activity).apply {
                                    setDesiredBarcodeFormats(listOf("QR_CODE"))
                                    setPrompt("Наведите камеру на QR-код на экране ПК")
                                    setBeepEnabled(true)
                                    setBarcodeImageEnabled(false)
                                    initiateScan()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Сканировать QR-код")
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showManual = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Ввести адрес и PIN вручную")
                        }
                    } else {
                        ManualPairForm(onSubmit = { host, port, pin ->
                            scope.launch { PairingCtl.pairWithPin(context, host, port, pin) }
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ManualPairForm(onSubmit: (host: String, port: Int, pin: String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8765") }
    var pin by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("Адрес ПК") },
            placeholder = { Text("192.168.1.3") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Порт") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("PIN-код с ПК") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Button(
            onClick = {
                val p = port.toIntOrNull() ?: 8765
                onSubmit(host.trim(), p, pin)
            },
            enabled = host.isNotBlank() && pin.length == 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("Подключить")
        }
    }
}
