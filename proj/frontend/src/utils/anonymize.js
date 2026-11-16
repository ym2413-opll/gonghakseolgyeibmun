// src/utils/anonymize.js
// 익명 질문·추가 질문 작성 및 AI 문장 다듬기 후 개인정보(이름·학번·학과·이메일·연락처) 노출 방지 유틸리티

const ANON_EMAIL_REGEX = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const ANON_PHONE_REGEX =
  /(?<!\d)(?:\+?82[-\s.]?0?1[016789]|01[016789])[-\s.]?\d{3,4}[-\s.]?\d{4}(?!\d)/g;
const ANON_STUDENT_ID_REGEX = /(?<!\d)(?:19|20)\d{6,8}(?!\d)/g;
const ANON_ENTRY_YEAR_BATCH_REGEX = /(?<!\d)\d{2}\s*학번/g;

function stripInvisibleChars(str) {
  let out = '';
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    if (
      (c <= 0x1f && c !== 0x09 && c !== 0x0a && c !== 0x0d) ||
      (c >= 0x7f && c <= 0x9f) ||
      c === 0x00ad ||
      c === 0x034f ||
      (c >= 0x200b && c <= 0x200f) ||
      (c >= 0x202a && c <= 0x202e) ||
      (c >= 0x2060 && c <= 0x206f) ||
      c === 0xfeff
    ) {
      continue;
    }
    out += str[i];
  }
  return out;
}

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function buildDeptVariants(cleanDept) {
  if (!cleanDept || cleanDept.length < 2) return [];
  const variants = new Set([cleanDept]);
  if (cleanDept.endsWith('학부')) {
    const stem = cleanDept.slice(0, -2);
    if (stem.length >= 2) {
      variants.add(`${stem}학과`);
      variants.add(`${stem}전공`);
    }
  } else if (cleanDept.endsWith('학과')) {
    const stem = cleanDept.slice(0, -2);
    if (stem.length >= 2) {
      variants.add(`${stem}학부`);
      variants.add(`${stem}전공`);
    }
  }
  return [...variants].sort((a, b) => b.length - a.length);
}

/**
 * 익명 게시물에서 작성자(학생)의 신상 식별 정보가 본문/제목에 남지 않도록 마스킹합니다.
 * 담당 교수 성함·호칭과 줄바꿈은 보존합니다.
 */
export function sanitizeAnonymousStudentText(text, user = null, { professorNames = [] } = {}) {
  if (!text) return '';
  let result = stripInvisibleChars(String(text));

  const cleanName = stripInvisibleChars(String(user?.name || '')).trim();
  const cleanDept = stripInvisibleChars(String(user?.department || '')).trim();
  const cleanId = stripInvisibleChars(String(user?.identifier || user?.studentId || '')).trim();
  const cleanTag = stripInvisibleChars(String(user?.displayTag || '')).trim();
  const cleanEmail = stripInvisibleChars(String(user?.email || '')).trim();
  const cleanUsername = stripInvisibleChars(String(user?.username || '')).trim();

  const deptVariants = buildDeptVariants(cleanDept);

  const profTokens = new Set(
    (professorNames || [])
      .map((raw) => stripInvisibleChars(String(raw || '')).trim())
      .filter((t) => t.length >= 2)
      .flatMap((t) => {
        const root = t.replace(/님$/, '').replace(/교수$/, '').trim();
        return root.length >= 2 ? [t, root] : [t];
      })
  );

  const compoundIntros = [];
  for (const dept of deptVariants) {
    if (cleanId.length >= 2 && cleanName.length >= 2) {
      compoundIntros.push(
        `${dept} ${cleanId} ${cleanName}`,
        `${dept} ${cleanId} (${cleanName})`,
        `${dept} (${cleanId}) ${cleanName}`
      );
    }
    if (cleanName.length >= 2) {
      compoundIntros.push(`${dept} ${cleanName}`);
    }
    if (cleanId.length >= 2) {
      compoundIntros.push(
        `${dept} ${cleanId}`,
        `${dept}(${cleanId})`,
        `${dept} (${cleanId})`
      );
    }
  }
  if (cleanId.length >= 2 && cleanName.length >= 2) {
    compoundIntros.push(
      `${cleanId} ${cleanName}`,
      `${cleanName}(${cleanId})`,
      `${cleanName} (${cleanId})`
    );
  }
  if (cleanTag.length >= 2 && cleanTag !== cleanName) {
    compoundIntros.push(cleanTag);
  }

  const uniqueIntros = [...new Set(compoundIntros)].sort((a, b) => b.length - a.length);
  for (const phrase of uniqueIntros) {
    result = result.split(phrase).join('학생');
  }

  if (cleanEmail) {
    result = result.split(cleanEmail).join('');
  }
  result = result
    .replace(ANON_EMAIL_REGEX, '')
    .replace(ANON_PHONE_REGEX, '');

  if (cleanId.length >= 2) {
    result = result.split(cleanId).join('');
  }
  if (cleanUsername.length >= 3 && !profTokens.has(cleanUsername)) {
    result = result.split(cleanUsername).join('');
  }
  result = result
    .replace(ANON_STUDENT_ID_REGEX, '')
    .replace(ANON_ENTRY_YEAR_BATCH_REGEX, '학생');

  if (cleanName.length >= 2) {
    // 학생과 교수가 동명이인이어도 'OOO 교수(님)' 호칭은 보존하고 본인 이름 표기만 마스킹
    result = result.replace(new RegExp(`${escapeRegex(cleanName)}(?!\\s*(?:교수|님))`, 'g'), '학생');
  }

  result = result
    .replace(/(?:학생|수강생)(?:\s+(?:학생|수강생))+/g, '학생')
    .replace(/\(\s*[,;/·\s]*\)/g, '')
    .replace(/[ \t]+([,.)\]])/g, '$1')
    .replace(/ {2,}/g, ' ');

  for (const dept of deptVariants) {
    if (!profTokens.has(dept)) {
      const escapedDept = escapeRegex(dept);
      result = result
        .replace(
          new RegExp(
            `${escapedDept}\\s*(?:\\d\\s*학년\\s*)?(?:소속\\s*)?(?:재학생|학생|수강생)`,
            'g'
          ),
          '학생'
        )
        .replace(
          new RegExp(`${escapedDept}(?=\\s*(?:입니다|인데|에서|소속|재학생|학생|수강생|\\d\\s*학년))`, 'g'),
          '학생'
        );
    }
  }

  return result
    .replace(/(?:학생|수강생)(?:\s+(?:학생|수강생))+/g, '학생')
    .replace(/\(\s*[,;/·\s]*\)/g, '')
    .replace(/[ \t]+([,.)\]])/g, '$1')
    .replace(/ {2,}/g, ' ')
    .trim();
}
