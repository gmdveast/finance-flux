package com.gmdveast.financeflux

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.gmdveast.financeflux.connectors.BoundConnectorClient
import com.gmdveast.financeflux.connectors.ConnectorDiscovery
import com.gmdveast.financeflux.connectors.ConnectorSyncCoordinator
import com.gmdveast.financeflux.connectors.ConnectorTrustStore
import com.gmdveast.financeflux.connectors.DiscoveredConnectorService
import com.gmdveast.financeflux.core.ConnectorManifest
import com.gmdveast.financeflux.core.ConnectorProfile
import com.gmdveast.financeflux.core.DesktopProtocolCompatibility
import com.gmdveast.financeflux.core.DesktopProtocolProblem
import com.gmdveast.financeflux.core.FinanceJson
import com.gmdveast.financeflux.core.FinanceReferenceLists
import com.gmdveast.financeflux.data.ProfileRepository
import com.gmdveast.financeflux.storage.SafVaultStore
import com.gmdveast.financeflux.storage.QueueArchiver
import com.gmdveast.financeflux.storage.ResetProcessor
import com.gmdveast.financeflux.updates.GitHubReleaseChecker
import com.gmdveast.financeflux.updates.PublicRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FluxHome()
                }
            }
        }
    }
}

@Composable
private fun FluxHome() {
    val context = LocalContext.current
    val activity = context.findComponentActivity()
    val scope = rememberCoroutineScope()
    val appPreferences = remember { context.getSharedPreferences("finance-flux", 0) }
    val profiles = remember { ProfileRepository(context) }
    val trust = remember { ConnectorTrustStore(context) }

    var treeValue by remember { mutableStateOf(appPreferences.getString("vaultTreeUri", null)) }
    var referenceLists by remember { mutableStateOf<FinanceReferenceLists?>(null) }
    var connectorServices by remember { mutableStateOf(emptyList<DiscoveredConnectorService>()) }
    var savedProfiles by remember { mutableStateOf(profiles.loadProfiles()) }
    var queueCounts by remember { mutableStateOf(0 to 0) }
    var protocolCompatibility by remember {
        mutableStateOf(DesktopProtocolCompatibility(DesktopProtocolProblem.MISSING))
    }
    var status by remember { mutableStateOf("Выберите папку Finance Sync внутри Obsidian vault.") }
    var busy by remember { mutableStateOf(false) }
    var selectedTrust by remember { mutableStateOf<DiscoveredConnectorService?>(null) }
    var profileService by remember { mutableStateOf<DiscoveredConnectorService?>(null) }
    var latestRelease by remember { mutableStateOf<PublicRelease?>(null) }
    var updateStatus by remember { mutableStateOf("") }

    suspend fun refreshVault() {
        val value = treeValue ?: return
        val store = runCatching { SafVaultStore(context, Uri.parse(value)) }.getOrNull() ?: return
        val reset = withContext(Dispatchers.IO) { ResetProcessor(store).apply() }
        withContext(Dispatchers.IO) { QueueArchiver(store).archiveCardsForMissingQueueFiles() }
        val loaded = withContext(Dispatchers.IO) { store.readReferenceLists() }
        val protocol = withContext(Dispatchers.IO) { store.readDesktopProtocolCompatibility() }
        val counts = withContext(Dispatchers.IO) { store.queueCounts() }
        referenceLists = loaded
        protocolCompatibility = protocol
        queueCounts = counts
        status = when {
            loaded == null -> "Справочники не найдены или недоступны. Проверьте config/lists.json и запустите Finance desktop."
            else -> "Папка подключена. Активных счетов: ${loaded.accounts.size}."
        }
        if (reset.deletedQueueFiles > 0 || reset.deletedCards > 0) {
            status += " Очистка после сброса: ${reset.deletedQueueFiles} событий, ${reset.deletedCards} карточек."
        }
    }

    fun refreshConnectors() {
        scope.launch {
            connectorServices = withContext(Dispatchers.IO) { ConnectorDiscovery.discover(context) }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val saved = appPreferences.edit().putString("vaultTreeUri", uri.toString()).commit()
            if (saved) {
                treeValue = uri.toString()
                status = "Папка выбрана. Загружаю справочники…"
            } else {
                status = "Не удалось сохранить доступ к выбранной папке."
            }
        }
    }

    LaunchedEffect(treeValue) {
        if (treeValue != null) refreshVault()
        refreshConnectors()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Finance Flux", style = MaterialTheme.typography.headlineMedium)
        Text("Android beta · протокол IPC 1 · приложение не меняет Excel")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Папка очереди", style = MaterialTheme.typography.titleMedium)
                Text(treeValue?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() } ?: "Не выбрана")
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (treeValue != null) {
                    Text(protocolCompatibilityMessage(protocolCompatibility), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }) { Text("Выбрать папку") }
                    TextButton(enabled = treeValue != null && !busy, onClick = { scope.launch { refreshVault() } }) { Text("Обновить") }
                }
                if (referenceLists != null) {
                    Text("Очередь: ${queueCounts.first} JSON · ${queueCounts.second} временных файлов")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Коннекторы", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = ::refreshConnectors) { Text("Найти") }
                }
                if (connectorServices.isEmpty()) Text("Не найдено companion-приложений с Finance Flux IPC.")
                connectorServices.forEach { service ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(service.displayName)
                            Text(service.packageName, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256: ${service.signingFingerprint}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (service.trusted) {
                            Column {
                                TextButton(
                                    enabled = referenceLists?.accounts?.isNotEmpty() == true,
                                    onClick = { profileService = service },
                                ) { Text("Профиль") }
                                TextButton(onClick = { trust.revoke(service.packageName); refreshConnectors() }) { Text("Отозвать") }
                            }
                        } else {
                            TextButton(onClick = { selectedTrust = service }) { Text("Доверить") }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Профили", style = MaterialTheme.typography.titleMedium)
                if (savedProfiles.isEmpty()) Text("Профили связывают коннектор с конкретным активным счётом.")
                savedProfiles.forEach { profile ->
                    ProfileCard(
                        profile = profile,
                        lists = referenceLists,
                        onToggle = {
                            profiles.saveProfile(profile.copy(enabled = !profile.enabled))
                            savedProfiles = profiles.loadProfiles()
                        },
                        onDelete = {
                            profiles.removeProfile(profile.id)
                            savedProfiles = profiles.loadProfiles()
                        },
                    )
                }
            }
        }

        Button(
            enabled = !busy && protocolCompatibility.compatible && referenceLists != null &&
                connectorServices.any(DiscoveredConnectorService::trusted) && savedProfiles.any(ConnectorProfile::enabled),
            onClick = {
                val uriText = treeValue ?: return@Button
                val lists = referenceLists ?: return@Button
                busy = true
                status = "Получаю кандидатов от доверенных коннекторов…"
                scope.launch {
                    val store = SafVaultStore(context, Uri.parse(uriText))
                    protocolCompatibility = withContext(Dispatchers.IO) { store.readDesktopProtocolCompatibility() }
                    if (!protocolCompatibility.compatible) {
                        status = protocolCompatibilityMessage(protocolCompatibility)
                        busy = false
                        return@launch
                    }
                    val result = ConnectorSyncCoordinator(context, store, lists, profiles, trust).sync(connectorServices)
                    queueCounts = withContext(Dispatchers.IO) { store.queueCounts() }
                    status = if (result.errors.isEmpty()) {
                        "В очередь добавлено: ${result.processed}. После сброса пропущено: ${result.discardedByReset}."
                    } else {
                        "Добавлено: ${result.processed}. После сброса пропущено: ${result.discardedByReset}. Есть события для проверки: ${result.errors.size}. Подробности источника не записываются в логи."
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Обработка…" else "Обновить очередь") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Проверка обновлений", style = MaterialTheme.typography.titleMedium)
                Text("Проверка вручную обращается только к публичным release notes на GitHub. APK не загружается автоматически и не размещается в публичном репозитории.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        updateStatus = "Проверяю GitHub…"
                        scope.launch {
                            latestRelease = withContext(Dispatchers.IO) { runCatching { GitHubReleaseChecker.latest() }.getOrNull() }
                            updateStatus = if (latestRelease == null) "Публичный релиз пока недоступен." else "Найдена версия ${latestRelease!!.tagName}."
                            busy = false
                        }
                    }) { Text("Проверить") }
                    if (latestRelease != null) {
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(latestRelease!!.url))
                            if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) {
                            Text("Открыть notes")
                        }
                    }
                }
                if (updateStatus.isNotBlank()) Text(updateStatus)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    selectedTrust?.let { service ->
        AlertDialog(
            onDismissRequest = { selectedTrust = null },
            title = { Text("Доверить companion-коннектор?") },
            text = {
                Text("Finance Flux сможет запрашивать нормализованные кандидаты у этого приложения и помещать их в выбранную очередь. Доступ к vault, Excel и Syncthing коннектор не получает.\n\n${service.packageName}\nSHA-256: ${service.signingFingerprint}")
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = trust.trust(service.packageName, service.signingFingerprint)
                    selectedTrust = null
                    status = if (saved) "Подпись коннектора сохранена в списке доверенных." else "Не удалось сохранить доверие к коннектору."
                    refreshConnectors()
                }) { Text("Доверить эту подпись") }
            },
            dismissButton = { TextButton(onClick = { selectedTrust = null }) { Text("Отмена") } },
        )
    }

    profileService?.let { service ->
        val lists = referenceLists
        if (lists != null) {
            ProfileEditor(
                service = service,
                lists = lists,
                onDismiss = { profileService = null },
                onSave = { profile ->
                    if (profiles.saveProfile(profile)) {
                        savedProfiles = profiles.loadProfiles()
                        status = "Профиль сохранён."
                        profileService = null
                    } else {
                        status = "Не удалось сохранить профиль."
                    }
                },
                onReadManifest = { onResult ->
                    scope.launch {
                        val manifest = runCatching {
                            BoundConnectorClient(context).withConnector(service.component) { api ->
                                val value = api.getManifestJson()
                                require(value.toByteArray(Charsets.UTF_8).size <= ConnectorSyncCoordinator.MAX_MANIFEST_BYTES)
                                FinanceJson.decodeManifest(value)
                            }
                        }.getOrNull()
                        onResult(manifest)
                    }
                },
            )
        }
    }
}

