package ai.affiora.mobileclaw.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.affiora.mobileclaw.data.model.ActionType
import ai.affiora.mobileclaw.data.model.ChatMessage
import ai.affiora.mobileclaw.data.model.ContentSegment
import ai.affiora.mobileclaw.data.model.MessageAction
import ai.affiora.mobileclaw.data.model.MessageRole
import ai.affiora.mobileclaw.data.model.ToolActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    isSpeaking: Boolean = false,
    onSpeak: ((String, String) -> Unit)? = null,
    onActionClicked: ((MessageAction) -> Unit)? = null,
) {
    val isUser = message.role == MessageRole.USER
    val isToolResult = message.role == MessageRole.TOOL_RESULT
    val isSystem = message.role == MessageRole.SYSTEM
    val isAssistant = message.role == MessageRole.ASSISTANT

    // Don't render TOOL_RESULT messages — tool info is embedded in assistant messages now
    if (isToolResult) return

    val horizontalArrangement = when {
        isUser -> Arrangement.End
        else -> Arrangement.Start
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = horizontalArrangement,
    ) {
        when {
            isSystem -> SystemBubble(message)
            else -> StandardBubble(
                message = message,
                isUser = isUser,
                searchQuery = searchQuery,
                onActionClicked = onActionClicked,
                isSpeaking = if (isAssistant) isSpeaking else false,
                onSpeak = if (isAssistant) onSpeak else null,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun StandardBubble(
    message: ChatMessage,
    isUser: Boolean,
    searchQuery: String = "",
    onActionClicked: ((MessageAction) -> Unit)? = null,
    isSpeaking: Boolean = false,
    onSpeak: ((String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val backgroundColor = when {
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val shape = when {
        isUser -> RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
        else -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    // Parse content into text segments and tool activities
    val segments = remember(message.content) {
        if (!isUser) ToolActivity.splitContent(message.content) else listOf(ContentSegment.Text(message.content))
    }

    // Extract plain text for clipboard (strip tool markers)
    val plainText = remember(segments) {
        segments.filterIsInstance<ContentSegment.Text>().joinToString("\n") { it.text }
    }

    Surface(
        shape = shape,
        color = backgroundColor,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MobileClaw", plainText))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            for (segment in segments) {
                when (segment) {
                    is ContentSegment.Tool -> {
                        ToolActivityRow(activity = segment.activity)
                    }
                    is ContentSegment.Text -> {
                        MarkdownText(
                            text = segment.text,
                            color = contentColor,
                            linkColor = if (isUser) contentColor else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Render action buttons
            if (!message.actions.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (action in message.actions) {
                        OutlinedButton(
                            onClick = {
                                when (action.type) {
                                    ActionType.OPEN_URL -> {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.value))
                                        context.startActivity(intent)
                                    }
                                    ActionType.COPY -> {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("MobileClaw", action.value))
                                    }
                                    else -> onActionClicked?.invoke(action)
                                }
                            },
                        ) {
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimestampText(
                    timestamp = message.timestamp,
                    color = contentColor.copy(alpha = 0.6f),
                )
                if (onSpeak != null && !isUser) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onSpeak(message.id, plainText) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isSpeaking) "Stop speaking" else "Speak",
                            modifier = Modifier.size(16.dp),
                            tint = contentColor.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

// ── Tool Activity Row (collapsible inline indicator) ─────────────────────────

@Composable
private fun ToolActivityRow(activity: ToolActivity) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when {
        activity.isPending -> MaterialTheme.colorScheme.tertiary
        activity.isError -> MaterialTheme.colorScheme.error
        else -> Color(0xFF4CAF50) // green
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            )
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Expand/collapse arrow
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            // Pulsing dot for pending state
            if (activity.isPending) {
                val infiniteTransition = rememberInfiniteTransition(label = "toolPulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "toolPulseAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = alpha)),
                )
            }

            // Tool name
            Text(
                text = activity.toolName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Status icon
            if (activity.isPending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = statusColor,
                )
            } else {
                Icon(
                    imageVector = if (activity.isError) Icons.Filled.Close else Icons.Filled.Check,
                    contentDescription = if (activity.isError) "Error" else "Success",
                    modifier = Modifier.size(14.dp),
                    tint = statusColor,
                )
            }
        }

        // Expanded details
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            ) {
                if (activity.input.isNotBlank()) {
                    Text(
                        text = activity.input.take(200),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (activity.result != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val prefix = if (activity.isError) "Error: " else ""
                    Text(
                        text = "$prefix${activity.result.take(200)}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = if (activity.isError) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemBubble(message: ChatMessage) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "System",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "System",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            MarkdownText(
                text = message.content,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                linkColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ToolResultBubble(message: ChatMessage, searchQuery: String = "") {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "Tool result",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = message.toolName ?: "Tool",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TimestampText(
                timestamp = message.timestamp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Markdown Renderer ────────────────────────────────────────────────────────

@Composable
private fun MarkdownText(
    text: String,
    color: Color,
    linkColor: Color = color,
) {
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val codeBlockBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    val segments = remember(text) { splitCodeBlocks(text) }

    Column {
        for (segment in segments) {
            when (segment) {
                is MarkdownSegment.CodeBlock -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    CodeBlockView(
                        code = segment.code,
                        language = segment.language,
                        backgroundColor = codeBlockBackground,
                        textColor = color,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                is MarkdownSegment.Inline -> {
                    val annotated = remember(segment.text, color, linkColor, codeBackground) {
                        parseInlineMarkdown(segment.text, color, linkColor, codeBackground)
                    }
                    val uriHandler = LocalUriHandler.current
                    ClickableText(
                        text = annotated,
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = color,
                        ),
                        onClick = { offset ->
                            annotated.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { annotation ->
                                    try {
                                        uriHandler.openUri(annotation.item)
                                    } catch (_: Exception) {
                                        // ignore bad URLs
                                    }
                                }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(
    code: String,
    language: String?,
    backgroundColor: Color,
    textColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

// ── Markdown Parsing ─────────────────────────────────────────────────────────

private sealed interface MarkdownSegment {
    data class Inline(val text: String) : MarkdownSegment
    data class CodeBlock(val code: String, val language: String?) : MarkdownSegment
}

private fun splitCodeBlocks(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val codeBlockRegex = Regex("```(\\w*)\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0

    for (match in codeBlockRegex.findAll(text)) {
        if (match.range.first > lastEnd) {
            val before = text.substring(lastEnd, match.range.first)
            if (before.isNotBlank()) segments.add(MarkdownSegment.Inline(before))
        }
        val lang = match.groupValues[1].ifBlank { null }
        val code = match.groupValues[2].trimEnd('\n')
        segments.add(MarkdownSegment.CodeBlock(code, lang))
        lastEnd = match.range.last + 1
    }

    if (lastEnd < text.length) {
        val remaining = text.substring(lastEnd)
        if (remaining.isNotBlank()) segments.add(MarkdownSegment.Inline(remaining))
    }

    if (segments.isEmpty()) segments.add(MarkdownSegment.Inline(text))
    return segments
}

private fun parseInlineMarkdown(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color,
): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        for ((lineIndex, line) in lines.withIndex()) {
            if (lineIndex > 0) append("\n")

            // Headings
            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(line.trimStart())
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val headingText = headingMatch.groupValues[2]
                val fontSize = when (level) {
                    1 -> 22.sp
                    2 -> 20.sp
                    3 -> 18.sp
                    else -> 16.sp
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize)) {
                    append(headingText)
                }
                continue
            }

            // Numbered list
            val numberedMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.+)$").find(line)
            if (numberedMatch != null) {
                val indent = numberedMatch.groupValues[1]
                val number = numberedMatch.groupValues[2]
                val content = numberedMatch.groupValues[3]
                append("$indent$number. ")
                appendInlineFormatting(content, textColor, linkColor, codeBackground)
                continue
            }

            // Bullet list
            val bulletMatch = Regex("^(\\s*)[-*]\\s+(.+)$").find(line)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1]
                val content = bulletMatch.groupValues[2]
                append("$indent\u2022 ")
                appendInlineFormatting(content, textColor, linkColor, codeBackground)
                continue
            }

            // Regular line
            appendInlineFormatting(line, textColor, linkColor, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineFormatting(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color,
) {
    val tokenRegex = Regex(
        "\\*\\*(.+?)\\*\\*" +          // group 1: bold
        "|\\*(.+?)\\*" +               // group 2: italic
        "|`([^`]+)`" +                 // group 3: inline code
        "|\\[([^]]+)]\\(([^)]+)\\)"    // group 4,5: link text, url
    )

    var lastEnd = 0
    for (match in tokenRegex.findAll(text)) {
        if (match.range.first > lastEnd) {
            append(text.substring(lastEnd, match.range.first))
        }

        when {
            match.groupValues[1].isNotEmpty() -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[1])
                }
            }
            match.groupValues[2].isNotEmpty() -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[2])
                }
            }
            match.groupValues[3].isNotEmpty() -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    )
                ) {
                    append(" ${match.groupValues[3]} ")
                }
            }
            match.groupValues[4].isNotEmpty() -> {
                val linkText = match.groupValues[4]
                val url = match.groupValues[5]
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) {
                    append(linkText)
                }
                pop()
            }
        }

        lastEnd = match.range.last + 1
    }

    if (lastEnd < text.length) {
        append(text.substring(lastEnd))
    }
}

@Composable
private fun TimestampText(
    timestamp: Long,
    color: Color,
) {
    val formatted = remember(timestamp) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.format(Date(timestamp))
    }

    Text(
        text = formatted,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
