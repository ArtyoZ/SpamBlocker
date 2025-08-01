package spam.blocker.ui.setting.bot

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import spam.blocker.G
import spam.blocker.R
import spam.blocker.db.Bot
import spam.blocker.db.BotTable
import spam.blocker.service.bot.botJson
import spam.blocker.ui.M
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.theme.SkyBlue
import spam.blocker.ui.widgets.ConfigImportDialog
import spam.blocker.ui.widgets.DividerItem
import spam.blocker.ui.widgets.GreyIcon
import spam.blocker.ui.widgets.LabelItem
import spam.blocker.ui.widgets.MenuButton
import spam.blocker.ui.widgets.Str
import java.util.UUID

// The row:
//         ? [Test] [New]
@Composable
fun BotHeader(
    vm: BotViewModel,
) {
    val ctx = LocalContext.current

    val initialBotToEdit = remember { mutableStateOf(Bot()) }
    val addTrigger = rememberSaveable { mutableStateOf(false) }
    if (addTrigger.value) {
        EditBotDialog(
            trigger = addTrigger,
            initial = initialBotToEdit.value,
            onDismiss = { G.botVM.reload(ctx) },
            onSave = { newBot ->
                // 1. add to db
                BotTable.addNewRecord(ctx, newBot)

                // 2. reload UI
                G.botVM.reload(ctx)
            }
        )
    }

    val importTrigger = remember { mutableStateOf(false) }
    if (importTrigger.value) {
        ConfigImportDialog(
            trigger = importTrigger,
        ) { configJson ->
            val newBot = botJson.decodeFromString<Bot>(configJson).copy(
                id = 0,
                workUUID = UUID.randomUUID().toString(),
            )

            // 1. add to db
            BotTable.addNewRecord(ctx, newBot)
            // 2. reload UI
            G.botVM.reload(ctx)
        }
    }

    val dropdownItems = remember {
        val ret = mutableListOf(
            LabelItem(
                label = ctx.getString(R.string.customize),
                icon = { GreyIcon(R.drawable.ic_note) }
            ) {
                initialBotToEdit.value = Bot()
                addTrigger.value = true
            },
            LabelItem(
                label = ctx.getString(R.string.import_),
                icon = { GreyIcon(R.drawable.ic_backup_import) }
            ) {
                importTrigger.value = true
            },
            DividerItem(),
        )
        ret += BotPresets.map { preset ->
            val bot = preset.newInstance(ctx)
            LabelItem(
                label = bot.desc,
                tooltip = ctx.getString(preset.tooltipId)
            ) {
                initialBotToEdit.value = bot

                addTrigger.value = true
            }
        }
        ret
    }

    LabeledRow(
        labelId = R.string.workflows,
        modifier = M.clickable{ vm.toggleCollapse(ctx) },
        isCollapsed = vm.listCollapsed.value,
        toggleCollapse = { vm.toggleCollapse(ctx) },
        helpTooltip = Str(R.string.help_workflows),
    ) {
        MenuButton(
            label = Str(R.string.new_),
            color = SkyBlue,
            items = dropdownItems,
        )
    }
}
