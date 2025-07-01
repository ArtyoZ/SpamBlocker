package spam.blocker.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import spam.blocker.G
import spam.blocker.R
import spam.blocker.ui.M
import spam.blocker.ui.setting.api.ApiHeader
import spam.blocker.ui.setting.api.ApiList
import spam.blocker.ui.setting.api.ApiQueryPresets
import spam.blocker.ui.setting.api.ApiReportPresets
import spam.blocker.ui.setting.bot.BotHeader
import spam.blocker.ui.setting.bot.BotList
import spam.blocker.ui.setting.misc.About
import spam.blocker.ui.setting.misc.BackupRestore
import spam.blocker.ui.setting.misc.Language
import spam.blocker.ui.setting.misc.Theme
import spam.blocker.ui.setting.quick.BlockType
import spam.blocker.ui.setting.quick.Contacts
import spam.blocker.ui.setting.quick.Dialed
import spam.blocker.ui.setting.quick.EmergencySituation
import spam.blocker.ui.setting.quick.MeetingMode
import spam.blocker.ui.setting.quick.OffTime
import spam.blocker.ui.setting.quick.RecentApps
import spam.blocker.ui.setting.quick.RepeatedCall
import spam.blocker.ui.setting.quick.SpamDB
import spam.blocker.ui.setting.quick.Stir
import spam.blocker.ui.setting.regex.PushAlertHeader
import spam.blocker.ui.setting.regex.PushAlertList
import spam.blocker.ui.setting.regex.PushAlertViewModel
import spam.blocker.ui.setting.regex.RuleHeader
import spam.blocker.ui.setting.regex.RuleList
import spam.blocker.ui.setting.regex.RuleSearchBox
import spam.blocker.ui.setting.regex.RuleViewModel
import spam.blocker.ui.setting.regex.SmsAlert
import spam.blocker.ui.setting.regex.SmsBomb
import spam.blocker.ui.theme.DarkOrange
import spam.blocker.ui.theme.SkyBlue
import spam.blocker.ui.theme.Teal200
import spam.blocker.ui.theme.White
import spam.blocker.ui.widgets.AnimatedVisibleV
import spam.blocker.ui.widgets.BalloonQuestionMark
import spam.blocker.ui.widgets.Fab
import spam.blocker.ui.widgets.FabWrapper
import spam.blocker.ui.widgets.GreyIcon16
import spam.blocker.ui.widgets.NormalColumnScrollbar
import spam.blocker.ui.widgets.RowVCenter
import spam.blocker.ui.widgets.Section
import spam.blocker.ui.widgets.Str
import spam.blocker.util.Lambda
import spam.blocker.util.isFreshInstall
import spam.blocker.util.spf

const val SettingRowMinHeight = 40

