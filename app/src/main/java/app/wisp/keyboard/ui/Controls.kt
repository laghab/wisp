package app.wisp.keyboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import app.wisp.keyboard.kbd.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/** One of the keyboard's line icons (the same drawings the toolbar uses), tinted and sized for settings. */
@Composable
fun WispIcon(icon: Icons.Icon, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIntoCanvas { Icons.draw(it.nativeCanvas, icon, this.size.width / 2f, this.size.height / 2f, this.size.width, tint.toArgb()) } }
}

@Composable
fun Section(title: String, icon: Icons.Icon? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) WispIcon(icon, MaterialTheme.colorScheme.primary, 20.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, name) -> FilterChip(selected == value, { onSelect(value) }, label = { Text(name) }) }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null)
    }
}

@Composable
fun SliderRow(label: String, value: Int, range: IntRange, unit: String = "%", onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value$unit", style = MaterialTheme.typography.bodyMedium)
        Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), modifier = Modifier.semantics { contentDescription = "$label, $value$unit" })
    }
}

@Composable
fun ColorSwatches(options: List<Pair<String, Color>>, selected: String, onSelect: (String) -> Unit) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (name, color) ->
            Box(
                Modifier.size(44.dp).background(color, CircleShape)
                    .then(if (name == selected) Modifier.padding(0.dp) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(name) }
                    .semantics { contentDescription = name; this.selected = name == selected },
                contentAlignment = Alignment.Center,
            ) { if (name == selected) Text("✓", color = if (color.luminance() > 0.5f) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue
