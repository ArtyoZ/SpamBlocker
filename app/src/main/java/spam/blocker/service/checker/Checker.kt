package spam.blocker.service.checker

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import spam.blocker.G
import spam.blocker.R
import spam.blocker.db.Bot
import spam.blocker.db.BotTable
import spam.blocker.db.CallTable
import spam.blocker.db.ContentRuleTable
import spam.blocker.db.NumberRuleTable
import spam.blocker.db.PushAlertTable
import spam.blocker.db.QuickCopyRuleTable
import spam.blocker.db.RegexRule
import spam.blocker.db.SmsTable
import spam.blocker.db.SpamTable
import spam.blocker.def.Def
import spam.blocker.def.Def.RESULT_BLOCKED_BY_NON_CONTACT
import spam.blocker.service.bot.ActionContext
import spam.blocker.service.bot.CalendarEvent
import spam.blocker.service.bot.CallEvent
import spam.blocker.service.bot.InterceptCall
import spam.blocker.service.bot.InterceptSms
import spam.blocker.service.bot.SmsEvent
import spam.blocker.service.bot.executeAll
import spam.blocker.ui.theme.Emerald
import spam.blocker.ui.theme.LightMagenta
import spam.blocker.ui.theme.Salmon
import spam.blocker.ui.theme.SkyBlue
import spam.blocker.util.A
import spam.blocker.util.Clipboard
import spam.blocker.util.Contacts
import spam.blocker.util.ILogger
import spam.blocker.util.Now
import spam.blocker.util.Permission
import spam.blocker.util.PhoneNumber
import spam.blocker.util.TimeSchedule
import spam.blocker.util.Util
import spam.blocker.util.Util.countHistorySMSByNumber
import spam.blocker.util.Util.getAppsEvents
import spam.blocker.util.Util.getHistoryCallsByNumber
import spam.blocker.util.Util.listRunningForegroundServiceNames
import spam.blocker.util.Util.listUsedAppWithinXSecond
import spam.blocker.util.formatAnnotated
import spam.blocker.util.hasFlag
import spam.blocker.util.race
import spam.blocker.util.regexMatches
import spam.blocker.util.regexMatchesNumber
import spam.blocker.util.spf

class CheckContext(
    var rawNumber: String,
    val callDetails: Call.Details? = null,
    val smsContent: String? = null,
    val logger: ILogger? = null,
    val startTimeMillis: Long = System.currentTimeMillis(),
    val checkers: List<IChecker>,
)


interface IChecker {
    fun priority(): Int
    fun check(cCtx: CheckContext): ICheckResult?
}

fun RegexRule.toNumberChecker(
    ctx: Context,
): IChecker {
    val forContact = this.patternFlags.hasFlag(Def.FLAG_REGEX_FOR_CONTACT)
    val forContactGroup = this.patternFlags.hasFlag(Def.FLAG_REGEX_FOR_CONTACT_GROUP)
    return if (forContact)
        Checker.RegexContact(ctx, this)
    else if (forContactGroup)
        Checker.ContactGroup(ctx, this)
    else
        Checker.Number(ctx, this)
}

// A pre-processor is simply a WorkflowRunner, it's also an `IChecker` and will be added to the
//   checkers list. But they have higher priorities and will get executed before other checkers.
// In this way, they can temporary modify other checkers, e.g.: disable a regex rule on the fly
//   without modifying the configuration.
object Preprocessors {

    // Run a workflow.
    open class WorkflowRunner(
        val ctx: Context,
        val bot: Bot,
    ) : IChecker {
        override fun priority(): Int {
            // To make sure it's executed before all other rules.
            return Int.MAX_VALUE - 1
        }
        override fun check(cCtx: CheckContext): ICheckResult? {
            val aCtx = ActionContext(
                logger = cCtx.logger,
                rawNumber = cCtx.rawNumber,
                smsContent = cCtx.smsContent,
                cCtx = cCtx,
            )
            bot.actions.executeAll(ctx, aCtx)

            return null
        }
    }
    // Collect all `Call Event` that defined in Workflow section.
    fun call(ctx: Context): List<WorkflowRunner> {
        return BotTable.listAll(ctx)
            .filter {
                val firstAction = it.actions.firstOrNull()
                firstAction is CallEvent && firstAction.isActivated()
            }
            .map {
                WorkflowRunner(ctx, it)
            }
    }
    // Collect all `SMS Event` that defined in Workflow section.
    fun sms(ctx: Context): List<WorkflowRunner> {
        return BotTable.listAll(ctx)
            .filter {
                val firstAction = it.actions.firstOrNull()
                firstAction is SmsEvent && firstAction.isActivated()
            }
            .map {
                WorkflowRunner(ctx, it)
            }
    }

