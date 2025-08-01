package spam.blocker.service.bot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import spam.blocker.Events
import spam.blocker.G
import spam.blocker.R
import spam.blocker.config.Configs
import spam.blocker.db.BotTable
import spam.blocker.db.CallTable
import spam.blocker.db.ContentRuleTable
import spam.blocker.db.ImportDbReason
import spam.blocker.db.NumberRuleTable
import spam.blocker.db.QuickCopyRuleTable
import spam.blocker.db.RegexRule
import spam.blocker.db.RuleTable
import spam.blocker.db.SmsTable
import spam.blocker.db.SpamNumber
import spam.blocker.db.SpamTable
import spam.blocker.db.listReportableAPIs
import spam.blocker.db.reScheduleBot
import spam.blocker.db.ruleTableForType
import spam.blocker.def.Def
import spam.blocker.service.checker.Checker
import spam.blocker.service.checker.Checker.RegexRuleChecker
import spam.blocker.ui.M
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.setting.api.tagCategory
import spam.blocker.ui.theme.DimGrey
import spam.blocker.ui.theme.DodgeBlue
import spam.blocker.ui.theme.LocalPalette
import spam.blocker.ui.theme.Pink80
import spam.blocker.ui.theme.Teal200
import spam.blocker.ui.widgets.AnimatedVisibleV
import spam.blocker.ui.widgets.DimGreyLabel
import spam.blocker.ui.widgets.GreenDot
import spam.blocker.ui.widgets.GreyIcon
import spam.blocker.ui.widgets.GreyIcon16
import spam.blocker.ui.widgets.GreyIcon18
import spam.blocker.ui.widgets.GreyIcon20
import spam.blocker.ui.widgets.GreyLabel
import spam.blocker.ui.widgets.LabelItem
import spam.blocker.ui.widgets.NumberInputBox
import spam.blocker.ui.widgets.PriorityBox
import spam.blocker.ui.widgets.RadioGroup
import spam.blocker.ui.widgets.RadioItem
import spam.blocker.ui.widgets.RegexInputBox
import spam.blocker.ui.widgets.RowVCenter
import spam.blocker.ui.widgets.RowVCenterSpaced
import spam.blocker.ui.widgets.Spinner
import spam.blocker.ui.widgets.Str
import spam.blocker.ui.widgets.StrInputBox
import spam.blocker.ui.widgets.SummaryLabel
import spam.blocker.ui.widgets.SwitchBox
import spam.blocker.util.A
import spam.blocker.util.AdbLogger
import spam.blocker.util.Algorithm.compressString
import spam.blocker.util.Algorithm.decompressToString
import spam.blocker.util.CSVParser
import spam.blocker.util.CountryCode
import spam.blocker.util.Now
import spam.blocker.util.Permission
import spam.blocker.util.PermissionWrapper
import spam.blocker.util.PermissiveJson
import spam.blocker.util.PermissivePrettyJson
import spam.blocker.util.Util
import spam.blocker.util.Util.domainFromUrl
import spam.blocker.util.Util.isAlphaNumber
import spam.blocker.util.Xml
import spam.blocker.util.formatAnnotated
import spam.blocker.util.httpRequest
import spam.blocker.util.logi
import spam.blocker.util.regexMatches
import spam.blocker.util.regexMatchesNumber
import spam.blocker.util.regexReplace
import spam.blocker.util.resolveBase64Tag
import spam.blocker.util.resolveHttpAuthTag
import spam.blocker.util.resolveNumberTag
import spam.blocker.util.resolvePathTags
import spam.blocker.util.resolveSHA1Tag
import spam.blocker.util.resolveSmsTag
import spam.blocker.util.resolveTimeTags
import spam.blocker.util.spf
import spam.blocker.util.toMap
import spam.blocker.util.toStringMap
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.net.HttpURLConnection

@Composable
fun NoOptionNeeded() {
    GreyLabel(text = Str(R.string.no_config_needed))
}

@Serializable
@SerialName("CleanupHistory")
class CleanupHistory(
    var expiry: Int = 90 // days
) : IPermissiveAction {
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val now = Now.currentMillis()
        val expireTimeMs = now - expiry.toLong() * 24 * 3600 * 1000

        aCtx.logger?.debug(
            ctx.getString(R.string.cleaning_up_history_db)
                .format("$expireTimeMs")
        )

        CallTable().clearRecordsBeforeTimestamp(ctx, expireTimeMs)
        SmsTable().clearRecordsBeforeTimestamp(ctx, expireTimeMs)

        Events.historyUpdated.fire()

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_cleanup_history)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current
        val nDays = ctx.resources.getQuantityString(R.plurals.days, expiry, expiry)
        SummaryLabel(ctx.getString(R.string.expiry) + ": $nDays")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_cleanup_history)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(R.drawable.ic_history_cleanup)
    }

    @Composable
    override fun Options() {
        NumberInputBox(
            intValue = expiry,
            label = { Text(Str(R.string.expiry_days)) },
            onValueChange = { newVal, hasError ->
                if (!hasError) {
                    expiry = newVal!!
                }
            }
        )
    }
}

const val HTTP_GET = 0
const val HTTP_POST = 1

