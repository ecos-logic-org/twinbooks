package org.ecos.logic.twinbooks.ui.screens.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ecos.logic.twinbooks.alignment.model.ServerStatus
import org.ecos.logic.twinbooks.alignment.repository.ServerConfig
import org.ecos.logic.twinbooks.alignment.repository.ServerSettingsStore
import org.ecos.logic.twinbooks.ui.screens.reader.serverStatusLook

/**
 * Optional TwinBooks server (paragraph alignment + translation). Without one the app
 * aligns and translates on the device, so nothing here is required to start reading.
 *
 * [testResult]: null = not tested yet, CHECKING = running, else the outcome.
 */
@Composable
fun ServerSettingsDialog(
    current: ServerConfig,
    status: ServerStatus,
    testResult: ServerStatus?,
    onTest: (ServerConfig) -> Unit,
    onEdited: () -> Unit,
    onSave: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var useServer by remember { mutableStateOf(current.isEnabled) }
    var url by remember { mutableStateOf(current.baseUrl) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var keyVisible by remember { mutableStateOf(false) }

    val normalizedUrl = ServerSettingsStore.normalizeBaseUrl(url)
    val edited = ServerConfig(normalizedUrl.orEmpty(), apiKey.trim(), enabled = useServer)
    val canSave = !useServer || normalizedUrl != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servidor TwinBooks") },
        text = {
            // Scrollable: in landscape with the keyboard up the fields don't fit otherwise
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Opcional. Sin servidor, TwinBooks alinea los párrafos de los dos libros y " +
                        "traduce en el propio dispositivo, también sin conexión. Un servidor " +
                        "TwinBooks (open source, puedes alojar el tuyo) mejora la alineación de " +
                        "párrafos y la traducción.",
                    fontSize = 13.sp,
                    color = Color(0xFFB0B0B0)
                )
                if (current.isEnabled) {
                    // Status of the saved server (the bookshelf only shows a coloured dot)
                    val (_, tint, label) = serverStatusLook(status)
                    Text(label, color = tint, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Usar un servidor", modifier = Modifier.weight(1f))
                    Switch(
                        checked = useServer,
                        onCheckedChange = { useServer = it; onEdited() }
                    )
                }
                if (useServer) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { typed ->
                            // A pasted twinbooks://connect link fills in the address and the key
                            val link = ServerSettingsStore.parseConnectLink(typed)
                            if (link != null) {
                                url = link.baseUrl
                                apiKey = link.apiKey
                            } else {
                                url = typed
                            }
                            onEdited()
                        },
                        label = { Text("Dirección del servidor") },
                        placeholder = { Text("https://twinbooks.example.org") },
                        supportingText = if (ServerSettingsStore.isInsecureUrl(url)) {
                            { Text("El servidor debe usar HTTPS") }
                        } else if (url.isNotBlank() && normalizedUrl == null) {
                            { Text("Dirección no válida") }
                        } else {
                            { Text("También puedes pegar aquí un enlace twinbooks://connect") }
                        },
                        singleLine = true,
                        isError = url.isNotBlank() && normalizedUrl == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; onEdited() },
                        label = { Text("Clave de API") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "Ocultar clave" else "Mostrar clave"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onTest(edited) },
                            enabled = normalizedUrl != null && testResult != ServerStatus.CHECKING
                        ) {
                            Text("Probar conexión")
                        }
                        TestResult(testResult)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // Off keeps the address and key, so turning it on again restores them
                onClick = { onSave(if (useServer) edited else edited.copy(baseUrl = normalizedUrl ?: current.baseUrl)) },
                enabled = canSave
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun TestResult(result: ServerStatus?) {
    when (result) {
        null -> Unit
        ServerStatus.CHECKING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        ServerStatus.ONLINE -> Text("Conectado, la clave es válida", color = Color(0xFF66BB6A), fontSize = 13.sp)
        ServerStatus.UNAUTHORIZED -> Text("El servidor rechaza la clave", color = Color(0xFFFFA726), fontSize = 13.sp)
        else -> Text("No se puede conectar", color = Color(0xFFEF5350), fontSize = 13.sp)
    }
}

/**
 * Confirmation for a twinbooks://connect link: never switch servers behind the user's back.
 * [testResult] is the connection test started when the link arrived.
 */
@Composable
fun ConnectServerDialog(
    config: ServerConfig,
    testResult: ServerStatus?,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Conectar con este servidor?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Un enlace quiere configurar TwinBooks para usar este servidor para alinear " +
                        "los párrafos y traducir:",
                    fontSize = 14.sp
                )
                Text(config.baseUrl, fontSize = 15.sp, color = Color(0xFF80CBC4))
                Text(
                    if (config.apiKey.isBlank()) "Sin clave de API." else "Incluye una clave de API.",
                    fontSize = 13.sp,
                    color = Color(0xFFB0B0B0)
                )
                Text(
                    "El texto de tus libros se enviará a ese servidor. Conéctate solo a servidores " +
                        "de confianza. Puedes cambiarlo cuando quieras con el botón ⚙️ de la estantería.",
                    fontSize = 13.sp,
                    color = Color(0xFFB0B0B0)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Prueba de conexión:", fontSize = 13.sp, color = Color(0xFFB0B0B0))
                    TestResult(testResult)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect) {
                Text("Conectar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
