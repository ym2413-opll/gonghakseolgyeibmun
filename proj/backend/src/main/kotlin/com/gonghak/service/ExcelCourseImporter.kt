package com.gonghak.service

import com.gonghak.util.SemesterUtils
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedExcelSectionRow(
    val college: String,
    val department: String,
    val major: String,
    val baseCode: String,
    val section: String,
    val courseName: String,
    val completionType: String,
    val credit: Int,
    val professorName: String,
    val schedule: String
)

data class ParsedExcelTimetable(
    val detectedSemester: String?,
    val totalDataRows: Int,
    val sections: List<ParsedExcelSectionRow>
)

/**
 * Pure JDK 17 `.xlsx` parser with strict Zip-Bomb and XXE defenses.
 * Supports the official Kwangwoon University timetable format (`2026학년도 2학기 개설과목 및 강의시간표.xlsx`)
 * as well as header-mapped custom `.xlsx` sheets.
 */
object ExcelCourseImporter {
    const val MAX_UPLOAD_BYTES = 15 * 1024 * 1024 // 15 MB
    private const val MAX_ZIP_ENTRIES = 250
    private const val MAX_SINGLE_XML_BYTES = 24 * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 36 * 1024 * 1024
    private const val MAX_SHEET_ROWS = 10_000

    fun parseXlsx(bytes: ByteArray): ParsedExcelTimetable {
        if (bytes.size < 4 || bytes.size > MAX_UPLOAD_BYTES) {
            throw IllegalArgumentException("엑셀 파일 크기가 유효하지 않습니다. (최대 15MB)")
        }
        // ZIP magic header PK\x03\x04
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            throw IllegalArgumentException("유효한 .xlsx (OOXML) 엑셀 파일이 아닙니다.")
        }

        val entries = readSafeZipEntries(bytes)
        val sharedStringsXml = entries["xl/sharedStrings.xml"]
        val sharedStrings = if (sharedStringsXml != null) {
            parseSharedStrings(parseSafeXml(sharedStringsXml))
        } else {
            emptyList()
        }

        val targetSheetPath = resolvePrimarySheetPath(entries)
            ?: throw IllegalArgumentException("엑셀 파일에서 워크시트(sheet1.xml)를 찾을 수 없습니다.")
        val sheetXml = entries[targetSheetPath]
            ?: throw IllegalArgumentException("엑셀 워크시트 데이터를 읽을 수 없습니다.")

        val sheetDoc = parseSafeXml(sheetXml)
        val grid = extractSheetRows(sheetDoc, sharedStrings)
        if (grid.isEmpty()) {
            throw IllegalArgumentException("엑셀 시트에 데이터가 없습니다.")
        }

        // 1. Detect semester banner in top 5 rows (e.g. "2026학년도 2학기 개설과목 및 강의시간표", "2026학년도 여름계절학기...")
        var detectedSemester: String? = null
        for (rIdx in 0 until minOf(5, grid.size)) {
            for (cellVal in grid[rIdx].values) {
                val parsed = SemesterUtils.parseOrNull(cellVal)
                if (parsed != null) {
                    detectedSemester = parsed
                    break
                }
            }
            if (detectedSemester != null) break
        }

        // 2. Locate main header row containing 학정번호/학수번호/과목코드 and 과목명/교과목명
        var headerRowIdx = -1
        var colCollege = 2
        var colDept = 3
        var colMajor = 4
        var colCode = 6
        var colName = 7
        var colSection = -1
        var colCompType = 17
        var colCredit = 19
        var colProf = 21
        var colScheduleSingle = -1
        val dayTriplets = mutableListOf<Triple<Int, Int, Int>>()

