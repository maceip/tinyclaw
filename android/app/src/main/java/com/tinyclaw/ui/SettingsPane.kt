package com.tinyclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tinyclaw.TinyClawBridge
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPane(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var settings by remember { mutableStateOf(TinyClawBridge.getSettingsJson()) }
    var saved by remember { mutableStateOf(false) }

    fun save() {
        TinyClawBridge.updateSettings(settings)
        saved = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Save",
                            tint = if (saved) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Provider Section ──
            SettingsSection("Provider") {
                ProviderSettingsCard(settings) { updated ->
                    settings = updated
                    saved = false
                }
            }

            // ── Pairing Section ──
            SettingsSection("Pairing") {
                PairingSettingsCard(settings) { updated ->
                    settings = updated
                    saved = false
                }
            }

            // ── Email Channel Section ──
            SettingsSection("Email Channel") {
                EmailSettingsCard(settings) { updated ->
                    settings = updated
                    saved = false
                }
            }

            // ── Agents Section ──
            SettingsSection("Agents") {
                AgentsSettingsCard(settings) { updated ->
                    settings = updated
                    saved = false
                }
            }

            // ── Teams Section ──
            SettingsSection("Teams") {
                TeamsSettingsCard(settings) { updated ->
                    settings = updated
                    saved = false
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSettingsCard(
    settings: JSONObject,
    onUpdate: (JSONObject) -> Unit
) {
    val providers = settings.optJSONObject("providers") ?: JSONObject()
    val currentDefault = providers.optString("default_provider", "local")
    val options = listOf("local", "anthropic", "openai")
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentDefault,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                val updated = JSONObject(settings.toString())
                                val p = updated.optJSONObject("providers") ?: JSONObject()
                                p.put("default_provider", option)
                                updated.put("providers", p)
                                onUpdate(updated)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingSettingsCard(
    settings: JSONObject,
    onUpdate: (JSONObject) -> Unit
) {
    val pairingEnabled = settings.optBoolean("pairing_enabled", false)
    val pairingState = remember { mutableStateOf(TinyClawBridge.getPairingState()) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Require pairing", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = pairingEnabled,
                    onCheckedChange = { enabled ->
                        val updated = JSONObject(settings.toString())
                        updated.put("pairing_enabled", enabled)
                        onUpdate(updated)
                    }
                )
            }

            if (pairingEnabled) {
                Spacer(Modifier.height(12.dp))

                val state = pairingState.value
                if (state.paired.isNotEmpty()) {
                    Text(
                        "Paired senders:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.paired.forEach { sender ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                sender.senderId,
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = {
                                TinyClawBridge.revokePairing(sender.senderId)
                                pairingState.value = TinyClawBridge.getPairingState()
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Revoke",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (state.pending.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pending approvals:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.pending.forEach { (code, senderId) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(code, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(senderId, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = {
                                TinyClawBridge.approvePairing(code)
                                pairingState.value = TinyClawBridge.getPairingState()
                            }) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Approve",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailSettingsCard(
    settings: JSONObject,
    onUpdate: (JSONObject) -> Unit
) {
    val channels = settings.optJSONObject("channels") ?: JSONObject()
    val email = channels.optJSONObject("email") ?: JSONObject()

    var imapHost by remember { mutableStateOf(email.optString("imap_host", "")) }
    var imapPort by remember { mutableStateOf(email.optString("imap_port", "993")) }
    var smtpHost by remember { mutableStateOf(email.optString("smtp_host", "")) }
    var smtpPort by remember { mutableStateOf(email.optString("smtp_port", "587")) }
    var username by remember { mutableStateOf(email.optString("username", "")) }
    var password by remember { mutableStateOf(email.optString("password", "")) }

    fun updateEmail() {
        val updated = JSONObject(settings.toString())
        val ch = updated.optJSONObject("channels") ?: JSONObject()
        val em = JSONObject()
        em.put("imap_host", imapHost)
        em.put("imap_port", imapPort.toIntOrNull() ?: 993)
        em.put("smtp_host", smtpHost)
        em.put("smtp_port", smtpPort.toIntOrNull() ?: 587)
        em.put("username", username)
        em.put("password", password)
        ch.put("email", em)
        updated.put("channels", ch)
        onUpdate(updated)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = imapHost,
                    onValueChange = { imapHost = it; updateEmail() },
                    label = { Text("IMAP Host") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = imapPort,
                    onValueChange = { imapPort = it; updateEmail() },
                    label = { Text("Port") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = smtpHost,
                    onValueChange = { smtpHost = it; updateEmail() },
                    label = { Text("SMTP Host") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = smtpPort,
                    onValueChange = { smtpPort = it; updateEmail() },
                    label = { Text("Port") },
                    modifier = Modifier.width(80.dp),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; updateEmail() },
                label = { Text("Email Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; updateEmail() },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentsSettingsCard(
    settings: JSONObject,
    onUpdate: (JSONObject) -> Unit
) {
    val agentsObj = settings.optJSONObject("agents") ?: JSONObject()
    var showAdd by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newProvider by remember { mutableStateOf("anthropic") }
    var newModel by remember { mutableStateOf("sonnet") }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            for (key in agentsObj.keys()) {
                val agent = agentsObj.getJSONObject(key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            agent.optString("name", key),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${agent.optString("provider", "local")} / ${agent.optString("model", "")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val updated = JSONObject(settings.toString())
                        val agents = updated.optJSONObject("agents") ?: JSONObject()
                        agents.remove(key)
                        updated.put("agents", agents)
                        onUpdate(updated)
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (showAdd) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newId,
                    onValueChange = { newId = it },
                    label = { Text("Agent ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProvider,
                        onValueChange = { newProvider = it },
                        label = { Text("Provider") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newModel,
                        onValueChange = { newModel = it },
                        label = { Text("Model") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = { showAdd = false }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (newId.isNotBlank() && newName.isNotBlank()) {
                            val updated = JSONObject(settings.toString())
                            val agents = updated.optJSONObject("agents") ?: JSONObject()
                            val agent = JSONObject()
                            agent.put("name", newName)
                            agent.put("provider", newProvider)
                            agent.put("model", newModel)
                            agent.put("working_directory", "")
                            agents.put(newId, agent)
                            updated.put("agents", agents)
                            onUpdate(updated)
                            newId = ""
                            newName = ""
                            showAdd = false
                        }
                    }) {
                        Text("Add")
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Agent")
                }
            }
        }
    }
}

@Composable
private fun TeamsSettingsCard(
    settings: JSONObject,
    onUpdate: (JSONObject) -> Unit
) {
    val teamsObj = settings.optJSONObject("teams") ?: JSONObject()
    var showAdd by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newLeader by remember { mutableStateOf("") }
    var newMembers by remember { mutableStateOf("") }
    var newStrategy by remember { mutableStateOf("leader-routes") }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            for (key in teamsObj.keys()) {
                val team = teamsObj.getJSONObject(key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            team.optString("name", key),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Leader: ${team.optString("leader_agent", "")} | ${team.optString("strategy", "")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val updated = JSONObject(settings.toString())
                        val teams = updated.optJSONObject("teams") ?: JSONObject()
                        teams.remove(key)
                        updated.put("teams", teams)
                        onUpdate(updated)
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (showAdd) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newId,
                        onValueChange = { newId = it },
                        label = { Text("Team ID") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = newLeader,
                    onValueChange = { newLeader = it },
                    label = { Text("Leader Agent ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newMembers,
                    onValueChange = { newMembers = it },
                    label = { Text("Members (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newStrategy,
                    onValueChange = { newStrategy = it },
                    label = { Text("Strategy (leader-routes, chain, fan-out)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = { showAdd = false }) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (newId.isNotBlank() && newName.isNotBlank()) {
                            val updated = JSONObject(settings.toString())
                            val teams = updated.optJSONObject("teams") ?: JSONObject()
                            val team = JSONObject()
                            team.put("name", newName)
                            team.put("leader_agent", newLeader)
                            team.put("agents", org.json.JSONArray(newMembers.split(",").map { it.trim() }))
                            team.put("strategy", newStrategy)
                            teams.put(newId, team)
                            updated.put("teams", teams)
                            onUpdate(updated)
                            newId = ""
                            newName = ""
                            newLeader = ""
                            newMembers = ""
                            showAdd = false
                        }
                    }) {
                        Text("Add")
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Team")
                }
            }
        }
    }
}
