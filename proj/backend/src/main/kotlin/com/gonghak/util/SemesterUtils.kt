package com.gonghak.util

object SemesterUtils {
    const val DEFAULT_SEMESTER = "2026-2"

    private val YEAR_TERM_REGEX = Regex(
        """(20\d{2})\s*(?:학년도|년|-|\.|/|\s)\s*(0?1|0?2|여름|하계|summer|겨울|동계|winter)(?:\s*(?:계절)?\s*학기)?""",
        RegexOption.IGNORE_CASE
    )

    fun parseOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val match = YEAR_TERM_REGEX.find(trimmed) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        if (year !in 2000..2100) return null
        val rawTerm = match.groupValues[2].lowercase()
        val canonicalTerm = when (rawTerm) {
            "1", "01" -> "1"
            "여름", "하계", "summer" -> "여름"
            "2", "02" -> "2"
            "겨울", "동계", "winter" -> "겨울"
            else -> return null
        }
        return "$year-$canonicalTerm"
    }

    fun normalizeSemester(raw: String?, fallback: String = DEFAULT_SEMESTER): String {
        return parseOrNull(raw) ?: parseOrNull(fallback) ?: DEFAULT_SEMESTER
    }

    fun semesterRank(semester: String?): Int {
        val norm = parseOrNull(semester) ?: return 0
        val parts = norm.split('-', limit = 2)
        if (parts.size != 2) return 0
        val year = parts[0].toIntOrNull() ?: return 0
        val termOrder = when (parts[1]) {
            "1" -> 1
            "여름" -> 2
            "2" -> 3
            "겨울" -> 4
            else -> 0
        }
        return year * 10 + termOrder
    }

    fun compareSemesters(a: String?, b: String?): Int {
        val rankA = semesterRank(a)
        val rankB = semesterRank(b)
        if (rankA != rankB) return rankA.compareTo(rankB)
        return (a ?: "").compareTo(b ?: "")
    }

    fun formatDisplayLabel(semester: String?): String {
        val norm = parseOrNull(semester) ?: return semester?.trim().orEmpty()
        val (year, term) = norm.split('-', limit = 2)
        return when (term) {
            "1" -> "${year}-1학기"
            "2" -> "${year}-2학기"
            "여름" -> "${year}-여름학기"
            "겨울" -> "${year}-겨울학기"
            else -> norm
        }
    }

    fun formatTimetableTitle(semester: String?): String {
        val norm = normalizeSemester(semester)
        val (year, term) = norm.split('-', limit = 2)
        val termTitle = when (term) {
            "1" -> "1학기"
            "2" -> "2학기"
            "여름" -> "여름계절학기"
            "겨울" -> "겨울계절학기"
            else -> "${term}학기"
        }
        return "${year}학년도 $termTitle 개설과목 및 강의시간표"
    }

    fun normalizeSectionLabel(rawSection: String?): String {
        val clean = rawSection?.trim().orEmpty()
        if (clean.isEmpty()) return "01분반"
        val withoutSuffix = clean.removeSuffix("분반").trim()
        if (withoutSuffix.isEmpty()) return "01분반"
        val num = withoutSuffix.toIntOrNull()
        return if (num != null && num in 1..99) {
            String.format("%02d분반", num)
        } else {
            "${withoutSuffix.take(20)}분반"
        }
    }

    /**
     * Splits full course codes like `7000-1-2957-01` or `7000-01-2608-02` into
     * `(baseCode = "7000-01-2608", section = "02분반")`.
     * If no section suffix is present (e.g., `7000-1-2957`), returns `(cleanCode, null)`.
     */
    fun splitCourseCodeAndSection(rawCode: String): Pair<String, String?> {
        val clean = rawCode.trim()
        if (clean.isEmpty()) return "" to null
        val segments = clean.split('-').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size >= 4) {
            val last = segments.last()
            if (Regex("^[0-9A-Za-z]{1,4}$").matches(last)) {
                val base = segments.dropLast(1).joinToString("-")
                return base to normalizeSectionLabel(last)
            }
        }
        return clean to null
    }

    fun normalizeCourseNameForMatch(name: String): String {
        return name.trim().replace(Regex("\\s+"), "").lowercase()
    }
}