@Serializable
@SerialName("HttpDownload")
open class HttpDownload(
    var method: Int = HTTP_GET,
    var url: String = "",
    var header: String = "",
    var body: String = "", // post body
) : IPermissiveAction {

    /*
    Split the header string:
        ua: chrome\n
        cookie: xxx\n
        some_key: yyy
    into:
        Map(
            ua => chrome,
            cookie => xxx,
            some_key => yyy,
        )
     */
    private fun splitHeader(allHeadersStr: String): Map<String, String> {
        return allHeadersStr.lines().filter { it.trim().isNotEmpty() }.associate { line ->
            val resolved = line
                .resolveHttpAuthTag()
                .resolveBase64Tag()
            val (key, value) = resolved.split(":").map { it.trim() }
            key to value
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val startTime = System.currentTimeMillis()

        return try {
            // 1. Url
            val resolvedUrl = url
                .resolveTimeTags()
                .resolveNumberTag(
                    cc = aCtx.cc,
                    domestic = aCtx.domestic,
                    fullNumber = aCtx.fullNumber,
                    rawNumber = aCtx.rawNumber,
                )
                .replace(tagCategory, aCtx.realCategory ?: "")
                .resolveSHA1Tag()
            aCtx.logger?.debug(ctx.getString(R.string.resolved_url).format(resolvedUrl))

            // 2. Headers map
            val headersMap = splitHeader(header)
            headersMap.forEach { (key, value) ->
                aCtx.logger?.debug("${ctx.getString(R.string.http_header)}: $key -> $value")
            }

            // 3. post body
            val resolvedBody = body
                .resolveNumberTag(
                    cc = aCtx.cc,
                    domestic = aCtx.domestic,
                    fullNumber = aCtx.fullNumber,
                    rawNumber = aCtx.rawNumber,
                )
                .resolveSmsTag(aCtx.smsContent)
                .replace(tagCategory, aCtx.realCategory ?: "")
            if (method == HTTP_POST) {
                aCtx.logger?.debug("${ctx.getString(R.string.http_post_body)}: $resolvedBody")
            }

            // 4. Send request
            val result = httpRequest(
                scope = aCtx.scope,
                urlString = resolvedUrl,
                headersMap = headersMap,
                method = method,
                postBody = resolvedBody,
            )

            aCtx.logger?.info(
                ctx.getString(R.string.time_cost)
                    .format("${System.currentTimeMillis() - startTime}")
            )

            aCtx.lastOutput = result?.bytes

            val echo = Util.truncate(String(result?.bytes ?: byteArrayOf()))
            if (result?.statusCode == HttpURLConnection.HTTP_OK) {
                aCtx.logger?.success("HTTP: <${result.statusCode}>")
                aCtx.logger?.debug(echo)
                true
            } else {
                aCtx.logger?.error("HTTP: <${result?.statusCode}>, $echo, ${result?.exception}")
                false
            }

        } catch (_: CancellationException) { // a winner is found, others are cancelled
            aCtx.logger?.debug(ctx.getString(R.string.another_thread_is_cancelled))
            false
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        } finally {
            // Save the url for following actions(ImportToSpamDb)
            aCtx.httpUrl = url
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_http_request)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(4) {
            GreyIcon20(R.drawable.ic_link)
            SummaryLabel(" $url")
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_http_download)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray, ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_http)
    }

    @Composable
    override fun Options() {

        StrInputBox(
            text = url,
            label = { Text(Str(R.string.url)) },
            leadingIconId = R.drawable.ic_link,
            placeholder = { DimGreyLabel("https://...") },
            helpTooltip = Str(R.string.help_http_url).format(
                Str(R.string.number_tags),
                Str(R.string.time_tags),
            ),
            onValueChange = { url = it }
        )

        StrInputBox(
            text = header,
            label = { Text(Str(R.string.http_header)) },
            leadingIconId = R.drawable.ic_http_header,
            onValueChange = { header = it },
            helpTooltip = Str(R.string.help_http_header) + "<br>" + Str(R.string.tags_supported) + Str(
                R.string.auth_tags
            ),
            placeholder = { DimGreyLabel("apikey: ABC\nAuth: key\n…") }
        )

        var selected by remember { mutableIntStateOf(method) }
        val options = remember {
            listOf("GET", "POST").mapIndexed { index, label ->
                LabelItem(label = label) {
                    method = index
                    selected = index
                }
            }
        }
        LabeledRow(R.string.http_method) {
            Spinner(options, selected)
        }

        AnimatedVisibleV(selected == HTTP_POST) {

            StrInputBox(
                text = body,
                label = { Text(Str(R.string.http_post_body)) },
                leadingIconId = R.drawable.ic_post,
                onValueChange = { body = it },
                helpTooltip = Str(R.string.help_http_post_body).format(
                    Str(R.string.number_tags) + "<br>" + Str(R.string.sms_tags)
                ),
            )
        }
    }
}


@Serializable
@SerialName("CleanupSpamDB")
class CleanupSpamDB(
    var expiry: Int = 1 // days
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val now = Now.currentMillis()
        val expireTimeMs = now - expiry.toLong() * 24 * 3600 * 1000

        val deletedCount = SpamTable.deleteBeforeTimestamp(ctx, expireTimeMs)

        aCtx.logger?.debug(
            ctx.getString(R.string.cleaning_up_spam_db)
                .format("$deletedCount", "$expireTimeMs")
        )

        // fire event to update the UI
        Events.spamDbUpdated.fire()

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_cleanup_spam_db)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        val nDays = ctx.resources.getQuantityString(R.plurals.days, expiry, expiry)
        SummaryLabel(ctx.getString(R.string.expiry) + ": $nDays")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_cleanup_spam_db)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_db_delete)
    }

    @Composable
    override fun Options() {
        NumberInputBox(
            intValue = expiry,
            label = { Text(Str(R.string.expiry_days)) },
            onValueChange = { newVal, hasError ->
                if (!hasError) {
                    expiry = newVal!!
                }
            }
        )
    }
}

// Dump current settings to a ByteArray
@Serializable
@SerialName("BackupExport")
class BackupExport(
    var includeSpamDB: Boolean = false
) : IPermissiveAction {
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        // Generate config data bytes
        val curr = Configs()
        curr.load(ctx, includeSpamDB)
        val compressed = compressString(curr.toJsonString())

        aCtx.logger?.debug(ctx.getString(R.string.action_backup_export))

        aCtx.lastOutput = compressed
        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_backup_export)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        val yes = ctx.getString(R.string.yes)
        val no = ctx.getString(R.string.no)
        SummaryLabel(ctx.getString(R.string.include_spam_db) + ": ${if (includeSpamDB) yes else no}")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_backup_export)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_backup_export)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var includeDB by remember { mutableStateOf(includeSpamDB) }

        LabeledRow(labelId = R.string.include_spam_db) {
            SwitchBox(includeDB) { on ->
                includeDB = on
                includeSpamDB = on
            }
        }
    }
}

// Backup import from a ByteArray
@Serializable
@SerialName("BackupImport")
class BackupImport(
    var includeSpamDB: Boolean = false
) : IPermissiveAction {
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val input = aCtx.lastOutput as ByteArray

        try {
            val jsonStr = decompressToString(input)
            val newCfg = Configs.createFromJson(jsonStr)
            newCfg.apply(ctx, includeSpamDB)

            aCtx.logger?.debug(ctx.getString(R.string.action_backup_import))

            Events.configImported.fire()
            return true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            return false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_backup_import)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        val yes = ctx.getString(R.string.yes)
        val no = ctx.getString(R.string.no)
        SummaryLabel(ctx.getString(R.string.include_spam_db) + ": ${if (includeSpamDB) yes else no}")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_backup_import)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_backup_import)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var includeDB by remember { mutableStateOf(includeSpamDB) }

        LabeledRow(labelId = R.string.include_spam_db) {
            SwitchBox(includeDB) { on ->
                includeDB = on
                includeSpamDB = on
            }
        }
    }
}

@Serializable
@SerialName("ReadFile")
class ReadFile(
    var dir: String = "{Downloads}",
    var filename: String = "",
) : IFileAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val path = dir.resolvePathTags()

        val fn = filename.resolveTimeTags()

        return try {
            aCtx.logger?.debug(label(ctx) + ": $path/$fn")

            val bytes = Util.readFile(path, fn)
            aCtx.lastOutput = bytes
            true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_read_file)
    }

    @Composable
    override fun Summary() {
        SummaryLabel("$dir/$filename")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_read_file).format(
            ctx.getString(R.string.path_tags),
            ctx.getString(R.string.time_tags)
        )
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_file_read)
    }

    @Composable
    override fun Options() {
        Column {
            StrInputBox(
                text = dir,
                label = { Text(Str(R.string.directory)) },
                helpTooltip = Str(R.string.tags_supported) + Str(R.string.path_tags),
                onValueChange = { dir = it }
            )
            StrInputBox(
                text = filename,
                label = { Text(Str(R.string.filename)) },
                onValueChange = { filename = it }
            )
        }
    }
}

