package com.gonghak.service

import com.gonghak.models.QuestionCategory
import com.gonghak.util.SemesterUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class EmailInlineAttachment(
    val id: String,
    val originalName: String,
    val contentType: String,
    val fileSize: Long,
    val cid: String? = null
)

data class EmailThreadPost(
    val sequenceNumber: Int,
    val authorDisplay: String,
    val roleLabel: String,
    val roleKind: String, // "op", "prof", "co-prof", "admin", "student"
    val isAnonymous: Boolean,
    val isEdited: Boolean = false,
    val isHidden: Boolean = false,
    val createdAt: Instant,
    val content: String,
    val attachments: List<EmailInlineAttachment> = emptyList()
)

data class EmailThreadSnapshot(
    val courseName: String,
    val category: QuestionCategory,
    val professorName: String,
    val title: String,
    val isThreadOpen: Boolean,
    val isCoProfessorAlert: Boolean,
    val hasReplyRoute: Boolean,
    val posts: List<EmailThreadPost>,
    val section: String? = null,
    val semester: String = "",
    val currentSemester: String = ""
)

/**
 * Renders a question thread (title banner, badges, chronological #1/#2/#3 post stream,
 * Markdown, Korean-adjacent emphasis, LaTeX math, tables, code blocks, inline photos,
 * and attachment cards) into email-client-safe inline-CSS HTML matching the browser thread view.
 */
object EmailThreadHtmlRenderer {
    private val KST_ZONE = ZoneId.of("Asia/Seoul")
    private val KST_FORMATTER = DateTimeFormatter.ofPattern("yyyy. M. d. a h:mm:ss", Locale.KOREAN)

    private const val MAX_BLOCKQUOTE_DEPTH = 8
    private const val MAX_LATEX_DEPTH = 16
    private const val MAX_LATEX_EXPR_LENGTH = 2_000
    private const val MAX_POST_HTML_LENGTH = 131_072 // 128 KB per post

    internal val ALLOWED_INLINE_IMAGE_MIME_TYPES = setOf(
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp"
    )

    private val HR_REGEX = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
    private val HEADING_PREFIX_REGEX = Regex("""^#{1,6}\s+""")
    private val UL_PREFIX_REGEX = Regex("""^\s*[-*+]\s+""")
    private val OL_PREFIX_REGEX = Regex("""^\s*\d+\.\s+""")
    private val LIST_PREFIX_REGEX = Regex("""^\s*(?:[-*+]|\d+\.)\s+""")
    private val TASK_ITEM_REGEX = Regex("""^\[([ xX])\]\s+(.*)$""")
    private val TABLE_SEP_CELL_REGEX = Regex("""^\s*:?-{3,}:?\s*$""")
    private val INLINE_CODE_REGEX = Regex("""`([^`\r\n]+)`""")
    private val INLINE_PAREN_MATH_REGEX = Regex("""\\\(([^\r\n]+?)\\\)""")
    private val INLINE_DOLLAR_MATH_REGEX = Regex("""\$([^$\r\n]+?)\$""")
    private val CURRENCY_KOREAN_REGEX = Regex("""^\d+(?:[.,]\d+)?\s*[가-힣]""")
    private val MD_IMAGE_REGEX = Regex("""!\[([^\]\n]{0,500})\]\(([^)\s]{1,2000})\)""")
    private val MD_LINK_REGEX = Regex("""\[([^\]\n]{1,500})\]\(([^)\s]{1,2000})\)""")
    private val PHOTO_REF_REGEX = Regex("""\[(?:#(\d+)\s*)?사진\s*(\d+)\]""")
    private val BOLD_STAR_REGEX = Regex("""\*\*(.+?)\*\*""")
    private val BOLD_UNDER_REGEX = Regex("""__(.+?)__""")
    private val STRIKE_REGEX = Regex("""~~(.+?)~~""")
    private val ITALIC_STAR_REGEX = Regex("""(?<!\*)\*([^*\r\n]+?)\*(?!\*)""")
    private val PLACEHOLDER_RESTORE_REGEX = Regex("""\uE100(\d+)\uE101""")

    private val BLOCKED_LATEX_COMMANDS = setOf(
        "href", "url", "includegraphics", "htmldata", "htmlstyle", "htmlclass", "htmlid",
        "def", "gdef", "edef", "xdef", "let", "newcommand", "renewcommand", "providecommand",
        "global", "futurelet", "write", "input", "include", "openin", "openout"
    )

