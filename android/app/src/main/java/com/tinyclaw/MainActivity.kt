package com.tinyclaw

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.tinyclaw.ui.AgentInfo
import com.tinyclaw.ui.AgentListPane
import com.tinyclaw.ui.ChatDetailPane
import com.tinyclaw.ui.ChatMessage
import com.tinyclaw.ui.DetailPlaceholder
import com.tinyclaw.ui.theme.TinyClawTheme
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

// Nav keys
@Serializable
private object AgentList : NavKey

@Serializable
private data class AgentDetail(val agentId: String, val agentName: String) : NavKey

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val API_PORT = 8787
    }

    private val agents = mutableStateListOf<AgentInfo>()
    private val chatMessages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val serviceRunning = mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TinyClawService.EXTRA_STATUS) ?: return
            when (status) {
                "running" -> serviceRunning.value = true
                "stopped", "error" -> serviceRunning.value = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        loadAgents()
        requestNotificationPermissionIfNeeded()

        setContent {
            TinyClawTheme {
                TinyClawNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter(TinyClawService.ACTION_STATUS_CHANGED)
        )
        loadAgents()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    private fun startService(modelId: String) {
        val intent = Intent(this, TinyClawService::class.java).apply {
            putExtra(TinyClawService.EXTRA_MODEL_ID, modelId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopService() {
        val intent = Intent(this, TinyClawService::class.java).apply {
            action = TinyClawService.ACTION_STOP
        }
        startService(intent)
    }

    private fun loadAgents() {
        val settingsFile = File(filesDir, ".tinyclaw/settings.json")
        val loaded = mutableListOf<AgentInfo>()

        if (settingsFile.exists()) {
            try {
                val json = JSONObject(settingsFile.readText())
                if (json.has("agents")) {
                    val agentsObj = json.getJSONObject("agents")
                    for (key in agentsObj.keys()) {
                        val agent = agentsObj.getJSONObject(key)
                        loaded.add(
                            AgentInfo(
                                id = key,
                                name = agent.optString("name", key),
                                description = agent.optString("description", ""),
                                model = agent.optString("model", "")
                            )
                        )
                    }
                }
            } catch (_: Exception) { }
        }

        // Always include demo agents so the UI isn't empty
        if (loaded.isEmpty()) {
            loaded.addAll(
                listOf(
                    AgentInfo("assistant", "Assistant", "General-purpose AI assistant", "gemma3-1b"),
                    AgentInfo("coder", "Coder", "Code generation and review", "gemma-3n-e4b"),
                    AgentInfo("researcher", "Researcher", "Web research and summarization", "phi-4-mini"),
                    AgentInfo("writer", "Writer", "Creative and technical writing", "qwen2.5-1.5b"),
                )
            )
        }

        agents.clear()
        agents.addAll(loaded)
    }

    private fun getMessagesForAgent(agentId: String): MutableList<ChatMessage> {
        return chatMessages.getOrPut(agentId) { mutableListOf() }
    }

    private fun sendMessage(agentId: String, content: String) {
        val messages = getMessagesForAgent(agentId)
        val ts = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        // Add the user's message immediately
        messages.add(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                content = content,
                isFromAgent = false,
                timestamp = ts
            )
        )

        if (!serviceRunning.value) {
            messages.add(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    content = "Service is not running. Start the service first.",
                    isFromAgent = true,
                    timestamp = ts
                )
            )
            return
        }

        // Post to the local HTTP API on a background thread
        thread {
            try {
                val url = URL("http://127.0.0.1:$API_PORT/v1/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5_000
                conn.readTimeout = 130_000 // server-side polls up to 120s
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("message", content)
                    put("agent", agentId)
                    put("sender", "android-user")
                    put("sender_id", "android")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val responseCode = conn.responseCode
                val responseBody = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                }

                val replyTs = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                val replyText = try {
                    JSONObject(responseBody).optString("message", responseBody)
                } catch (_: Exception) {
                    responseBody
                }

                runOnUiThread {
                    messages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            content = replyText,
                            isFromAgent = true,
                            timestamp = replyTs
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to API", e)
                val errTs = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                runOnUiThread {
                    messages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            content = "Error: ${e.localizedMessage ?: "Connection failed"}",
                            isFromAgent = true,
                            timestamp = errTs
                        )
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Composable
    private fun TinyClawNavHost() {
        val backStack = rememberNavBackStack(AgentList)

        // Configure adaptive layout for foldables
        val windowAdaptiveInfo = currentWindowAdaptiveInfo()
        val directive = remember(windowAdaptiveInfo) {
            calculatePaneScaffoldDirective(windowAdaptiveInfo)
                .copy(horizontalPartitionSpacerSize = 0.dp)
        }
        val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            sceneStrategy = listDetailStrategy,
            transitionSpec = {
                (slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(300))) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { -it / 3 },
                        animationSpec = tween(400)
                    ) + fadeOut(animationSpec = tween(200)))
            },
            popTransitionSpec = {
                (slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(300))) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { it / 3 },
                        animationSpec = tween(400)
                    ) + fadeOut(animationSpec = tween(200)))
            },
            predictivePopTransitionSpec = {
                (scaleIn(
                    initialScale = 0.9f,
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(300))) togetherWith
                    (scaleOut(
                        targetScale = 0.85f,
                        animationSpec = tween(400)
                    ) + fadeOut(animationSpec = tween(300)))
            },
            entryProvider = entryProvider {
                entry<AgentList>(
                    metadata = ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            DetailPlaceholder()
                        }
                    )
                ) {
                    AgentListPane(
                        agents = agents,
                        selectedAgentId = null,
                        isServiceRunning = serviceRunning.value,
                        onAgentClick = { agent ->
                            backStack.add(AgentDetail(agent.id, agent.name))
                        },
                        onStartService = { modelId -> startService(modelId) },
                        onStopService = { stopService() }
                    )
                }

                entry<AgentDetail>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) { detail ->
                    val messages = remember(detail.agentId) {
                        getMessagesForAgent(detail.agentId)
                    }
                    ChatDetailPane(
                        agentName = detail.agentName,
                        messages = messages,
                        onSendMessage = { content ->
                            sendMessage(detail.agentId, content)
                        }
                    )
                }
            }
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