        for (rIdx in 0 until minOf(12, grid.size)) {
            val row = grid[rIdx]
            val normalizedCells = row.mapValues { (_, v) -> v.replace(Regex("\\s+"), "") }
            val foundCodeCol = normalizedCells.entries.firstOrNull { (_, v) ->
                v in setOf("학정번호", "학수번호", "과목코드", "교과목코드", "강좌번호")
            }?.key
            val foundNameCol = normalizedCells.entries.firstOrNull { (_, v) ->
                v in setOf("과목명", "교과목명", "강의명", "강좌명")
            }?.key

            if (foundCodeCol != null && foundNameCol != null) {
                headerRowIdx = rIdx
                colCode = foundCodeCol
                colName = foundNameCol
                normalizedCells.forEach { (cIdx, label) ->
                    when {
                        label in setOf("개설대학", "단과대학", "대학") -> colCollege = cIdx
                        label in setOf("개설학과", "학과", "학부", "개설학부") -> colDept = cIdx
                        label in setOf("개설전공", "전공") -> colMajor = cIdx
                        label == "분반" -> colSection = cIdx
                        label == "이수구분" -> colCompType = cIdx
                        label == "학점" -> colCredit = cIdx
                        label in setOf("담당교수", "교수명", "교강사", "담당교강사") -> colProf = cIdx
                        label in setOf("강의시간", "시간표", "요일및교시") -> colScheduleSingle = cIdx
                        label.startsWith("요일1") -> dayTriplets.add(Triple(cIdx, cIdx + 1, cIdx + 2))
                        label.startsWith("요일2") -> dayTriplets.add(Triple(cIdx, cIdx + 1, cIdx + 2))
                        label.startsWith("요일3") -> dayTriplets.add(Triple(cIdx, cIdx + 1, cIdx + 2))
                    }
                }
                break
            }
        }

        if (headerRowIdx < 0) {
            throw IllegalArgumentException("엑셀 헤더 행('학정번호', '과목명')을 찾을 수 없습니다. 시간표 엑셀 양식을 확인해 주세요.")
        }

        if (dayTriplets.isEmpty() && colScheduleSingle < 0) {
            dayTriplets.add(Triple(24, 25, 26))
            dayTriplets.add(Triple(27, 28, 29))
            dayTriplets.add(Triple(30, 31, 32))
        }

        // Check if headerRowIdx + 1 is a subheader row (e.g., '일', '시간', '강의실')
        val dataStartRow = if (headerRowIdx + 1 < grid.size) {
            val nextRow = grid[headerRowIdx + 1]
            val nextCode = nextRow[colCode]?.trim().orEmpty()
            val nextName = nextRow[colName]?.trim().orEmpty()
            val hasSubHeader = nextCode.isEmpty() && nextName.isEmpty() &&
                nextRow.values.any { it.trim() in setOf("일", "시간", "강의실") }
            if (hasSubHeader) headerRowIdx + 2 else headerRowIdx + 1
        } else {
            headerRowIdx + 1
        }

        val parsedSections = mutableListOf<ParsedExcelSectionRow>()
        var dataRowsSeen = 0

