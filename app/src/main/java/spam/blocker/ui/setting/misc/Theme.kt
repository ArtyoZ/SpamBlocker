package spam.blocker.ui.setting.misc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import spam.blocker.G
import spam.blocker.R
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.widgets.ColorPickerButton
import spam.blocker.ui.widgets.FlowRowSpaced
import spam.blocker.ui.widgets.GreyButton
import spam.blocker.ui.widgets.PopupDialog
import spam.blocker.ui.widgets.Str


@Composable
fun EditThemeDialog(trigger: MutableState<Boolean>) {
    PopupDialog(trigger, transparentBackground = true) {
        FlowRowSpaced(10, vSpace = 20) {
            G.palette.allColors.forEach { color ->

                ColorPickerButton(
                    color = color.state.value,
                    text = Str(color.labelId),
                    okLabel = Str(R.string.ok),
                    clearLabel = Str(R.string.reset),
                ) { newColor ->
                    if (newColor == null)
                        color.reset()
                    else
                        color.update(newColor)
                }
            }
        }
    }
}

@Composable
fun Theme() {
    LabeledRow(R.string.theme) {
        val trigger = remember { mutableStateOf(false) }
        EditThemeDialog(trigger)

        GreyButton(Str(R.string.customize)) {
            trigger.value = true
        }
    }
}