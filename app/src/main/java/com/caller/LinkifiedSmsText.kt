package com.caller

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

private const val TAG_URL = "URL_TAG"
private const val TAG_PHONE = "PHONE_TAG"

// Matches phone numbers (7 to 15 digits, optional +, spaces, or hyphens) while avoiding 4-6 digit OTPs
private val PHONE_NUMBER_REGEX = Regex(
    """(?<!\d)(\+?\d[\d\s-]{5,14}\d)(?!\d)"""
)

private data class LinkSpan(
    val start: Int,
    val end: Int,
    val tag: String,
    val value: String
)

@Composable
fun LinkifiedSmsText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    linkColor: Color = Color(0xFF3B82F6),
    style: TextStyle = LocalTextStyle.current,
    onMessageClick: (() -> Unit)? = null,
    onMessageLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotatedText: AnnotatedString = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            val spans = mutableListOf<LinkSpan>()

            // 1. Detect Web URLs (http://, https://, www., bare domains)
            val urlMatcher = Patterns.WEB_URL.matcher(text)
            while (urlMatcher.find()) {
                val start = urlMatcher.start()
                val end = urlMatcher.end()
                val rawUrl = urlMatcher.group() ?: continue
                val normalizedUrl = if (
                    rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true)
                ) {
                    rawUrl
                } else {
                    "https://$rawUrl"
                }
                spans.add(LinkSpan(start, end, TAG_URL, normalizedUrl))
            }

            // 2. Detect Phone Numbers (skipping ranges already matched as part of a URL)
            PHONE_NUMBER_REGEX.findAll(text).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val rawPhone = match.value.trim()
                val digitCount = rawPhone.count { it.isDigit() }

                val overlapsUrl = spans.any { start < it.end && end > it.start }
                if (!overlapsUrl && digitCount in 7..15) {
                    val dialable = buildString {
                        rawPhone.forEachIndexed { index, c ->
                            if (c.isDigit() || (c == '+' && index == 0)) append(c)
                        }
                    }
                    spans.add(LinkSpan(start, end, TAG_PHONE, dialable))
                }
            }

            // 3. Apply Blue + Underline SpanStyle & Annotations
            spans.forEach { span ->
                addStyle(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = span.start,
                    end = span.end
                )
                addStringAnnotation(
                    tag = span.tag,
                    annotation = span.value,
                    start = span.start,
                    end = span.end
                )
            }
        }
    }

    Text(
        text = annotatedText,
        color = textColor,
        style = style,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotatedText, onMessageClick, onMessageLongClick) {
            detectTapGestures(
                onLongPress = {
                    onMessageLongClick?.invoke()
                },
                onTap = { pos ->
                    val layout = layoutResult
                    if (layout != null) {
                        val offset = layout.getOffsetForPosition(pos)

                        // Check if user tapped a URL
                        val urlAnnotation = annotatedText
                            .getStringAnnotations(TAG_URL, offset, offset)
                            .firstOrNull()
                        if (urlAnnotation != null) {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlAnnotation.item)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
                            }
                            return@detectTapGestures
                        }

                        // Check if user tapped a Phone Number
                        val phoneAnnotation = annotatedText
                            .getStringAnnotations(TAG_PHONE, offset, offset)
                            .firstOrNull()
                        if (phoneAnnotation != null) {
                            runCatching {
                                val intent = Intent(
                                    Intent.ACTION_DIAL,
                                    Uri.fromParts("tel", phoneAnnotation.item, null)
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "Cannot dial number", Toast.LENGTH_SHORT).show()
                            }
                            return@detectTapGestures
                        }
                    }

                    // Otherwise, trigger normal bubble click if provided
                    onMessageClick?.invoke()
                }
            )
        }
    )
}