private fun protocolCompatibilityMessage(value: DesktopProtocolCompatibility): String = when (value.problem) {
    null -> "Desktop поддерживает автоматические операции schema 2."
    DesktopProtocolProblem.MISSING -> "Не найден config/protocol.json. Запустите desktop и синхронизируйте папку Finance Sync."
    DesktopProtocolProblem.INVALID -> "config/protocol.json повреждён или не распознан. Обновите данные из desktop."
    DesktopProtocolProblem.UNSUPPORTED_SCHEMA -> "Версия протокола desktop не поддерживается этой версией Flux."
    DesktopProtocolProblem.WRONG_APPLICATION -> "config/protocol.json создан не Finance-desktop."
    DesktopProtocolProblem.OPERATION_SCHEMA_UNSUPPORTED -> "Desktop не объявил поддержку операций schema 2. Обновите desktop и синхронизируйте папку."
    DesktopProtocolProblem.CAPABILITY_MISSING -> "Desktop не объявил приём автоматических операций. Обновите desktop и синхронизируйте папку."
}

@Composable
private fun ProfileCard(
    profile: ConnectorProfile,
    lists: FinanceReferenceLists?,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val account = lists?.accounts?.firstOrNull { it.id == profile.accountId }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text("${profile.connectorId} · ${profile.packageName}", style = MaterialTheme.typography.bodySmall)
            Text("Счёт: ${account?.name ?: "#${profile.accountId}"} (${account?.currency ?: "?"})")
            Text("Расход: ${profile.defaultExpenseCategory ?: "без категории"} · Доход: ${profile.defaultIncomeCategory ?: "без категории"}")
            Row {
                TextButton(onClick = onToggle) { Text(if (profile.enabled) "Отключить" else "Включить") }
                TextButton(onClick = onDelete) { Text("Удалить профиль") }
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    service: DiscoveredConnectorService,
    lists: FinanceReferenceLists,
    onDismiss: () -> Unit,
    onSave: (ConnectorProfile) -> Unit,
    onReadManifest: ((ConnectorManifest?) -> Unit) -> Unit,
) {
    var name by remember(service.packageName) { mutableStateOf("${service.displayName} · ${lists.accounts.firstOrNull()?.name.orEmpty()}") }
    var connectorId by remember(service.packageName) { mutableStateOf("") }
    var manifestMessage by remember(service.packageName) { mutableStateOf("") }
    var accountId by remember(service.packageName) { mutableStateOf(lists.accounts.firstOrNull()?.id) }
    var expenseCategory by remember(service.packageName) { mutableStateOf("") }
    var incomeCategory by remember(service.packageName) { mutableStateOf("") }

    val accountChoices = lists.accounts.map { it.id.toString() to "${it.name} (${it.currency})" }
    val expenseChoices = listOf("" to "Не задавать") + lists.categories.filter { it.type == "Расход" }.map { it.name to it.name }
    val incomeChoices = listOf("" to "Не задавать") + lists.categories.filter { it.type == "Доход" }.map { it.name to it.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый профиль") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(value = connectorId, onValueChange = { connectorId = it.take(64) }, label = { Text("ID коннектора") }, singleLine = true)
                TextButton(onClick = {
                    onReadManifest { manifest ->
                        if (manifest != null && manifest.schema == 1 && manifest.minIpcVersion <= 1 && manifest.maxIpcVersion >= 1) {
                            connectorId = manifest.connectorId
                            if (name.isBlank()) name = manifest.displayName
                            manifestMessage = "Получен контракт IPC 1: ${manifest.displayName}"
                        } else {
                            manifestMessage = "Не удалось прочитать совместимый manifest коннектора."
                        }
                    }
                }) { Text("Прочитать manifest") }
                if (manifestMessage.isNotBlank()) Text(manifestMessage, style = MaterialTheme.typography.bodySmall)
                ChoiceField("Счёт", accountId?.toString().orEmpty(), accountChoices) { accountId = it.toIntOrNull() }
                ChoiceField("Категория расхода", expenseCategory, expenseChoices) { expenseCategory = it }
                ChoiceField("Категория дохода", incomeCategory, incomeChoices) { incomeCategory = it }
            }
        },
        confirmButton = {
            TextButton(enabled = validConnectorId(connectorId) && name.isNotBlank() && accountId != null, onClick = {
                val next = ConnectorProfile(
                    id = "profile-${UUID.randomUUID().toString().replace("-", "")}",
                    connectorId = connectorId,
                    packageName = service.packageName,
                    name = name.trim(),
                    accountId = accountId!!,
                    defaultExpenseCategory = expenseCategory.takeIf(String::isNotBlank),
                    defaultIncomeCategory = incomeCategory.takeIf(String::isNotBlank),
                    enabled = true,
                )
                onSave(next)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ChoiceField(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember(label) { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == selected }?.second ?: "Выберите"
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedLabel) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, caption) ->
                DropdownMenuItem(text = { Text(caption) }, onClick = { expanded = false; onSelected(value) })
            }
        }
    }
}

private fun validConnectorId(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$").matches(value)

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
