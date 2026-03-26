package ai.affiora.mobileclaw.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

enum class RiskLevel(val label: String, val icon: ImageVector) {
    LOW("Low Risk", Icons.Filled.Check),
    MEDIUM("Medium Risk", Icons.Filled.Security),
    HIGH("High Risk", Icons.Filled.Warning),
}

enum class AlwaysAllowChoice {
    NONE,
    SESSION,
    PERMANENT,
}

@Composable
fun ExecApprovalDialog(
    toolName: String,
    parameters: Map<String, String>,
    preview: String,
    riskLevel: RiskLevel,
    onApprove: (alwaysAllowChoice: AlwaysAllowChoice) -> Unit,
    onDeny: () -> Unit,
    timeoutSeconds: Int = 60,
) {
    var alwaysAllowChoice by remember { mutableStateOf(AlwaysAllowChoice.NONE) }
    var progress by remember { mutableFloatStateOf(1f) }
    var remainingSeconds by remember { mutableStateOf(timeoutSeconds) }

    // Countdown timer — auto-deny when it reaches zero
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        val totalMs = timeoutSeconds * 1000L
        while (remainingSeconds > 0) {
            delay(100L)
            val elapsed = System.currentTimeMillis() - startTime
            progress = 1f - (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
            remainingSeconds = ((totalMs - elapsed) / 1000).toInt().coerceAtLeast(0)
        }
        onDeny()
    }

    Dialog(onDismissRequest = onDeny) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header with tool icon and name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Action Approval",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Risk level badge
                RiskBadge(riskLevel = riskLevel)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // Preview
                Text(
                    text = "Action Preview",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (parameters.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))

                    // Parameters detail
                    Text(
                        text = "Parameters",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            parameters.forEach { (key, value) ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "$key: ",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Always allow options
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = alwaysAllowChoice == AlwaysAllowChoice.SESSION,
                        onCheckedChange = {
                            alwaysAllowChoice = if (it) AlwaysAllowChoice.SESSION else AlwaysAllowChoice.NONE
                        },
                    )
                    Text(
                        text = "Allow for this session",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(
                        checked = alwaysAllowChoice == AlwaysAllowChoice.PERMANENT,
                        onCheckedChange = {
                            alwaysAllowChoice = if (it) AlwaysAllowChoice.PERMANENT else AlwaysAllowChoice.NONE
                        },
                    )
                    Text(
                        text = "Always allow permanently",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Timeout progress
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        remainingSeconds <= 10 -> MaterialTheme.colorScheme.error
                        remainingSeconds <= 30 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = "Auto-deny in ${remainingSeconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(12.dp))

                // Approve / Deny buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onDeny) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Deny",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Deny")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onApprove(alwaysAllowChoice) }) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Approve",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Approve")
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(riskLevel: RiskLevel) {
    val backgroundColor = when (riskLevel) {
        RiskLevel.LOW -> Color(0xFF4CAF50).copy(alpha = 0.15f)
        RiskLevel.MEDIUM -> Color(0xFFFF9800).copy(alpha = 0.15f)
        RiskLevel.HIGH -> Color(0xFFF44336).copy(alpha = 0.15f)
    }
    val contentColor = when (riskLevel) {
        RiskLevel.LOW -> Color(0xFF2E7D32)
        RiskLevel.MEDIUM -> Color(0xFFE65100)
        RiskLevel.HIGH -> Color(0xFFC62828)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = riskLevel.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = riskLevel.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

/**
 * Determines risk level for a tool based on its name.
 * Tools that modify data or interact with external services are higher risk.
 */
fun determineRiskLevel(toolName: String): RiskLevel = when {
    toolName in HIGH_RISK_TOOLS -> RiskLevel.HIGH
    toolName in MEDIUM_RISK_TOOLS -> RiskLevel.MEDIUM
    else -> RiskLevel.LOW
}

/**
 * Parses a tool input map into readable key-value pairs for display.
 */
fun parseParametersForDisplay(input: Map<String, Any?>): Map<String, String> {
    return input.mapValues { (_, value) ->
        value?.toString() ?: "null"
    }
}

private val HIGH_RISK_TOOLS = setOf(
    "sms",
    "phone",
    "http",
    "files",
)

private val MEDIUM_RISK_TOOLS = setOf(
    "calendar",
    "contacts",
    "web",
    "skills_author",
    "schedule",
    "openai",
    "app",
)