@Serializable
@SerialName("WriteFile")
class WriteFile(
    var dir: String = "{Downloads}",
    var filename: String = "",
) : IFileAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val input = aCtx.lastOutput as ByteArray

        val path = dir.resolvePathTags()
        val fn = filename.resolveTimeTags()

        aCtx.logger?.debug(label(ctx) + ": $path/$fn")

        return try {
            Util.writeFile(path, fn, input)
            true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_write_file)
    }

    @Composable
    override fun Summary() {
        SummaryLabel("$dir/$filename")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_write_file).format(
            ctx.getString(R.string.path_tags),
            ctx.getString(R.string.time_tags)
        )
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_file_write)
    }

    @Composable
    override fun Options() {
        Column {
            StrInputBox(
                text = dir,
                label = { Text(Str(R.string.directory)) },
                helpTooltip = Str(R.string.tags_supported) + Str(R.string.path_tags),
                onValueChange = { dir = it }
            )
            StrInputBox(
                text = filename,
                label = { Text(Str(R.string.filename)) },
                onValueChange = { filename = it }
            )
        }
    }
}

/*
input: ByteArray
output: List<RegexRule>
 */
@Serializable
@SerialName("ParseCSV")
class ParseCSV(
    // a json string contains column map
    var columnMapping: String = "{}"
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val input = aCtx.lastOutput as ByteArray

        return try {
            val csv = CSVParser(
                PushbackReader(BufferedReader(InputStreamReader(ByteArrayInputStream(input)))),
                JSONObject(columnMapping).toStringMap(),
            ).parse()

            val rules = csv.rows.map { row ->
                RegexRule.fromMap(csv.headers.zip(row).toMap())
            }
            aCtx.logger?.debug(ctx.getString(R.string.parsed_n_rules).format("${rules.size}"))

            aCtx.lastOutput = rules
            true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_parse_csv)
    }

    @Composable
    override fun Summary() {
        SummaryLabel(columnMapping)
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_parse_csv)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_csv)
    }

    @Composable
    override fun Options() {
        StrInputBox(
            text = columnMapping,
            label = { Text(Str(R.string.column_mapping)) },
            helpTooltip = Str(R.string.import_csv_columns),
            onValueChange = { columnMapping = it }
        )
    }
}

/*
input: ByteArray
output: List<RegexRule>
 */
@Serializable
@SerialName("ParseXML")
class ParseXML(
    var xpath: String = ""
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val input = aCtx.lastOutput as ByteArray

        return try {
            val rules = Xml.parse(bytes = input, xpath).map {
                RegexRule.fromMap(it)
            }

            aCtx.logger?.debug(ctx.getString(R.string.parsed_n_rules).format("${rules.size}"))

            aCtx.lastOutput = rules
            true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_parse_xml)
    }

    @Composable
    override fun Summary() {
        SummaryLabel("XPath: $xpath")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_parse_xml)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_xml)
    }

    @Composable
    override fun Options() {
        StrInputBox(
            text = xpath,
            label = { Text("XPath") },
            onValueChange = { xpath = it }
        )
    }
}

/*
input: ByteArray
output: List<RegexRule>
 */
@Serializable
@SerialName("RegexExtract")
class RegexExtract(
    var pattern: String = "",
    var regexFlags: Int = Def.DefaultRegexFlags,
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val input = aCtx.lastOutput as ByteArray

        return try {
            val opts = Util.flagsToRegexOptions(regexFlags)

            val haystack = input.toString(Charsets.UTF_8)
            val all = pattern.toRegex(opts).findAll(haystack)

            val rules = all.map {
                RegexRule(pattern = it.groupValues[1])
            }.toList()

            aCtx.logger?.debug(ctx.getString(R.string.parsed_n_rules).format("${rules.size}"))

            aCtx.lastOutput = rules
            true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_regex_extract)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        val label = ctx.getString(R.string.regex_pattern)
        SummaryLabel("$label: $pattern")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_regex_extract)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_regex_capture)
    }

    @Composable
    override fun Options() {
        val flags = remember { mutableIntStateOf(regexFlags) }
        RegexInputBox(
            regexStr = pattern,
            label = { Text(Str(R.string.regex_pattern)) },
            regexFlags = flags,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    pattern = newVal
                }
            },
            onFlagsChange = {
                flags.intValue = it
                regexFlags = it
            }
        )
    }
}

/*
input: List<RegexRule> or QueryResult
output: null
 */
@Serializable
@SerialName("ImportToSpamDB")
class ImportToSpamDB(
    // Only presets can use ImportDbReason.ByAPI
    // This is for internal app use, not displayed on GUI
    val importReason: ImportDbReason = ImportDbReason.Manually,
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val rules = aCtx.lastOutput as List<*> // it's actually `List<RegexRule>`

        return try {
            val now = System.currentTimeMillis()

            val numbers = rules.map {
                SpamNumber(
                    peer = (it as RegexRule).pattern,
                    time = now,
                    importReason = importReason,
                    // when import after API query, log the api domain for future reporting
                    importReasonExtra = if (importReason == ImportDbReason.ByAPI)
                        domainFromUrl(aCtx.httpUrl) else null,
                )
            }

            if (numbers.isNotEmpty()) {
                aCtx.logger?.debug(
                    ctx.getString(R.string.add_n_numbers_to_spam_db)
                        .format("${numbers.size}")
                )
            }

            val errorStr = SpamTable.addAll(ctx, numbers)

            // Fire a global event to update UI
            Events.spamDbUpdated.fire()

            if (errorStr == null) {
                true
            } else {
                aCtx.logger?.error(errorStr)
                false
            }
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_import_to_spam_db)
    }

    @Composable
    override fun Summary() {
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_import_to_spam_db)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_db_add)
    }

    @Composable
    override fun Options() {
        NoOptionNeeded()
    }
}

enum class ImportType {
    Create,
    Replace, // create if not found
    Merge, // create if not found
}

/*
input: List<RegexRule>
output: null
 */
