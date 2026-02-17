package com.tinyclaw

import org.json.JSONArray
import org.json.JSONObject

/**
 * Singleton JNI bridge to the Rust TinyClaw native library.
 * All native methods are accessed through this object.
 */
object TinyClawBridge {

    init {
        System.loadLibrary("tinyclaw_android")
    }

    // ─── Native declarations ────────────────────────────────────────────

    external fun nativeGetStatus(): String
    external fun nativeSendMessage(agentId: String, message: String): String
    external fun nativePollResponses(): String
    external fun nativeGetSettings(): String
    external fun nativeUpdateSettings(json: String): Boolean
    external fun nativeListPairedSenders(): String
    external fun nativeApprovePairing(code: String): String
    external fun nativeRevokePairing(senderId: String): Boolean
    external fun nativeGetEvents(): String

    // ─── Kotlin-friendly wrappers ───────────────────────────────────────

    data class StatusInfo(
        val running: Boolean,
        val version: String
    )

    fun getStatus(): StatusInfo {
        return try {
            val json = JSONObject(nativeGetStatus())
            StatusInfo(
                running = json.optBoolean("running", false),
                version = json.optString("version", "unknown")
            )
        } catch (_: Exception) {
            StatusInfo(running = false, version = "unknown")
        }
    }

    data class SendResult(
        val messageId: String?,
        val error: String?
    )

    fun sendMessage(agentId: String, message: String): SendResult {
        return try {
            val json = JSONObject(nativeSendMessage(agentId, message))
            SendResult(
                messageId = json.optString("message_id", null),
                error = json.optString("error", null)
            )
        } catch (e: Exception) {
            SendResult(messageId = null, error = e.message)
        }
    }

    data class ResponseMessage(
        val messageId: String,
        val message: String,
        val agent: String?,
        val sender: String,
        val timestamp: Long
    )

    fun pollResponses(): List<ResponseMessage> {
        return try {
            val arr = JSONArray(nativePollResponses())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ResponseMessage(
                    messageId = obj.getString("message_id"),
                    message = obj.getString("message"),
                    agent = obj.optString("agent", null),
                    sender = obj.optString("sender", ""),
                    timestamp = obj.optLong("timestamp", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getSettingsJson(): JSONObject {
        return try {
            JSONObject(nativeGetSettings())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun updateSettings(json: JSONObject): Boolean {
        return try {
            nativeUpdateSettings(json.toString())
        } catch (_: Exception) {
            false
        }
    }

    data class PairedSender(
        val senderId: String,
        val code: String,
        val name: String,
        val pairedAt: Long
    )

    data class PairingState(
        val paired: List<PairedSender>,
        val pending: Map<String, String>  // code -> sender_id
    )

    fun getPairingState(): PairingState {
        return try {
            val json = JSONObject(nativeListPairedSenders())
            val paired = mutableListOf<PairedSender>()
            val pairedObj = json.optJSONObject("paired")
            if (pairedObj != null) {
                for (key in pairedObj.keys()) {
                    val info = pairedObj.getJSONObject(key)
                    paired.add(
                        PairedSender(
                            senderId = key,
                            code = info.optString("code", ""),
                            name = info.optString("name", key),
                            pairedAt = info.optLong("paired_at", 0)
                        )
                    )
                }
            }

            val pending = mutableMapOf<String, String>()
            val pendingObj = json.optJSONObject("pending")
            if (pendingObj != null) {
                for (key in pendingObj.keys()) {
                    pending[key] = pendingObj.getString(key)
                }
            }

            PairingState(paired = paired, pending = pending)
        } catch (_: Exception) {
            PairingState(paired = emptyList(), pending = emptyMap())
        }
    }

    fun approvePairing(code: String): String? {
        val result = nativeApprovePairing(code)
        return result.ifEmpty { null }
    }

    fun revokePairing(senderId: String): Boolean {
        return nativeRevokePairing(senderId)
    }

    data class TinyClawEvent(
        val type: String,
        val data: JSONObject
    )

    fun drainEvents(): List<TinyClawEvent> {
        return try {
            val arr = JSONArray(nativeGetEvents())
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    // Events are tagged enums serialized by serde
                    val type = obj.keys().next()
                    val data = obj.optJSONObject(type) ?: JSONObject()
                    TinyClawEvent(type = type, data = data)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