    // Collect all `Calendar Event` that defined in Workflow section.
    fun calendarEvent(ctx: Context): List<WorkflowRunner> {
        val bots = BotTable.listAll(ctx).filter {
            val firstAction = it.actions.firstOrNull()
            firstAction is CalendarEvent && firstAction.isActivated()
        }
        if (bots.isEmpty()) // No calendar workflow enabled
            return listOf()

        val ongoingEvents = Util.ongoingCalendarEvents(ctx)
        if (ongoingEvents.isEmpty()) // No calendar event currently occurring
            return listOf()

        var ret = mutableListOf<WorkflowRunner>()

        ongoingEvents.forEach { eventTitle ->
            ret += bots
                // only keep bots that match this eventTitle
                .filter {
                    val ce = it.actions[0] as CalendarEvent
                    ce.eventTitle.regexMatches(eventTitle, ce.eventTitleFlags)
                }
                .map {
                    WorkflowRunner(ctx, it)
                }
        }
        return ret
    }

}
class Checker { // for namespace only

    // This checks if the incoming call is from an emergency number.
    // It's always enabled, there is no setting entry for this.
    private class EmergencyCall(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return Int.MAX_VALUE
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger
            val callDetails = cCtx.callDetails
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.emergency_call).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )
            if (callDetails == null) {// there is no callDetail when testing
                logger?.debug(ctx.getString(R.string.skip_for_testing))
                return null
            }

            if (callDetails.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)
                || callDetails.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
                || Util.isEmergencyNumber(ctx, cCtx.rawNumber)
            ) {
                logger?.success(
                    ctx.getString(R.string.allowed_by)
                        .format(ctx.getString(R.string.emergency_call))
                )
                return ByEmergencyCall()
            }

            return null
        }
    }

    // This is the `Emergency` in Quick Settings, it allows all calls after calling an emergency number.
    private class EmergencySituation(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return Int.MAX_VALUE
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val spf = spf.EmergencySituation(ctx)
            if (!spf.isEnabled())
                return null

            val logger = cCtx.logger
            val callDetails = cCtx.callDetails
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.emergency_situation).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // 1. check time
            val lastEccCallTime: Long = spf.getTimestamp()
            val duration: Long = (spf.getDuration() * 60 * 1000).toLong()
            val now = System.currentTimeMillis()
            if (lastEccCallTime + duration < now) {
                return null
            }

            // 2. check STIR
            val isStirEnabled = spf.isStirEnabled()
            if (isStirEnabled && callDetails != null) { // only check for real call, there is no `callDetails` when testing
                val stir = callDetails.callerNumberVerificationStatus
                val fail = stir == Connection.VERIFICATION_STATUS_FAILED
                if (fail) {
                    return null
                }
            }

            logger?.success(
                ctx.getString(R.string.allowed_by)
                    .format(ctx.getString(R.string.emergency_situation))
            )
            return ByEmergencySituation()
        }
    }

    private class STIR(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return spf.Stir(ctx).getPriority()
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger
            val callDetails = cCtx.callDetails

            val spf = spf.Stir(ctx)
            if (!spf.isEnabled())
                return null

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.stir_attestation).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // STIR only works >= Android 11
            if (Build.VERSION.SDK_INT < Def.ANDROID_11) {
                logger?.debug(ctx.getString(R.string.android_ver_lower_than_11))
                return null
            }

            // there is no callDetail when testing
            if (callDetails == null) {
                logger?.debug(ctx.getString(R.string.skip_for_testing))
                return null
            }


            val stir = callDetails.callerNumberVerificationStatus

            val fail = stir == Connection.VERIFICATION_STATUS_FAILED

            if (fail) {
                val ret = BySTIR(Def.RESULT_BLOCKED_BY_STIR, stir)
                logger?.error(
                    ctx.getString(R.string.blocked_by)
                        .format(ret.resultReasonStr(ctx))
                )
                return ret
            }

            return null
        }
    }

    // The "Database" in quick settings.
    // It checks whether the number exists in the spam database.
    private class SpamDB(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return spf.SpamDB(ctx).getPriority()
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            val enabled = spf.SpamDB(ctx).isEnabled()
            if (!enabled)
                return null

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.database).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val record = SpamTable.findByNumber(ctx, rawNumber) ?:
                    SpamTable.findByNumber(ctx, Util.clearNumber(rawNumber))

            if (record != null) {
                logger?.error(
                    ctx.getString(R.string.blocked_by).format(ctx.getString(R.string.database))
                )
                return BySpamDb(matchedNumber = record.peer)
            }
            return null
        }
    }

    // The "Contacts" in quick settings.
    // It checks whether the phone number belongs to a contact.
    private class Contact(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            val spf = spf.Contact(ctx)
            val isStrict = spf.isStrict()
            return if (isStrict)
                spf.getStrictPriority()
            else
                spf.getLenientPriority()
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            val spf = spf.Contact(ctx)

            if (!spf.isEnabled() or !Permission.contacts.isGranted) {
                return null
            }
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.contacts).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val contact = Contacts.findContactByRawNumber(ctx, rawNumber)
            if (contact != null) { // is contact
                logger?.success(
                    ctx.getString(R.string.allowed_by)
                        .format(ctx.getString(R.string.contacts)) + ": ${contact.name}"
                )
                return ByContact(Def.RESULT_ALLOWED_BY_CONTACT, contact.name)
            } else { // not contact
                if (spf.isStrict()) {
                    val ret = ByContact(RESULT_BLOCKED_BY_NON_CONTACT)
                    logger?.error(
                        ctx.getString(R.string.blocked_by).format(
                            ret.resultReasonStr(ctx)
                        )
                    )
                    return ret
                }
            }
            return null
        }
    }

    private class RepeatedCall(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger
            val isTesting = cCtx.callDetails == null

            val canReadCalls = Permission.callLog.isGranted
            val canReadSMSs = Permission.readSMS.isGranted

            val spf = spf.RepeatedCall(ctx)
            if (!spf.isEnabled() || (!canReadCalls && !canReadSMSs)) {
                return null
            }
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.repeated_call).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val times = spf.getTimes()
            val durationMinutes = spf.getInXMin()
            val smsEnabled = spf.isSmsEnabled()

            val durationMillis = durationMinutes.toLong() * 60 * 1000

            val phoneNumber = PhoneNumber(ctx, rawNumber)

            // count Calls from real call history
            var nCalls = getHistoryCallsByNumber(
                ctx,
                phoneNumber,
                Def.DIRECTION_INCOMING,
                durationMillis
            ).size
            // When testing, there is no real call history, try local db instead
            if (isTesting) {
                val nCallsTesting = CallTable().countRepeatedRecordsWithinSeconds(
                    ctx,
                    rawNumber,
                    durationMinutes * 60
                )
                if (nCalls < nCallsTesting) // use the larger one
                    nCalls = nCallsTesting
            }

            // count SMSs from real SMS history
            var nSMSs = if(smsEnabled)
                countHistorySMSByNumber(
                    ctx,
                    phoneNumber,
                    Def.DIRECTION_INCOMING,
                    durationMillis
                )
            else
                0
            if (isTesting) { // try local db
                val nSMSsTesting = if (smsEnabled)
                    SmsTable().countRepeatedRecordsWithinSeconds(
                        ctx,
                        rawNumber,
                        durationMinutes * 60
                    )
                else
                    0
                if (nSMSs < nSMSsTesting)
                    nSMSs = nSMSsTesting
            }


            logger?.debug("${ctx.getString(R.string.call)}: $nCalls, ${ctx.getString(R.string.sms)}: $nSMSs")

            // check
            if (nCalls + nSMSs >= times) {
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.repeated_call))
                )
                return ByRepeatedCall()
            }
            return null
        }
    }

    private class Dialed(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            val spf = spf.Dialed(ctx)
            if (!spf.isEnabled())
                return null

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.dialed_number).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val smsEnabled = spf.isSmsEnabled()
            val durationDays = spf.getDays()

            val durationMillis = durationDays.toLong() * 24 * 3600 * 1000

            // repeated count of call/sms, sms also counts
            val phoneNumber = PhoneNumber(ctx, rawNumber)
            val nCalls = getHistoryCallsByNumber(
                ctx,
                phoneNumber,
                Def.DIRECTION_OUTGOING,
                durationMillis
            ).size
            val nSMSs = if (smsEnabled)
                countHistorySMSByNumber(
                    ctx,
                    phoneNumber,
                    Def.DIRECTION_OUTGOING,
                    durationMillis
                )
            else
                0
            logger?.debug("${ctx.getString(R.string.call)}: $nCalls, ${ctx.getString(R.string.sms)}: $nSMSs")

            if (nCalls + nSMSs > 0) {
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.dialed_number))
                )
                return ByDialedNumber()
            }
            return null
        }
    }

    private class Answered(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            val spf = spf.Answered(ctx)
            if (!spf.isEnabled())
                return null

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.answered_number).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val minDuration = spf.getMinDuration()
            val durationDays = spf.getDays()

            val durationMillis = durationDays.toLong() * 24 * 3600 * 1000

            // repeated count of call/sms, sms also counts
            val phoneNumber = PhoneNumber(ctx, rawNumber)
            val nCalls = getHistoryCallsByNumber(
                ctx,
                phoneNumber,
                Def.DIRECTION_INCOMING,
                durationMillis
            ).filter {
                it.duration >= minDuration
            }.size

            logger?.debug("${ctx.getString(R.string.call)}: $nCalls")

            if (nCalls > 0) {
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.answered_number))
                )
                return ByAnsweredNumber()
            }
            return null
        }
    }

    private class OffTime(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger

            val spf = spf.OffTime(ctx)
            if (!spf.isEnabled()) {
                return null
            }
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.off_time).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val stHour = spf.getStartHour()
            val stMin = spf.getStartMin()
            val etHour = spf.getEndHour()
            val etMin = spf.getEndMin()

            // Entire day
            if (stHour == etHour && stMin == etMin) {
                logger?.debug(ctx.getString(R.string.entire_day))
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.off_time))
                )
                return ByOffTime()
            }

            if (Util.isCurrentTimeWithinRange(stHour, stMin, etHour, etMin)) {
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.off_time))
                )
                return ByOffTime()
            }

            return null
        }
    }

    private class RecentApp(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.recent_apps).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val spf = spf.RecentApps(ctx)

            val duration = spf.getInXMin() // in minutes

            // To avoid querying db for each app, aggregate them by duration, like:
            //  pkg.a,pkg.b@20,pkg.c
            // ->
            //  map {
            //    5  -> [pkg.a,pkg.c], // 5 is the default duration
            //    20 -> [pkg.b],
            //  }
            //  So it only queries db twice: for 5 min and 20 min.
            val aggregation = spf.getList().groupBy {
                it.duration ?: duration
            }.mapValues { (_, values) ->
                values.map { it.pkgName }
            }

            for ((duration, appList) in aggregation) {
                val usedApps = listUsedAppWithinXSecond(ctx, duration * 60)

                val intersection = appList.toList().intersect(usedApps.toSet())

                if (intersection.isNotEmpty()) {
                    logger?.success(
                        ctx.getString(R.string.allowed_by)
                            .format(ctx.getString(R.string.recent_apps)) + ": ${intersection.first()}"
                    )
                    return ByRecentApp(pkgName = intersection.first())
                }
            }
            return null
        }
    }

    private class MeetingMode(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            val spf = spf.MeetingMode(ctx)
            return spf.getPriority()
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.in_meeting).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val spf = spf.MeetingMode(ctx)

            val appInfos = spf.getList()

            val eventsMap = getAppsEvents(ctx, appInfos.map { it.pkgName }.toSet())

            // Check if any app is running a foreground service
            val appInMeeting = appInfos.firstOrNull {
                val runningServiceNames = listRunningForegroundServiceNames(
                    appEvents = eventsMap[it.pkgName],
                )
                val exclusions = it.exclusions

                runningServiceNames.any { serviceName ->
                    !exclusions.any { serviceName.contains(it) }
                }
            }

            if (appInMeeting != null) {
                logger?.error(
                    ctx.getString(R.string.blocked_by)
                        .format(ctx.getString(R.string.in_meeting)) + ": $appInMeeting"
                )
                return ByMeetingMode(pkgName = appInMeeting.pkgName)
            }
            return null
        }
    }

    private class InstantQuery(
        private val ctx: Context,
        private val forType: Int, // for call or sms
    ) : IChecker {
        override fun priority(): Int {
            return spf.ApiQueryOptions(ctx).getPriority()
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            val apis = G.apiQueryVM.table.listAll(ctx)
                .filter { it.enabled }
                .filter { // check if the first action is InterceptCall/InterceptSms
                    0 == it.actions.indexOfFirst { act ->
                        if (forType == Def.ForNumber)
                            act is InterceptCall
                        else
                            act is InterceptSms
                    }
                }

            if (apis.isEmpty())
                return null

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.instant_query).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // The call screening time limit is 5 seconds, with a 500ms buffer for
            //  the inaccuracy of System.currentTimeMillis(), the total time limit
            //  is actually 4500ms
            val now = System.currentTimeMillis()
            val alreadyCost = now - cCtx.startTimeMillis
            val buffer = 500
            val timeLeft = 5000 - buffer - alreadyCost
            if (timeLeft <= 0) {
                logger?.warn(
                    ctx.getString(R.string.not_enough_time_left).format(
                        "$buffer", "${5000 - buffer}", "$alreadyCost"
                    )
                )
                return null
            }

            // Run all apis simultaneously, get the first non-null result, which means "determined",
            //  return that result immediately and kill other threads.
            var (winnerApi, result) = race(
                competitors = apis,
                timeoutMillis = timeLeft,
                runner = { api ->
                    { scope ->
                        try {
                            val aCtx = ActionContext(
                                scope = scope,
                                logger = logger,
                                rawNumber = rawNumber,
                                smsContent = cCtx.smsContent,
                            )
                            val success = api.actions.executeAll(ctx, aCtx)

                            if (!success) {
                                null
                            }

                            val result = aCtx.racingResult
                            if (result?.determined == true) {
                                result
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            )

            if (result?.determined == true) { // either spam or non-spam
                winnerApi!! // If it's determined, the winnerApi must exist

                if (result.isSpam)
                    logger?.error(
                        ctx.getString(R.string.blocked_by)
                            .format(ctx.getString(R.string.instant_query)) + " <${winnerApi.summary()}>"
                    )
                else
                    logger?.success(
                        ctx.getString(R.string.allowed_by)
                            .format(ctx.getString(R.string.instant_query)) + " <${winnerApi.summary()}>"
                    )

                return ByApiQuery(
                    type = if (result.isSpam) Def.RESULT_BLOCKED_BY_API_QUERY else Def.RESULT_ALLOWED_BY_API_QUERY,
                    detail = ApiQueryResultDetail(
                        apiSummary = winnerApi.summary(),
                        apiDomain = winnerApi.domain()!!,
                        queryResult = result,
                    )
                )
            }

            return null
        }
    }

    open class RegexRuleChecker(
        val ctx: Context,
        var rule: RegexRule,
    ) : IChecker {
        override fun priority(): Int {
            return rule.priority
        }
        override fun check(cCtx: CheckContext): ICheckResult? {
            throw Exception("unimplemented RegexRuleChecker.check()")
            return null
        }

        fun ifEnabled(cCtx: CheckContext): Boolean {
            // 0. check if the rule is enabled (has FLAG_FOR_CALL for call, or FLAG_FOR_SMS for sms)
            val isForSMS = cCtx.smsContent != null
            if (!rule.flags.hasFlag(if (isForSMS) Def.FLAG_FOR_SMS else Def.FLAG_FOR_CALL)) {
                return false
            }
            // 1. check time schedule
            if (TimeSchedule.dissatisfyNow(rule.schedule)) {
                cCtx.logger?.debug(ctx.getString(R.string.outside_time_schedule))
                return false
            }
            return true
        }
    }

    // Check if a number rule matches the incoming number
    class Number(
        ctx: Context,
        rule: RegexRule,
    ) : RegexRuleChecker(ctx, rule) {
        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            if (!ifEnabled(cCtx)) {
                return null
            }

            logger?.debug(
                (ctx.getString(R.string.checking_template)+ ": %s")
                    .formatAnnotated(
                        ctx.getString(R.string.number_rule).A(SkyBlue),
                        priority().toString().A(LightMagenta),
                        rule.summary().A(if (rule.isBlacklist) Salmon else Emerald),
                    )
            )

            // 2. check regex
            if (rule.pattern.regexMatchesNumber(rawNumber, rule.patternFlags)) {
                val block = rule.isBlacklist

                if (block)
                    logger?.error(
                        ctx.getString(R.string.blocked_by)
                            .format(ctx.getString(R.string.number_rule)) + ": ${rule.summary()}"
                    )
                else
                    logger?.success(
                        ctx.getString(R.string.allowed_by)
                            .format(ctx.getString(R.string.number_rule)) + ": ${rule.summary()}"
                    )

                return ByRegexRule(
                    type = if (block) Def.RESULT_BLOCKED_BY_NUMBER else Def.RESULT_ALLOWED_BY_NUMBER,
                    rule = rule,
                )
            }

            return null
        }
    }

    // The regex flag `Contact`, it matches the contact name instead of the phone number
    class RegexContact(
        ctx: Context,
        rule: RegexRule
    ) : RegexRuleChecker(ctx, rule) {
        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            if (!Permission.contacts.isGranted) {
                return null
            }

            if (!ifEnabled(cCtx)) {
                return null
            }

            logger?.debug(
                (ctx.getString(R.string.checking_template) + ": ${rule.summary()}")
                    .formatAnnotated(
                        ctx.getString(R.string.contact_rule).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // 2. check regex
            val contactInfo = Contacts.findContactByRawNumber(ctx, rawNumber)
            if (contactInfo != null) {

                if (rule.matches(contactInfo.name)) {
                    val block = rule.isBlacklist

                    if (block)
                        logger?.error(
                            ctx.getString(R.string.blocked_by)
                                .format(ctx.getString(R.string.contact_rule)) + ": ${rule.summary()}"
                        )
                    else
                        logger?.success(
                            ctx.getString(R.string.allowed_by)
                                .format(ctx.getString(R.string.contact_rule)) + ": ${rule.summary()}"
                        )

                    return ByRegexRule(
                        type = if (block) Def.RESULT_BLOCKED_BY_CONTACT_REGEX else Def.RESULT_ALLOWED_BY_CONTACT_REGEX,
                        rule = rule,
                    )
                }
            }
            return null
        }
    }

    // The regex flag `Contact Group`, it matches the contact group name instead of the phone number
    class ContactGroup(
        ctx: Context,
        rule: RegexRule
    ) : RegexRuleChecker(ctx, rule) {
        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val logger = cCtx.logger

            if (!Permission.contacts.isGranted) {
                return null
            }

            if (!ifEnabled(cCtx)) {
                return null
            }

            logger?.debug(
                (ctx.getString(R.string.checking_template)+ ": ${rule.summary()}")
                    .formatAnnotated(
                        ctx.getString(R.string.contact_group).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // 2. check regex
            val group = Contacts.findGroupsContainNumber(ctx, rawNumber)
                .find { groupName ->
                    rule.matches(groupName)
                }

            if (group != null) { // found match
                val block = rule.isBlacklist

                if (block)
                    logger?.error(
                        ctx.getString(R.string.blocked_by)
                            .format(ctx.getString(R.string.contact_group)) + ": ${rule.summary()}"
                    )
                else
                    logger?.success(
                        ctx.getString(R.string.allowed_by)
                            .format(ctx.getString(R.string.contact_group)) + ": ${rule.summary()}"
                    )

                return ByRegexRule(
                    type = if (block) Def.RESULT_BLOCKED_BY_CONTACT_GROUP else Def.RESULT_ALLOWED_BY_CONTACT_GROUP,
                    rule = rule,
                )
            }
            return null
        }
    }

    /*
        Check if text message body matches the SMS Content rule,
        the number is also checked when "for particular number" is enabled
     */
    class Content(
        ctx: Context,
        rule: RegexRule
    ) : RegexRuleChecker(ctx, rule) {
        override fun priority(): Int {
            return rule.priority
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val rawNumber = cCtx.rawNumber
            val smsContent = cCtx.smsContent!!
            val logger = cCtx.logger

            if (!ifEnabled(cCtx)) {
                return null
            }

            logger?.debug(
                (ctx.getString(R.string.checking_template)+ ": ${rule.summary()}")
                    .formatAnnotated(
                        ctx.getString(R.string.content_rule).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // 1. check regex
            val contentMatches = rule.matches(smsContent)

            // 2. check for particular number
            fun particularMatches(): Boolean {
                if (rule.patternExtra == "") { // "for particular number" is not enabled
                    return true
                }

                val forContactGroup =
                    rule.patternExtraFlags.hasFlag(Def.FLAG_REGEX_FOR_CONTACT_GROUP)

                val forContact =
                    rule.patternExtraFlags.hasFlag(Def.FLAG_REGEX_FOR_CONTACT)

                return if (forContactGroup) {
                    Contacts.findGroupsContainNumber(ctx, rawNumber)
                        .any { groupName ->
                            rule.extraMatches(groupName)
                        }
                } else if (forContact) {
                    val contactInfo = Contacts.findContactByRawNumber(ctx, rawNumber)
                    contactInfo != null && rule.extraMatches(contactInfo.name)
                } else {
                    // regular number
                    rule.patternExtra.regexMatchesNumber(rawNumber, rule.patternExtraFlags)
                }
            }

            if (contentMatches && particularMatches()) {
                val block = rule.isBlacklist

                if (block)
                    logger?.error(
                        ctx.getString(R.string.blocked_by)
                            .format(ctx.getString(R.string.content_rule)) + ": ${rule.summary()}"
                    )
                else
                    logger?.success(
                        ctx.getString(R.string.allowed_by)
                            .format(ctx.getString(R.string.content_rule)) + ": ${rule.summary()}"
                    )

                return ByRegexRule(
                    type = if (block) Def.RESULT_BLOCKED_BY_CONTENT else Def.RESULT_ALLOWED_BY_CONTENT,
                    rule = rule,
                )
            }
            return null
        }
    }

    private class PushAlert(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            if (!Permission.notificationAccess.isGranted) {
                return null
            }

            // Skip if no valid rule is set.
            val enabled = PushAlertTable.listAll(ctx).any {
                it.enabled && it.isValid()
            }
            if (!enabled) {
                return null
            }

            val logger = cCtx.logger

            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.push_alert).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // Sleep 500ms for the `NotificationListenerService` to process all buffered notifications,
            //  see "NotificationListenerService.kt" for a detailed explanation.
            runBlocking(IO) {
                delay(500)
            }

            val spf = spf.PushAlert(ctx)

            // Following information is updated by NotificationMonitorService when receiving notifications.
            val pkgName = spf.getPkgName()
            val body = spf.getBody()
            val expireTime: Long = spf.getExpireTime() // millis

            val now = System.currentTimeMillis()

            if (now < expireTime) {
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.push_alert))
                )
                return ByPushAlert(detail = PushAlertDetail(
                    pkgName = pkgName,
                    body = body,
                ))
            }

            return null
        }
    }

    private class SmsAlert(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 10
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger

            val spf = spf.SmsAlert(ctx)
            if (!spf.isEnabled()) {
                return null
            }
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.sms_alert).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            val duration = spf.getDuration().toLong() * 1000 // second * 1000 -> millis
            val receiveSmsTimestamp = spf.getTimestamp() // the time it received that SMS
            val expire = receiveSmsTimestamp + duration

            val now = Now.currentMillis()

            if (now < expire) {
                logger?.success(
                    ctx.getString(R.string.allowed_by).format(ctx.getString(R.string.sms_alert))
                )
                return BySmsAlert()
            }

            return null
        }
    }

    private class SmsBomb(
        private val ctx: Context,
    ) : IChecker {
        override fun priority(): Int {
            return 20
        }

        override fun check(cCtx: CheckContext): ICheckResult? {
            val logger = cCtx.logger

            val spf = spf.SmsBomb(ctx)
            if (!spf.isEnabled()) {
                return null
            }
            logger?.debug(
                ctx.getString(R.string.checking_template)
                    .formatAnnotated(
                        ctx.getString(R.string.sms_bomb).A(SkyBlue),
                        priority().toString().A(LightMagenta)
                    )
            )

            // 1. check if regex matches
            val regex = spf.getRegexStr()
            val flags = spf.getRegexFlags()
            val matches = regex.regexMatches(cCtx.smsContent!!, flags)
            if (!matches) {
                return null
            }

            var blockIt = false
            // 2. check if lockscreen protect on
            if (spf.isLockScreenProtectionEnabled() && Util.isDeviceLocked(ctx)) {
                blockIt = true
            }

            // 3. check if within interval
            val now = Now.currentMillis()
            val lastBombTime = spf.getTimestamp()
            val interval = spf.getInterval() // in seconds

            if (lastBombTime + interval*1000 > now) {
                blockIt = true
            }

            // 4. save the last bomb time
            spf.setTimestamp(Now.currentMillis())

            if (blockIt) {
                logger?.error(
                    ctx.getString(R.string.blocked_by).format(ctx.getString(R.string.sms_bomb))
                )
                return BySmsBomb()
            }
            return null
        }
    }


    companion object {

        fun checkCallWithCheckers(
            ctx: Context,
            logger: ILogger?,
            rawNumber: String,
            callDetails: Call.Details? = null,
            checkers: List<IChecker>,
        ): ICheckResult {
            val cCtx = CheckContext(
                rawNumber = rawNumber,
                callDetails = callDetails,
                logger = logger,
                checkers = checkers,
            )
            // sort by priority desc
            val sortedCheckers = checkers.sortedByDescending {
                it.priority()
            }

            // try all checkers in order, until a match is found
            var result: ICheckResult? = null
            sortedCheckers.firstOrNull {
                result = it.check(cCtx)
                result != null
            }
            // match found
            if (result != null) {
                return result
            }

            // The call passed all rules.
            logger?.success(ctx.getString(R.string.passed_by_default))
            // pass by default
            return ByDefault()
        }

        fun checkCall(
            ctx: Context,
            logger: ILogger?,
            rawNumber: String,
            callDetails: Call.Details? = null
        ): ICheckResult {

            val checkers = arrayListOf(
                EmergencyCall(ctx),
                EmergencySituation(ctx),
                STIR(ctx),
                SpamDB(ctx),
                Contact(ctx),
                RepeatedCall(ctx),
                Dialed(ctx),
                Answered(ctx),
                RecentApp(ctx),
                MeetingMode(ctx),
                OffTime(ctx),
                InstantQuery(ctx, Def.ForNumber),
                PushAlert(ctx),
                SmsAlert(ctx)
            )

            // Add number rules to checkers
            val rules = NumberRuleTable().listAll(ctx)
            checkers += rules.map {
                it.toNumberChecker(ctx)
            }

            // Add all pre-processors
            checkers += Preprocessors.calendarEvent(ctx)
            checkers += Preprocessors.call(ctx)

            return checkCallWithCheckers(
                ctx, logger, rawNumber, callDetails, checkers)
        }

        fun checkSmsWithCheckers(
            ctx: Context,
            logger: ILogger?,
            rawNumber: String,
            messageBody: String,
            checkers: List<IChecker>,
        ): ICheckResult {
            val cCtx = CheckContext(
                rawNumber = rawNumber,
                smsContent = messageBody,
                logger = logger,
                checkers = checkers,
            )

            // sort by priority desc
            val sortedCheckers = checkers.sortedByDescending {
                it.priority()
            }

            // try all checkers in order, until a match is found
            var result: ICheckResult? = null
            sortedCheckers.firstOrNull {
                result = it.check(cCtx)
                result != null
            }
            // match found
            if (result != null) {
                return result
            }

            // The SMS message passed all rules.
            logger?.success(ctx.getString(R.string.passed_by_default))

            // pass by default
            return ByDefault()
        }
        fun checkSms(
            ctx: Context,
            logger: ILogger?,
            rawNumber: String,
            messageBody: String
        ): ICheckResult {
            val checkers = arrayListOf<IChecker>(
                Contact(ctx),
                SpamDB(ctx),
                MeetingMode(ctx),
                OffTime(ctx),
                SmsBomb(ctx),
                InstantQuery(ctx, Def.ForSms),
            )

            //  add number rules to checkers
            val numberRules = NumberRuleTable().listAll(ctx)
            checkers += numberRules.map {
                it.toNumberChecker(ctx)
            }

            //  add sms content rules to checkers
            val contentFilters = ContentRuleTable().listAll(ctx)
            checkers += contentFilters.map {
                Content(ctx, it)
            }

            // Add all pre-processors
            checkers += Preprocessors.calendarEvent(ctx)
            checkers += Preprocessors.sms(ctx)

            return checkSmsWithCheckers(
                ctx, logger, rawNumber, messageBody, checkers
            )
        }

        // It returns a list<String>, all the Strings will be shown as Buttons in the notification.
        fun checkQuickCopy(
            ctx: Context,
            rawNumber: String, messageBody: String?,
            isCall: Boolean, // is this called from incoming call or message.
            isBlocked: Boolean,
        ): List<String> {

            return QuickCopyRuleTable().listAll(ctx).filter {
                val c1 = it.flags.hasFlag(if (isCall) Def.FLAG_FOR_CALL else Def.FLAG_FOR_SMS)
                val c2 =
                    it.flags.hasFlag(if (isBlocked) Def.FLAG_FOR_BLOCKED else Def.FLAG_FOR_PASSED)
                c1 && c2
            }.sortedByDescending {
                it.priority
            }.fold(mutableListOf()) { acc, it ->

                fun autoCopy(text: String) {
                    if (it.flags.hasFlag(Def.FLAG_AUTO_COPY)) {
                        CoroutineScope(IO).launch { Clipboard.copy(ctx, text) }
                    }
                }

                val opts = Util.flagsToRegexOptions(it.patternFlags)
                val regex = it.pattern.toRegex(opts)

                val forNumber = it.flags.hasFlag(Def.FLAG_FOR_NUMBER)
                val forContent = it.flags.hasFlag(Def.FLAG_FOR_CONTENT)

                if (forNumber) {
                    val numberToCheck = if (it.patternFlags.hasFlag(Def.FLAG_REGEX_RAW_NUMBER))
                        rawNumber
                    else
                        Util.clearNumber(rawNumber)

                    val extracted = Util.extractString(regex, numberToCheck)
                    if (extracted != null) {
                        acc.add(extracted)
                        autoCopy(extracted)
                    }
                }
                if (forContent && messageBody != null) {
                    val extracted = Util.extractString(regex, messageBody)
                    if (extracted != null) {
                        acc.add(extracted)
                        autoCopy(extracted)
                    }
                }
                acc
            }
        }
    }
}