@Serializable
@SerialName("ImportAsRegexRule")
class ImportAsRegexRule(
    var description: String = "",
    var priority: Int = 0,
    var isWhitelist: Boolean = false,
    var importType: ImportType = ImportType.Create,
    var importAs: Int = Def.ForNumber,
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val rules = aCtx.lastOutput as List<*> // it's actually `List<RegexRule>`

        return try {
            val numberList = rules.map { (it as RegexRule).pattern }.distinct()

            // Do nothing when there isn't any number to add, to prevent adding a `()`
            if (numberList.isEmpty()) {
                aCtx.logger?.warn(ctx.getString(R.string.nothing_to_import))
                true
            }

            aCtx.logger?.info(
                ctx.getString(R.string.importing_n_numbers)
                    .format("${numberList.size}")
            )

            when (importType) {
                ImportType.Create -> create(ctx, aCtx, numberList)
                ImportType.Replace -> replace(ctx, aCtx, numberList)
                else -> merge(ctx, aCtx, numberList)
            }

            // fire event to update the UI
            Events.regexRuleUpdated.fire()

            true
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            false
        }
    }

    private fun getTable(): RuleTable {
        return when (importAs) {
            Def.ForNumber -> NumberRuleTable()
            Def.ForSms -> ContentRuleTable()
            else -> QuickCopyRuleTable()
        }
    }

    private fun create(ctx: Context, aCtx: ActionContext, numbers: List<String>) {
        // Join numbers to `11|22|33...`
        val combinedPattern = numbers.joinToString("|")

        aCtx.logger?.debug(
            ctx.getString(R.string.create_rule_with_numbers)
                .format("${numbers.size}")
        )

        val newRule = RegexRule(
            pattern = "($combinedPattern)",
            description = description,
            priority = priority,
            isBlacklist = !isWhitelist,
        )
        getTable().addNewRule(ctx, newRule)
    }

    private fun replace(ctx: Context, aCtx: ActionContext, numbers: List<String>) {
        aCtx.logger?.debug(
            ctx.getString(R.string.replace_rule_with_desc)
                .format(description)
        )

        val table = getTable()
        val oldRules = table.findRuleByDesc(ctx, description)
        if (oldRules.isEmpty()) {
            aCtx.logger?.warn(
                ctx.getString(R.string.rule_with_desc_not_found)
                    .format(description)
            )
            create(ctx, aCtx, numbers)
        } else {
            // 1. delete the previous rule
            table.deleteById(ctx, oldRules[0].id)
            // 2. create a new one
            create(ctx, aCtx, numbers)
        }
    }

    private fun merge(ctx: Context, aCtx: ActionContext, numbers: List<String>) {
        aCtx.logger?.debug(
            ctx.getString(R.string.merging_rule_with_desc)
                .format(description)
        )

        val table = getTable()
        val oldRules = table.findRuleByDesc(ctx, description)
        if (oldRules.isEmpty()) {
            aCtx.logger?.warn(
                ctx.getString(R.string.rule_with_desc_not_found)
                    .format(description)
            )

            create(ctx, aCtx, numbers)
        } else {
            val previous = oldRules[0]
            val oldNumbers = previous.pattern.trim('(', ')').split('|')

            val all = (oldNumbers + numbers).distinct()

            aCtx.logger?.debug(
                ctx.getString(R.string.regex_count_after_merge)
                    .format("${oldNumbers.size}", "${all.size}")
            )

            table.updateRuleById(
                ctx, previous.id, previous.copy(
                    pattern = "(" + all.joinToString("|") + ")"
                )
            )
        }
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_import_as_regex_rule)
    }

    @Composable
    override fun Summary() {
        SummaryLabel(description)
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_import_as_regex_rule)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_regex)
    }

    @Composable
    override fun Options() {
        val ctx = LocalContext.current
        val C = LocalPalette.current
        Column {
            StrInputBox(
                text = description,
                label = { Text(Str(R.string.description)) },
                onValueChange = { description = it }
            )
            PriorityBox(priority) { newVal, hasError ->
                if (!hasError) {
                    priority = newVal!!
                }
            }
            // Import to Number/Content/QuickCopy
            LabeledRow(R.string.import_as) {
                var selected by rememberSaveable {
                    mutableIntStateOf(importAs)
                }

                val items = listOf(
                    Str(R.string.number_rule),
                    Str(R.string.content_rule),
                    Str(R.string.quick_copy),
                )
                Spinner(
                    items = items.mapIndexed { index, label ->
                        LabelItem(
                            label = label,
                        ) {
                            selected = index
                            importAs = index
                        }
                    },
                    selected = selected,
                )
            }
            // Type Whitelist/ Blacklist
            LabeledRow(R.string.type) {
                var applyToWorB by rememberSaveable { mutableIntStateOf(if (isWhitelist) 0 else 1) }
                val items = listOf(
                    RadioItem(Str(R.string.allow), C.pass),
                    RadioItem(Str(R.string.block), C.block),
                )
                RadioGroup(items = items, selectedIndex = applyToWorB) {
                    applyToWorB = it
                    isWhitelist = it == 0
                }
            }
            // Type Create/Replace/Merge
            LabeledRow(
                R.string.mode,
                helpTooltip = Str(R.string.help_regex_action_add_mode),
            ) {
                var selected by rememberSaveable {
                    mutableIntStateOf(ImportType.entries.indexOf(importType))
                }

                val items = ctx.resources.getStringArray(R.array.regex_action_add_mode)
                Spinner(
                    items = items.mapIndexed { index, label ->
                        LabelItem(
                            label = label,
                        ) {
                            selected = index
                            importType = ImportType.entries[index]
                        }
                    },
                    selected = selected,
                )
            }
        }
    }
}

/*
input: List<RegexRule>
output: List<RegexRule>
 */
@Serializable
@SerialName("ConvertNumber")
class ConvertNumber(
    var from: String = "",
    var flags: Int = Def.DefaultRegexFlags,
    var to: String = "",
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val rules = aCtx.lastOutput as List<*> // List<RegexRule>

        aCtx.logger?.debug(
            ctx.getString(R.string.replace_number_from_to)
                .format(from, to)
        )

        val clearedRuleList = rules.map {
            val r = it as RegexRule

            val newNum = from.toRegex().replace(r.pattern, to)

            r.copy(
                pattern = newNum
            )
        }
        aCtx.lastOutput = clearedRuleList
        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_convert_number)
    }

    @Composable
    override fun Summary() {
        SummaryLabel("$from -> $to")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_convert_number)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_replace)
    }

    @Composable
    override fun Options() {

        val flagsState = remember { mutableIntStateOf(flags) }
        RegexInputBox(
            label = { Text(Str(R.string.replace_from)) },
            placeholder = {
                DimGreyLabel(
                    Str(R.string.regex_pattern) + "\n"
                            + Str(R.string.for_example) + " " + "(\"|-)"
                )
            },
            regexStr = from,
            onRegexStrChange = { newVal, hasErr ->
                if (!hasErr) {
                    from = newVal
                }
            },
            regexFlags = flagsState,
            onFlagsChange = {
                flagsState.intValue = it
                flags = it
            }
        )
        StrInputBox(
            label = { Text(Str(R.string.replace_to)) },
            text = to,
            onValueChange = {
                to = it
            }
        )
    }
}

/*
input: None
output: Map<forType, List<RegexRule>>
use case: auto disable rules: https://github.com/aj3423/SpamBlocker/issues/190
 */
@Serializable
@SerialName("FindRules")
class FindRules(
    var pattern: String = "", // description pattern
    var flags: Int = Def.DefaultRegexFlags,
) : IPermissiveAction {

    // This is used in CalendarPreprocessor.
    // Rules have been loaded into memory as List<Checker.RegexRule>
    fun findInMemory(ctx: Context, aCtx: ActionContext) : Boolean {
        val cCtx = aCtx.cCtx!!

        val map = listOf(
            Def.ForNumber, Def.ForSms,
        ).associateWith { type ->
            cCtx.checkers.filter {
                if (type == Def.ForNumber)
                    it is RegexRuleChecker && it !is Checker.Content
                else
                    it is Checker.Content
            }.map {
                (it as RegexRuleChecker).rule
            }
        }

        aCtx.lastOutput = map

        return true
    }
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        if (aCtx.isInMemory) {
            return findInMemory(ctx, aCtx)
        }

        // foundPair is Pair<tableIndex, List<RegexRule>>?
        val map = listOf(
            Def.ForNumber, Def.ForSms, Def.ForQuickCopy
        )
            .associateWith {
                ruleTableForType(it).listAll(ctx).filter {
                    pattern.regexMatches(it.description, flags)
                }
            }

        aCtx.logger?.debug(
            ctx.getString(R.string.find_rule_with_desc)
                .formatAnnotated("${map.values.sumOf { it.size }}".A(Teal200), pattern.A(DimGrey))
        )

        aCtx.lastOutput = map
        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_find_rules)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        SummaryLabel(pattern)
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_find_rules)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_find)
    }

    @Composable
    override fun Options() {
        val flagsState = remember { mutableIntStateOf(flags) }
        RegexInputBox(
            label = { Text(Str(R.string.description)) },
            placeholder = {
                DimGreyLabel(
                    Str(R.string.regex_pattern) + "\n"
                            + Str(R.string.for_example) + "\n"
                            + ".*"
                )
            },
            regexStr = pattern,
            onRegexStrChange = { newVal, hasErr ->
                if (!hasErr) {
                    pattern = newVal
                }
            },
            regexFlags = flagsState,
            onFlagsChange = {
                flagsState.intValue = it
                flags = it
            }
        )
    }
}