@Composable
fun SettingScreen() {
    val ctx = LocalContext.current

    val testingTrigger = rememberSaveable { mutableStateOf(false) }
    PopupTesting(testingTrigger)

    // Hide FAB when scrolled to the bottom
    val scrollState = rememberScrollState()
    val bottomReached by remember {
        derivedStateOf {
            scrollState.maxValue > 0 && scrollState.value == scrollState.maxValue
        }
    }

    // Show text "Testing" on the testing tube icon, and hide this text once it's clicked.
    val spf = spf.Global(ctx)
    var alsoShowText by remember {
        mutableStateOf(
            ctx.isFreshInstall && !spf.isTestingIconClicked()
        )
    }
    FabWrapper(
        fabRow = { positionModifier ->
            if (G.globallyEnabled.value) {
                Fab(
                    visible = !bottomReached,
                    text = if (alsoShowText) ctx.getString(R.string.title_rule_testing) else null,
                    iconId = R.drawable.ic_tube,
                    iconColor = White,
                    iconSize = 36,
                    bgColor = Teal200,
                    modifier = positionModifier
                ) {
                    testingTrigger.value = true

                    spf.setTestingIconClicked(true)
                    alsoShowText = false
                }
            }
        }
    ) {

        val focusManager = LocalFocusManager.current

        NormalColumnScrollbar(state = scrollState) {
            Column(
                modifier = M
                    .verticalScroll(scrollState)
                    .padding(top = 8.dp)

                    // For hiding the RuleSearchBox when clicking around
                    .clickable(
                        // Disable the clicking ripple effect
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // The RuleSearchBox will be hidden when it loses focus
                        focusManager.clearFocus(true)
                    }
            ) {
                // global
                GloballyEnabled()

                if (G.globallyEnabled.value) {
                    // quick settings
                    Section(
                        title = Str(R.string.quick_settings),
                        horizontalPadding = 8
                    ) {
                        Column {
                            Contacts()
                            Stir()
                            SpamDB()
                            RepeatedCall()
                            Dialed()
                            RecentApps()
                            MeetingMode()
                            OffTime()
                            EmergencySituation()
                            BlockType()
                        }
                    }

                    Section(
                        title = Str(R.string.regex_settings),
                        horizontalPadding = 8
                    ) {
                        Column {
                            // NumberRule / ContentRule / QuickCopy
                            listOf<RuleViewModel>(
                                G.NumberRuleVM,
                                G.ContentRuleVM,
                                G.QuickCopyRuleVM,
                            ).forEach { vm ->
                                LaunchedEffect(true) { vm.reloadDbAndOptions(ctx) }

                                RuleHeader(vm)
                                AnimatedVisibleV(!vm.listCollapsed.value) {
                                    Column {
                                        RuleSearchBox(vm)
                                        RuleList(vm)
                                    }
                                }
                            }

                            // Push Alert
                            LaunchedEffect(true) { PushAlertViewModel.reloadDbAndOptions(ctx) }
                            PushAlertHeader()
                            AnimatedVisibleV(!PushAlertViewModel.listCollapsed.value) {
                                PushAlertList()
                            }

                            // SMS Alert
                            SmsAlert()

                            // SMS Bomb
                            SmsBomb()
                        }
                    }

                    // Instant Query
                    Section(
                        title = Str(R.string.instant_query),
                        horizontalPadding = 8
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            // Api Query list
                            LaunchedEffect(true) { G.apiQueryVM.reloadDb(ctx) }
                            ApiHeader(G.apiQueryVM, ApiQueryPresets)
                            AnimatedVisibleV(!G.apiQueryVM.listCollapsed.value) {
                                ApiList(G.apiQueryVM)
                            }
                        }
                    }

                    // Report Number
                    Section(
                        title = Str(R.string.report_number),
                        horizontalPadding = 8
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            // Api Report list
                            LaunchedEffect(true) { G.apiReportVM.reloadDb(ctx) }
                            ApiHeader(G.apiReportVM, ApiReportPresets)
                            AnimatedVisibleV(!G.apiReportVM.listCollapsed.value) {
                                ApiList(G.apiReportVM)
                            }
                        }
                    }
                }

                // Automation
                Section(
                    title = Str(R.string.automation),
                    horizontalPadding = 8
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        // Bot list
                        LaunchedEffect(true) { G.botVM.reload(ctx) }
                        BotHeader(G.botVM)
                        AnimatedVisibleV(!G.botVM.listCollapsed.value) {
                            BotList()
                        }
                    }
                }

                // Miscellaneous
                Section(
                    title = Str(R.string.miscellaneous),
                    horizontalPadding = 8
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Theme()
                        Language()
                        BackupRestore()
                        About()
                    }
                }
            }
        }
    }
}


@Composable
fun SettingRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    RowVCenter(
        modifier = modifier
            .heightIn(min = SettingRowMinHeight.dp)
            .padding(vertical = 2.dp)
    ) {
        content()
    }
}

@Composable
fun SettingLabel(
    labelId: Int,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Text(
        text = stringResource(id = labelId),
        modifier = modifier,
        maxLines = 1,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = color ?: SkyBlue,
    )
}

// This is used in SettingScreen
@Composable
fun LabeledRow(
    labelId: Int?,
    modifier: Modifier = Modifier,
    color: Color? = null,

    // Padding indentation for labels in Section Group
    paddingHorizontal: Int = 0,

    // Show the question icon or not
    helpTooltip: String? = null,

    // Show a down arrow to indicate the content below is collapsed
    // - null: it's not collapsable
    // - true/false: if it's collapsed or not
    isCollapsed: Boolean? = false,
    toggleCollapse: Lambda? = null,

    // Items on the right side, e.g.: "New" button
    content: @Composable RowScope.() -> Unit,
) {

    SettingRow(
        modifier = modifier
            .clickable {
                // 1. expand/collapse
                if (toggleCollapse != null)
                    toggleCollapse()
            }
            .padding(horizontal = paddingHorizontal.dp),
    ) {
        RowVCenter(
            modifier = M.wrapContentWidth()
        ) {
            // label
            if (labelId != null) {
                SettingLabel(
                    labelId,
                    color = color
                )
            }

            // collapsed indicator
            if (isCollapsed == true) {
                GreyIcon16(
                    iconId = R.drawable.ic_dropdown_arrow,
                )
            }

            // balloon tooltip
            helpTooltip?.let {
                BalloonQuestionMark(it)
            }

            // rest content
            RowVCenter(
                modifier = M.weight(1f),
                horizontalArrangement = Arrangement.End,
            ) {
                content()
            }
        }
    }
}

