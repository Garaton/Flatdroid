package com.garathon.flatdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.garathon.flatdroid.R

// Rows for choices: the whole row, label included, is one touch target, so tapping the text
// works like tapping the control (the control itself takes no clicks of its own).

// Used only for pop up dialogs, toggles (SwitchRows) are for settings
@Composable
fun CheckboxRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, developer: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (developer) DeveloperMark()
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
    }
}

@Composable
fun RadioRow(selected: Boolean, onSelect: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, developer: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (developer) DeveloperMark()
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
    }
}

/**
 * A switch with a title and an optional hint below it. A [developer] setting (shown only with
 * developer options) has a wrench in front of it.
 */
@Composable
fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String? = null, enabled: Boolean = true, developer: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (developer) DeveloperMark()
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun TextRow(
    content: String, onChange: (String) -> Unit, title: String, hint: String? = null, isError: Boolean, enabled: Boolean = true, developer: Boolean = false,
    singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Number,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (developer) DeveloperMark()
        OutlinedTextField(
            value = content,
            onValueChange = onChange,
            label = { Text(title) },
            supportingText = { if (hint != null) Text(hint) else null },
            isError = isError,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A choice from a list, shaped like a text field: [title] on its border, the [selected] option
 * inside, [hint] below; a tap opens the list. Its text is plain, so it can't be edited or
 * selected. [label] names each option, and also [selected] when it isn't one of [options].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownRow(
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    title: String,
    hint: String? = null,
    enabled: Boolean = true,
    developer: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val text = label(selected)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (developer) DeveloperMark()
        Column(modifier = Modifier.weight(1f)) {
            ExposedDropdownMenuBox(
                expanded = expanded && enabled,
                onExpandedChange = { if (enabled) expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                        .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
                    propagateMinConstraints = true,
                ) {
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = text,
                        innerTextField = {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) colors.unfocusedTextColor else colors.disabledTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = enabled,
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = interactionSource,
                        label = { Text(title) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
                        colors = colors,
                    )
                }
                ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    for (option in options) {
                        DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); expanded = false })
                    }
                }
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) colors.unfocusedSupportingTextColor else colors.disabledSupportingTextColor,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
        }
    }
}

/**
 * A section header on the settings screens. Its look is set here for all of them:
 * [SECTION_TITLE_TOP_PADDING] separates it from the section above, and [sectionTitleStyle]
 * is its text size (setting titles are 16 sp, the page title `headlineSmall` 24 sp).
 */
@Composable
fun SectionTitle(text: String, danger: Boolean = false) {
    Text(
        text,
        style = sectionTitleStyle(),
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = SECTION_TITLE_TOP_PADDING),
    )
}

/** Extra space above each section header. */
val SECTION_TITLE_TOP_PADDING = 12.dp

/** The section headers' text style: `titleLarge` (22 sp). */
@Composable
fun sectionTitleStyle() = MaterialTheme.typography.titleLarge

/** The wrench in front of a developer setting, so it is told apart from the others. */
@Composable
fun DeveloperMark() {
    Icon(
        Icons.Filled.Build,
        contentDescription = stringResource(R.string.developer_setting),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