/*
input: Map<forType, List<RegexRule>>
output: none
 */
@Serializable
@SerialName("ModifyRules")
class ModifyRules(
    var config: String = "",
) : IPermissiveAction {

    fun modifyInMemory(ctx: Context, aCtx: ActionContext): Boolean {
        val rulesMap = aCtx.lastOutput as Map<Int, List<RegexRule>>

        val cCtx = aCtx.cCtx!!

        val mapConfig = JSONObject(config).toMap()

        rulesMap.forEach { (forType, ruleList) ->

            cCtx.checkers.filter {
                if (forType == Def.ForNumber)
                    it is RegexRuleChecker && it !is Checker.Content
                else
                    it is Checker.Content
            }.filter { checker ->
                ruleList.any { it.id == (checker as RegexRuleChecker).rule.id }
            }.forEach {
                val rule = (it as RegexRuleChecker).rule

                val strOrigin = PermissiveJson.encodeToString(rule)
                val mapOrigin = JSONObject(strOrigin).toMap()

                val mapModified = mapOrigin + mapConfig // override with mapModify

                val newRule = PermissiveJson.decodeFromString<RegexRule>(JSONObject(mapModified).toString())

                aCtx.logger?.debug(
                    ctx.getString(R.string.rule_updated_temporarily)
                        .formatAnnotated(
                            rule.summary().A(Teal200),
                            config.A(DimGrey)
                        )
                )
                it.rule = newRule
            }
        }

        return true
    }
    @Suppress("UNCHECKED_CAST")
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        if (aCtx.isInMemory) {
            return modifyInMemory(ctx, aCtx)
        }

        val rulesMap = aCtx.lastOutput as Map<Int, List<RegexRule>>

        aCtx.logger?.debug(
            ctx.getString(R.string.modify_n_rules)
                .format("${rulesMap.values.sumOf { it.size} }")
        )

        try {
            val mapConfig = JSONObject(config).toMap()

            rulesMap.forEach { (forType, ruleList) ->
                val table = ruleTableForType(forType)

                ruleList.forEach { rule ->
                    val strOrigin = PermissiveJson.encodeToString(rule)
                    val mapOrigin = JSONObject(strOrigin).toMap()

                    val mapModified = mapOrigin + mapConfig // override with mapModify

                    val newRule =
                        PermissiveJson.decodeFromString<RegexRule>(JSONObject(mapModified).toString())
                    table.updateRuleById(ctx, rule.id, newRule)

                    aCtx.logger?.debug(
                        ctx.getString(R.string.rule_updated)
                            .formatAnnotated(
                                rule.summary().A(Teal200),
                                config.A(DimGrey)
                            )
                    )
                }
            }
        } catch (e: Exception) {
            aCtx.logger?.error("$e")
            return false
        }

        // fire event to update the UI
        Events.regexRuleUpdated.fire()

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_modify_rules)
    }

    @Composable
    override fun Summary() {
        SummaryLabel(config)
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_modify_rules)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_replace)
    }

    @Composable
    override fun Options() {
        StrInputBox(
            label = { Text(Str(R.string.config_text)) },
            placeholder = { DimGreyLabel(Str(R.string.action_modify_rules_placeholder)) },
            text = config,
            onValueChange = {
                config = it
            }
        )
    }
}

/*
input: None
output: None
 */
@Serializable
@SerialName("EnableWorkflow")
class EnableWorkflow(
    var enable: Boolean = false,
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val workTag = aCtx.workTag

        aCtx.logger?.debug("${label(ctx)}: $workTag")

        // null when Testing, do nothing
        if (workTag == null) {
            aCtx.logger?.debug(ctx.getString(R.string.skip_for_testing))
            return true
        }

        val bot = BotTable
            .findByWorkUuid(ctx, workTag)
            ?.copy(enabled = enable)

        if (bot != null) {
            // 1. enable/disable bot
            BotTable.updateById(ctx, bot.id, bot)

            // 2. reSchedule
            reScheduleBot(ctx, bot)

            // 3. fire event
            Events.botUpdated.fire()
        }

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_enable_workflow)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        SummaryLabel("${ctx.getString(R.string.enable)}: ${ctx.getString(if (enable) R.string.yes else R.string.no)}")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_enable_workflow)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_workflow)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var enabled by remember { mutableStateOf(enable) }

        LabeledRow(labelId = R.string.enable) {
            SwitchBox(enabled) { on ->
                enabled = on
                enable = on
            }
        }
    }
}

/*
input: None
output: None
 */
@Serializable
@SerialName("EnableApp")
class EnableApp(
    var enable: Boolean = true,
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        spf.Global(ctx).setGloballyEnabled(enable)
        G.globallyEnabled.value = enable

        aCtx.logger?.debug("${ctx.getString(R.string.action_enable_app)}: $enable")

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_enable_app)
    }

    @Composable
    override fun Summary() {
        val ctx = LocalContext.current

        SummaryLabel("${ctx.getString(R.string.enable)}: ${ctx.getString(if (enable) R.string.yes else R.string.no)}")
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_enable_app)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_toggle)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var enabled by remember { mutableStateOf(enable) }

        LabeledRow(labelId = R.string.enable) {
            SwitchBox(enabled) { on ->
                enabled = on
                enable = on
            }
        }
    }
}

