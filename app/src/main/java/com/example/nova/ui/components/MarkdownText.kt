package com.example.nova.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmAccentDark
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmCanvas

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val blocks = parseMarkdownBlocks(markdown)

    Column(modifier = modifier) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Header -> {
                    Text(
                        text = block.text,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    CodeSnippetCard(
                        code = block.code,
                        language = block.language,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Code", block.code))
                            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    SelectionContainer {
                        Text(
                            text = renderInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 22.sp
                            ),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeSnippetCard(
    code: String,
    language: String?,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (language ?: "code").uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.testTag("copy_code_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

sealed class MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String, val language: String?) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        if (line.trim().startsWith("```")) {
            val lang = line.trim().removePrefix("```").trim().takeIf { it.isNotBlank() }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), lang))
            i++
        } else if (line.trim().startsWith("#")) {
            val trimmed = line.trim()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
            val headerText = trimmed.drop(level).trim()
            blocks.add(MarkdownBlock.Header(level, headerText))
            i++
        } else if (line.isNotBlank()) {
            val paraLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].trim().startsWith("```") && !lines[i].trim().startsWith("#")) {
                paraLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString("\n")))
        } else {
            i++
        }
    }
    return blocks
}

fun renderInlineMarkdown(raw: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val len = raw.length

        while (cursor < len) {
            when {
                raw.startsWith("**", cursor) -> {
                    val end = raw.indexOf("**", cursor + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(raw.substring(cursor + 2, end))
                        }
                        cursor = end + 2
                    } else {
                        append(raw[cursor])
                        cursor++
                    }
                }
                raw.startsWith("`", cursor) -> {
                    val end = raw.indexOf("`", cursor + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = WarmAccentContainer,
                                color = WarmAccentDark
                            )
                        ) {
                            append(" " + raw.substring(cursor + 1, end) + " ")
                        }
                        cursor = end + 1
                    } else {
                        append(raw[cursor])
                        cursor++
                    }
                }
                else -> {
                    append(raw[cursor])
                    cursor++
                }
            }
        }
    }
}