        for (rIdx in dataStartRow until grid.size) {
            val row = grid[rIdx]
            val rawFullCode = row[colCode]?.trim().orEmpty()
            val rawCourseName = row[colName]?.trim().orEmpty()
            if (rawFullCode.isEmpty() && rawCourseName.isEmpty()) continue
            if (rawCourseName.isEmpty()) continue
            dataRowsSeen++

            val (splitBase, splitSec) = SemesterUtils.splitCourseCodeAndSection(rawFullCode)
            val explicitSec = if (colSection >= 0) row[colSection]?.trim()?.takeIf { it.isNotEmpty() } else null
            val finalBaseCode = splitBase.ifBlank { rawFullCode }
            val finalSection = SemesterUtils.normalizeSectionLabel(explicitSec ?: splitSec ?: "01")

            val college = row[colCollege]?.trim().orEmpty()
            val dept = row[colDept]?.trim().orEmpty()
            val major = row[colMajor]?.trim().orEmpty()
            val compType = row[colCompType]?.trim().orEmpty().ifBlank { "전선" }
            val credit = row[colCredit]?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(0, 15) ?: 3
            val profName = row[colProf]?.trim().orEmpty().ifBlank { "미지정" }

            val schedule = if (colScheduleSingle >= 0 && !row[colScheduleSingle].isNullOrBlank()) {
                row[colScheduleSingle]!!.trim()
            } else {
                val parts = mutableListOf<String>()
                for ((dCol, tCol, roomCol) in dayTriplets) {
                    val day = row[dCol]?.trim().orEmpty()
                    val periodRaw = row[tCol]?.trim().orEmpty()
                    val room = row[roomCol]?.trim().orEmpty()
                    if (day.isNotEmpty() || periodRaw.isNotEmpty()) {
                        val periodClean = if (periodRaw.endsWith(".0")) periodRaw.removeSuffix(".0") else periodRaw
                        val timePart = buildString {
                            if (day.isNotEmpty()) append(day)
                            if (periodClean.isNotEmpty()) {
                                if (isNotEmpty()) append(' ')
                                append(if (periodClean.endsWith("교시")) periodClean else "${periodClean}교시")
                            }
                            if (room.isNotEmpty()) {
                                append("($room)")
                            }
                        }.trim()
                        if (timePart.isNotEmpty()) parts.add(timePart)
                    }
                }
                parts.joinToString(", ")
            }

            parsedSections.add(
                ParsedExcelSectionRow(
                    college = PasswordSecurity.sanitizeInput(college).take(100),
                    department = PasswordSecurity.sanitizeInput(dept).take(100),
                    major = PasswordSecurity.sanitizeInput(major).take(100),
                    baseCode = PasswordSecurity.sanitizeInput(finalBaseCode).take(50),
                    section = PasswordSecurity.sanitizeInput(finalSection).take(50),
                    courseName = PasswordSecurity.sanitizeInput(rawCourseName).take(100),
                    completionType = PasswordSecurity.sanitizeInput(compType).take(50),
                    credit = credit,
                    professorName = PasswordSecurity.sanitizeInput(profName).take(100),
                    schedule = PasswordSecurity.sanitizeInput(schedule).take(200)
                )
            )
        }

        if (parsedSections.isEmpty()) {
            throw IllegalArgumentException("엑셀 파일에서 유효한 강의 행을 찾을 수 없습니다.")
        }