    private val LATEX_SYMBOLS = mapOf(
        "infty" to "&infin;",
        "pm" to "&plusmn;",
        "mp" to "&#8723;",
        "times" to "&times;",
        "div" to "&divide;",
        "cdot" to "&middot;",
        "cdots" to "&ctdot;",
        "ldots" to "&hellip;",
        "dots" to "&hellip;",
        "vdots" to "&#8942;",
        "ddots" to "&#8945;",
        "le" to "&le;",
        "leq" to "&le;",
        "ge" to "&ge;",
        "geq" to "&ge;",
        "ne" to "&ne;",
        "neq" to "&ne;",
        "approx" to "&approx;",
        "equiv" to "&equiv;",
        "sim" to "&sim;",
        "simeq" to "&#8771;",
        "cong" to "&cong;",
        "propto" to "&prop;",
        "to" to "&rarr;",
        "rightarrow" to "&rarr;",
        "leftarrow" to "&larr;",
        "leftrightarrow" to "&harr;",
        "Rightarrow" to "&rArr;",
        "Leftarrow" to "&lArr;",
        "Leftrightarrow" to "&hArr;",
        "implies" to "&rArr;",
        "iff" to "&hArr;",
        "mapsto" to "&#8614;",
        "partial" to "&part;",
        "nabla" to "&nabla;",
        "forall" to "&forall;",
        "exists" to "&exist;",
        "in" to "&isin;",
        "notin" to "&notin;",
        "subset" to "&sub;",
        "subseteq" to "&sube;",
        "supset" to "&sup;",
        "supseteq" to "&supe;",
        "cup" to "&cup;",
        "cap" to "&cap;",
        "emptyset" to "&empty;",
        "varnothing" to "&empty;",
        "land" to "&and;",
        "lor" to "&or;",
        "lnot" to "&not;",
        "neg" to "&not;",
        "angle" to "&ang;",
        "perp" to "&perp;",
        "parallel" to "&#8741;",
        "degree" to "&deg;",
        "circ" to "&#8728;",
        "mid" to "&#8739;",
        "ell" to "&#8467;",
        "hbar" to "&#8463;",
        "alpha" to "&alpha;",
        "beta" to "&beta;",
        "gamma" to "&gamma;",
        "delta" to "&delta;",
        "epsilon" to "&epsilon;",
        "varepsilon" to "&#949;",
        "zeta" to "&zeta;",
        "eta" to "&eta;",
        "theta" to "&theta;",
        "vartheta" to "&thetasym;",
        "iota" to "&iota;",
        "kappa" to "&kappa;",
        "lambda" to "&lambda;",
        "mu" to "&mu;",
        "nu" to "&nu;",
        "xi" to "&xi;",
        "pi" to "&pi;",
        "rho" to "&rho;",
        "sigma" to "&sigma;",
        "tau" to "&tau;",
        "upsilon" to "&upsilon;",
        "phi" to "&phi;",
        "varphi" to "&#966;",
        "chi" to "&chi;",
        "psi" to "&psi;",
        "omega" to "&omega;",
        "Gamma" to "&Gamma;",
        "Delta" to "&Delta;",
        "Theta" to "&Theta;",
        "Lambda" to "&Lambda;",
        "Xi" to "&Xi;",
        "Pi" to "&Pi;",
        "Sigma" to "&Sigma;",
        "Upsilon" to "&Upsilon;",
        "Phi" to "&Phi;",
        "Psi" to "&Psi;",
        "Omega" to "&Omega;",
        "quad" to "&emsp;",
        "qquad" to "&emsp;&emsp;",
        "," to "&thinsp;",
        ";" to "&ensp;",
        "!" to "",
        " " to "&nbsp;",
        "{" to "{",
        "}" to "}",
        "%" to "%",
        "#" to "#",
        "&" to "&amp;",
        "_" to "_",
        "$" to "$"
    )

