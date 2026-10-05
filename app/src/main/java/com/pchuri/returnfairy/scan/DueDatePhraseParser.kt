package com.pchuri.returnfairy.scan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

sealed interface DueDateParseResult {
    data class Resolved(val date: LocalDate) : DueDateParseResult
    data object Unsupported : DueDateParseResult
    data object Rejected : DueDateParseResult
}

object DueDatePhraseParser {

    private val isoDate = Regex("(?<!\\d)(\\d{4}-\\d{1,2}-\\d{1,2})(?!\\d)")
    private val dottedDate = Regex("(?<!\\d)(\\d{4})[./](\\d{1,2})[./](\\d{1,2})(?!\\d)")
    private val koreanDate = Regex("(?<!\\d)(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일")
    private val koreanMonthDay = Regex("(?<!\\d)(\\d{1,2})월\\s*(\\d{1,2})일")
    private val koreanOffset = Regex("(\\d+)\\s*(일|주|개월|달)\\s*(후|뒤)")
    private val englishOffset = Regex("(?:in\\s+)?(\\d+)\\s*(days?|weeks?|months?)(?:\\s+(?:later|from\\s+now))?\\b")
    private val dayAfterTomorrow = Regex("\\bday\\s+after\\s+tomorrow\\b")
    private val tomorrow = Regex("\\btomorrow\\b")
    private val todayWord = Regex("\\btoday\\b")
    private val currentMonthLastWeekday = Regex(
        "(?:이번\\s*달(?:의)?\\s*(?:마지막|최종)\\s*평일|(?:last|final)\\s+weekday\\s+(?:of\\s+)?this\\s+month)"
    )
    private val currentMonthLastDay = Regex(
        "(?:이번\\s*달(?:의)?\\s*(?:마지막|최종)\\s*(?:날|날짜)|(?:last|final)\\s+day\\s+(?:of\\s+)?this\\s+month)"
    )
    private val englishNegation = Regex(
        """\b(?:not|no|never|isn['’]t|aren['’]t|wasn['’]t|weren['’]t|don['’]t|doesn['’]t|didn['’]t|won['’]t|can['’]t|cannot|couldn['’]t|shouldn['’]t|wouldn['’]t)\b"""
    )
    private val koreanNegation = Regex("""아니|아닌|아님|말고|(?:^|\s)안(?:\s|돼|되|됨)""")

    private val koreanWeekdays = mapOf(
        "월요일" to DayOfWeek.MONDAY,
        "화요일" to DayOfWeek.TUESDAY,
        "수요일" to DayOfWeek.WEDNESDAY,
        "목요일" to DayOfWeek.THURSDAY,
        "금요일" to DayOfWeek.FRIDAY,
        "토요일" to DayOfWeek.SATURDAY,
        "일요일" to DayOfWeek.SUNDAY,
    )
    private val englishWeekdays = DayOfWeek.entries.associateBy { it.name.lowercase(Locale.ROOT) }

    fun parse(phrase: String, today: LocalDate = LocalDate.now()): DueDateParseResult {
        val normalized = phrase.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return DueDateParseResult.Unsupported
        if (containsNegation(normalized) || normalized.contains(" ago") || normalized.contains(" before")) {
            return DueDateParseResult.Rejected
        }

        val candidates = buildList {
            addAll(parseExplicitDates(normalized, today))
            addAll(parseRelativeDates(normalized, today))
            addAll(parseOffsets(normalized, today))
            addAll(parseWeekdays(normalized, today))
            addAll(parseMonthBoundaries(normalized, today))
        }
        if (candidates.any { it.isFailure }) return DueDateParseResult.Rejected
        val dates = candidates.mapNotNull { it.getOrNull() }.distinct()
        if (dates.isEmpty()) return DueDateParseResult.Unsupported
        if (dates.any { it !in today..today.plusYears(3) }) return DueDateParseResult.Rejected
        return dates.singleOrNull()
            ?.let(DueDateParseResult::Resolved)
            ?: DueDateParseResult.Rejected
    }