// For "API Query" only, not for Workflows.
// This action parses the incoming number and fill the ActionContext with cc/domestic/number,
//  which can be used in following actions like HttpRequest.
@Serializable
// The historical name, it should be renamed to `InterceptCall`.
// If you know how to rename it with history compatibility, please answer here:
//   https://stackoverflow.com/q/79415650/2219196
@SerialName("ParseIncomingNumber")
class InterceptCall(
    var numberFilter: String = ".*",
) : IAction {

    override fun requiredPermissions(ctx: Context): List<PermissionWrapper> {
        return listOf(
            PermissionWrapper(
                Permission.phoneState,
                prompt = ctx.getString(R.string.auto_detect_cc_permission)
            )
        )
    }

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        // The `rawNumber` is set by the workflow caller before it's executed.
        val rawNumber = aCtx.rawNumber!!
        aCtx.logger?.debug("${label(ctx)}: $rawNumber")

        // Skip alpha/empty numbers, such as: Microsoft and ""
        if (rawNumber.isEmpty() || isAlphaNumber(rawNumber)) {
            aCtx.logger?.warn(ctx.getString(R.string.skip_alpha_empty_number))
            return false
        }

        val matchesFilter = numberFilter.toRegex().matches(rawNumber)
        if (!matchesFilter) {
            aCtx.logger?.debug(
                ctx.getString(R.string.number_not_match_filter)
                    .format(rawNumber, numberFilter)
            )
            return false
        }

        val clearedNumber = Util.clearNumber(rawNumber)

        if (rawNumber.startsWith("+")) {
            aCtx.fullNumber = clearedNumber
            val (ok, cc, domestic) = CountryCode.parseCcDomestic(clearedNumber)
            if (ok) { // +1 222 333 4444 mobile format
                aCtx.cc = cc
                aCtx.domestic = domestic
            } else { // unknown error
                aCtx.logger?.error(ctx.getString(R.string.fail_detect_cc) + " " + ctx.getString(R.string.bug_number))
                return false
            }
            return true
        } else { // not start with "+"
            val cc = CountryCode.current(ctx)
            if (cc == null) {
                aCtx.logger?.error(ctx.getString(R.string.fail_detect_cc))
                return false
            }

            // Not sure if it's possible to have a number start with CC but has no leading `+`,
            //  for instance: 33xxxxxxxx
            // Check the part xxxxxxxx, if it's still a valid number for cc==33,
            //   then the xxxxxxxx is the domestic part.
            // Otherwise, the entire 33xxxxxxxx is a domestic number
            if (clearedNumber.startsWith(cc.toString())) {
                val rest = clearedNumber.substring(cc.toString().length)
                val pnUtil = PhoneNumberUtil.getInstance()
                val n = Phonenumber.PhoneNumber().apply {
                    countryCode = cc
                    nationalNumber = rest.toLong()
                }
                if (pnUtil.isValidNumber(n)) { // the number start with CC
                    aCtx.cc = cc.toString()
                    aCtx.domestic = rest
                } else { // it's simply a domestic number
                    aCtx.cc = cc.toString()
                    aCtx.domestic = clearedNumber
                }
            } else { // it's simply a domestic number
                aCtx.cc = cc.toString()
                aCtx.domestic = clearedNumber
            }
        }

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_intercept_call)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(4) {
            GreyIcon20(R.drawable.ic_filter)
            SummaryLabel(numberFilter)
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_intercept_call).format(
            ctx.getString(R.string.number_tags)
        )
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon20(iconId = R.drawable.ic_call)
    }

    @Composable
    override fun Options() {
        val dummyFlags = remember { mutableIntStateOf(Def.FLAG_REGEX_RAW_NUMBER) }
        RegexInputBox(
            regexStr = numberFilter,
            label = { Text(Str(R.string.number_filter)) },
            leadingIcon = { GreyIcon18(R.drawable.ic_filter) },
            helpTooltipId = R.string.help_number_filter,
            placeholder = { DimGreyLabel(".*") },
            regexFlags = dummyFlags,
            showFlagsIcon = false,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    numberFilter = newVal
                }
            },
            onFlagsChange = { }
        )
    }
}


// This action must be the first action of the workflow.
@Serializable
@SerialName("InterceptSms")
class InterceptSms(
) : IPermissiveAction {
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        // Nothing to do
        // It just needs to be there, as the first action in the workflow.
        // Because new SMS will only be checked by such workflows.
        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_intercept_sms)
    }

    @Composable
    override fun Summary() {
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_intercept_sms)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon20(iconId = R.drawable.ic_sms)
    }

    @Composable
    override fun Options() {
        NoOptionNeeded()
    }
}

// The return value of the Action ParseQueryResult,
//  and it will be saved along with other information in HistoryTable.
@Serializable
@SerialName("ApiQueryResult")
data class ApiQueryResult(
    val determined: Boolean = false,
    // These values are only useful when determined == true
    val isSpam: Boolean = false,
    val category: String? = null,
    val serverEcho: String? = null,
)

@Serializable
@SerialName("ParseQueryResult")
class ParseQueryResult(
    var negativeSig: String = "", // positive signature
    var negativeFlags: Int = Def.DefaultRegexFlags,

    var positiveSig: String = "",
    var positiveFlags: Int = Def.DefaultRegexFlags,

    var categorySig: String = "",
    var categoryFlags: Int = Def.DefaultRegexFlags,
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        var input = if (aCtx.lastOutput is ByteArray) {
            aCtx.lastOutput as ByteArray
        } else {
            aCtx.lastParsedQueryData!!
        }
        aCtx.lastParsedQueryData = input // Save for following `ParseQueryResult` actions

        val html = String(input)

        aCtx.logger?.debug(label(ctx))

        // 1. negative
        val negativeOpts = Util.flagsToRegexOptions(negativeFlags)
        val isNegative = if (negativeSig.isEmpty())
            null
        else
            negativeSig.toRegex(negativeOpts).containsMatchIn(html)

        // 2. positive
        val positiveOpts = Util.flagsToRegexOptions(positiveFlags)
        val isPositive = if (positiveSig.isEmpty())
            null
        else
            positiveSig.toRegex(positiveOpts).containsMatchIn(html)

        var category: String? = null

        val determined = isNegative == true || isPositive == true

        // 3. category
        if (determined) {
            if (categorySig.trim().isNotEmpty()) {
                val categoryOpts = Util.flagsToRegexOptions(categoryFlags)
                category = categorySig.trim().toRegex(categoryOpts).findAll(html)
                    .map {
                        it.groups
                            .drop(1)
                            .filterNotNull()
                            .firstOrNull()?.value
                    }
                    .filterNot { it.isNullOrEmpty() }
                    .joinToString(" ")
            }
        }

        // show log
        if (isNegative == true) {
            aCtx.logger?.error(
                ctx.getString(R.string.identified_as_spam)
                    .format(category ?: "")
            )
        } else if (isPositive == true) {
            aCtx.logger?.success(
                ctx.getString(R.string.identified_as_valid)
                    .format(category ?: "")
            )
        } else {
            aCtx.logger?.debug(ctx.getString(R.string.unidentified_number))
        }

        // Only update the racingResult when it's the first `ParseQueryResult` or determined.
        if (determined || aCtx.racingResult == null) {
            val result = ApiQueryResult(
                determined = determined,
                isSpam = isNegative == true,
                category = category,
                serverEcho = html,
            )
            aCtx.lastOutput = result
            aCtx.racingResult = result
        }

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_parse_query_result)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(4) {
            if (negativeSig.isNotEmpty()) {
                RowVCenter {
                    GreyIcon16(R.drawable.ic_no)
                    SummaryLabel(negativeSig)
                }
            }

            if (positiveSig.isNotEmpty()) {
                RowVCenter {
                    GreyIcon16(R.drawable.ic_yes)
                    SummaryLabel(positiveSig)
                }
            }

            if (categorySig.isNotEmpty()) {
                RowVCenter {
                    GreyIcon16(R.drawable.ic_category)
                    SummaryLabel(categorySig)
                }
            }
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_parse_query_result)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.ByteArray, ParamType.InstantQueryResult)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None, ParamType.InstantQueryResult)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_find_check)
    }

    @Composable
    override fun Options() {
        val negativeFlagsCopy = remember { mutableIntStateOf(negativeFlags) }
        RegexInputBox(
            regexStr = negativeSig,
            label = { Text(Str(R.string.negative_identifier)) },
            leadingIcon = { GreyIcon18(R.drawable.ic_no) },
            helpTooltipId = R.string.help_negative_identifier,
            placeholder = { DimGreyLabel(Str(R.string.hint_negative_identifier)) },
            regexFlags = negativeFlagsCopy,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    negativeSig = newVal
                }
            },
            onFlagsChange = {
                negativeFlagsCopy.intValue = it
                negativeFlags = it
            }
        )
        val positiveFlagsCopy = remember { mutableIntStateOf(positiveFlags) }
        RegexInputBox(
            regexStr = positiveSig,
            label = { Text(Str(R.string.positive_identifier)) },
            leadingIcon = { GreyIcon18(R.drawable.ic_yes) },
            helpTooltipId = R.string.help_positive_identifier,
            placeholder = { DimGreyLabel(Str(R.string.hint_positive_identifier)) },
            regexFlags = positiveFlagsCopy,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    positiveSig = newVal
                }
            },
            onFlagsChange = {
                positiveFlagsCopy.intValue = it
                positiveFlags = it
            }
        )
        val reasonFlagsCopy = remember { mutableIntStateOf(categoryFlags) }
        RegexInputBox(
            regexStr = categorySig,
            label = { Text(Str(R.string.category_identifier)) },
            leadingIcon = { GreyIcon18(R.drawable.ic_category) },
            helpTooltipId = R.string.help_category_identifier,
            placeholder = { DimGreyLabel(Str(R.string.hint_category_identifier)) },
            regexFlags = reasonFlagsCopy,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    categorySig = newVal
                }
            },
            onFlagsChange = {
                reasonFlagsCopy.intValue = it
                categoryFlags = it
            }
        )
    }
}