        return ParsedExcelTimetable(
            detectedSemester = detectedSemester,
            totalDataRows = dataRowsSeen,
            sections = parsedSections
        )
    }

    private fun readSafeZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val relevantSuffixes = setOf("sharedStrings.xml", "workbook.xml", "workbook.xml.rels")
        val map = mutableMapOf<String, ByteArray>()
        var entryCount = 0
        var totalUncompressed = 0L

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw IllegalArgumentException("엑셀 압축 항목 수가 허용 한도를 초과했습니다.")
                }
                val normName = entry.name.replace('\\', '/').removePrefix("/")
                val isRelevant = !entry.isDirectory && (
                    relevantSuffixes.any { normName.endsWith(it) } ||
                        (normName.startsWith("xl/worksheets/") && normName.endsWith(".xml"))
                    )
                if (isRelevant) {
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val read = zis.read(buf)
                        if (read == -1) break
                        entryBytes += read
                        totalUncompressed += read
                        if (entryBytes > MAX_SINGLE_XML_BYTES || totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            throw IllegalArgumentException("엑셀 내부 XML 크기가 허용 한도를 초과했습니다.")
                        }
                        baos.write(buf, 0, read)
                    }
                    map[normName] = baos.toByteArray()
                }
                zis.closeEntry()
            }
        }
        return map
    }

    private fun parseSafeXml(xmlBytes: ByteArray): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        return dbf.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
    }

    private fun resolvePrimarySheetPath(entries: Map<String, ByteArray>): String? {
        if ("xl/worksheets/sheet1.xml" in entries) {
            // Try workbook.xml first to honor sheet order (`강의시간표` is first sheet)
            val wbBytes = entries["xl/workbook.xml"]
            val relsBytes = entries["xl/_rels/workbook.xml.rels"]
            if (wbBytes != null && relsBytes != null) {
                runCatching {
                    val wbDoc = parseSafeXml(wbBytes)
                    val relsDoc = parseSafeXml(relsBytes)
                    val sheets = wbDoc.getElementsByTagName("sheet")
                    var chosenRid: String? = null
                    for (i in 0 until sheets.length) {
                        val el = sheets.item(i) as? Element ?: continue
                        val name = el.getAttribute("name")
                        val rid = el.getAttribute("r:id").ifBlank { el.getAttribute("id") }
                        if (chosenRid == null || name.contains("시간표")) {
                            chosenRid = rid
                            if (name.contains("시간표")) break
                        }
                    }
                    if (!chosenRid.isNullOrBlank()) {
                        val rels = relsDoc.getElementsByTagName("Relationship")
                        for (i in 0 until rels.length) {
                            val rel = rels.item(i) as? Element ?: continue
                            if (rel.getAttribute("Id") == chosenRid) {
                                val target = rel.getAttribute("Target").removePrefix("/")
                                val full = if (target.startsWith("xl/")) target else "xl/$target"
                                if (full in entries) return full
                            }
                        }
                    }
                }
            }
            return "xl/worksheets/sheet1.xml"
        }
        return entries.keys.firstOrNull { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
    }

    private fun parseSharedStrings(doc: Document): List<String> {
        val siNodes = doc.getElementsByTagName("si")
        val list = ArrayList<String>(siNodes.length)
        for (i in 0 until siNodes.length) {
            val si = siNodes.item(i)
            val sb = StringBuilder()
            collectTextElements(si, sb)
            list.add(sb.toString())
        }
        return list
    }

    private fun collectTextElements(node: Node, sb: StringBuilder) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val tag = child.nodeName.substringAfter(':')
                if (tag == "rPh") continue // skip phonetic furigana runs
                if (tag == "t") {
                    sb.append(child.textContent ?: "")
                } else {
                    collectTextElements(child, sb)
                }
            }
        }
    }

    private fun extractSheetRows(doc: Document, sharedStrings: List<String>): List<Map<Int, String>> {
        val rowNodes = doc.getElementsByTagName("row")
        val limit = minOf(rowNodes.length, MAX_SHEET_ROWS)
        val rows = ArrayList<Map<Int, String>>(limit)

        for (r in 0 until limit) {
            val rowEl = rowNodes.item(r) as? Element ?: continue
            val cellMap = mutableMapOf<Int, String>()
            val children = rowEl.childNodes
            var fallbackCol = 0
            for (c in 0 until children.length) {
                if (cellMap.size >= 100) break
                val cellNode = children.item(c)
                if (cellNode.nodeType != Node.ELEMENT_NODE) continue
                val tag = cellNode.nodeName.substringAfter(':')
                if (tag != "c") continue
                val cellEl = cellNode as Element
                val ref = cellEl.getAttribute("r")
                val colIdx = if (ref.isNotBlank()) columnIndexFromCellRef(ref) else fallbackCol
                fallbackCol = colIdx + 1

                val cellType = cellEl.getAttribute("t")
                val textValue = when (cellType) {
                    "s" -> {
                        val vText = findChildText(cellEl, "v")?.trim().orEmpty()
                        val sIdx = vText.toIntOrNull()
                        if (sIdx != null && sIdx in sharedStrings.indices) sharedStrings[sIdx] else ""
                    }
                    "inlineStr" -> {
                        val sb = StringBuilder()
                        collectTextElements(cellEl, sb)
                        sb.toString()
                    }
                    else -> findChildText(cellEl, "v").orEmpty()
                }
                if (textValue.isNotEmpty()) {
                    cellMap[colIdx] = textValue
                }
            }
            rows.add(cellMap)
        }
        return rows
    }

    private fun findChildText(parent: Element, localName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val ch = children.item(i)
            if (ch.nodeType == Node.ELEMENT_NODE && ch.nodeName.substringAfter(':') == localName) {
                return ch.textContent
            }
        }
        return null
    }

    private fun columnIndexFromCellRef(cellRef: String): Int {
        var col = 0
        for (ch in cellRef) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
            } else {
                break
            }
        }
        return (col - 1).coerceAtLeast(0)
    }
}
