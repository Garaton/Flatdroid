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
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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

/**
 * Whether developer options are on, for the rows below: a row marked `developer` shows nothing
 * without them, so a page lists its developer settings with no check of its own. The settings
 * screens provide it; elsewhere it is off.
 */
val LocalDeveloperOptions = compositionLocalOf { false }

// Rows for choices: the whole row, label included, is one touch target, so tapping the text
// works like tapping the control (the control itself takes no clicks of its own).

// Used only for pop up dialogs, toggles (SwitchRows) are for settings
@Composable
fun CheckboxRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, developer: Boolean = false) {
    if (developer && !LocalDeveloperOptions.current) return
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
    if (developer && !LocalDeveloperOptions.current) return
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
 * A switch with a title and an optional hint. With a hint, the title has the row's full width and
 * the switch sits beside the hint below it, so a long title doesn't wrap early; without one, the
 * switch is beside the title. The hint is muted, and a disabled row is dimmed. A [developer]
 * setting (shown only with developer options, see [LocalDeveloperOptions]) has a wrench just before its title.
 */
@Composable
fun SwitchRow(
    checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String? = null, enabled: Boolean = true, developer: Boolean = false,
    /** Set while the value is an override of a default: removes the override. */
    onReset: (() -> Unit)? = null,
) {
    if (developer && !LocalDeveloperOptions.current) return
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val hintColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hint == null) {
            SettingTitle(title, developer, Modifier.weight(1f), titleColor, enabled)
            onReset?.let { ResetButton(it) }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingTitle(title, developer, Modifier.weight(1f), titleColor, enabled)
                    onReset?.let { ResetButton(it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = hintColor, modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                }
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

/**
 * Removes an override: the setting goes back to the default it overrides. As tall as a line of
 * text, so showing it moves nothing.
 */
@Composable
fun ResetButton(onReset: () -> Unit) {
    Box(
        modifier = Modifier.size(RESET_SIZE).clip(CircleShape).clickable(role = Role.Button, onClick = onReset),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.RestartAlt,
            contentDescription = stringResource(R.string.settings_use_default),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private val RESET_SIZE = 24.dp
private val RESET_SLOT = 32.dp

/**
 * A setting's title; a [developer] setting has the wrench just before it, so its hint and field
 * stay aligned with the others. The wrench is dimmed with the setting while it is not [enabled].
 */
@Composable
fun SettingTitle(title: String, developer: Boolean, modifier: Modifier = Modifier, color: Color = Color.Unspecified, enabled: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (developer) DeveloperMark(TITLE_MARK_SIZE, enabled)
        Text(title, color = color, modifier = Modifier.weight(1f, fill = false))
    }
}

private val TITLE_MARK_SIZE = 16.dp

@Composable
fun TextRow(
    content: String, onChange: (String) -> Unit, title: String, hint: String? = null, isError: Boolean, enabled: Boolean = true, developer: Boolean = false,
    singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Number,
    /** Lines the field starts with when it isn't [singleLine]; it grows as the text wraps. */
    minLines: Int = 3,
) {
    if (developer && !LocalDeveloperOptions.current) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = content,
            onValueChange = onChange,
            label = { SettingTitle(title, developer, enabled = enabled) },
            supportingText = { if (hint != null) Text(hint) else null },
            isError = isError,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f).keepInViewWhileFocused(),
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
    /** Set while the value is an override of a default: removes the override. */
    onReset: (() -> Unit)? = null,
    /** The setting can be an override: room is kept beside it for [onReset], so the field doesn't change width. */
    resettable: Boolean = onReset != null,
) {
    if (developer && !LocalDeveloperOptions.current) return
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val text = label(selected)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                        label = { SettingTitle(title, developer, enabled = enabled) },
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
        if (resettable) {
            Box(Modifier.size(RESET_SLOT), contentAlignment = Alignment.Center) { onReset?.let { ResetButton(it) } }
        }
    }
}

/** The text style of a card's title: `titleMedium` (16 sp), below the page title's `titleLarge` (22 sp). */
@Composable
fun sectionTitleStyle() = MaterialTheme.typography.titleMedium

/** The wrench in front of a developer setting, so it is told apart from the others. */
@Composable
fun DeveloperMark(size: Dp = 20.dp, enabled: Boolean = true) {
    Icon(
        Icons.Filled.Build,
        contentDescription = stringResource(R.string.developer_setting),
        tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
        modifier = Modifier.size(size),
    )
}
