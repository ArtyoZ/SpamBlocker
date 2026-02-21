package spam.blocker.ui.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import spam.blocker.G
import spam.blocker.util.Lambda1


@Composable
fun CheckBox(
    checked: Boolean,
    onCheckChange: Lambda1<Boolean>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable ()->Unit)? = null,
) {
    RowVCenterSpaced(
        space = 4,
        modifier = modifier
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {
                    onCheckChange(!checked)
                }
            )
            .clip(MaterialTheme.shapes.small)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = G.palette.teal200,
                uncheckedColor = G.palette.textGrey,
            ),
        )
        label?.let {
            label()
        }
    }
}