// Generate a List<RegexRule> for next step ImportToSpamDB
@Serializable
@SerialName("FilterSpamResult")
class FilterSpamResult() : IPermissiveAction {
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val input = aCtx.lastOutput as ApiQueryResult

        aCtx.lastOutput = if (input.determined && input.isSpam) {
            listOf(
                RegexRule(
                    pattern = aCtx.rawNumber!!,
                    isBlacklist = true,
                )
            )
        } else
            listOf()

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_filter_query_result)
    }

    @Composable
    override fun Summary() {
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_filter_query_result)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.InstantQueryResult)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.RuleList)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_filter)
    }

    @Composable
    override fun Options() {
        NoOptionNeeded()
    }
}

// Spam category config, for reporting number.
@Serializable
@SerialName("CategoryConfig")
class CategoryConfig(
    // e.g.:
    //  {
    //    "{fraud}" to "G_FRAUD",
    //    "{advertising}" to "E_ADVERTISING",
    //    ...
    //  }
    var map: Map<String, String> = mapOf()
) : IPermissiveAction {

    // It gets the ActionContext.tagCategory tag and fill the ActionContext.realCategory
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        // 1. check it in the map config
        var realCategoryStr = map[aCtx.tagCategory]

        if (realCategoryStr == null) {
            aCtx.logger?.warn(
                ctx.getString(R.string.missing_category).formatAnnotated(
                    aCtx.tagCategory!!.A(DodgeBlue),
                    ctx.getString(R.string.action_category_config).A(Pink80)
                )
            )
            return false
        }

        // 2. save it in ActionContext, it will be used in the next http Action
        aCtx.realCategory = realCategoryStr
        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_category_config)
    }

    @Composable
    override fun Summary() {
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_category_config)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(iconId = R.drawable.ic_category)
    }

    @Composable
    override fun Options() {
        var jsonStr by remember { mutableStateOf(PermissivePrettyJson.encodeToString(map)) }
        StrInputBox(
            text = jsonStr,
            label = { Text(Str(R.string.action_category_config)) },
            leadingIconId = R.drawable.ic_category,
            placeholder = {
                DimGreyLabel(
                    """
                {
                  "{marketing}": "gym",
                  "{other}": "bitcoin",
                  ...
                }
            """.trimIndent()
                )
            },
            helpTooltip = Str(R.string.help_action_category_config),
            onValueChange = { newVal ->
                try {
                    map = PermissiveJson.decodeFromString(newVal)
                    jsonStr = newVal
                } catch (_: Exception) {
                }
            }
        )
    }
}

// Report number to all api endpoints.
// (For internal app usage only.)
@Serializable
@SerialName("ReportNumber")
class ReportNumber(
    val rawNumber: String,
    val asTagCategory: String,
    val domainFilter: List<String>? = null // only report to APIs that matches these domains
) : IAction {
    override fun requiredPermissions(ctx: Context): List<PermissionWrapper> {
        return listOf(
            PermissionWrapper(
                Permission.callLog,
                prompt = ctx.getString(R.string.report_number_require_call_log_permission)
            ),
        )
    }

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val apis = listReportableAPIs(ctx = ctx, rawNumber = rawNumber, domainFilter = domainFilter)

        // Report
        val scope = CoroutineScope(IO)
        apis.forEach { api ->
            scope.launch {
                val aCtx = ActionContext(
                    scope = scope,
                    logger = AdbLogger(),
                    rawNumber = rawNumber,
                    tagCategory = asTagCategory,
                )
                val success = api.actions.executeAll(ctx, aCtx)
                logi("report number $rawNumber to ${api.summary()}, success: $success")
            }
            true
        }

        return true
    }

    override fun label(ctx: Context): String {
        return "Report Number" // it will not be displayed on UI
    }

    @Composable
    override fun Summary() {
    }

    override fun tooltip(ctx: Context): String {
        return ""
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
    }

    @Composable
    override fun Options() {
    }
}

// Continue or terminate the workflow according to ongoing calendar event.
@Serializable
class CalendarEvent(
    var enabled: Boolean = true,
    var eventTitle: String = "",
    var eventTitleFlags: Int = Def.DefaultRegexFlags,
) : IAction {
    override fun requiredPermissions(ctx: Context): List<PermissionWrapper> {
        return listOf(PermissionWrapper(Permission.calendar))
    }
    fun isActivated(): Boolean {
        return enabled && Permission.calendar.isGranted
    }
    @Composable
    fun TriggerType(modifier: Modifier) {
        RowVCenterSpaced(2, modifier = modifier) {
            GreyIcon18(R.drawable.ic_incoming)
            GreyIcon18(R.drawable.ic_calendar)
            GreyLabel(
                text = eventTitle,
                modifier = M.padding(start = 4.dp)
            )
        }
    }
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        if (!Permission.calendar.isGranted || !enabled)
            return false

        val ongoingEvents = Util.ongoingCalendarEvents(ctx)
        val triggered = ongoingEvents.any { it ->
            eventTitle.regexMatches(it, eventTitleFlags)
        }
        if (triggered) {
            aCtx.logger?.warn(
                ctx.getString(R.string.calendar_event_is_triggered)
                    .formatAnnotated(
                        eventTitle.A(Teal200)
                    )
            )
        }
        // Calendar Events modifies rules temporarily.
        aCtx.isInMemory = true

        return triggered
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.calendar_event)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(8) {
            // Green dot
            if (enabled) {
                GreenDot()
            }
            SummaryLabel(eventTitle)
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_calendar_event)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(R.drawable.ic_calendar)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var enabledState by remember { mutableStateOf(enabled) }

        LabeledRow(labelId = R.string.enable) {
            SwitchBox(enabledState) { on ->
                enabled = on
                enabledState = on
            }
        }

        val flags = remember { mutableIntStateOf(eventTitleFlags) }
        RegexInputBox(
            regexStr = eventTitle,
            label = { Text(Str(R.string.event_title)) },
            regexFlags = flags,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    eventTitle = newVal
                }
            },
            onFlagsChange = {
                flags.intValue = it
                eventTitleFlags = it
            }
        )
    }
}

