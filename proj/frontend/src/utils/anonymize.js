// src/utils/anonymize.js
// 익명 질문·추가 질문 작성 및 AI 문장 다듬기 후 개인정보(이름·학번·학과·이메일·연락처) 노출 방지 유틸리티

const ANON_EMAIL_REGEX = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const ANON_PHONE_REGEX = /(?<!\d)01[016789][-\s.]?\d{3,4}[-\s.]?\d{4}(?!\d)/g;
const ANON_STUDENT_ID_REGEX = /(?<!\d)20\d{6,8}(?!\d)/g;

function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * 익명 게시물에서 작성자(학생)의 신상 식별 정보가 본문/제목에 남지 않도록 마스킹합니다.
 * 담당 교수 성함·호칭과 줄바꿈은 보존합니다.
 */
export function sanitizeAnonymousStudentText(text, user = null, { professorNames = [] } = {}) {
  if (!text) return '';
  let result = String(text);

  const cleanName = String(user?.name || '').trim();
  const cleanDept = String(user?.department || '').trim();
  const cleanId = String(user?.identifier || user?.studentId || '').trim();
  const cleanTag = String(user?.displayTag || '').trim();
  const cleanEmail = String(user?.email || '').trim();

  const profTokens = new Set(
    (professorNames || [])
      .map((raw) => String(raw || '').trim())
      .filter((t) => t.length >= 2)
      .flatMap((t) => {
        const root = t.replace(/님$/, '').replace(/교수$/, '').trim();
        return root.length >= 2 ? [t, root] : [t];
      })
  );

  const compoundIntros = [];
  if (cleanDept.length >= 2 && cleanId.length >= 2 && cleanName.length >= 2) {
    compoundIntros.push(
      `${cleanDept} ${cleanId} ${cleanName}`,
      `${cleanDept} ${cleanId} (${cleanName})`,
      `${cleanDept} (${cleanId}) ${cleanName}`
    );
  }
  if (cleanDept.length >= 2 && cleanName.length >= 2) {
    compoundIntros.push(`${cleanDept} ${cleanName}`);
  }
  if (cleanDept.length >= 2 && cleanId.length >= 2) {
    compoundIntros.push(
      `${cleanDept} ${cleanId}`,
      `${cleanDept}(${cleanId})`,
      `${cleanDept} (${cleanId})`
    );
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
  result = result.replace(ANON_STUDENT_ID_REGEX, '');

  if (cleanName.length >= 2 && !profTokens.has(cleanName)) {
    result = result.replace(new RegExp(`${escapeRegex(cleanName)}(?!\\s*교수)`, 'g'), '학생');
  }

  result = result
    .replace(/(?:학생|수강생)(?:\s+(?:학생|수강생))+/g, '학생')
    .replace(/\(\s*[,;/·\s]*\)/g, '')
    .replace(/[ \t]+([,.)\]])/g, '$1')
    .replace(/ {2,}/g, ' ');

  if (cleanDept.length >= 2 && !profTokens.has(cleanDept)) {
    const escapedDept = escapeRegex(cleanDept);
    result = result
      .replace(new RegExp(`${escapedDept}\\s*(?:소속\\s*)?(?:학생|수강생)`, 'g'), '학생')
      .replace(new RegExp(`${escapedDept}(?=\\s*(?:입니다|학생|수강생))`, 'g'), '학생');
  }

  return result
    .replace(/(?:학생|수강생)(?:\s+(?:학생|수강생))+/g, '학생')
    .replace(/\(\s*[,;/·\s]*\)/g, '')
    .replace(/[ \t]+([,.)\]])/g, '$1')
    .replace(/ {2,}/g, ' ')
    .trim();
}
