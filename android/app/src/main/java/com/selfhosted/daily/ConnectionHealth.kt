package com.selfhosted.daily

enum class ConnectionHealthLevel {
    GREEN,
    YELLOW,
    RED
}

data class ConnectionHealthSnapshot(
    val level: ConnectionHealthLevel = ConnectionHealthLevel.RED,
    val title: String = "Keine nutzbare Verbindung",
    val summary: String = "Noch keine erfolgreiche Verbindung zu Daily.",
    val networkLine: String = "Netz: unbekannt",
    val serverLine: String = "Server: noch kein erfolgreicher Kontakt",
    val uploadLine: String = "Uploads: keine aktiven Hinweise",
    val lastErrorLine: String? = null,
    val reasonLines: List<String> = emptyList(),
    val updatedAtMs: Long = 0L
)

data class ConnectionHealthInputs(
    val nowMs: Long,
    val startupDone: Boolean,
    val serverConnected: Boolean,
    val lastPingMs: Long?,
    val lastApiSuccessAtMs: Long,
    val lastApiFailureAtMs: Long,
    val lastApiFailureMessage: String,
    val networkSnapshot: String,
    val refreshCircuitRemainingMs: Long,
    val lastRefreshFailureClass: String,
    val uploadQueue: List<QueuedUploadItem>,
    val networkRecoveryActive: Boolean,
    val networkRecoveryReason: String,
    val lastNetworkTransitionAtMs: Long
)

data class ParsedNetworkSnapshot(
    val activeNetwork: Boolean,
    val internet: Boolean,
    val validated: Boolean,
    val transport: String,
    val metered: Boolean?,
    val downKbps: Int?,
    val upKbps: Int?
)

internal fun parseApiErrorCode(rawBody: String?): String? {
    val raw = rawBody?.trim().orEmpty()
    if (raw.isBlank()) return null
    val match = Regex("\"errorCode\"\\s*:\\s*\"([^\"]+)\"").find(raw) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

internal fun parseNetworkSnapshot(snapshot: String): ParsedNetworkSnapshot {
    val parts = snapshot.split(";")
        .mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) null else entry.substring(0, idx).trim() to entry.substring(idx + 1).trim()
        }
        .toMap()
    fun bool(key: String): Boolean = parts[key].equals("true", ignoreCase = true)
    fun int(key: String): Int? = parts[key]?.toIntOrNull()
    return ParsedNetworkSnapshot(
        activeNetwork = bool("activeNetwork"),
        internet = bool("internet"),
        validated = bool("validated"),
        transport = parts["transport"].orEmpty().ifBlank { "unbekannt" },
        metered = parts["metered"]?.let { it.equals("true", ignoreCase = true) },
        downKbps = int("downKbps"),
        upKbps = int("upKbps")
    )
}

