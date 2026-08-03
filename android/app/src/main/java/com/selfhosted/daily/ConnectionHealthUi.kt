package com.selfhosted.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionHealthDot(
    snapshot: ConnectionHealthSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(connectionHealthColor(snapshot.level), CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
fun ConnectionHealthDialog(
    snapshot: ConnectionHealthSnapshot,
    onDismiss: () -> Unit
) {
    var showNetworkUsage by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schliessen") }
        },
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(connectionHealthColor(snapshot.level), CircleShape)
                )
                Text(snapshot.title)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(snapshot.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusSection("Grund", snapshot.reasonLines)
                StatusSection("Netz", listOf(snapshot.networkLine.removePrefix("Netz: ")))
                StatusSection("Server", listOf(snapshot.serverLine.removePrefix("Server: ")))
                StatusSection("Uploads", listOf(snapshot.uploadLine.removePrefix("Uploads: ")))
                snapshot.lastErrorLine?.let {
                    StatusSection("Letzter Fehler", listOf(it.removePrefix("Letzter Fehler: ")))
                }
                TextButton(onClick = { showNetworkUsage = !showNetworkUsage }) {
                    Text(if (showNetworkUsage) "Datenverbrauch ausblenden" else "Datenverbrauch anzeigen")
                }
                if (showNetworkUsage) {
                    NetworkUsageSummaryCard()
                }
            }
        }
    )
}

@Composable
private fun StatusSection(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp)
            )
        }
    }
}

private fun connectionHealthColor(level: ConnectionHealthLevel): Color = when (level) {
    ConnectionHealthLevel.GREEN -> Color(0xFF2E7D32)
    ConnectionHealthLevel.YELLOW -> Color(0xFFF9A825)
    ConnectionHealthLevel.RED -> Color(0xFFD32F2F)
}
