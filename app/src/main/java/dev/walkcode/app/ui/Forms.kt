package dev.walkcode.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    mono: Boolean = false,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = WC.Off) } },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** A read-only field that opens a dropdown of [options] (null entries render as [noneLabel]). */
@Composable
fun <T> Picker(
    label: String,
    selected: T?,
    options: List<T>,
    render: (T) -> String,
    onSelect: (T?) -> Unit,
    noneLabel: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = selected?.let(render) ?: noneLabel.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Text("▾", color = WC.Muted) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The text field swallows clicks, so a transparent layer on top opens the menu.
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (noneLabel != null) {
                DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); open = false })
            }
            options.forEach { o ->
                DropdownMenuItem(text = { Text(render(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
fun FormColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { content() }
}