internal fun evaluateConnectionHealth(inputs: ConnectionHealthInputs): ConnectionHealthSnapshot {
    val network = parseNetworkSnapshot(inputs.networkSnapshot)
    val queue = inputs.uploadQueue
    val pausedQueue = queue.count { it.status == UploadQueueStatus.PAUSED }
    val secureWaiting = queue.count { it.status == UploadQueueStatus.WAITING_FOR_SECURE_NETWORK }
    val retrying = queue.count { it.status == UploadQueueStatus.FAILED_TRANSIENT }
    val awaitingAck = queue.count { it.status == UploadQueueStatus.AWAITING_SERVER_ACK }
    val waitingForNetwork = queue.count { it.status == UploadQueueStatus.WAITING_FOR_NETWORK }
    val authPaused = queue.any {
        it.status == UploadQueueStatus.PAUSED &&
            (it.lastFailureClass == "auth_missing" || it.lastFailureClass == "http_401")
    }
    val uploadLine = buildString {
        if (queue.isEmpty()) {
            append("Uploads: keine aktiven Hinweise")
        } else {
            append("Uploads: ")
            val parts = mutableListOf<String>()
            if (waitingForNetwork > 0) parts += "$waitingForNetwork wartet auf Verbindung"
            if (secureWaiting > 0) parts += "$secureWaiting wartet auf sichere Verbindung"
            if (retrying > 0) parts += "$retrying im Retry"
            if (awaitingAck > 0) parts += "$awaitingAck wartet auf Bestaetigung"
            if (pausedQueue > 0) parts += "$pausedQueue pausiert"
            append(if (parts.isEmpty()) "keine Warnungen" else parts.joinToString(", "))
        }
    }
    val networkLine = buildString {
        append("Netz: ")
        if (!network.activeNetwork) {
            append("kein aktives Netzwerk")
        } else {
            append(network.transport.replace('|', '/').ifBlank { "unbekannt" })
            append(", Internet ").append(if (network.internet) "ja" else "nein")
            append(", Validierung ").append(if (network.validated) "ja" else "nein")
            network.metered?.let { append(", ").append(if (it) "getaktet" else "ungetaktet") }
        }
    }
    val serverLine = buildString {
        append("Server: ")
        when {
            inputs.lastApiSuccessAtMs > 0L -> {
                append("letzter erfolgreicher Kontakt vor ")
                append(formatElapsedShort(inputs.nowMs - inputs.lastApiSuccessAtMs))
                inputs.lastPingMs?.let { append(", Ping ").append(it).append(" ms") }
            }
            inputs.serverConnected -> append("erreichbar")
            else -> append("noch kein erfolgreicher Kontakt")
        }
    }

    val reasonLines = mutableListOf<String>()
    var level = ConnectionHealthLevel.GREEN
    var title = "Verbindung gut"
    var summary = "Daily ist erreichbar und es liegen keine kritischen Sync-Probleme vor."

    if (!inputs.startupDone) {
        level = if (!network.activeNetwork || !network.internet) ConnectionHealthLevel.RED else ConnectionHealthLevel.YELLOW
        title = if (level == ConnectionHealthLevel.RED) "Keine nutzbare Verbindung" else "Verbindung wird vorbereitet"
        summary = if (level == ConnectionHealthLevel.RED) {
            "Die App startet noch und hat derzeit keine nutzbare Verbindung."
        } else {
            "Die App startet noch und prueft gerade die Verbindung zu Daily."
        }
        reasonLines += "App-Start noch nicht abgeschlossen"
    }

    if (!network.activeNetwork || !network.internet) {
        level = ConnectionHealthLevel.RED
        title = "Keine nutzbare Verbindung"
        summary = "Es gibt aktuell keine verwendbare Internetverbindung."
        reasonLines += "Kein aktives Netzwerk mit Internet"
    } else if (!network.validated) {
        if (level != ConnectionHealthLevel.RED) {
            level = ConnectionHealthLevel.RED
            title = "Keine nutzbare Verbindung"
            summary = "Das aktuelle Netzwerk ist nicht als internetfaehig validiert."
        }
        reasonLines += "Netzwerk nicht validiert"
    }

    if (inputs.networkRecoveryActive && network.activeNetwork && network.internet) {
        if (level != ConnectionHealthLevel.RED) {
            level = ConnectionHealthLevel.YELLOW
            title = "Verbindung wird wiederhergestellt"
            summary = "Nach einem Netzwechsel prueft Daily die Verbindung erneut."
        }
        reasonLines += when (inputs.networkRecoveryReason) {
            "transport_changed" -> "Netzwerktyp wurde gewechselt"
            "network_restored" -> "Netzwerk wurde gerade wiederhergestellt"
            "validated_network" -> "Validiertes Netzwerk wird neu verifiziert"
            "network_lost" -> "Netzwerk war kurzzeitig unterbrochen"
            "dns_failure" -> "DNS-Aufloesung wird nach Netzwechsel erneut geprueft"
            else -> "Verbindung befindet sich in der Recovery-Phase"
        }
    }

    if (authPaused) {
        level = ConnectionHealthLevel.RED
        title = "Keine nutzbare Verbindung"
        summary = "Ein Upload wurde wegen Anmelde- oder Sitzungsproblemen pausiert."
        reasonLines += "Upload-Warteschlange pausiert wegen Anmeldung/Sitzung"
    } else if (waitingForNetwork > 0) {
        level = ConnectionHealthLevel.RED
        title = "Keine nutzbare Verbindung"
        summary = "Uploads warten derzeit auf eine wieder nutzbare Verbindung."
        reasonLines += "Uploads warten auf Verbindung"
    }

    if (inputs.lastApiFailureAtMs > inputs.lastApiSuccessAtMs && inputs.lastApiFailureMessage.isNotBlank()) {
        val recentTransitionFailure = inputs.lastNetworkTransitionAtMs > 0L &&
            inputs.lastApiFailureAtMs >= inputs.lastNetworkTransitionAtMs &&
            inputs.networkRecoveryActive &&
            network.activeNetwork &&
            network.internet
        if (level != ConnectionHealthLevel.RED) {
            level = ConnectionHealthLevel.YELLOW
            title = if (recentTransitionFailure) "Verbindung wird wiederhergestellt" else "Verbindung eingeschraenkt"
            summary = if (recentTransitionFailure) {
                "Der letzte Serverkontakt fiel waehrend des Netzwechsels aus."
            } else {
                "Der letzte Kontakt zu Daily war fehlerhaft."
            }
        }
        reasonLines += if (recentTransitionFailure) {
            "Letzter Serverkontakt scheiterte waehrend des Netzwechsels"
        } else {
            "Letzter Serverkontakt fehlgeschlagen"
        }
    }

    if (inputs.refreshCircuitRemainingMs > 0L) {
        if (level != ConnectionHealthLevel.RED) {
            level = ConnectionHealthLevel.YELLOW
            title = "Verbindung eingeschraenkt"
            summary = "Daily drosselt gerade Refreshes nach wiederholten Netzwerkfehlern."
        }
        reasonLines += "Refresh-Circuit-Breaker offen"
    }

    if (inputs.lastRefreshFailureClass.isNotBlank() && inputs.lastRefreshFailureClass != "http_401") {
        if (level != ConnectionHealthLevel.RED && isNetworkFailureClassForHealth(inputs.lastRefreshFailureClass)) {
            level = ConnectionHealthLevel.YELLOW
            title = "Verbindung eingeschraenkt"
            summary = "Die letzten Refreshes waren instabil."
            reasonLines += "Letzter Refresh-Fehler: ${inputs.lastRefreshFailureClass}"
        }
    }

    if (secureWaiting > 0 || retrying > 0 || awaitingAck > 0 || pausedQueue > 0) {
        if (level != ConnectionHealthLevel.RED) {
            level = ConnectionHealthLevel.YELLOW
            title = "Verbindung eingeschraenkt"
            summary = "Es gibt aktive Upload- oder Sync-Warnungen."
        }
        if (secureWaiting > 0) reasonLines += "Uploads warten auf sichere Verbindung"
        if (retrying > 0) reasonLines += "Uploads werden erneut versucht"
        if (awaitingAck > 0) reasonLines += "Uploads warten auf Server-Bestaetigung"
        if (pausedQueue > 0 && !authPaused) reasonLines += "Uploads sind pausiert"
    }

    if (inputs.lastPingMs != null && inputs.lastPingMs > 1500L && level == ConnectionHealthLevel.GREEN) {
        level = ConnectionHealthLevel.YELLOW
        title = "Verbindung eingeschraenkt"
        summary = "Die Verbindung ist erreichbar, aber aktuell langsam."
        reasonLines += "Ping ueber 1500 ms"
    }

    if (level == ConnectionHealthLevel.GREEN && inputs.lastApiSuccessAtMs <= 0L && !inputs.serverConnected) {
        level = ConnectionHealthLevel.YELLOW
        title = "Verbindung wird vorbereitet"
        summary = "Es liegt noch kein bestaetigter erfolgreicher Kontakt zu Daily vor."
        reasonLines += "Noch kein erfolgreicher Serverkontakt bestaetigt"
    }

    if (reasonLines.isEmpty()) {
        reasonLines += "Netzwerk validiert und Daily zuletzt erfolgreich erreichbar"
    }

    return ConnectionHealthSnapshot(
        level = level,
        title = title,
        summary = summary,
        networkLine = networkLine,
        serverLine = serverLine,
        uploadLine = uploadLine,
        lastErrorLine = inputs.lastApiFailureMessage.trim().takeIf { it.isNotBlank() }?.let { "Letzter Fehler: $it" },
        reasonLines = reasonLines.distinct(),
        updatedAtMs = inputs.nowMs
    )
}

private fun isNetworkFailureClassForHealth(failureClass: String): Boolean =
    failureClass == "dns" ||
        failureClass == "connect" ||
        failureClass == "timeout" ||
        failureClass == "offline" ||
        failureClass == "no_active_network" ||
        failureClass == "ssl_handshake" ||
        failureClass == "cert_path_validator" ||
        failureClass == "ssl_other"

private fun formatElapsedShort(deltaMs: Long): String {
    val safe = deltaMs.coerceAtLeast(0L)
    val seconds = safe / 1000L
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60L}m"
        else -> "${seconds / 3600L}h"
    }
}
