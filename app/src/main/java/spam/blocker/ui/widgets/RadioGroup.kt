package spam.blocker.ui.widgets

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import spam.blocker.G
import spam.blocker.ui.M
import spam.blocker.util.Lambda1

data class RadioItem(
    val text: String,
    val color: Color = Color.Unspecified
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioGroup(
    items: List<RadioItem>,
    selectedIndex: Int,
    onSelect: Lambda1<Int>,
) {
    val C = G.palette

    FlowRowSpaced(8) {
        items.forEachIndexed { idx, item ->
            RowVCenter(
                M.selectable(
                    // make the text also clickable
                    selected = idx == selectedIndex,
                    onClick = {
                        onSelect(idx)
                    },
                ),
            ) {
                RadioButton(
                    selected = idx == selectedIndex,
                    onClick = null,
                    modifier = M
                        .scale(0.7f)
                        .width(24.dp)// reduce the gap between RadioButton and Text
                        .height(10.dp),
                    colors = RadioButtonDefaults.colors(
                        selectedColor = C.teal200,
                        unselectedColor = C.disabled,
                    )
                )
                Text(
                    text = item.text,
                    color = item.color,
                )
            }
        }
    }
}