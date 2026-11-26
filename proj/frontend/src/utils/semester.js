// src/utils/semester.js
// 정규학기(YYYY-1, YYYY-2) 및 계절학기(YYYY-여름, YYYY-겨울) 정규화·비교·표시 유틸리티

export const DEFAULT_SEMESTER = '2026-2';

const YEAR_TERM_REGEX =
  /(20\d{2})\s*(?:학년도|년|-|\.|\/|\s)\s*(0?1|0?2|여름|하계|summer|겨울|동계|winter)(?:\s*(?:계절)?\s*학기)?/i;

export function parseSemesterOrNull(raw) {
  if (!raw || typeof raw !== 'string') return null;
  const match = raw.trim().match(YEAR_TERM_REGEX);
  if (!match) return null;
  const year = Number(match[1]);
  if (!Number.isInteger(year) || year < 2000 || year > 2100) return null;
  const rawTerm = match[2].toLowerCase();
  let term = null;
  if (rawTerm === '1' || rawTerm === '01') term = '1';
  else if (rawTerm === '여름' || rawTerm === '하계' || rawTerm === 'summer') term = '여름';
  else if (rawTerm === '2' || rawTerm === '02') term = '2';
  else if (rawTerm === '겨울' || rawTerm === '동계' || rawTerm === 'winter') term = '겨울';
  if (!term) return null;
  return `${year}-${term}`;
}

export function normalizeSemester(raw, fallback = DEFAULT_SEMESTER) {
  return parseSemesterOrNull(raw) || parseSemesterOrNull(fallback) || DEFAULT_SEMESTER;
}

export function semesterRank(sem) {
  const norm = parseSemesterOrNull(sem);
  if (!norm) return 0;
  const [yStr, term] = norm.split('-');
  const year = Number(yStr);
  const order = term === '1' ? 1 : term === '여름' ? 2 : term === '2' ? 3 : term === '겨울' ? 4 : 0;
  return year * 10 + order;
}

export function compareSemesters(a, b) {
  const rA = semesterRank(a);
  const rB = semesterRank(b);
  if (rA !== rB) return rA - rB;
  return String(a || '').localeCompare(String(b || ''));
}

export function formatSemesterLabel(sem) {
  const norm = parseSemesterOrNull(sem);
  if (!norm) return String(sem || '').trim();
  const [year, term] = norm.split('-');
  if (term === '1') return `${year}-1학기`;
  if (term === '2') return `${year}-2학기`;
  if (term === '여름') return `${year}-여름학기`;
  if (term === '겨울') return `${year}-겨울학기`;
  return norm;
}

export function buildSemesterOptions(currentSemester = DEFAULT_SEMESTER, existingSemesters = []) {
  const normCurrent = normalizeSemester(currentSemester);
  const baseYear = Number(normCurrent.split('-')[0]) || 2026;
  const generated = [];
  for (let y = baseYear - 1; y <= baseYear + 1; y += 1) {
    generated.push(`${y}-1`, `${y}-여름`, `${y}-2`, `${y}-겨울`);
  }
  const merged = new Set(
    [...generated, normCurrent, ...(existingSemesters || []).map((s) => parseSemesterOrNull(s)).filter(Boolean)]
  );
  return Array.from(merged).sort(compareSemesters);
}

export function isNonCurrentSemesterThread(question, fallbackCurrentSemester = '') {
  if (!question) return false;
  if (question.isPastSemester === true) return true;
  const qSem = parseSemesterOrNull(question.semester);
  const curSem = parseSemesterOrNull(question.currentSemester || fallbackCurrentSemester);
  if (!qSem || !curSem) return false;
  return qSem !== curSem;
}