// This will be triggered on receiving SMS messages
@Serializable
class SmsEvent(
    var enabled: Boolean = true,
    var number: String = ".*",
    var numberFlags: Int = Def.DefaultRegexFlags,
    var content: String = ".*",
    var contentFlags: Int = Def.DefaultRegexFlags,
) : IAction {
    override fun requiredPermissions(ctx: Context): List<PermissionWrapper> {
        return listOf(
            PermissionWrapper(Permission.receiveSMS),
            PermissionWrapper(Permission.batteryUnRestricted, isOptional = true),
        )
    }

    @Composable
    fun TriggerType(modifier: Modifier) {
        RowVCenterSpaced(2, modifier = modifier) {
            GreyIcon18(R.drawable.ic_sms)
            GreyLabel(
                text = "$content <- $number",
                modifier = M.padding(start = 4.dp)
            )
        }
    }
    fun isActivated(): Boolean {
        return enabled && Permission.receiveSMS.isGranted
    }

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val rawNumber = aCtx.rawNumber
        val smsContent = aCtx.smsContent

        // It's testing in the workflow dialog
        if (rawNumber == null || smsContent == null) {
            aCtx.logger?.error(ctx.getString(R.string.use_global_testing_instead))
            return false
        }

        if (!Permission.receiveSMS.isGranted || !enabled)
            return false

        if (!number.regexMatchesNumber(rawNumber, numberFlags)) {
            return false
        }
        if (!content.regexMatches(smsContent, contentFlags)) {
            return false
        }

        aCtx.logger?.warn(
            ctx.getString(R.string.sms_event_triggered)
                .formatAnnotated(
                    "$content <- $number".A(Teal200)
                )
        )

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.sms_event)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(8) {
            // Green dot
            if (enabled) {
                GreenDot()
            }
            SummaryLabel("$content <- $number")
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_sms_event)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon20(R.drawable.ic_sms)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var enabledState by remember { mutableStateOf(enabled) }

        LabeledRow(labelId = R.string.enable) {
            SwitchBox(enabledState) { on ->
                enabled = on
                enabledState = on
            }
        }

        val flagsNumber = remember { mutableIntStateOf(numberFlags) }
        RegexInputBox(
            regexStr = number,
            label = { Text(Str(R.string.phone_number)) },
            regexFlags = flagsNumber,
            placeholder = { DimGreyLabel(".*") },
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    number = newVal
                }
            },
            onFlagsChange = {
                flagsNumber.intValue = it
                numberFlags = it
            }
        )
        val flagsContent = remember { mutableIntStateOf(contentFlags) }
        RegexInputBox(
            regexStr = content,
            label = { Text(Str(R.string.sms_content)) },
            regexFlags = flagsContent,
            placeholder = { DimGreyLabel(".*") },
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    content = newVal
                }
            },
            onFlagsChange = {
                flagsContent.intValue = it
                contentFlags = it
            }
        )
    }
}


// Workflows that contain this preprocessor will be executed before checking the number.
@Serializable
class CallEvent(
    var enabled : Boolean = true,
    var number: String = ".*",
    var numberFlags: Int = Def.DefaultRegexFlags,
) : IAction {
    override fun requiredPermissions(ctx: Context): List<PermissionWrapper> {
        return listOf(
            PermissionWrapper(Permission.callScreening),
        )
    }
    @Composable
    fun TriggerType(modifier: Modifier) {
        RowVCenterSpaced(2, modifier = modifier) {
            GreyIcon18(R.drawable.ic_incoming)
            GreyLabel(
                text = number,
                modifier = M.padding(start = 4.dp)
            )
        }
    }
    fun isActivated(): Boolean {
        return enabled && Permission.callScreening.isGranted
    }
    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val rawNumber = aCtx.rawNumber

        // It's testing in the workflow dialog
        if (rawNumber == null) {
            aCtx.logger?.error(ctx.getString(R.string.use_global_testing_instead))
            return false
        }
        if (!enabled) {
            return false
        }

        if (!number.regexMatchesNumber(rawNumber, numberFlags)) {
            return false
        }

        aCtx.logger?.warn(
            ctx.getString(R.string.call_event_is_triggered)
                .formatAnnotated(
                    number.A(Teal200)
                )
        )
        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.call_event)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(8) {
            // Green dot
            if (enabled) {
                GreenDot()
            }
            SummaryLabel(number)
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_call_event)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(R.drawable.ic_incoming)
    }

    @Composable
    override fun Options() {
        // Must use a state, otherwise the switch doesn't change on click
        var enabledState by remember { mutableStateOf(enabled) }

        LabeledRow(labelId = R.string.enable) {
            SwitchBox(enabledState) { on ->
                enabled = on
                enabledState = on
            }
        }

        val flagsNumber = remember { mutableIntStateOf(numberFlags) }
        RegexInputBox(
            regexStr = number,
            label = { Text(Str(R.string.phone_number)) },
            leadingIcon = { GreyIcon18(R.drawable.ic_filter) },
            regexFlags = flagsNumber,
            placeholder = { DimGreyLabel(".*") },
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    number = newVal
                }
            },
            onFlagsChange = {
                flagsNumber.intValue = it
                numberFlags = it
            }
        )
    }
}

// Modify the incoming number before screening.
// Use case: Some MNO provide services that allow a secondary number for the same SIM card. For number
//  3377777777, the secondary number can be "1113377777777" (with an extra prefix 111),
//  the prefix 111 needs to be removed.
@Serializable
class ModifyNumber(
    var from: String = "",
    var fromFlags: Int = Def.DefaultRegexFlags,
    var to: String = "",
) : IPermissiveAction {

    override fun execute(ctx: Context, aCtx: ActionContext): Boolean {
        val cCtx = aCtx.cCtx!!

        val before = cCtx.rawNumber
        val after = before.regexReplace(from, to, fromFlags)

        if (before != after) {
            cCtx.rawNumber = after

            aCtx.logger?.warn(
                ctx.getString(R.string.modify_number_template)
                    .formatAnnotated(
                        before.A(DimGrey),
                        after.A(DimGrey)
                    )
            )
        }

        return true
    }

    override fun label(ctx: Context): String {
        return ctx.getString(R.string.action_modify_number)
    }

    @Composable
    override fun Summary() {
        RowVCenterSpaced(8) {
            SummaryLabel("$from -> $to")
        }
    }

    override fun tooltip(ctx: Context): String {
        return ctx.getString(R.string.help_action_modify_number)
    }

    override fun inputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    override fun outputParamType(): List<ParamType> {
        return listOf(ParamType.None)
    }

    @Composable
    override fun Icon() {
        GreyIcon(R.drawable.ic_replace)
    }

    @Composable
    override fun Options() {
        val fromFlagsState = remember { mutableIntStateOf(fromFlags) }
        RegexInputBox(
            regexStr = from,
            label = { Text(Str(R.string.replace_from)) },
            regexFlags = fromFlagsState,
            onRegexStrChange = { newVal, hasError ->
                if (!hasError) {
                    from = newVal
                }
            },
            onFlagsChange = {
                fromFlagsState.intValue = it
                fromFlags = it
            }
        )
        StrInputBox(
            text = to,
            label = { Text(Str(R.string.replace_to)) },
            onValueChange = {
                to = it
            }
        )
    }
}
