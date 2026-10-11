package org.sarmg.xszc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp

internal object AppShapes {
    // Nominal radii follow the SwiftUI client roles. IosLikeCornerShape adds smooth shoulders;
    // equal pt/dp values alone do not establish a visual match to the native iOS renderer.
    val control = CircleShape
    val dialog = IosLikeCornerShape(CornerSize(36.dp))
    // Account fields are 52 dp tall, so 26 dp already reaches their half-height.
    val field = IosLikeCornerShape(CornerSize(26.dp))
    val group = IosLikeCornerShape(CornerSize(28.dp))
    val notification = IosLikeCornerShape(CornerSize(32.dp))
    val photo = IosLikeCornerShape(CornerSize(25))
    val compact = IosLikeCornerShape(CornerSize(10.dp))
    val sheet = group
}

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF0A84FF), onPrimary = Color.White,
        primaryContainer = Color(0xFF173B64), onPrimaryContainer = Color(0xFFB9DAFF),
        background = Color.Black, surface = Color.Black, onSurface = Color(0xFFF2F2F7),
        onSurfaceVariant = Color(0xFF98989D), surfaceVariant = Color(0xFF2C2C2E),
        secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = Color(0xFFF2F2F7),
        surfaceContainer = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF242426), surfaceContainerHighest = Color(0xFF2C2C2E), surfaceContainerLowest = Color.Black,
        outlineVariant = Color(0xFF38383A), error = Color(0xFFFF453A),
    ) else lightColorScheme(
        primary = Color(0xFF0088FF), onPrimary = Color.White,
        primaryContainer = Color(0xFFD9EAFF), onPrimaryContainer = Color(0xFF0066CC),
        background = Color.White, surface = Color.White, onSurface = Color(0xFF1C1C1E),
        onSurfaceVariant = Color(0xFF808085), surfaceVariant = Color(0xFFE5E5EA),
        secondaryContainer = Color(0xFFF1F1F3), onSecondaryContainer = Color(0xFF1C1C1E),
        surfaceContainer = Color(0xFFF2F2F7), surfaceContainerLow = Color.White,
        surfaceContainerHigh = Color(0xFFF7F7F9), surfaceContainerHighest = Color(0xFFE5E5EA), surfaceContainerLowest = Color.White,
        outlineVariant = Color(0xFFE5E5EA), error = Color(0xFFFF3B30),
    )
    val typography = Typography(
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal),
        labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
        titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    )
    val shapes = Shapes(extraSmall = AppShapes.compact, small = AppShapes.compact,
        medium = AppShapes.group, large = AppShapes.group, extraLarge = AppShapes.dialog)
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}

@Composable
internal fun settingsBackgroundColor(): Color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer

/** Compact visible capsules, with Android's native button semantics and touch handling. */
@Composable
internal fun GalleryToolbarButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FilledTonalButton(onClick = onClick, enabled = enabled, shape = AppShapes.control,
            modifier = modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).height(40.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) { Text(text) }
    }
}

@Composable
internal fun GalleryInlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TextButton(onClick = onClick, enabled = enabled, shape = AppShapes.control,
            modifier = modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).height(24.dp),
            contentPadding = PaddingValues(0.dp)) { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun GallerySelectionButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    size: Dp = 44.dp, enabled: Boolean = true) {
    Box(modifier.size(size).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(23.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(2.dp)
            .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}

@Composable
internal fun AppTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = AppShapes.control, color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("本地", "云端", "传输", "设置").forEachIndexed { index, title ->
                    val active = selected == index
                    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Column(Modifier.weight(1f).height(56.dp).clip(AppShapes.control)
                        .background(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(index) }).testTag("navigation.$index"),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(listOf(R.drawable.ic_photo_library, R.drawable.ic_cloud, R.drawable.ic_transfer, R.drawable.ic_settings)[index]),
                            null, Modifier.size(26.dp), tint = tint)
                        Text(title, color = tint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GalleryEmptyState(title: String, message: String, icon: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