    private val ROMAN_FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "log", "ln", "exp", "det", "dim", "gcd", "max", "min", "sup", "inf", "arg", "deg", "ker", "Pr"
    )

    private val BIG_OPERATORS = mapOf(
        "sum" to "&sum;",
        "prod" to "&prod;",
        "int" to "&int;",
        "iint" to "&#8748;",
        "iiint" to "&#8749;",
        "oint" to "&#8750;",
        "bigcup" to "&#8899;",
        "bigcap" to "&#8898;",
        "lim" to "lim"
    )

    private val BLACKBOARD_CHARS = mapOf(
        'R' to "&#8477;",
        'N' to "&#8469;",
        'Z' to "&#8484;",
        'Q' to "&#8474;",
        'C' to "&#8450;",
        'P' to "&#8473;",
        'E' to "&#120124;"
    )

    fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isSafeEmailUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1fKB", kb)
        return String.format(Locale.US, "%.1fMB", kb / 1024.0)
    }

    private fun formatTimestamp(instant: Instant): String {
        return runCatching { instant.atZone(KST_ZONE).format(KST_FORMATTER) }.getOrDefault(instant.toString())
    }

    fun renderThreadEmailHtml(snapshot: EmailThreadSnapshot): String {
        val catLabel = when (snapshot.category) {
            QuestionCategory.GRADE -> "성적"
            QuestionCategory.ASSIGNMENT -> "과제"
            QuestionCategory.LECTURE -> "강의"
        }
        val (catBg, catColor, catBorder) = when (snapshot.category) {
            QuestionCategory.GRADE -> Triple("#fef2f2", "#b91c1c", "#fecaca")
            QuestionCategory.ASSIGNMENT -> Triple("#eff6ff", "#1d4ed8", "#bfdbfe")
            QuestionCategory.LECTURE -> Triple("#f0fdf4", "#15803d", "#bbf7d0")
        }

        val profAnswerCount = snapshot.posts.drop(1).count { it.roleKind == "prof" || it.roleKind == "co-prof" || it.roleKind == "admin" }
        val lastPost = snapshot.posts.lastOrNull()
        val isSolved = snapshot.posts.size > 1 && lastPost != null && lastPost.roleKind != "op" && lastPost.roleKind != "student"
        val statusText = when {
            isSolved -> "답변 완료 ($profAnswerCount)"
            profAnswerCount > 0 -> "추가 질문 대기"
            else -> "답변 대기"
        }
        val (statusBg, statusColor, statusBorder) = if (isSolved) {
            Triple("#dcfce7", "#15803d", "#86efac")
        } else {
            Triple("#fef3c7", "#b45309", "#fde68a")
        }

        val imagesBySeq: Map<Int, List<EmailInlineAttachment>> = snapshot.posts
            .filterNot { it.isHidden }
            .associate { post ->
                post.sequenceNumber to post.attachments.filter {
                    it.contentType.substringBefore(';').trim().lowercase() in ALLOWED_INLINE_IMAGE_MIME_TYPES
                }
            }

        val sb = StringBuilder(8192)
        sb.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">")
        sb.append("</head><body style=\"margin:0;padding:0;background-color:#f4f4f5;color:#18181b;font-family:'Pretendard','Apple SD Gothic Neo','Malgun Gothic',-apple-system,BlinkMacSystemFont,sans-serif;line-height:1.65;-webkit-font-smoothing:antialiased;\">")

        sb.append("<div style=\"max-width:760px;margin:0 auto;padding:20px 14px 28px;\">")

        if (snapshot.hasReplyRoute) {
            sb.append("<div style=\"text-align:center;font-size:12px;color:#71717a;padding:6px 10px 14px;border-bottom:1px dashed #d4d4d8;margin-bottom:16px;\">")
            sb.append(escapeHtml(EMAIL_REPLY_ABOVE_MARKER))
            sb.append("</div>")
        }

        // Top portal header bar
        sb.append("<div style=\"margin-bottom:14px;font-size:12px;color:#52525b;\">")
        sb.append("<strong style=\"color:#18181b;\">광운대학교 교수질문센터</strong>")
        if (snapshot.isCoProfessorAlert) {
            sb.append(" &middot; <span style=\"color:#1d4ed8;font-weight:600;\">공동강의 질문 알림</span>")
        } else {
            sb.append(" &middot; 스레드 알림")
        }
        sb.append("</div>")

        // Topic Title Banner (.forum-thread-title-box)
        sb.append("<div style=\"background-color:#ffffff;border:1px solid #e4e4e7;border-radius:12px;padding:20px 22px;margin-bottom:16px;box-shadow:0 1px 2px rgba(0,0,0,0.04);\">")
        sb.append("<div style=\"margin-bottom:10px;line-height:2.0;\">")
        sb.append(renderPill(catLabel, catBg, catColor, catBorder, bold = true))
        val courseLabel = if (!snapshot.section.isNullOrBlank()) {
            "${snapshot.courseName} (${snapshot.section})"
        } else {
            snapshot.courseName
        }
        sb.append(renderPill(courseLabel, "#f4f4f5", "#18181b", "#e4e4e7", bold = true))
        if (snapshot.semester.isNotBlank() && snapshot.currentSemester.isNotBlank() && snapshot.semester != snapshot.currentSemester) {
            sb.append(renderPill("${SemesterUtils.formatDisplayLabel(snapshot.semester)} 작성", "#eff6ff", "#1d4ed8", "#bfdbfe", bold = true))
        }
        sb.append(renderPill("담당: ${snapshot.professorName}", "#fafafa", "#52525b", "#e4e4e7"))
        if (snapshot.isThreadOpen) {
            sb.append(renderPill("열림", "#f0fdf4", "#15803d", "#bbf7d0"))
        } else {
            sb.append(renderPill("질문자, 담당교수 외 닫힘", "#f4f4f5", "#52525b", "#d4d4d8"))
        }
        sb.append(renderPill(statusText, statusBg, statusColor, statusBorder, bold = true))
        sb.append("</div>")
        sb.append("<h2 style=\"margin:0;font-size:19px;font-weight:800;line-height:1.38;color:#18181b;word-break:break-word;\">")
        sb.append(escapeHtml(snapshot.title))
        sb.append("</h2>")
        sb.append("</div>")

        // Chronological Post Stream (.forum-post-stream)
        for (post in snapshot.posts) {
            sb.append(renderPostCard(post, imagesBySeq))
        }

        // Footer
        sb.append("<div style=\"margin-top:16px;padding:12px 16px;background-color:#ffffff;border:1px solid #e4e4e7;border-radius:10px;font-size:12px;color:#71717a;text-align:center;\">")
        if (snapshot.hasReplyRoute) {
            sb.append("이 메일에 바로 <strong>답장</strong>을 보내시면 위 스레드에 답변이 자동 등록됩니다.")
        } else {
            sb.append("포털에 로그인해 해당 질문 스레드에서 직접 답변을 작성할 수 있습니다.")
        }
        sb.append("</div>")

        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun renderPill(text: String, bg: String, color: String, border: String, bold: Boolean = false): String {
        val weight = if (bold) "700" else "600"
        return "<span style=\"display:inline-block;margin:0 6px 4px 0;padding:2px 9px;border-radius:999px;background-color:$bg;color:$color;border:1px solid $border;font-size:12px;font-weight:$weight;line-height:1.45;\">${escapeHtml(text)}</span>"
    }

    private fun renderPostCard(
        post: EmailThreadPost,
        imagesBySeq: Map<Int, List<EmailInlineAttachment>>
    ): String {
        val isProf = post.roleKind == "prof" || post.roleKind == "co-prof"
        val isAdmin = post.roleKind == "admin"
        val cardBorder = when {
            isAdmin -> "#fecaca"
            isProf -> "#bbf7d0"
            else -> "#e4e4e7"
        }
        val sidebarBg = when {
            isAdmin -> "#fef2f2"
            isProf -> "#f0fdf4"
            else -> "#fafafa"
        }
        val avatarBg = when {
            isAdmin -> "#dc2626"
            isProf -> "#15803d"
            else -> "#18181b"
        }
        val (roleBg, roleColor, roleBorder) = when {
            isAdmin -> Triple("#fee2e2", "#b91c1c", "#fecaca")
            isProf -> Triple("#dcfce7", "#15803d", "#86efac")
            post.sequenceNumber == 1 -> Triple("#f4f4f5", "#18181b", "#d4d4d8")
            else -> Triple("#eff6ff", "#1d4ed8", "#bfdbfe")
        }
        val avatarChar = if (post.isAnonymous) "익" else post.authorDisplay.trim().firstOrNull()?.toString() ?: "수"
        val seqBadgeLabel = when {
            post.sequenceNumber == 1 -> "#1 · 질문"
            isAdmin -> "#${post.sequenceNumber} · 관리자 답변"
            isProf -> "#${post.sequenceNumber} · 교수 답변"
            else -> "#${post.sequenceNumber} · 추가 질문"
        }

        val renderedBody = if (post.isHidden) {
            "<p style=\"margin:0;color:#71717a;font-style:italic;\">관리자에 의해 가려진 게시물입니다.</p>"
        } else {
            renderMarkdownToEmailHtml(post.content, currentSeq = post.sequenceNumber, imagesBySeq = imagesBySeq)
        }

        val sb = StringBuilder(2048)
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:separate;border-spacing:0;background-color:#ffffff;border:1px solid $cardBorder;border-radius:12px;margin-bottom:14px;overflow:hidden;box-shadow:0 1px 2px rgba(0,0,0,0.03);\">")
        sb.append("<tr>")

        // Left sidebar (Author info)
        sb.append("<td valign=\"top\" width=\"156\" style=\"width:156px;background-color:$sidebarBg;border-right:1px solid #e4e4e7;padding:18px 14px;text-align:center;\">")
        sb.append("<div style=\"width:44px;height:44px;line-height:44px;border-radius:10px;background-color:$avatarBg;color:#ffffff;font-size:17px;font-weight:800;margin:0 auto 10px;\">${escapeHtml(avatarChar)}</div>")
        sb.append("<div style=\"font-size:13px;font-weight:700;color:#18181b;word-break:break-word;margin-bottom:6px;\">${escapeHtml(post.authorDisplay)}</div>")
        sb.append("<div style=\"margin-bottom:4px;\"><span style=\"display:inline-block;padding:2px 8px;border-radius:999px;background-color:$roleBg;color:$roleColor;border:1px solid $roleBorder;font-size:11px;font-weight:700;\">${escapeHtml(post.roleLabel)}</span></div>")
        if (post.isAnonymous) {
            sb.append("<div><span style=\"display:inline-block;padding:1px 7px;border-radius:999px;background-color:#f4f4f5;color:#52525b;border:1px solid #d4d4d8;font-size:11px;font-weight:600;\">익명 보호됨</span></div>")
        }
        sb.append("</td>")

        // Right body column
        sb.append("<td valign=\"top\" style=\"padding:16px 20px;background-color:#ffffff;\">")

        // Header bar inside post
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-bottom:1px solid #f4f4f5;padding-bottom:8px;margin-bottom:14px;\"><tr>")
        sb.append("<td style=\"font-size:12px;color:#71717a;\">${escapeHtml(formatTimestamp(post.createdAt))}")
        if (post.isEdited) {
            sb.append(" <em style=\"color:#71717a;\">(수정됨)</em>")
        }
        sb.append("</td>")
        sb.append("<td align=\"right\" style=\"white-space:nowrap;\"><span style=\"display:inline-block;padding:2px 8px;border-radius:999px;background-color:#f4f4f5;color:#52525b;border:1px solid #e4e4e7;font-size:11px;font-weight:700;\">${escapeHtml(seqBadgeLabel)}</span></td>")
        sb.append("</tr></table>")

        // Markdown body
        sb.append("<div style=\"font-size:14px;line-height:1.7;color:#18181b;word-break:break-word;\">")
        sb.append(renderedBody)
        sb.append("</div>")

        // Attachments grid
        if (post.attachments.isNotEmpty() && !post.isHidden) {
            sb.append(renderAttachmentsSection(post.attachments))
        }

        sb.append("</td></tr></table>")
        return sb.toString()
    }

    private fun renderAttachmentsSection(attachments: List<EmailInlineAttachment>): String {
        val sb = StringBuilder()
        sb.append("<div style=\"margin-top:16px;padding-top:12px;border-top:1px solid #e4e4e7;\">")
        sb.append("<div style=\"font-size:12px;font-weight:700;color:#52525b;margin-bottom:8px;\">첨부파일 (${attachments.size})</div>")
        for (att in attachments) {
            val isImg = att.contentType.startsWith("image/", ignoreCase = true)
            if (isImg && !att.cid.isNullOrBlank()) {
                sb.append("<div style=\"margin-bottom:10px;padding:8px;border:1px solid #e4e4e7;border-radius:8px;background-color:#fafafa;\">")
                sb.append("<img src=\"cid:${escapeHtml(att.cid)}\" alt=\"${escapeHtml(att.originalName)}\" style=\"max-width:100%;height:auto;border-radius:6px;display:block;margin-bottom:6px;\" />")
                sb.append("<div style=\"font-size:12px;color:#52525b;\">${escapeHtml(att.originalName)} &middot; ${formatFileSize(att.fileSize)}</div>")
                sb.append("</div>")
            } else {
                sb.append("<div style=\"display:inline-block;margin:0 8px 6px 0;padding:6px 10px;border:1px solid #e4e4e7;border-radius:8px;background-color:#fafafa;font-size:12px;color:#18181b;\">")
                sb.append("&#128206; <strong>${escapeHtml(att.originalName)}</strong> <span style=\"color:#71717a;\">(${formatFileSize(att.fileSize)})</span>")
                sb.append("</div>")
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    /**
     * Converts Markdown + LaTeX math + [사진 N] inline photo references into XSS-safe inline-CSS HTML.
     */
    fun renderMarkdownToEmailHtml(
        markdown: String,
        currentSeq: Int = 1,
        imagesBySeq: Map<Int, List<EmailInlineAttachment>> = emptyMap(),
        depth: Int = 0
    ): String {
        if (markdown.isBlank()) return ""
        if (depth > MAX_BLOCKQUOTE_DEPTH) {
            return "<p style=\"margin:0 0 10px;\">${escapeHtml(markdown.take(4000))}</p>"
        }
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val out = StringBuilder(minOf(markdown.length * 2, MAX_POST_HTML_LENGTH))
        var i = 0

        while (i < lines.size) {
            if (out.length >= MAX_POST_HTML_LENGTH) {
                out.append("<p style=\"margin:8px 0 0;font-size:12px;color:#71717a;\">… (본문이 길어 일부가 생략되었습니다. 포털에서 전체 내용을 확인해 주세요.)</p>")
                break
            }
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced code block ```...```
            if (trimmed.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume closing ```
                out.append("<pre style=\"margin:10px 0;padding:12px 14px;border-radius:8px;background-color:#fafafa;border:1px solid #e4e4e7;overflow-x:auto;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:13px;line-height:1.5;color:#18181b;white-space:pre-wrap;\"><code>")
                out.append(escapeHtml(codeLines.joinToString("\n")))
                out.append("</code></pre>")
                continue
            }

            // 2. Display math block $$...$$ or \[...\]
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                val isDollar = trimmed.startsWith("$$")
                val openDelim = if (isDollar) "$$" else "\\["
                val closeDelim = if (isDollar) "$$" else "\\]"
                val afterOpen = trimmed.removePrefix(openDelim)
                val sameLineClose = afterOpen.indexOf(closeDelim)
                val mathExpr = if (sameLineClose >= 0) {
                    i++
                    afterOpen.substring(0, sameLineClose).trim()
                } else {
                    val mathLines = mutableListOf<String>()
                    if (afterOpen.isNotBlank()) mathLines.add(afterOpen)
                    i++
                    while (i < lines.size) {
                        val cur = lines[i]
                        val closeIdx = cur.indexOf(closeDelim)
                        if (closeIdx >= 0) {
                            val beforeClose = cur.substring(0, closeIdx)
                            if (beforeClose.isNotBlank()) mathLines.add(beforeClose)
                            i++
                            break
                        }
                        mathLines.add(cur)
                        i++
                    }
                    mathLines.joinToString("\n").trim()
                }
                out.append(renderDisplayMathBlock(mathExpr))
                continue
            }

            // 3. Horizontal rule
            if (HR_REGEX.matches(trimmed)) {
                out.append("<hr style=\"border:none;border-top:1px solid #e4e4e7;margin:14px 0;\" />")
                i++
                continue
            }

            // 4. Headings (# .. ######)
            val headingMatch = HEADING_REGEX.find(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val sizePx = when (level) {
                    1 -> 20
                    2 -> 18
                    3 -> 16
                    else -> 15
                }
                val borderCss = if (level <= 2) "border-bottom:1px solid #e4e4e7;padding-bottom:4px;" else ""
                out.append("<h$level style=\"margin:14px 0 8px;font-size:${sizePx}px;font-weight:700;line-height:1.35;color:#18181b;$borderCss\">")
                out.append(renderInlineWithFigures(headingMatch.groupValues[2], currentSeq, imagesBySeq))
                out.append("</h$level>")
                i++
                continue
            }

            // 5. Blockquote (> ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").removePrefix(" "))
                    i++
                }
                out.append("<blockquote style=\"margin:10px 0;padding:8px 14px;border-left:3px solid #18181b;background-color:#fafafa;color:#52525b;border-radius:0 6px 6px 0;\">")
                out.append(renderMarkdownToEmailHtml(quoteLines.joinToString("\n"), currentSeq, imagesBySeq, depth + 1))
                out.append("</blockquote>")
                continue
            }

            // 6. GFM Table
            if (trimmed.contains('|') && i + 1 < lines.size && isTableSeparatorLine(lines[i + 1])) {
                val headers = splitTableRow(line)
                val aligns = parseTableAlignments(lines[i + 1])
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains('|')) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                out.append("<div style=\"margin:10px 0;overflow-x:auto;\"><table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;font-size:13px;\">")
                out.append("<thead><tr>")
                headers.forEachIndexed { colIdx, cell ->
                    val align = aligns.getOrNull(colIdx) ?: "left"
                    out.append("<th style=\"border:1px solid #e4e4e7;padding:7px 10px;background-color:#fafafa;font-weight:700;color:#18181b;text-align:$align;\">")
                    out.append(renderInlineWithFigures(cell, currentSeq, imagesBySeq))
                    out.append("</th>")
                }
                out.append("</tr></thead><tbody>")
                for (row in rows) {
                    out.append("<tr>")
                    for (colIdx in headers.indices) {
                        val cell = row.getOrNull(colIdx).orEmpty()
                        val align = aligns.getOrNull(colIdx) ?: "left"
                        out.append("<td style=\"border:1px solid #e4e4e7;padding:7px 10px;color:#18181b;text-align:$align;vertical-align:top;\">")
                        out.append(renderInlineWithFigures(cell, currentSeq, imagesBySeq))
                        out.append("</td>")
                    }
                    out.append("</tr>")
                }
                out.append("</tbody></table></div>")
                continue
            }

            // 7. Unordered / Task list
            if (UL_PREFIX_REGEX.containsMatchIn(line)) {
                out.append("<ul style=\"margin:8px 0 10px;padding-left:22px;\">")
                while (i < lines.size && UL_PREFIX_REGEX.containsMatchIn(lines[i])) {
                    val itemText = lines[i].replaceFirst(UL_PREFIX_REGEX, "")
                    val taskChecked = TASK_ITEM_REGEX.find(itemText)
                    out.append("<li style=\"margin:4px 0;\">")
                    if (taskChecked != null) {
                        val box = if (taskChecked.groupValues[1].equals("x", ignoreCase = true)) "&#9745; " else "&#9744; "
                        out.append(box)
                        out.append(renderInlineWithFigures(taskChecked.groupValues[2], currentSeq, imagesBySeq))
                    } else {
                        out.append(renderInlineWithFigures(itemText, currentSeq, imagesBySeq))
                    }
                    out.append("</li>")
                    i++
                }
                out.append("</ul>")
                continue
            }

            // 8. Ordered list
            if (OL_PREFIX_REGEX.containsMatchIn(line)) {
                out.append("<ol style=\"margin:8px 0 10px;padding-left:22px;\">")
                while (i < lines.size && OL_PREFIX_REGEX.containsMatchIn(lines[i])) {
                    val itemText = lines[i].replaceFirst(OL_PREFIX_REGEX, "")
                    out.append("<li style=\"margin:4px 0;\">")
                    out.append(renderInlineWithFigures(itemText, currentSeq, imagesBySeq))
                    out.append("</li>")
                    i++
                }
                out.append("</ol>")
                continue
            }

            // 9. Paragraph (collect contiguous non-block lines)
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty() ||
                    curTrim.startsWith("```") ||
                    curTrim.startsWith("$$") ||
                    curTrim.startsWith("\\[") ||
                    curTrim.startsWith(">") ||
                    HEADING_PREFIX_REGEX.containsMatchIn(curTrim) ||
                    HR_REGEX.matches(curTrim) ||
                    LIST_PREFIX_REGEX.containsMatchIn(cur) ||
                    (curTrim.contains('|') && i + 1 < lines.size && isTableSeparatorLine(lines[i + 1]))
                ) {
                    break
                }
                paraLines.add(cur)
                i++
            }
            if (paraLines.isNotEmpty()) {
                val figures = mutableListOf<String>()
                val renderedLines = paraLines.map { renderInlineMarkdown(it, currentSeq, imagesBySeq, figures) }
                out.append("<p style=\"margin:0 0 10px;\">")
                out.append(renderedLines.joinToString("<br />"))
                out.append("</p>")
                figures.forEach { out.append(it) }
            }
        }

        return out.toString()
    }

    private fun isTableSeparatorLine(line: String): Boolean {
        val trimmed = line.trim().trim('|')
        if (trimmed.isEmpty()) return false
        return trimmed.split('|').all { cell ->
            TABLE_SEP_CELL_REGEX.matches(cell)
        }
    }

    private fun splitTableRow(line: String): List<String> {
        return line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
    }

    private fun parseTableAlignments(sepLine: String): List<String> {
        return splitTableRow(sepLine).map { cell ->
            val left = cell.startsWith(":")
            val right = cell.endsWith(":")
            when {
                left && right -> "center"
                right -> "right"
                else -> "left"
            }
        }
    }

    private fun renderInlineWithFigures(
        text: String,
        currentSeq: Int,
        imagesBySeq: Map<Int, List<EmailInlineAttachment>>
    ): String {
        val figures = mutableListOf<String>()
        val inlineHtml = renderInlineMarkdown(text, currentSeq, imagesBySeq, figures)
        return if (figures.isEmpty()) inlineHtml else inlineHtml + figures.joinToString("")
    }

    private fun renderInlineMarkdown(
        text: String,
        currentSeq: Int,
        imagesBySeq: Map<Int, List<EmailInlineAttachment>>,
        collectedFigures: MutableList<String>
    ): String {
        // Protect inline code, inline math ($...$, \(...\)), images, and links via placeholders before emphasis
        val placeholders = mutableListOf<String>()
        fun stash(html: String): String {
            val idx = placeholders.size
            placeholders.add(html)
            return "\uE100${idx}\uE101"
        }

        // Strip placeholder sentinel chars from untrusted user input to prevent placeholder injection/HTML-bomb DoS
        var s = text.replace("\uE100", "").replace("\uE101", "")

        // 1. Inline code `...`
        if (s.contains('`')) {
            s = INLINE_CODE_REGEX.replace(s) { mr ->
                stash(
                    "<code style=\"font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12.5px;padding:1px 5px;border-radius:4px;background-color:#f4f4f5;border:1px solid #e4e4e7;color:#18181b;\">${escapeHtml(mr.groupValues[1])}</code>"
                )
            }
        }

        // 2. Inline LaTeX math \(...\) and $...$
        if (s.contains("\\(") && s.contains("\\)")) {
            s = INLINE_PAREN_MATH_REGEX.replace(s) { mr ->
                stash(renderInlineMathSpan(mr.groupValues[1].trim()))
            }
        }
        if (s.contains('$')) {
            s = INLINE_DOLLAR_MATH_REGEX.replace(s) { mr ->
                val inner = mr.groupValues[1].trim()
                if (inner.isBlank() || CURRENCY_KOREAN_REGEX.containsMatchIn(inner)) {
                    mr.value
                } else {
                    stash(renderInlineMathSpan(inner))
                }
            }
        }

        // 3. External markdown images ![alt](url) -> render as safe clickable link (prevents tracking pixels)
        if (s.contains("](")) {
            s = MD_IMAGE_REGEX.replace(s) { mr ->
                val alt = mr.groupValues[1].ifBlank { "외부 이미지" }
                val url = mr.groupValues[2]
                if (isSafeEmailUrl(url) && !url.lowercase().startsWith("mailto:")) {
                    stash(
                        "<a href=\"${escapeHtml(url)}\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"color:#2563eb;text-decoration:underline;font-size:13px;\">[이미지: ${escapeHtml(alt)}]</a>"
                    )
                } else {
                    stash("<span style=\"color:#71717a;font-size:12px;\">[이미지: ${escapeHtml(alt)}]</span>")
                }
            }

            // 4. Markdown links [text](url)
            s = MD_LINK_REGEX.replace(s) { mr ->
                val label = escapeHtml(mr.groupValues[1])
                val url = mr.groupValues[2]
                if (isSafeEmailUrl(url)) {
                    stash(
                        "<a href=\"${escapeHtml(url)}\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"color:#2563eb;text-decoration:underline;\">$label</a>"
                    )
                } else {
                    stash("<span style=\"color:#71717a;\">$label</span>")
                }
            }
        }

        // 5. Inline photo references [사진 N] / [#M 사진 N] (local CID-validated attachments only)
        if (s.contains("사진")) {
            s = PHOTO_REF_REGEX.replace(s) { mr ->
                val targetSeq = mr.groupValues[1].toIntOrNull() ?: currentSeq
                val photoIdx = mr.groupValues[2].toIntOrNull() ?: 0
                val img = imagesBySeq[targetSeq]?.getOrNull(photoIdx - 1)
                val badgeLabel = if (mr.groupValues[1].isNotBlank()) "#$targetSeq 사진 $photoIdx" else "사진 $photoIdx"
                if (img != null && !img.cid.isNullOrBlank()) {
                    collectedFigures.add(
                        "<div style=\"margin:8px 0 10px;padding:8px;border:1px solid #e4e4e7;border-radius:8px;background-color:#fafafa;\">" +
                            "<img src=\"cid:${escapeHtml(img.cid)}\" alt=\"${escapeHtml(img.originalName)}\" style=\"max-width:100%;height:auto;border-radius:6px;display:block;margin-bottom:5px;\" />" +
                            "<div style=\"font-size:12px;color:#52525b;\">[${escapeHtml(badgeLabel)}] ${escapeHtml(img.originalName)}</div>" +
                            "</div>"
                    )
                }
                stash(
                    "<span style=\"display:inline-block;padding:1px 6px;border-radius:4px;background-color:#f4f4f5;border:1px solid #d4d4d8;font-size:12px;font-weight:600;color:#18181b;\">[${escapeHtml(badgeLabel)}]</span>"
                )
            }
        }

        // 6. Escape remaining plain text
        var escaped = escapeHtml(s)

        // 7. Bold, Strikethrough, Italic (works even when adjacent to Korean characters)
        if (escaped.contains("**")) escaped = BOLD_STAR_REGEX.replace(escaped, "<strong>$1</strong>")
        if (escaped.contains("__")) escaped = BOLD_UNDER_REGEX.replace(escaped, "<strong>$1</strong>")
        if (escaped.contains("~~")) escaped = STRIKE_REGEX.replace(escaped, "<del style=\"color:#71717a;\">$1</del>")
        if (escaped.contains('*')) escaped = ITALIC_STAR_REGEX.replace(escaped, "<em>$1</em>")

        // 8. Restore stashed HTML placeholders safely
        return PLACEHOLDER_RESTORE_REGEX.replace(escaped) { mr ->
            mr.groupValues[1].toIntOrNull()?.let { placeholders.getOrNull(it) }.orEmpty()
        }
    }

    private fun renderDisplayMathBlock(latex: String): String {
        val rendered = renderLatexToEmailHtml(latex, displayMode = true)
        return "<div style=\"margin:12px 0;padding:12px 16px;background-color:#fafafa;border:1px solid #e4e4e7;border-radius:8px;text-align:center;overflow-x:auto;font-family:'Cambria Math','STIX Two Math','Times New Roman',Georgia,serif;font-size:16px;line-height:1.5;color:#18181b;\">$rendered</div>"
    }

    private fun renderInlineMathSpan(latex: String): String {
        val rendered = renderLatexToEmailHtml(latex, displayMode = false)
        return "<span style=\"font-family:'Cambria Math','STIX Two Math','Times New Roman',Georgia,serif;font-size:1.05em;color:#18181b;\">$rendered</span>"
    }

    /**
     * Renders LaTeX expressions into semantic inline-CSS HTML + Unicode math symbols
     * so formulas display properly in all email clients without JavaScript.
     */
    internal fun renderLatexToEmailHtml(rawLatex: String, displayMode: Boolean = false): String {
        val clean = rawLatex.trim()
        if (clean.isEmpty()) return ""
        if (clean.length > MAX_LATEX_EXPR_LENGTH) {
            return escapeHtml(clean.take(MAX_LATEX_EXPR_LENGTH)) + "&hellip;"
        }
        return LatexHtmlParser(clean, displayMode, depth = 0).parse(stopAt = null)
    }

    private class LatexHtmlParser(
        private val src: String,
        private val displayMode: Boolean,
        private val depth: Int = 0
    ) {
        private var pos = 0

        private fun parseNested(sub: String, subDisplayMode: Boolean = false): String {
            if (sub.isEmpty()) return ""
            if (depth >= MAX_LATEX_DEPTH) return escapeHtml(sub)
            return LatexHtmlParser(sub, subDisplayMode, depth + 1).parse(null)
        }

        fun parse(stopAt: String?): String {
            if (depth > MAX_LATEX_DEPTH) return escapeHtml(src)
            val sb = StringBuilder()
            while (pos < src.length) {
                if (stopAt != null && src.startsWith(stopAt, pos)) {
                    break
                }
                val ch = src[pos]
                when {
                    ch == '\\' -> sb.append(parseCommand())
                    ch == '{' -> {
                        val group = readBracedGroup()
                        sb.append(parseNested(group, displayMode))
                    }
                    ch == '}' -> {
                        pos++
                    }
                    ch == '^' -> {
                        pos++
                        val arg = readAtomHtml()
                        sb.append("<sup style=\"font-size:0.75em;line-height:0;\">$arg</sup>")
                    }
                    ch == '_' -> {
                        pos++
                        val arg = readAtomHtml()
                        sb.append("<sub style=\"font-size:0.75em;line-height:0;\">$arg</sub>")
                    }
                    ch == '-' -> {
                        pos++
                        sb.append("&minus;")
                    }
                    ch.isWhitespace() -> {
                        pos++
                        if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
                    }
                    ch in 'A'..'Z' || ch in 'a'..'z' -> {
                        pos++
                        sb.append("<i style=\"font-style:italic;\">$ch</i>")
                    }
                    else -> {
                        pos++
                        sb.append(escapeHtml(ch.toString()))
                    }
                }
            }
            return sb.toString()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun readBracedGroup(): String {
            skipWs()
            if (pos >= src.length) return ""
            if (src[pos] != '{') {
                val c = src[pos++]
                return c.toString()
            }
            pos++ // consume '{'
            val start = pos
            var depth = 1
            while (pos < src.length && depth > 0) {
                val c = src[pos]
                if (c == '\\' && pos + 1 < src.length) {
                    pos += 2
                    continue
                }
                if (c == '{') depth++
                else if (c == '}') depth--
                pos++
            }
            val end = if (depth == 0) pos - 1 else pos
            return src.substring(start, end)
        }

        private fun readOptionalBracketGroup(): String? {
            skipWs()
            if (pos >= src.length || src[pos] != '[') return null
            pos++ // consume '['
            val start = pos
            var depth = 1
            while (pos < src.length && depth > 0) {
                val c = src[pos]
                if (c == '[') depth++
                else if (c == ']') depth--
                pos++
            }
            val end = if (depth == 0) pos - 1 else pos
            return src.substring(start, end)
        }

        private fun readAtomHtml(atomDepth: Int = 0): String {
            skipWs()
            if (pos >= src.length) return ""
            if (atomDepth >= MAX_LATEX_DEPTH) {
                val c = src[pos++]
                return escapeHtml(c.toString())
            }
            return when (src[pos]) {
                '{' -> parseNested(readBracedGroup(), false)
                '\\' -> parseCommand(atomDepth + 1)
                else -> {
                    val c = src[pos++]
                    if (c in 'A'..'Z' || c in 'a'..'z') "<i style=\"font-style:italic;\">$c</i>" else escapeHtml(c.toString())
                }
            }
        }

        private fun parseCommand(cmdDepth: Int = 0): String {
            pos++ // consume '\'
            if (pos >= src.length) return ""
            if (cmdDepth >= MAX_LATEX_DEPTH) return ""
            val first = src[pos]
            if (first == '\\') {
                pos++
                return "<br />"
            }
            if (!first.isLetter()) {
                pos++
                return LATEX_SYMBOLS[first.toString()] ?: escapeHtml(first.toString())
            }
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val cmd = src.substring(start, pos)
            val cmdLower = cmd.lowercase()

            if (cmdLower in BLOCKED_LATEX_COMMANDS) {
                readOptionalBracketGroup()
                if (pos < src.length && src[pos] == '{') readBracedGroup()
                return ""
            }

            return when (cmd) {
                "frac", "dfrac", "tfrac" -> {
                    val num = parseNested(readBracedGroup(), false)
                    val den = parseNested(readBracedGroup(), false)
                    "<span style=\"display:inline-table;vertical-align:middle;text-align:center;margin:0 2px;line-height:1.15;\">" +
                        "<span style=\"display:table-row;\"><span style=\"display:table-cell;border-bottom:1px solid #18181b;padding:0 3px 1px;font-size:0.88em;\">$num</span></span>" +
                        "<span style=\"display:table-row;\"><span style=\"display:table-cell;padding:1px 3px 0;font-size:0.88em;\">$den</span></span>" +
                        "</span>"
                }
                "sqrt" -> {
                    val rootOpt = readOptionalBracketGroup()
                    val inner = parseNested(readBracedGroup(), false)
                    val rootPrefix = if (!rootOpt.isNullOrBlank()) {
                        "<sup style=\"font-size:0.7em;margin-right:-2px;\">${parseNested(rootOpt, false)}</sup>"
                    } else {
                        ""
                    }
                    "$rootPrefix<span style=\"white-space:nowrap;\">&radic;<span style=\"border-top:1px solid #18181b;padding:0 3px 0 2px;\">$inner</span></span>"
                }
                "text", "textrm", "mathrm", "operatorname" -> {
                    val inner = readBracedGroup()
                    "<span style=\"font-style:normal;font-family:'Pretendard','Malgun Gothic',sans-serif;\">${escapeHtml(inner)}</span>"
                }
                "mathbf", "textbf", "boldsymbol" -> {
                    val inner = parseNested(readBracedGroup(), false)
                    "<strong style=\"font-style:normal;font-weight:700;\">$inner</strong>"
                }
                "mathbb" -> {
                    val inner = readBracedGroup()
                    inner.map { BLACKBOARD_CHARS[it] ?: escapeHtml(it.toString()) }.joinToString("")
                }
                "left", "right", "big", "Big", "bigg", "Bigg" -> {
                    skipWs()
                    if (pos < src.length) {
                        val delim = src[pos]
                        when (delim) {
                            '.' -> {
                                pos++
                                ""
                            }
                            '\\' -> parseCommand(cmdDepth + 1)
                            else -> {
                                pos++
                                escapeHtml(delim.toString())
                            }
                        }
                    } else {
                        ""
                    }
                }
                "begin" -> parseEnvironment()
                "end" -> {
                    readBracedGroup()
                    ""
                }
                "bar", "overline" -> {
                    val inner = parseNested(readBracedGroup(), false)
                    "<span style=\"text-decoration:overline;\">$inner</span>"
                }
                "hat" -> {
                    val inner = parseNested(readBracedGroup(), false)
                    "${inner}&#770;"
                }
                "vec" -> {
                    val inner = parseNested(readBracedGroup(), false)
                    "${inner}&#8407;"
                }
                in BIG_OPERATORS -> {
                    val sym = BIG_OPERATORS[cmd]!!
                    val sizeStyle = if (cmd == "lim") "font-style:normal;" else if (displayMode) "font-size:1.45em;line-height:1;" else "font-size:1.15em;"
                    var subHtml: String? = null
                    var supHtml: String? = null
                    repeat(2) {
                        skipWs()
                        if (pos < src.length && src[pos] == '_') {
                            pos++
                            subHtml = readAtomHtml()
                        } else if (pos < src.length && src[pos] == '^') {
                            pos++
                            supHtml = readAtomHtml()
                        }
                    }
                    if (displayMode && (subHtml != null || supHtml != null)) {
                        val sbOp = StringBuilder()
                        sbOp.append("<span style=\"display:inline-table;vertical-align:middle;text-align:center;margin:0 3px;line-height:1.05;\">")
                        if (supHtml != null) {
                            sbOp.append("<span style=\"display:table-row;\"><span style=\"display:table-cell;font-size:0.7em;\">$supHtml</span></span>")
                        }
                        sbOp.append("<span style=\"display:table-row;\"><span style=\"display:table-cell;$sizeStyle\">$sym</span></span>")
                        if (subHtml != null) {
                            sbOp.append("<span style=\"display:table-row;\"><span style=\"display:table-cell;font-size:0.7em;\">$subHtml</span></span>")
                        }
                        sbOp.append("</span>")
                        sbOp.toString()
                    } else {
                        val sub = subHtml?.let { "<sub style=\"font-size:0.72em;\">$it</sub>" }.orEmpty()
                        val sup = supHtml?.let { "<sup style=\"font-size:0.72em;\">$it</sup>" }.orEmpty()
                        "<span style=\"$sizeStyle\">$sym</span>$sub$sup"
                    }
                }
                in ROMAN_FUNCTIONS -> "<span style=\"font-style:normal;margin-right:2px;\">$cmd</span>"
                else -> LATEX_SYMBOLS[cmd] ?: escapeHtml(cmd)
            }
        }

        private fun parseEnvironment(): String {
            val envName = readBracedGroup().trim()
            val endMarker = "\\end{$envName}"
            val endIdx = src.indexOf(endMarker, pos)
            val body = if (endIdx >= 0) {
                val slice = src.substring(pos, endIdx)
                pos = endIdx + endMarker.length
                slice
            } else {
                val slice = src.substring(pos)
                pos = src.length
                slice
            }
            val rows = body.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() }
            val (leftDelim, rightDelim) = when (envName) {
                "pmatrix" -> "(" to ")"
                "bmatrix" -> "[" to "]"
                "Bmatrix" -> "{" to "}"
                "vmatrix" -> "|" to "|"
                "cases" -> "{" to ""
                else -> "" to ""
            }
            val tableSb = StringBuilder()
            tableSb.append("<span style=\"display:inline-flex;align-items:center;vertical-align:middle;\">")
            if (leftDelim.isNotEmpty()) {
                tableSb.append("<span style=\"font-size:1.6em;font-weight:300;padding:0 2px;\">$leftDelim</span>")
            }
            tableSb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"display:inline-table;vertical-align:middle;border-collapse:collapse;\">")
            for (row in rows) {
                val cells = row.split("&")
                tableSb.append("<tr>")
                for (cell in cells) {
                    val cellHtml = parseNested(cell.trim(), false)
                    tableSb.append("<td style=\"padding:2px 6px;text-align:center;\">$cellHtml</td>")
                }
                tableSb.append("</tr>")
            }
            tableSb.append("</table>")
            if (rightDelim.isNotEmpty()) {
                tableSb.append("<span style=\"font-size:1.6em;font-weight:300;padding:0 2px;\">$rightDelim</span>")
            }
            tableSb.append("</span>")
            return tableSb.toString()
        }
    }
}