    private fun parseExplicitDates(phrase: String, today: LocalDate): List<Result<LocalDate>> = buildList {
        isoDate.findAll(phrase).forEach { match ->
            val (year, month, day) = match.groupValues[1].split("-")
            add(validDateResult(year, month, day))
        }
        dottedDate.findAll(phrase).forEach { match ->
            add(validDateResult(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        }
        val koreanDateMatches = koreanDate.findAll(phrase).toList()
        koreanDateMatches.forEach { match ->
            add(validDateResult(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
        }
        koreanMonthDay.findAll(phrase)
            .filterNot { monthDay -> koreanDateMatches.any { monthDay.range.isWithin(it.range) } }
            .forEach { match ->
                add(runCatching {
                    val month = match.groupValues[1].toInt()
                    val day = match.groupValues[2].toInt()
                    val thisYear = LocalDate.of(today.year, month, day)
                    if (thisYear < today) LocalDate.of(today.year + 1, month, day) else thisYear
                })
            }
    }

    private fun parseRelativeDates(phrase: String, today: LocalDate): List<Result<LocalDate>> = buildList {
        Regex("모레").findAll(phrase).forEach { add(runCatching { today.plusDays(2) }) }
        Regex("내일").findAll(phrase).forEach { add(runCatching { today.plusDays(1) }) }
        Regex("오늘").findAll(phrase).forEach { add(Result.success(today)) }

        val dayAfterTomorrowMatches = dayAfterTomorrow.findAll(phrase).toList()
        dayAfterTomorrowMatches.forEach { add(runCatching { today.plusDays(2) }) }
        tomorrow.findAll(phrase)
            .filterNot { match -> dayAfterTomorrowMatches.any { match.range.isWithin(it.range) } }
            .forEach { add(runCatching { today.plusDays(1) }) }
        todayWord.findAll(phrase).forEach { add(Result.success(today)) }
    }

    private fun parseOffsets(phrase: String, today: LocalDate): List<Result<LocalDate>> = buildList {
        koreanOffset.findAll(phrase).forEach { match ->
            add(runCatching { addOffset(today, match.groupValues[1], match.groupValues[2]) })
        }
        englishOffset.findAll(phrase).forEach { match ->
            add(runCatching { addOffset(today, match.groupValues[1], match.groupValues[2]) })
        }
    }

    private fun validDateResult(year: String, month: String, day: String): Result<LocalDate> =
        runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }

    private fun addOffset(today: LocalDate, amountText: String, unit: String): LocalDate {
        val amount = requireNotNull(amountText.toLongOrNull())
        require(amount in 0..1095)
        return when (unit) {
            "일", "day", "days" -> today.plusDays(amount)
            "주", "week", "weeks" -> today.plusWeeks(amount)
            "개월", "달", "month", "months" -> today.plusMonths(amount)
            else -> error("Unsupported date offset unit")
        }
    }

    private fun parseWeekdays(phrase: String, today: LocalDate): List<Result<LocalDate>> = buildList {
        koreanWeekdays.forEach { (word, dayOfWeek) ->
            Regex(word).findAll(phrase).forEach {
                add(runCatching { weekdayInRequestedWeek(phrase, today, dayOfWeek) })
            }
        }
        englishWeekdays.forEach { (word, dayOfWeek) ->
            Regex("\\b$word\\b").findAll(phrase).forEach {
                add(runCatching { weekdayInRequestedWeek(phrase, today, dayOfWeek) })
            }
        }
    }

    private fun weekdayInRequestedWeek(phrase: String, today: LocalDate, dayOfWeek: DayOfWeek): LocalDate {
        val weeksAhead = when {
            phrase.contains("다다음") || phrase.contains("week after next") -> 2L
            phrase.contains("다음 주") || phrase.contains("다음주") || phrase.contains("next week") -> 1L
            phrase.contains("이번 주") || phrase.contains("이번주") || phrase.contains("this week") -> 0L
            else -> return today.with(TemporalAdjusters.next(dayOfWeek))
        }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.plusWeeks(weeksAhead).with(TemporalAdjusters.nextOrSame(dayOfWeek))
    }

    private fun parseMonthBoundaries(phrase: String, today: LocalDate): List<Result<LocalDate>> = buildList {
        currentMonthLastWeekday.findAll(phrase).forEach {
            add(runCatching { lastWeekdayOfMonth(today) })
        }
        currentMonthLastDay.findAll(phrase).forEach {
            add(runCatching { today.with(TemporalAdjusters.lastDayOfMonth()) })
        }
    }

    private fun lastWeekdayOfMonth(today: LocalDate): LocalDate {
        val lastDay = today.with(TemporalAdjusters.lastDayOfMonth())
        return when (lastDay.dayOfWeek) {
            DayOfWeek.SATURDAY -> lastDay.minusDays(1)
            DayOfWeek.SUNDAY -> lastDay.minusDays(2)
            else -> lastDay
        }
    }

    private fun IntRange.isWithin(other: IntRange): Boolean =
        first >= other.first && last <= other.last

    private fun containsNegation(phrase: String): Boolean =
        englishNegation.containsMatchIn(phrase) || koreanNegation.containsMatchIn(phrase)
}
