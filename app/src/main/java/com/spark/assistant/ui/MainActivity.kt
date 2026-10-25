package com.spark.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.menuAnchor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spark.assistant.assistant.AssistantSetup
import com.spark.assistant.engine.ActionDispatcher
import com.spark.assistant.engine.ActionMapping
import com.spark.assistant.engine.ActionMappingStore
import com.spark.assistant.engine.CommandParser
import com.spark.assistant.engine.ModelManager
import com.spark.assistant.engine.NeedleBridge
import com.spark.assistant.engine.VoiceService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    val mappings: MutableStateFlow<List<ActionMapping>> = MutableStateFlow(emptyList())
    val downloadProgress: MutableStateFlow<Float?> = MutableStateFlow(null)
    val log: MutableStateFlow<String> = MutableStateFlow("")

    fun refresh(context: Context) {
        viewModelScope.launch {
            mappings.value = ActionMappingStore.current(context)
            val w = ModelManager.whistleFile(context)
            val n = ModelManager.needleFile(context)
            log.value = "models: whistle=${mb(w)} needle=${mb(n)} " +
                "engine=${if (NeedleBridge.nativeAvailable) "loaded" else "not loaded"}"
        }
    }

    private fun mb(f: File): String = "${f.length() / 1048576}MB"

    fun download(context: Context) {
        viewModelScope.launch {
            downloadProgress.value = 0f
            try {
                val assets = context.assets.open("spark_tools.json").bufferedReader().readText()
                ModelManager.downloadMissing(context) { frac, _ -> downloadProgress.value = frac }
                val ok = withContext(Dispatchers.IO) {
                    ModelManager.ensureLoaded(context, assets)
                }
                log.value = if (ok) "engine loaded ✔" else "engine load FAILED"
            } catch (e: Exception) {
                log.value = "download failed: ${e.message}"
            } finally {
                downloadProgress.value = null
            }
            refresh(context)
        }
    }

    fun ensureEngine(context: Context) {
        viewModelScope.launch {
            if (NeedleBridge.nativeAvailable) return@launch
            try {
                val assets = context.assets.open("spark_tools.json").bufferedReader().readText()
                val ok = withContext(Dispatchers.IO) { ModelManager.ensureLoaded(context, assets) }
                log.value = if (ok) "engine loaded ✔" else "models missing — download first"
            } catch (e: Exception) {
                log.value = "engine load failed: ${e.message}"
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SparkScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SparkScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val mappings by vm.mappings.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val log by vm.log.collectAsState()
    var isDefault by remember { mutableStateOf(false) }
    var modelsReady by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var testPhrase by remember { mutableStateOf("hey spark play Bohemian Rhapsody") }
    var testResult by remember { mutableStateOf("") }

    val micPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.ensureEngine(context) else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = AssistantSetup.isDefaultAssistant(context)
    }

    LaunchedEffect(Unit) {
        isDefault = AssistantSetup.isDefaultAssistant(context)
        modelsReady = ModelManager.modelsReady(context)
        vm.refresh(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            vm.ensureEngine(context)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Spark — offline assistant", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Push-to-talk only: no always-on mic. Say \"Hey Spark\" " +
                    "out of habit if you like — it is stripped, never required.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("Assistant role: ${if (isDefault) "Spark is default ✔" else "not default"}")
                    Text("Models: ${if (modelsReady) "downloaded ✔" else "missing"}")
                    if (log.isNotBlank()) Text(log, style = MaterialTheme.typography.bodySmall)
                    if (progress != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text("  downloading… ${(progress!! * 100).toInt()}%")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.download(context) }) { Text("Download models") }
                        OutlinedButton(onClick = {
                            AssistantSetup.createRequestIntent(context)?.let {
                                roleLauncher.launch(it)
                            } ?: Toast.makeText(
                                context, "Assistant role not available on this device",
                                Toast.LENGTH_LONG,
                            ).show()
                        }) { Text("Set as assistant") }
                    }
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            micPerm.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            context.startForegroundService(
                                Intent(context, VoiceService::class.java)
                                    .setAction(VoiceService.ACTION_LISTEN_ONCE),
                            )
                        }
                    }) { Text("🎤 Listen once") }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Action mappings", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { showAdd = true }) { Text("+ Add") }
            }
            Text(
                "Keyword → installed app, e.g. keyword “tune” → your music player. " +
                    "Then “play jazz on tune” opens that app with your query.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (mappings.isEmpty()) {
            item { Text("No mappings yet — add one above.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(mappings, key = { it.keyword }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("“${m.keyword}” → ${m.label.ifBlank { m.packageName }}")
                            Text(
                                if (m.queryTemplate != null) "template: ${m.queryTemplate}" else "plain launch",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                ActionMappingStore.remove(context, m.keyword)
                                vm.refresh(context)
                            }
                        }) { Text("Remove") }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Try a phrase (offline, no models)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = testPhrase,
                        onValueChange = { testPhrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Phrase") },
                    )
                    Button(onClick = {
                        val call = CommandParser.parse(testPhrase, mappings)
                        testResult = call?.let { "✔ ${it.name} ${it.arguments}" }
                            ?: "no matching command"
                    }) { Text("Parse") }
                    if (testResult.isNotBlank()) Text(testResult)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        AddMappingDialog(
            onDismiss = { showAdd = false },
            onAdd = { mapping ->
                scope.launch {
                    ActionMappingStore.add(context, mapping)
                    vm.refresh(context)
                    showAdd = false
                }
            },
        )
    }
    activity?.let { } // keep activity reference for lint (role flow uses launcher)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMappingDialog(onDismiss: () -> Unit, onAdd: (ActionMapping) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<ActionDispatcher.InstalledApp>()) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ActionDispatcher.InstalledApp?>(null) }
    var keyword by remember { mutableStateOf("") }
    var withQuery by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { ActionDispatcher(context).installedApps() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Map keyword → app", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.lowercase().trim() },
                    label = { Text("Keyword (e.g. tune)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("App") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        apps.take(400).forEach { app ->
                            DropdownMenuItem(
                                text = { Text("${app.label} (${app.packageName})") },
                                onClick = { selected = app; expanded = false },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withQuery, onCheckedChange = { withQuery = it })
                    Text("Accepts play/search query")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = keyword.isNotBlank() && selected != null,
                        onClick = {
                            onAdd(
                                ActionMapping(
                                    keyword = keyword,
                                    packageName = selected!!.packageName,
                                    label = selected!!.label,
                                    queryTemplate = if (withQuery) "play {query}" else null,
                                ),
                            )
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}
