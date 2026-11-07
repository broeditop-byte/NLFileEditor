package com.notlexi.nlfileeditor.hud

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.ui.theme.NLFileEditorTheme

/**
 * Compact value-input dialog shown above the game. A real activity is used because text input
 * does not work reliably inside a non-focusable overlay window. It only reports the typed value;
 * nothing is written until the user presses "Save / Patch" in the HUD.
 */
class HudEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val change = ChangedValue(
            key = intent.getStringExtra(EXTRA_KEY).orEmpty(),
            oldValue = intent.getStringExtra(EXTRA_OLD).orEmpty(),
            newValue = intent.getStringExtra(EXTRA_NEW).orEmpty(),
            startIndex = intent.getIntExtra(EXTRA_START, -1),
            endIndex = intent.getIntExtra(EXTRA_END, -1),
            kind = if (intent.getStringExtra(EXTRA_KIND) == ValueKind.BOOLEAN.name) ValueKind.BOOLEAN else ValueKind.NUMBER
        )
        if (change.key.isEmpty() || change.startIndex < 0) {
            finish()
            return
        }
        setContent {
            NLFileEditorTheme(darkTheme = true) {
                EditDialog(
                    change = change,
                    onCancel = { finish() },
                    onConfirm = { value ->
                        HudRuntime.edits.tryEmit(value)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_OLD = "old"
        private const val EXTRA_NEW = "new"
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"
        private const val EXTRA_KIND = "kind"

        fun intentFor(context: Context, change: ChangedValue): Intent =
            Intent(context, HudEditActivity::class.java)
                .putExtra(EXTRA_KEY, change.key)
                .putExtra(EXTRA_OLD, change.oldValue)
                .putExtra(EXTRA_NEW, change.newValue)
                .putExtra(EXTRA_START, change.startIndex)
                .putExtra(EXTRA_END, change.endIndex)
                .putExtra(EXTRA_KIND, change.kind.name)
    }
}

@Composable
private fun EditDialog(change: ChangedValue, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(change.newValue) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val isBoolean = change.kind == ValueKind.BOOLEAN
    val shape = RoundedCornerShape(22.dp)

    LaunchedEffect(Unit) {
        if (!isBoolean) {
            focus.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF121833))
                .border(1.dp, NLColors.gradient, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Edit value", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(change.key, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(
                "Detected: ${change.oldValue} \u2192 ${change.newValue}",
                color = NLColors.Success, fontSize = 13.sp
            )
            if (isBoolean) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in listOf("True", "False")) {
                        val chosen = text.equals(option, ignoreCase = true)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (chosen) Color(0x553D7BFF) else Color(0x22FFFFFF))
                                .border(1.dp, if (chosen) NLColors.Blue else Color(0x553D7BFF), RoundedCornerShape(12.dp))
                                .clickable { text = option; error = null },
                            contentAlignment = Alignment.Center
                        ) { Text(option, color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("Cancel", primary = false, modifier = Modifier.weight(1f), onClick = onCancel)
                DialogButton("Set value", primary = true, modifier = Modifier.weight(1f), onClick = {
                    val problem = ChangedValueEditor.validate(change, text)
                    if (problem != null) error = problem else onConfirm(text.trim())
                })
            }
        }
    }
}

private fun Modifier.clip(shape: RoundedCornerShape): Modifier =
    this.then(Modifier.clip(shape))

@Composable
private fun DialogButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val base = modifier.height(44.dp).clip(shape)
    val styled = if (primary) base.background(NLColors.gradient, shape)
    else base.background(Color(0x22FFFFFF)).border(1.dp, Color(0x553D7BFF), shape)
    Box(styled.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
