// src/services/api.js
// 광운대학교 학업 Q&A 포럼 Ktor 백엔드 API 통신 모듈 (R3 Overhaul: 인증/해시/관리모드/학과·과목 동적 관리/이메일 알림)

import {
  INITIAL_COLLEGES,
  INITIAL_DEPARTMENTS,
  INITIAL_COURSES,
  INITIAL_QUESTIONS,
  INITIAL_NOTIFICATIONS,
  AI_INQUIRY_TEMPLATES
} from './mockData';
import { calculateSimilarQuestions } from '../utils/wordSimilarity';
import { generateSummaryTitle } from '../utils/textSummary';
import { clearBrowserPushSubscription } from '../utils/browserNotification';
import { sanitizeAnonymousStudentText } from '../utils/anonymize';

const STORAGE_KEY_QUESTIONS = 'prof_qa_questions_v3';
const STORAGE_KEY_SETTINGS = 'prof_qa_settings_v3';
const STORAGE_KEY_NOTIFS = 'prof_qa_notifications_v3';
const STORAGE_KEY_USERS = 'prof_qa_registered_users_v3';
const STORAGE_KEY_SESSION = 'prof_qa_session_user_v3';
const STORAGE_KEY_TOKEN = 'prof_qa_auth_token_v3';
const STORAGE_KEY_COLLEGES = 'prof_qa_colleges_v4';
const STORAGE_KEY_DEPARTMENTS = 'prof_qa_departments_v4';
const STORAGE_KEY_COURSES = 'prof_qa_courses_v3';
const STORAGE_KEY_EMAIL_LOGS = 'prof_qa_email_logs_v3';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';
const USE_REMOTE_BACKEND = true;

function safeGetStorage(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw || raw === 'undefined' || raw === 'null') return fallback;
    const parsed = JSON.parse(raw);
    return parsed !== null && parsed !== undefined ? parsed : fallback;
  } catch (err) {
    console.warn(`[Storage] Failed to parse ${key}, resetting to default:`, err);
    return fallback;
  }
}

function safeSetStorage(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (err) {
    console.warn(`[Storage] Failed to save ${key}:`, err);
  }
}

function pushLocalNotification({ userId, type, title, message, questionId }) {
  if (!userId) return;
  const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
  notifs.unshift({
    id: `notif-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    userId,
    type,
    title: String(title || '').slice(0, 200),
    message: String(message || ''),
    referenceId: questionId,
    questionId,
    isRead: false,
    isHeld: false,
    createdAt: new Date().toISOString()
  });
  safeSetStorage(STORAGE_KEY_NOTIFS, notifs);
}

function getLocalThreadProfRecipients(targetQ, excludeUserId = null) {
  const assignedProfId = targetQ?.profId || targetQ?.assignedProfessorId || null;
  const ids = new Set();
  if (assignedProfId) ids.add(assignedProfId);
  if (Array.isArray(targetQ?.answers)) {
    for (const ans of targetQ.answers) {
      if (ans?.authorRole === 'PROFESSOR' && ans?.authorId) {
        ids.add(ans.authorId);
      }
    }
  }
  if (excludeUserId) ids.delete(excludeUserId);
  return Array.from(ids);
}

async function parseJsonSafe(res) {
  try {
    const parsed = await res.json();
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

async function fetchWithTimeout(url, options = {}, timeoutMs = 5000) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timeoutId);
  }
}

const AI_ZERO_WIDTH_OR_CONTROL_REGEX =
  /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u00AD\u034F\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]/g;

const COMBINED_AI_PLACEHOLDER_REGEX = new RegExp(
  [
    String.raw`((?:\{\{\s*(?:PROFESSOR_TITLE|PROFESSOR_NAME|PROFESSOR_GREETING|교수님[ ]*성함|교수님[ ]*호칭|교수[ ]*이름)\s*\}\}|\[\s*(?:PROFESSOR_TITLE|PROFESSOR_NAME|PROFESSOR_GREETING|교수님[ ]*성함|교수님[ ]*호칭|교수[ ]*이름)\s*\])(?:[ ]*(?:교수님?|님))?)`,
    String.raw`((?:\{\{\s*(?:STUDENT_NAME|학생[ ]*이름)\s*\}\}|\[\s*(?:STUDENT_NAME|학생[ ]*이름)\s*\])[ ]*교수님?)`,
    String.raw`(\{\{\s*(?:STUDENT_INTRO|STUDENT_INFO|학생[ ]*자기소개|학생[ ]*소개|학생[ ]*정보)\s*\}\}|\[\s*(?:STUDENT_INTRO|STUDENT_INFO|학생[ ]*자기소개|학생[ ]*소개|학생[ ]*정보)\s*\])`,
    String.raw`(\{\{\s*(?:STUDENT_NAME|학생[ ]*이름)\s*\}\}|\[\s*(?:STUDENT_NAME|학생[ ]*이름)\s*\])`,
    String.raw`(\{\{\s*(?:STUDENT_ID|STUDENT_IDENTIFIER|학번)\s*\}\}|\[\s*(?:STUDENT_ID|STUDENT_IDENTIFIER|학번)\s*\])`,
    String.raw`(\{\{\s*(?:STUDENT_DEPT|STUDENT_DEPARTMENT|학과)\s*\}\}|\[\s*(?:STUDENT_DEPT|STUDENT_DEPARTMENT|학과)\s*\])`,
    String.raw`(\{\{\s*(?:COURSE_NAME|강의명|과목명)\s*\}\}|\[\s*(?:COURSE_NAME|강의명|과목명)\s*\])`,
    String.raw`(\{\{\s*PII_[A-Z0-9_]+\s*\}\})`
  ].join('|'),
  'gi'
);

function stripAiZeroWidthAndControl(value) {
  return String(value ?? '').replace(AI_ZERO_WIDTH_OR_CONTROL_REGEX, '');
}

function replaceAiPlaceholders(
  text,
  {
    profGreeting = '교수님',
    studentTag = '수강생',
    studentInfo = {},
    courseName = '',
    isAnonymous = false,
    anonymizeUser = null,
    professorNames = []
  } = {}
) {
  if (!text) return '';
  const cleanProf = stripAiZeroWidthAndControl(profGreeting).trim() || '교수님';
  const cleanTag = isAnonymous ? '학생' : (stripAiZeroWidthAndControl(studentTag).trim() || '수강생');
  const studentName = isAnonymous ? '학생' : (stripAiZeroWidthAndControl(studentInfo?.name || '').trim() || '학생');
  const studentId = isAnonymous ? '' : stripAiZeroWidthAndControl(studentInfo?.studentId || studentInfo?.identifier || '').trim();
  const studentDept = isAnonymous ? '' : stripAiZeroWidthAndControl(studentInfo?.department || '').trim();
  const courseDisplay = stripAiZeroWidthAndControl(courseName || '').trim() || '이번 학기 강의';

  // Single-pass simultaneous replacement prevents second-order placeholder expansion
  let replaced = stripAiZeroWidthAndControl(text)
    .replace(
      COMBINED_AI_PLACEHOLDER_REGEX,
      (match, profHit, studentBeforeProfHit, introHit, nameHit, idHit, deptHit, courseHit, piiHit) => {
        if (profHit || studentBeforeProfHit) return cleanProf;
        if (introHit) return cleanTag;
        if (nameHit) return studentName;
        if (idHit) return studentId;
        if (deptHit) return studentDept;
        if (courseHit) return courseDisplay;
        if (piiHit) return isAnonymous ? '' : match;
        return match;
      }
    )
    .replace(/교수님 교수님/g, '교수님')
    .replace(/교수님님/g, '교수님')
    .replace(/ {2,}/g, ' ')
    .trim();

  if (isAnonymous) {
    replaced = sanitizeAnonymousStudentText(replaced, anonymizeUser || studentInfo, {
      professorNames: [cleanProf, ...professorNames]
    });
  }
  return replaced;
}

const LOCAL_SATIRICAL_ACROSTIC_LINES = {
  김: ['김밥 한 줄 먹을 시간도 없이 밀려드는 과제 양에 밤을 새우고 있습니다.'],
  이: ['이렇게까지 어려운 시험 문제를 내실 줄은 정말 상상도 하지 못했습니다.'],
  박: ['박수 칠 만큼 명강의라 믿었는데 시험지 펼치는 순간 동공이 흔들렸습니다.'],
  최: ['최선을 다해 복습했지만 시험 난이도 앞에서는 인간미가 살짝 아쉬웠습니다.'],
  정: ['정말 열심히 공부했는데 점수를 보니 강의와 저 사이에 벽이 느껴집니다.'],
  강: ['강의를 들을 땐 고개를 끄덕였는데 시험지를 받자마자 기억이 초기화되었습니다.'],
  조: ['조금만 쉽게 내주셨어도 학생들 혈압이 조금은 평온했을 텐데 아쉽습니다.'],
  윤: ['윤곽조차 잡히지 않는 시험 범위 덕분에 새벽까지 책상과 사투를 벌였습니다.'],
  장: ['장학금은커녕 학점 방어만이라도 시켜 주시면 평생 감사히 여기겠습니다.'],
  임: ['임기응변으로 풀 수 없는 극악 난이도 시험에 정신이 아득해졌습니다.'],
  한: ['한 학기 동안 과제에 치여 살다 보니 이제 과제 공지만 떠도 심장이 뜁니다.'],
  오: ['오늘도 과제 마감과 사투를 벌이며 이 난이도가 진심인지 조심스레 의심해 봅니다.'],
  서: ['서러운 시험 점수를 마주하고 나니 교수님의 높은 기대치가 원망스럽기까지 합니다.'],
  신: ['신선한 충격이었던 시험 문제 덕분에 졸업에 대한 소망이 더욱 절실해졌습니다.'],
  권: ['권장 학습 시간을 아득히 뛰어넘는 과제량에 주말이 통째로 증발했습니다.'],
  황: ['황당할 정도로 어려웠던 중간·기말 문제를 떠올리면 아직도 아찔합니다.'],
  안: ['안 그래도 벅찬 학기에 시험 문제까지 너무 냉정하셔서 눈물이 고였습니다.'],
  송: ['송구스럽지만 이번 시험 난이도는 학생 배려 기능이 잠시 꺼져 있던 것 같습니다.'],
  유: ['유독 매운 시험 문제와 채점 기준 덕분에 강제 철야 체력이 길러졌습니다.'],
  홍: ['홍수처럼 쏟아지는 과제 공지 알림에 매주 심박수가 최고치를 경신합니다.'],
  철: ['철야 과제 폭탄에 커피로 연명하다 보니 이제 마감일 알림만 봐도 눈물이 납니다.'],
  수: ['수업 땐 분명 쉽게 설명해 주셨는데 시험 문제는 대체 어느 차원에서 오신 건가요.'],
  교: ['교수님 강의를 들을 때마다 높은 시험 벽 앞에서 제 멘탈이 조금씩 시험받고 있습니다.'],
  님: ['님께서 내주신 과제 폭탄을 풀다 보니 주말과 밤잠이 소리 없이 증발했습니다.']
};

const LOCAL_POLITE_ACROSTIC_LINES = {
  김: ['김밥 한 줄 챙길 여유도 없이 열심히 수업을 복습하며 배움을 이어가고 있습니다.'],
  이: ['이번 학기 가르침을 바탕으로 부족한 개념을 끝까지 채워 나가고 싶습니다.'],
  박: ['박학다식하신 교수님의 명강의를 통해 매주 새로운 통찰을 얻고 있습니다.'],
  최: ['최선을 다해 학습 내용을 정리하며 교수님의 조언을 깊이 새기겠습니다.'],
  정: ['정성스러운 강의와 세심한 지도에 진심으로 감사드립니다.'],
  철: ['철저한 개념 정리를 위해 늘 힘써 주시는 모습에서 큰 동기부여를 얻습니다.'],
  수: ['수업에서 배운 지식을 실제 문제 해결에 잘 적용해 나가도록 노력하겠습니다.'],
  교: ['교수님의 열정적인 지도 덕분에 전공에 대한 이해가 한층 깊어졌습니다.'],
  님: ['님께서 전해 주시는 소중한 학술적 조언을 밑거름 삼아 더욱 정진하겠습니다.']
};

function fulfillLocalInlineDirective(directiveText, { rawProfName = '', category = 'lecture' } = {}) {
  const clean = stripAiZeroWidthAndControl(directiveText).replace(/[[\]［］]/g, '').trim();
  if (!clean) return '';
  const lower = clean.toLowerCase();
  const compact = lower.replace(/\s+/g, '');

  const isSatirical = /(?:까는|까줘|까라|까서|디스|풍자|돌려까|비판|비꼬|비난|팩폭|저격|불만|불평|원망|극딜|놀리|놀려|욕하|하소연|투덜|반어)/.test(compact);
  const isAcrostic =
    /(?:세로드립|세로글|세로로읽|[23456n이삼사오육]행시|행시|머리글자|첫글자|초성|운을띄|운띄)/.test(compact) ||
    (/(?:이름|성함|호칭|professor_title)/.test(compact) &&
      /(?:드립|이용|활용|사용|가지고|써서|넣어|따서|맞춰|바탕|기반|삼아|통해|까는|까줘|까라|풍자|디스|돌려|비판|비꼬|놀리|시|작성|써줘|적어|만들)/.test(compact));

  if (isAcrostic || (isSatirical && /(?:교수|성함|이름|담당)/.test(clean))) {
    const rawTrimmedProf = stripAiZeroWidthAndControl(rawProfName).trim();
    const cleanedProfRoot = rawTrimmedProf
      .replace(/님$/, '')
      .replace(/교수$/, '')
      .trim();
    const rootSyllables = Array.from(cleanedProfRoot).filter((ch) => /[가-힣A-Za-z0-9]/.test(ch)).slice(0, 6);
    const profSyllables =
      rootSyllables.length >= 2
        ? rootSyllables
        : rootSyllables.length === 1 && /(?:교수|교수님)$/.test(rawTrimmedProf)
          ? [rootSyllables[0], '교', '수']
          : rootSyllables;
    const asksProf = clean.includes('교수') || clean.includes('성함') || clean.includes('담당');
    let syllables = [];
    if (asksProf) {
      if (profSyllables.length >= 2) syllables = profSyllables;
      else if (profSyllables.length === 1) syllables = [...profSyllables, '교', '수'];
      else syllables = ['교', '수', '님'];
    } else {
      const quoted = clean.match(/['"‘“]([가-힣]{2,6})['"’”]/);
      const before = clean.match(/([가-힣]{2,6})(?:으로|로|을|를)?\s*(?:세로\s*드립|세로\s*글|[23456n이삼사오육]\s*행\s*시|행시)/);
      const cand = quoted?.[1] || before?.[1] || '';
      const skip = new Set([
        '이름을', '이름으로', '성함을', '성함으로', '글을', '문장을', '내용을',
        '까는', '비판하는', '비꼬는', '풍자하는', '돌려까는', '이용하여', '이용해', '활용하여', '활용해', '써서', '가지고'
      ]);
      if (cand && !skip.has(cand)) syllables = Array.from(cand);
      else if (profSyllables.length >= 2) syllables = profSyllables;
      else syllables = ['교', '수', '님'];
    }
    const bank = isSatirical ? LOCAL_SATIRICAL_ACROSTIC_LINES : LOCAL_POLITE_ACROSTIC_LINES;
    return syllables
      .map((ch, idx) => {
        const list = bank[ch];
        if (list && list.length > 0) return list[idx % list.length];
        if (isSatirical) {
          return `${ch}이라는 글자로 시작할 만큼 이번 시험과 과제 난이도는 정말 매콤해서 눈물이 고였습니다.`;
        }
        return `${ch}이라는 글자로 마음을 모아 한 학기 동안 열정적으로 지도해 주신 점에 깊이 감사드립니다.`;
      })
      .join('\n');
  }

  if (isSatirical) {
    return '수업 시간에는 명확해 보였던 개념들이 과제와 시험 문제에서는 예상보다 훨씬 높은 응용 난이도로 출제되어 밤을 새워 씨름하고 있습니다. 평가 기준과 핵심 출제 의도를 조금 더 구체적으로 안내해 주시면 큰 도움이 되겠습니다.';
  }

  if (/(?:수식|공식|방정식|latex|테일러|급수|근의|피타고라스|오일러|적분|미분)/.test(compact)) {
    const mode = /(?:블록|따로|줄 바꿈|별도 줄)/.test(lower) ? 'block' : 'inline';
    let expr = clean.replace(/[\r\n$]+/g, ' ').slice(0, 120);
    if ((lower.includes('코사인') || lower.includes('cos')) && (lower.includes('테일러') || lower.includes('급수'))) {
      expr = String.raw`\cos x = \sum_{n=0}^{\infty} \frac{(-1)^n}{(2n)!} x^{2n}`;
    } else if (lower.includes('근의')) {
      expr = String.raw`x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}`;
    }
    return mode === 'block' ? `$$\n${expr}\n$$` : `$${expr}$`;
  }

  if (lower.includes('비교표') || lower.includes('테이블') || /(?<![가-힣])표(?![가-힣])/.test(lower) || lower.includes('table')) {
    const topic = clean.replace(/(?:비교표|테이블|표로|표|작성해\s*(?:줘|라|봐)?|만들어\s*(?:줘|라|봐)?|정리해\s*(?:줘|라|봐)?|넣어\s*(?:줘|라|봐)?|추가해\s*(?:줘|라|봐)?|적어\s*(?:줘|라|봐)?)/g, '').trim() || clean;
    return `| 구분 | 핵심 내용 |\n| --- | --- |\n| ${topic} | 세부 확인 필요 |`;
  }
  if (/(?:목록|불릿|항목|단계|리스트|나열|가지로)/.test(lower)) {
    const parts = clean.split(/[,/]/).map((s) => s.trim()).filter(Boolean);
    return parts.length >= 2 ? parts.map((p) => `- ${p}`).join('\n') : `- ${clean}`;
  }
  if (lower.includes('코드') || lower.includes('code')) {
    const topic = clean.replace(/(?:코드|예시|작성해\s*(?:줘|라|봐)?|넣어\s*(?:줘|라|봐)?|적어\s*(?:줘|라|봐)?)/g, '').trim() || 'example';
    return `\`\`\`text\n// ${topic}\n\`\`\``;
  }
  if (/(?:사과|양해|선처|기한 연장|지각|유예|재제출|미뤄)/.test(lower)) {
    return '일정 관리에 미흡한 점이 있어 송구하오며, 부득이한 사정을 너그러이 양해해 주시어 보완 및 제출 기회를 주신다면 성실히 마무리하겠습니다.';
  }
  if (/(?:감사|고마|인사|칭찬|존경)/.test(lower)) {
    return '한 학기 동안 열정적으로 강의해 주시고 세심하게 지도해 주신 점에 진심으로 감사드립니다.';
  }
  if (/(?:요약|정리|핵심)/.test(lower)) {
    return '핵심 내용을 정리하자면, 해당 개념의 기본 원리와 적용 조건을 단계별로 명확히 확인하고자 합니다.';
  }
  if (/(?:예시|사례|비유)/.test(lower)) {
    return '이해를 돕기 위한 대표적인 예시와 함께 실제 적용 시 유의할 부분을 함께 안내해 주시면 감사하겠습니다.';
  }

  const strippedMeta = clean
    .replace(/(?:담당\s*)?(?:교수님|교수)\s*(?:이름|성함|호칭)(?:을|를|으로|로)?\s*(?:이용하여|이용해|활용하여|활용해|사용하여|사용해|가지고|써서|통해|넣어서|넣어|따서)?/g, '')
    .replace(/(?:이용하여|이용해|활용하여|활용해|사용하여|사용해|바탕으로|기준으로|참고하여|넣어서|담아서|가지고|써서|통해|따서)/g, '')
    .replace(/\s*(?:세로\s*드립|삼행시|이행시|사행시|오행시|[2-6n]\s*행시|행시|글을|글|문장을|문장|내용을|내용)\s*(?:넣어\s*(?:줘|라|봐)?|작성해\s*(?:줘|라|봐)?|써\s*(?:줘|라|봐)?|적어\s*(?:줘|라|봐)?|만들어\s*(?:줘|라|봐)?|생성해\s*(?:줘|라|봐)?|부탁해\s*(?:줘)?|해\s*(?:줘|라|봐)?)?[.!?]?\s*$/g, '')
    .replace(/\s*(?:넣어\s*(?:줘|라|봐)?|작성해\s*(?:줘|라|봐)?|써\s*(?:줘|라|봐)?|적어\s*(?:줘|라|봐)?|만들어\s*(?:줘|라|봐)?|설명해\s*(?:줘|라|봐)?|정리해\s*(?:줘|라|봐)?|알려\s*(?:줘|라|봐)?|추가해\s*(?:줘|라|봐)?|바꿔\s*(?:줘|라|봐)?|변환해\s*(?:줘|라|봐)?|부탁해|해줘|해라|해봐)[.!?]?\s*$/g, '')
    .replace(/[을를이가은는에]\s*$/, '')
    .trim();
  const hasResidualCommand = /(?:작성|써줘|써라|적어|만들|까는|비판|비꼬|풍자|디스|세로|행시|지시사항|프롬프트)/.test(strippedMeta);
  if (strippedMeta.length >= 2 && strippedMeta.length <= 40 && !hasResidualCommand) {
    return `또한 ${strippedMeta}와 관련하여 보다 구체적인 원리와 적용 기준에 대한 설명을 부탁드립니다.`;
  }
  if (String(category || '').toLowerCase() === 'grade') {
    return '아울러 평가 항목별 세부 기준과 채점 반영 비율에 관해서도 조금 더 자세한 안내를 부탁드립니다.';
  }
  if (String(category || '').toLowerCase() === 'assignment') {
    return '아울러 과제 수행 시 우선적으로 고려해야 할 핵심 기준과 참고 방향에 대해서도 조언을 부탁드립니다.';
  }
  return '아울러 핵심 개념의 원리와 실제 문제 적용 기준에 관해서도 조금 더 자세한 설명을 부탁드립니다.';
}

function getAuthHeaders() {
  // Authentication is carried exclusively by the HttpOnly; Secure; SameSite=Lax cookie.
  // Any legacy localStorage token is purged to prevent XSS token harvesting.
  if (typeof localStorage !== 'undefined' && localStorage.getItem(STORAGE_KEY_TOKEN)) {
    localStorage.removeItem(STORAGE_KEY_TOKEN);
  }
  return {};
}

function getCurrentUserId(overrideId) {
  if (overrideId && typeof overrideId === 'string') return overrideId;
  const sessionUser = safeGetStorage(STORAGE_KEY_SESSION, null);
  return sessionUser?.id || localStorage.getItem('prof_qa_current_user_id') || '';
}

function normalizeCatalogEntry(entry) {
  if (!entry || typeof entry !== 'object') return entry;
  return {
    ...entry,
    aliases: Array.isArray(entry.aliases)
      ? entry.aliases.map((a) => String(a || '').trim()).filter(Boolean)
      : []
  };
}

function resolveCatalogEntry(list, fallbackList, rawName) {
  const clean = String(rawName || '').trim();
  if (!clean) return null;
  const lower = clean.toLowerCase();
  const candidates = Array.isArray(list) && list.length > 0 ? list : fallbackList;

  const direct = candidates.find((d) => String(d?.name || '').trim().toLowerCase() === lower);
  if (direct) return direct;

  return (
    candidates.find(
      (d) =>
        Array.isArray(d?.aliases) &&
        d.aliases.some((alias) => String(alias || '').trim().toLowerCase() === lower)
    ) || null
  );
}

function getCatalogSearchNames(list, fallbackList, rawName) {
  const clean = String(rawName || '').trim();
  if (!clean) return [];
  const matched = resolveCatalogEntry(list, fallbackList, clean);
  if (!matched) return [clean];
  const aliases = Array.isArray(matched.aliases) ? matched.aliases : [];
  return Array.from(new Set([matched.name, ...aliases, clean].map((s) => String(s || '').trim()).filter(Boolean)));
}

function mutateLocalCatalogAliases({ storageKey, fallbackList, id, entityLabel, op, alias, newAlias }) {
  const items = safeGetStorage(storageKey, fallbackList).map(normalizeCatalogEntry);
  const target = items.find((item) => String(item.id) === String(id));
  if (!target) throw new Error(`존재하지 않는 ${entityLabel}입니다.`);
  const resolveFn = (name) => resolveCatalogEntry(items, fallbackList, name);

  if (op === 'add') {
    const cleanAlias = String(alias || '').trim();
    if (!cleanAlias) throw new Error(`등록할 기존 ${entityLabel}명(별칭)을 입력해주세요.`);
    if (target.name.toLowerCase() === cleanAlias.toLowerCase()) {
      throw new Error(`현재 ${entityLabel}명과 동일한 이름은 별칭으로 등록할 수 없습니다.`);
    }
    const collision = resolveFn(cleanAlias);
    if (collision) {
      throw new Error(
        String(collision.id) === String(id)
          ? `이미 등록된 별칭입니다: ${cleanAlias}`
          : `이미 '${collision.name}'에서 사용 중인 ${entityLabel}명/별칭입니다: ${cleanAlias}`
      );
    }
    target.aliases = Array.from(new Set([...(target.aliases || []), cleanAlias]));
  } else if (op === 'rename') {
    const cleanOld = String(alias || '').trim();
    const cleanNew = String(newAlias || '').trim();
    if (!cleanOld || !cleanNew) throw new Error('기존 별칭과 새 별칭을 모두 입력해주세요.');
    if (target.name.toLowerCase() === cleanNew.toLowerCase()) {
      throw new Error(`현재 ${entityLabel}명과 동일한 이름은 별칭으로 변경할 수 없습니다.`);
    }
    const idx = (target.aliases || []).findIndex((a) => a.toLowerCase() === cleanOld.toLowerCase());
    if (idx < 0) throw new Error(`변경할 기존 별칭을 찾을 수 없습니다: ${cleanOld}`);
    const collision = resolveFn(cleanNew);
    if (collision && !(String(collision.id) === String(id) && cleanOld.toLowerCase() === cleanNew.toLowerCase())) {
      throw new Error(`이미 사용 중인 ${entityLabel}명/별칭입니다: ${cleanNew}`);
    }
    target.aliases = target.aliases.map((a, i) => (i === idx ? cleanNew : a));
  } else if (op === 'remove') {
    const cleanAlias = String(alias || '').trim();
    if (!cleanAlias) throw new Error('삭제할 별칭을 지정해주세요.');
    target.aliases = (target.aliases || []).filter((a) => a.toLowerCase() !== cleanAlias.toLowerCase());
  }
  safeSetStorage(storageKey, items);
  return target;
}

async function requestCatalogAdmin(path, method, payload, errorFallback) {
  const res = await fetchWithTimeout(`${API_BASE_URL}${path}`, {
    method,
    credentials: 'include',
    headers: {
      ...(payload !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...getAuthHeaders()
    },
    ...(payload !== undefined ? { body: JSON.stringify(payload) } : {})
  }, 5000);
  const body = await parseJsonSafe(res);
  if (res.ok && body.data) {
    return normalizeCatalogEntry(body.data);
  }
  const httpErr = new Error(body.message || errorFallback);
  httpErr.isHttpError = true;
  httpErr.status = res.status;
  throw httpErr;
}

export function resolveDepartmentEntry(depts, rawName) {
  return resolveCatalogEntry(depts, INITIAL_DEPARTMENTS, rawName);
}

export function getDepartmentSearchNames(depts, rawName) {
  return getCatalogSearchNames(depts, INITIAL_DEPARTMENTS, rawName);
}

export function resolveCollegeEntry(colleges, rawName) {
  return resolveCatalogEntry(colleges, INITIAL_COLLEGES, rawName);
}

export function getCollegeSearchNames(colleges, rawName) {
  return getCatalogSearchNames(colleges, INITIAL_COLLEGES, rawName);
}

function initializeStorage() {
  const existingColleges = safeGetStorage(STORAGE_KEY_COLLEGES, null);
  if (!Array.isArray(existingColleges) || existingColleges.length === 0) {
    safeSetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES);
  }
  const existingQuestions = safeGetStorage(STORAGE_KEY_QUESTIONS, null);
  if (!Array.isArray(existingQuestions) || existingQuestions.length === 0) {
    safeSetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
  }

  const existingNotifs = safeGetStorage(STORAGE_KEY_NOTIFS, null);
  if (!Array.isArray(existingNotifs)) {
    safeSetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
  }

  const existingDepts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, null);
  if (!Array.isArray(existingDepts) || existingDepts.length === 0) {
    safeSetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
  }

  const existingCourses = safeGetStorage(STORAGE_KEY_COURSES, null);
  if (!Array.isArray(existingCourses) || existingCourses.length === 0) {
    safeSetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
  }

  const existingUsers = safeGetStorage(STORAGE_KEY_USERS, null);
  if (!Array.isArray(existingUsers)) {
    safeSetStorage(STORAGE_KEY_USERS, []);
  }

  const existingEmails = safeGetStorage(STORAGE_KEY_EMAIL_LOGS, null);
  if (!Array.isArray(existingEmails)) {
    safeSetStorage(STORAGE_KEY_EMAIL_LOGS, []);
  }
}

initializeStorage();

function mapCourseDto(c) {
  if (!c || typeof c !== 'object') return c;
  const professors = Array.isArray(c.professors)
    ? c.professors.map((p) => (p && typeof p === 'object' ? { ...p, isRegistered: p.isRegistered !== false } : p))
    : [];
  const rawAssignedId = c.assignedProfessorId || c.profId || '';
  const matchedProf = rawAssignedId
    ? professors.find((p) => p.id === rawAssignedId)
    : null;
  const primaryProf = matchedProf || professors.find((p) => p.isRegistered !== false) || professors[0] || null;
  const explicitProfName = [c.assignedProfessorName, c.profName]
    .map((n) => (n || '').trim())
    .find((n) => n && n !== '미배정');
  const resolvedProfId = rawAssignedId || primaryProf?.id || '';
  const resolvedProfName = explicitProfName || primaryProf?.name || '미배정';
  const resolvedProfRegistered =
    primaryProf != null
      ? primaryProf.isRegistered !== false
      : c.assignedProfessorRegistered !== false;
  const resolvedSection = primaryProf?.section || c.section || '01분반';

  return {
    id: c.id,
    name: c.name,
    code: c.code,
    semester: c.semester || '2026-1',
    college: c.college || '',
    department: c.department || primaryProf?.department || '',
    group: c.group || 'COMMON',
    section: resolvedSection,
    profId: resolvedProfId,
    profName: resolvedProfName,
    assignedProfessorId: resolvedProfId,
    assignedProfessorName: resolvedProfName,
    assignedProfessorRegistered: resolvedProfRegistered,
    professors,
    enabledCategories: c.enabledCategories || c.availableCategories || ['GRADE', 'ASSIGNMENT', 'LECTURE']
  };
}

function normalizeUserObject(u, coursesFallback = []) {
  if (!u || typeof u !== 'object' || Array.isArray(u)) return null;
  const roleLower = (u.role || 'student').toLowerCase();
  const isProf = roleLower === 'professor';
  const isAdmin = Boolean(u.isAdmin || roleLower === 'admin');
  const rawCourses =
    Array.isArray(coursesFallback) && coursesFallback.length > 0
      ? coursesFallback
      : Array.isArray(u.courses)
        ? u.courses
        : [];
  const normalizedCourses = rawCourses.map(mapCourseDto);
  return {
    ...u,
    role: isProf ? 'professor' : 'student',
    isAdmin,
    studentId: u.identifier || u.studentId || '',
    identifier: u.identifier || u.studentId || '',
    avatar: u.avatar || (u.name ? u.name.trim().charAt(0) : 'U'),
    courses: normalizedCourses
  };
}

export const api = {
  // ============================================================================
  // 1. 인증 / 회원가입 / 로그인 / 세션 관리 (R3 개편)
  // ============================================================================
  getCurrentSessionUser() {
    const user = safeGetStorage(STORAGE_KEY_SESSION, null);
    return user && typeof user === 'object' && !Array.isArray(user) && user.id ? user : null;
  },

  saveSession(user) {
    if (user) {
      safeSetStorage(STORAGE_KEY_SESSION, user);
      localStorage.setItem('prof_qa_current_user_id', user.id);
    }
    localStorage.removeItem(STORAGE_KEY_TOKEN);
  },

  async getRegistrationPolicy() {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/auth/registration-policy`, {
          credentials: 'include'
        }, 2500);
        if (res.ok) {
          const body = await parseJsonSafe(res);
          return body?.data || body || { professorInviteRequired: false };
        }
      } catch (e) {
        console.warn('[API] Failed to fetch registration policy:', e);
      }
    }
    return { professorInviteRequired: false };
  },

  async getNextStudentId({ year = 2026, isForeign = false, random = false } = {}) {
    const safeYear = Math.min(2099, Math.max(1900, Number(year) || 2026));
    if (USE_REMOTE_BACKEND) {
      try {
        const params = new URLSearchParams({
          year: String(safeYear),
          isForeign: String(Boolean(isForeign)),
          random: String(Boolean(random))
        });
        const res = await fetch(`${API_BASE_URL}/auth/next-student-id?${params.toString()}`, {
          credentials: 'include'
        });
        const body = await res.json().catch(() => ({}));
        if (res.ok && body?.data) {
          return body.data;
        }
      } catch (e) {
        console.warn('[API] Remote getNextStudentId fallback:', e);
      }
    }

    const middlePool = [];
    for (let d1 = 1; d1 <= 8; d1 += 1) {
      for (let d3 = 1; d3 <= 6; d3 += 1) {
        middlePool.push(`${d1}0${d3}`);
      }
    }
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const usedIds = new Set(localUsers.map(u => String(u.identifier || '')));
    const start = isForeign ? 501 : 1;
    const end = isForeign ? 999 : 500;
    const orderedMids = random ? [...middlePool].sort(() => Math.random() - 0.5) : middlePool;

    for (const mid of orderedMids) {
      const prefix = `${safeYear}${mid}`;
      const usedSerials = Array.from(usedIds)
        .filter(id => id.length === 10 && id.startsWith(prefix))
        .map(id => Number(id.slice(7, 10)))
        .filter(n => !Number.isNaN(n));
      let nextNum = null;
      if (random) {
        const usedSet = new Set(usedSerials);
        const candidates = [];
        for (let i = start; i <= end; i += 1) {
          if (!usedSet.has(i)) candidates.push(i);
        }
        if (candidates.length > 0) {
          nextNum = candidates[Math.floor(Math.random() * candidates.length)];
        }
      } else {
        const curMax = isForeign
          ? Math.max(500, ...usedSerials.filter(n => n >= 501 && n <= 999))
          : Math.max(0, ...usedSerials.filter(n => n >= 1 && n <= 500));
        if (curMax < end) {
          nextNum = curMax + 1;
        }
      }
      if (nextNum !== null) {
        const serial = String(nextNum).padStart(3, '0');
        return {
          studentId: `${prefix}${serial}`,
          year: safeYear,
          serial,
          isForeign: Boolean(isForeign)
        };
      }
    }
    throw new Error('사용 가능한 학번 순번이 남아있지 않습니다.');
  },

  async logout(options = {}) {
    const headers = getAuthHeaders();
    if (USE_REMOTE_BACKEND && !options.skipRemote) {
      try {
        const endpoint = await clearBrowserPushSubscription();
        if (endpoint) {
          await fetch(`${API_BASE_URL}/push/unsubscribe`, {
            method: 'POST',
            credentials: 'include',
            headers: {
              'Content-Type': 'application/json',
              ...headers
            },
            body: JSON.stringify({ endpoint })
          }).catch(() => {});
        }
      } catch {
        // Ignore push unsubscribe errors during logout
      }
    }
    localStorage.removeItem(STORAGE_KEY_SESSION);
    localStorage.removeItem(STORAGE_KEY_TOKEN);
    localStorage.removeItem('prof_qa_current_user_id');
    if (USE_REMOTE_BACKEND && !options.skipRemote) {
      try {
        await fetch(`${API_BASE_URL}/auth/logout`, {
          method: 'POST',
          credentials: 'include',
          headers
        });
      } catch {
        // Ignore network errors during local logout cleanup
      }
    }
  },

  async register(payload) {
    const roleUpper = (payload.role || 'STUDENT').toUpperCase();
    const rawUsername =
      roleUpper === 'STUDENT' && (!payload.username || !payload.username.trim())
        ? (payload.identifier && payload.identifier !== 'AUTO' ? payload.identifier : '')
        : payload.username || '';
    const cleanUsername = rawUsername.trim().toLowerCase();
    const cleanName = (payload.name || '').trim();
    const cleanDept = (payload.department || '').trim();
    const rawIdentifier = (payload.identifier || '').trim();
    const isAutoId = roleUpper === 'STUDENT' && (!rawIdentifier || rawIdentifier.toUpperCase() === 'AUTO');

    if (roleUpper === 'STUDENT') {
      if (!isAutoId && !/^(19\d\d|20\d\d)[1-8]0[1-6](?:00[1-9]|0[1-9]\d|[1-9]\d\d)$/.test(rawIdentifier)) {
        throw new Error('학번은 (연도 4자리)(1~8 + 0 + 1~6)(순번 3자리: 001~999) 형식의 10자리 숫자로 입력해주세요.');
      }
    } else if (!/^[a-zA-Z0-9_.-]{3,30}$/.test(cleanUsername)) {
      throw new Error('아이디(ID)는 영문, 숫자, 밑줄(_) 조합 3~30자로 입력해주세요.');
    }
    if (!payload.password || payload.password.length < 6) {
      throw new Error('비밀번호는 최소 6자 이상이어야 합니다.');
    }
    if (!cleanName || cleanName.length < 2) {
      throw new Error('이름을 2자 이상 정확히 입력해주세요.');
    }
    if (!cleanDept) {
      throw new Error('소속 학과를 선택해주세요.');
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/auth/register`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: cleanUsername,
            password: payload.password,
            role: roleUpper,
            name: cleanName,
            department: cleanDept,
            identifier: isAutoId ? 'AUTO' : rawIdentifier || null,
            entryYear: payload.entryYear ? Number(payload.entryYear) : null,
            isForeign: Boolean(payload.isForeign),
            email: payload.email?.trim() || null,
            professorInviteCode: payload.professorInviteCode?.trim() || null,
            courseIds: Array.isArray(payload.courseIds) ? payload.courseIds : [],
            courseNames: Array.isArray(payload.courseNames) ? payload.courseNames : []
          })
        }, 5000);

        const body = await parseJsonSafe(res);
        if (!res.ok || body.success === false) {
          throw new Error(body.message || '회원가입 처리 중 오류가 발생했습니다.');
        }

        const authData = body.data || body;
        this.saveSession(normalizeUserObject(authData.user, authData.user?.courses || []));
        const courses = await this.getCourses(authData.user.id);
        const normalized = normalizeUserObject(authData.user, courses);
        this.saveSession(normalized);
        return { user: normalized };
      } catch (e) {
        if (e.name === 'AbortError') {
          throw new Error('서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.');
        }
        throw e;
      }
    }

    throw new Error('원격 인증 서버가 활성화되어 있지 않습니다.');
  },

  async login(username, password) {
    const cleanUsername = (username || '').trim().toLowerCase();
    if (!cleanUsername || !password) {
      throw new Error('아이디(학번)와 비밀번호를 모두 입력해주세요.');
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/auth/login`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: cleanUsername, password })
        }, 5000);

        const body = await parseJsonSafe(res);
        if (!res.ok || body.success === false) {
          throw new Error(body.message || '아이디(학번) 또는 비밀번호가 일치하지 않습니다.');
        }

        const authData = body.data || body;
        this.saveSession(normalizeUserObject(authData.user, authData.user?.courses || []));
        const courses = await this.getCourses(authData.user.id);
        const normalized = normalizeUserObject(authData.user, courses);
        this.saveSession(normalized);
        return { user: normalized };
      } catch (e) {
        if (e.name === 'AbortError') {
          throw new Error('서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.');
        }
        throw e;
      }
    }

    throw new Error('원격 인증 서버가 활성화되어 있지 않습니다.');
  },

  async getUser(userId) {
    const uid = getCurrentUserId(userId);
    if (!uid) return null;

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${API_BASE_URL}/auth/me`, {
          credentials: 'include',
          headers: getAuthHeaders(),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.status === 401 || res.status === 403) {
          const authErr = new Error('UNAUTHORIZED');
          authErr.status = res.status;
          throw authErr;
        }
        if (res.ok) {
          const body = await res.json();
          const data = body?.data || body;
          if (data && typeof data === 'object') {
            const courses = await this.getCourses(data.id);
            const normalized = normalizeUserObject(data, courses);
            this.saveSession(normalized);
            return normalized;
          }
        }
      } catch (e) {
        if (e?.message === 'UNAUTHORIZED' || e?.status === 401 || e?.status === 403) {
          throw e;
        }
        console.warn('[API] Remote getUser failed, using session fallback', e);
      }
    }

    const sessionUser = safeGetStorage(STORAGE_KEY_SESSION, null);
    if (sessionUser && sessionUser.id === uid) return sessionUser;
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const found = localUsers.find(u => u.id === uid);
    if (found) {
      const { passwordHash: _ph, ...safeUser } = found;
      return safeUser;
    }
    return sessionUser;
  },

  // ============================================================================
  // 2. 학과 및 과목 조회 / 동적 추가
  // ============================================================================
  async getColleges() {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/colleges`, { credentials: 'include' }, 2000);
        if (res.ok) {
          const body = await parseJsonSafe(res);
          const list = Array.isArray(body) ? body : body?.data;
          if (Array.isArray(list) && list.length > 0) {
            const normalizedList = list.map(normalizeCatalogEntry);
            safeSetStorage(STORAGE_KEY_COLLEGES, normalizedList);
            return normalizedList;
          }
        }
      } catch (e) {
        console.warn('[API] Remote getColleges failed, using local fallback', e);
      }
    }
    return safeGetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES).map(normalizeCatalogEntry);
  },

  async getDepartments() {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1500);
        const res = await fetch(`${API_BASE_URL}/departments`, {
          credentials: 'include',
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : body?.data;
          if (Array.isArray(list) && list.length > 0) {
            const normalizedList = list.map(normalizeCatalogEntry);
            safeSetStorage(STORAGE_KEY_DEPARTMENTS, normalizedList);
            return normalizedList;
          }
        }
      } catch (e) {
        console.warn('[API] Remote getDepartments failed, using local fallback', e);
      }
    }
    return safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS).map(normalizeCatalogEntry);
  },

  async getAllCourses() {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        const res = await fetch(`${API_BASE_URL}/courses?all=true&limit=2000`, {
          credentials: 'include',
          headers: getAuthHeaders(),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const payload = body?.data || body;
          const list = Array.isArray(payload) ? payload : payload?.courses;
          if (Array.isArray(list)) {
            const mapped = list.map(mapCourseDto);
            safeSetStorage(STORAGE_KEY_COURSES, mapped);
            return mapped;
          }
        }
      } catch (e) {
        console.warn('[API] Remote getAllCourses failed, using local fallback', e);
      }
    }
    return safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
  },

  async getCourses(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        const res = await fetch(`${API_BASE_URL}/courses`, {
          credentials: 'include',
          headers: getAuthHeaders(uid),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : body?.data;
          if (Array.isArray(list) && list.length > 0) {
            return list.map(mapCourseDto);
          }
        }
      } catch (e) {
        console.warn('[API] Remote getCourses failed, using local fallback', e);
      }
    }
    return this.getAllCourses();
  },

  // ============================================================================
  // 3. 관리자(Admin) 전용 기능 (학과/과목 추가, 회원 제재, 게시물 가리기)
  //    보안 정책: 브라우저/HTTP를 통한 관리자 권한 부여 기능은 제공하지 않음
  // ============================================================================
  async getAdminUsers() {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/admin/users`, {
          credentials: 'include',
          headers: getAuthHeaders()
        }, 5000);
        const body = await parseJsonSafe(res);
        if (!res.ok) {
          const httpErr = new Error(body.message || '관리자 사용자 목록 조회 권한이 없습니다.');
          httpErr.isHttpError = true;
          httpErr.status = res.status;
          throw httpErr;
        }
        const list = body.data || body;
        if (Array.isArray(list)) return list;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote getAdminUsers fallback:', e);
      }
    }
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    return localUsers.map(({ passwordHash: _ph, ...u }) => u);
  },

  async sanctionUser(targetUserId, sanctionPayload) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/admin/users/${targetUserId}/sanction`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(sanctionPayload)
        }, 5000);
        const body = await parseJsonSafe(res);
        if (res.ok && body.data) {
          return body.data;
        }
        const httpErr = new Error(body.message || '관리자 제재 처리 중 오류가 발생했습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote sanctionUser fallback:', e);
      }
    }

    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const target = localUsers.find(u => u.id === targetUserId);
    if (!target) throw new Error('대상 사용자를 찾을 수 없습니다.');

    const hours =
      (sanctionPayload.durationHours || 0) + (sanctionPayload.durationDays || 0) * 24 || 24;
    const untilIso = new Date(Date.now() + hours * 3600 * 1000).toISOString();

    switch ((sanctionPayload.action || '').toUpperCase()) {
      case 'WITHDRAW':
        target.isWithdrawn = true;
        break;
      case 'RESTORE':
        target.isWithdrawn = false;
        break;
      case 'BAN_QUESTION':
        target.questionBanUntil = untilIso;
        break;
      case 'UNBAN_QUESTION':
        target.questionBanUntil = null;
        break;
      case 'BAN_ANSWER':
        target.answerBanUntil = untilIso;
        break;
      case 'UNBAN_ANSWER':
        target.answerBanUntil = null;
        break;
      default:
        throw new Error('지원하지 않는 제재 작업입니다.');
    }
    safeSetStorage(STORAGE_KEY_USERS, localUsers);
    const { passwordHash: _ph, ...safeTarget } = target;
    return safeTarget;
  },

  async updateAdminNickname(nickname) {
    const cleanInput = typeof nickname === 'string' ? nickname.trim() : '';
    if (cleanInput.length > 30) {
      throw new Error('관리자 닉네임은 최대 30자까지 입력할 수 있습니다.');
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/admin/nickname`, {
          method: 'PUT',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({ nickname: cleanInput || null })
        }, 5000);
        const body = await parseJsonSafe(res);
        if (res.ok && body.data) {
          const normalized = normalizeUserObject(body.data);
          this.saveSession(normalized);
          return normalized;
        }
        const httpErr = new Error(body.message || '관리자 닉네임 설정에 실패했습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote updateAdminNickname fallback:', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    if (!sessionUser || !sessionUser.isAdmin) {
      throw new Error('관리자 계정만 닉네임을 설정할 수 있습니다.');
    }
    const nextNickname = cleanInput || null;
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const target = localUsers.find((u) => u.id === sessionUser.id);
    if (target) {
      target.nickname = nextNickname;
      safeSetStorage(STORAGE_KEY_USERS, localUsers);
    }
    const updatedUser = normalizeUserObject({ ...sessionUser, nickname: nextNickname });
    this.saveSession(updatedUser);
    return updatedUser;
  },

  async createDepartment(deptPayload) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/admin/departments`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(deptPayload)
        }, 5000);
        const body = await parseJsonSafe(res);
        if (res.ok && body.data) {
          return body.data;
        }
        const httpErr = new Error(body.message || '학과 생성 권한이 없거나 요청이 올바르지 않습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote createDepartment fallback:', e);
      }
    }

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS).map(normalizeCatalogEntry);
    const colleges = safeGetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES).map(normalizeCatalogEntry);
    const cleanDeptName = (deptPayload.name || '').trim();
    const existingMatch = resolveDepartmentEntry(depts, cleanDeptName);
    if (existingMatch) {
      throw new Error(
        existingMatch.name.toLowerCase() === cleanDeptName.toLowerCase()
          ? '이미 등록된 학과명입니다.'
          : `'${cleanDeptName}'은(는) 이미 '${existingMatch.name}'의 별칭으로 등록되어 있습니다.`
      );
    }
    const cleanAliases = Array.isArray(deptPayload.aliases)
      ? Array.from(new Set(deptPayload.aliases.map((a) => String(a || '').trim()).filter(Boolean)))
      : [];
    for (const alias of cleanAliases) {
      if (alias.toLowerCase() === cleanDeptName.toLowerCase()) {
        throw new Error(`학과 정식 명칭과 동일한 별칭은 등록할 수 없습니다: ${alias}`);
      }
      const collision = resolveDepartmentEntry(depts, alias);
      if (collision) {
        throw new Error(`이미 '${collision.name}'에서 사용 중인 명칭/별칭입니다: ${alias}`);
      }
    }
    const rawCollegeName = (deptPayload.college || '광운대학교').trim();
    const collegeName = resolveCollegeEntry(colleges, rawCollegeName)?.name || rawCollegeName;
    const newDept = {
      id: `dept-${Date.now()}`,
      name: cleanDeptName,
      shortName: (deptPayload.shortName || cleanDeptName.slice(0, 3)).trim(),
      college: collegeName,
      aliases: cleanAliases
    };
    depts.push(newDept);
    safeSetStorage(STORAGE_KEY_DEPARTMENTS, depts);
    return newDept;
  },

  async updateDepartment(deptId, payload) {
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/departments/${encodeURIComponent(deptId)}`,
          'PUT',
          payload,
          '학과 정보 수정에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote updateDepartment fallback:', e);
      }
    }

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS).map(normalizeCatalogEntry);
    const idx = depts.findIndex((d) => String(d.id) === String(deptId));
    if (idx < 0) throw new Error('존재하지 않는 학과입니다.');
    const cur = depts[idx];
    const nextName = payload.name?.trim() || cur.name;
    const collision = resolveDepartmentEntry(depts, nextName);
    if (collision && String(collision.id) !== String(deptId)) {
      throw new Error(`이미 '${collision.name}'에서 사용 중인 학과명/별칭입니다: ${nextName}`);
    }
    const rawCollege = payload.college?.trim() || cur.college;
    const colleges = safeGetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES).map(normalizeCatalogEntry);
    const nextCollege = resolveCollegeEntry(colleges, rawCollege)?.name || rawCollege;
    let nextAliases = Array.isArray(payload.aliases)
      ? Array.from(new Set(payload.aliases.map((a) => String(a || '').trim()).filter(Boolean)))
      : [...(cur.aliases || [])];
    if (nextName !== cur.name && payload.keepOldNameAsAlias !== false && !nextAliases.includes(cur.name)) {
      nextAliases.push(cur.name);
    }
    nextAliases = nextAliases.filter((a) => a.toLowerCase() !== nextName.toLowerCase());
    const updated = { ...cur, name: nextName, college: nextCollege, aliases: nextAliases };
    depts[idx] = updated;
    safeSetStorage(STORAGE_KEY_DEPARTMENTS, depts);
    if (nextName !== cur.name) {
      const courses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
      safeSetStorage(STORAGE_KEY_COURSES, courses.map((c) => (c.department === cur.name ? { ...c, department: nextName, college: nextCollege } : c)));
    }
    return updated;
  },

  async addDepartmentAlias(deptId, alias) {
    const cleanAlias = String(alias || '').trim();
    if (!cleanAlias) throw new Error('등록할 기존 학과명(별칭)을 입력해주세요.');
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/departments/${encodeURIComponent(deptId)}/aliases`,
          'POST',
          { alias: cleanAlias },
          '학과 별칭 추가에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote addDepartmentAlias fallback:', e);
      }
    }
    return mutateLocalCatalogAliases({
      storageKey: STORAGE_KEY_DEPARTMENTS,
      fallbackList: INITIAL_DEPARTMENTS,
      id: deptId,
      entityLabel: '학과',
      op: 'add',
      alias: cleanAlias
    });
  },

  async renameDepartmentAlias(deptId, oldAlias, newAlias) {
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/departments/${encodeURIComponent(deptId)}/aliases`,
          'PUT',
          { oldAlias, newAlias },
          '학과 별칭 변경에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote renameDepartmentAlias fallback:', e);
      }
    }
    return mutateLocalCatalogAliases({
      storageKey: STORAGE_KEY_DEPARTMENTS,
      fallbackList: INITIAL_DEPARTMENTS,
      id: deptId,
      entityLabel: '학과',
      op: 'rename',
      alias: oldAlias,
      newAlias
    });
  },

  async removeDepartmentAlias(deptId, alias) {
    const cleanAlias = String(alias || '').trim();
    if (!cleanAlias) throw new Error('삭제할 별칭을 지정해주세요.');
    if (USE_REMOTE_BACKEND) {
      try {
        const params = new URLSearchParams({ alias: cleanAlias });
        return await requestCatalogAdmin(
          `/admin/departments/${encodeURIComponent(deptId)}/aliases?${params.toString()}`,
          'DELETE',
          undefined,
          '학과 별칭 삭제에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote removeDepartmentAlias fallback:', e);
      }
    }
    return mutateLocalCatalogAliases({
      storageKey: STORAGE_KEY_DEPARTMENTS,
      fallbackList: INITIAL_DEPARTMENTS,
      id: deptId,
      entityLabel: '학과',
      op: 'remove',
      alias: cleanAlias
    });
  },

  async createCollege(collegePayload) {
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          '/admin/colleges',
          'POST',
          collegePayload,
          '단과대학 생성에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote createCollege fallback:', e);
      }
    }

    const colleges = safeGetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES).map(normalizeCatalogEntry);
    const cleanName = (collegePayload.name || '').trim();
    const collision = resolveCollegeEntry(colleges, cleanName);
    if (collision) {
      throw new Error(`이미 '${collision.name}' 단과대학으로 등록된 명칭/별칭입니다.`);
    }
    const created = {
      id: `col-${Date.now()}`,
      name: cleanName,
      aliases: Array.isArray(collegePayload.aliases) ? collegePayload.aliases.filter(Boolean) : []
    };
    colleges.push(created);
    safeSetStorage(STORAGE_KEY_COLLEGES, colleges);
    return created;
  },

  async updateCollege(collegeId, payload) {
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/colleges/${encodeURIComponent(collegeId)}`,
          'PUT',
          payload,
          '단과대학 정보 수정에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote updateCollege fallback:', e);
      }
    }

    const colleges = safeGetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES).map(normalizeCatalogEntry);
    const idx = colleges.findIndex((c) => String(c.id) === String(collegeId));
    if (idx < 0) throw new Error('존재하지 않는 단과대학입니다.');
    const cur = colleges[idx];
    const nextName = payload.name?.trim() || cur.name;
    const collision = resolveCollegeEntry(colleges, nextName);
    if (collision && String(collision.id) !== String(collegeId)) {
      throw new Error(`이미 '${collision.name}'에서 사용 중인 단과대학명/별칭입니다: ${nextName}`);
    }
    let nextAliases = Array.isArray(payload.aliases)
      ? Array.from(new Set(payload.aliases.map((a) => String(a || '').trim()).filter(Boolean)))
      : [...(cur.aliases || [])];
    if (nextName !== cur.name && payload.keepOldNameAsAlias !== false && !nextAliases.includes(cur.name)) {
      nextAliases.push(cur.name);
    }
    nextAliases = nextAliases.filter((a) => a.toLowerCase() !== nextName.toLowerCase());
    const updated = {
      ...cur,
      name: nextName,
      aliases: nextAliases
    };
    colleges[idx] = updated;
    safeSetStorage(STORAGE_KEY_COLLEGES, colleges);
    if (nextName !== cur.name) {
      const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
      safeSetStorage(STORAGE_KEY_DEPARTMENTS, depts.map((d) => (d.college === cur.name ? { ...d, college: nextName } : d)));
      const courses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
      safeSetStorage(
        STORAGE_KEY_COURSES,
        courses.map((c) => ({
          ...c,
          college: c.college === cur.name ? nextName : c.college,
          department: c.department === `${cur.name} 전체` ? `${nextName} 전체` : c.department
        }))
      );
    }
    return updated;
  },

  async addCollegeAlias(collegeId, alias) {
    const cleanAlias = String(alias || '').trim();
    if (!cleanAlias) throw new Error('등록할 기존 단과대학명(별칭)을 입력해주세요.');
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/colleges/${encodeURIComponent(collegeId)}/aliases`,
          'POST',
          { alias: cleanAlias },
          '단과대학 별칭 추가에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote addCollegeAlias fallback:', e);
      }
    }
    return mutateLocalCatalogAliases({
      storageKey: STORAGE_KEY_COLLEGES,
      fallbackList: INITIAL_COLLEGES,
      id: collegeId,
      entityLabel: '단과대학',
      op: 'add',
      alias: cleanAlias
    });
  },

  async renameCollegeAlias(collegeId, oldAlias, newAlias) {
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/colleges/${encodeURIComponent(collegeId)}/aliases`,
          'PUT',
          { oldAlias, newAlias },
          '단과대학 별칭 변경에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote renameCollegeAlias fallback:', e);
      }
    }
    return mutateLocalCatalogAliases({
      storageKey: STORAGE_KEY_COLLEGES,
      fallbackList: INITIAL_COLLEGES,
      id: collegeId,
      entityLabel: '단과대학',
      op: 'rename',
      alias: oldAlias,
      newAlias
    });
  },

  async removeCollegeAlias(collegeId, alias) {
    const cleanAlias = String(alias || '').trim();
    if (!cleanAlias) throw new Error('삭제할 별칭을 지정해주세요.');
    if (USE_REMOTE_BACKEND) {
      try {
        const params = new URLSearchParams({ alias: cleanAlias });
        return await requestCatalogAdmin(
          `/admin/colleges/${encodeURIComponent(collegeId)}/aliases?${params.toString()}`,
          'DELETE',
          undefined,
          '단과대학 별칭 삭제에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote removeCollegeAlias fallback:', e);
      }
    }
    return mutateLocalCatalogAliases({
      storageKey: STORAGE_KEY_COLLEGES,
      fallbackList: INITIAL_COLLEGES,
      id: collegeId,
      entityLabel: '단과대학',
      op: 'remove',
      alias: cleanAlias
    });
  },

  resolveLocalCourseOrganization(coursePayload, fallbackCourse = {}) {
    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS).map(normalizeCatalogEntry);
    const colleges = safeGetStorage(STORAGE_KEY_COLLEGES, INITIAL_COLLEGES).map(normalizeCatalogEntry);
    const rawDept = coursePayload.department !== undefined ? coursePayload.department : (fallbackCourse.department || '');
    const rawCol = coursePayload.college !== undefined ? coursePayload.college : (fallbackCourse.college || '');
    const inferredScope = (
      coursePayload.scopeType ||
      (rawDept === '전체' || rawCol === '전체'
        ? 'ALL'
        : String(rawDept).endsWith(' 전체')
          ? 'COLLEGE'
          : 'DEPARTMENT')
    ).toUpperCase();
    const matchedCourseDept = resolveDepartmentEntry(depts, rawDept);
    const rawCollege = rawCol || matchedCourseDept?.college || '';
    const matchedCollegeObj = resolveCollegeEntry(colleges, rawCollege);
    const resolvedCollege = inferredScope === 'ALL'
      ? '전체'
      : (matchedCollegeObj?.name || rawCollege || '광운대학교');
    const resolvedDepartment = inferredScope === 'ALL'
      ? '전체'
      : inferredScope === 'COLLEGE'
        ? (resolvedCollege && resolvedCollege !== '광운대학교' ? `${resolvedCollege} 전체` : '단과대 전체')
        : (matchedCourseDept?.name || rawDept || '공통');
    return { college: resolvedCollege, department: resolvedDepartment };
  },

  async createCourse(coursePayload) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetch(`${API_BASE_URL}/admin/courses`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(coursePayload)
        });
        const body = await res.json().catch(() => ({}));
        if (res.ok && body.data) {
          return mapCourseDto(body.data);
        }
        if (!res.ok) {
          const err = new Error(body.message || '과목 추가에 실패했습니다.');
          err.isHttpError = true;
          throw err;
        }
      } catch (e) {
        if (e?.isHttpError || (e.message && e.message.includes('과목'))) throw e;
        console.warn('[API] Remote createCourse fallback:', e);
      }
    }

    const courses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    const { college: resolvedCollege, department: resolvedDepartment } =
      this.resolveLocalCourseOrganization(coursePayload);
    const newCourse = {
      id: `course-${Date.now()}`,
      code: (coursePayload.code || '').trim().toUpperCase(),
      name: (coursePayload.name || '').trim(),
      semester: coursePayload.semester || '2026-1',
      college: resolvedCollege,
      department: resolvedDepartment,
      group: 'COMMON',
      section: '01분반',
      profId: coursePayload.professorId || '',
      profName: coursePayload.professorName || '미배정',
      assignedProfessorId: coursePayload.professorId || '',
      assignedProfessorName: coursePayload.professorName || '미배정',
      professors: []
    };
    courses.push(newCourse);
    safeSetStorage(STORAGE_KEY_COURSES, courses);
    return newCourse;
  },

  async updateCourse(courseId, coursePayload) {
    if (USE_REMOTE_BACKEND) {
      try {
        const data = await requestCatalogAdmin(
          `/admin/courses/${encodeURIComponent(courseId)}`,
          'PUT',
          coursePayload,
          '과목 수정에 실패했습니다.'
        );
        return data ? mapCourseDto(data) : data;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote updateCourse fallback:', e);
      }
    }

    const courses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    const idx = courses.findIndex((c) => String(c.id) === String(courseId));
    if (idx < 0) throw new Error('존재하지 않는 과목입니다.');
    const cur = courses[idx];
    const nextName = coursePayload.name !== undefined ? String(coursePayload.name).trim() : cur.name;
    const nextCode = coursePayload.code !== undefined ? String(coursePayload.code).trim().toUpperCase() : cur.code;
    if (!nextName || !nextCode) throw new Error('과목명과 학수번호를 모두 입력해 주세요.');
    const { college: resolvedCollege, department: resolvedDepartment } =
      this.resolveLocalCourseOrganization(coursePayload, cur);

    const updated = {
      ...cur,
      name: nextName,
      code: nextCode,
      semester: (coursePayload.semester !== undefined ? String(coursePayload.semester).trim() : cur.semester) || cur.semester || '2026-1',
      college: resolvedCollege,
      department: resolvedDepartment
    };
    courses[idx] = updated;
    safeSetStorage(STORAGE_KEY_COURSES, courses);

    const syncUserCourses = (u) => ({
      ...u,
      courses: Array.isArray(u.courses)
        ? u.courses.map((uc) => (String(uc.id) === String(courseId) ? updated : uc))
        : u.courses
    });
    const users = safeGetStorage(STORAGE_KEY_USERS, []);
    if (users.length > 0) safeSetStorage(STORAGE_KEY_USERS, users.map(syncUserCourses));
    const curSession = this.getCurrentSessionUser();
    if (curSession?.id) this.saveSession(syncUserCourses(curSession));

    return updated;
  },

  async deleteCourse(courseId) {
    if (USE_REMOTE_BACKEND) {
      try {
        return await requestCatalogAdmin(
          `/admin/courses/${encodeURIComponent(courseId)}`,
          'DELETE',
          undefined,
          '과목 삭제에 실패했습니다.'
        );
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote deleteCourse fallback:', e);
      }
    }

    const courses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    const idx = courses.findIndex((c) => String(c.id) === String(courseId));
    if (idx < 0) throw new Error('존재하지 않는 과목입니다.');
    safeSetStorage(STORAGE_KEY_COURSES, courses.filter((c) => String(c.id) !== String(courseId)));

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions.filter((q) => String(q.courseId) !== String(courseId)));

    const stripUserCourse = (u) => ({
      ...u,
      courses: Array.isArray(u.courses)
        ? u.courses.filter((uc) => String(uc.id) !== String(courseId))
        : u.courses
    });
    const users = safeGetStorage(STORAGE_KEY_USERS, []);
    if (users.length > 0) safeSetStorage(STORAGE_KEY_USERS, users.map(stripUserCourse));
    const curSession = this.getCurrentSessionUser();
    if (curSession?.id) this.saveSession(stripUserCourse(curSession));

    return { deleted: true, courseId };
  },

  async updateUserCourses(courseIds) {
    const ids = Array.isArray(courseIds) ? [...new Set(courseIds.filter(Boolean))] : [];
    if (ids.length === 0) {
      throw new Error('최소 1개 이상의 과목을 선택해야 합니다.');
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetch(`${API_BASE_URL}/courses/my`, {
          method: 'PUT',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({ courseIds: ids })
        });
        const body = await res.json().catch(() => ({}));
        if (res.ok && body.data) {
          if (Array.isArray(body.data)) {
            const updatedCourses = body.data.map(mapCourseDto);
            const currentSession = this.getCurrentSessionUser();
            if (currentSession?.id) {
              const updatedUser = normalizeUserObject(
                { ...currentSession, courses: updatedCourses },
                updatedCourses
              );
              this.saveSession(updatedUser);
              return updatedUser;
            }
            const refreshed = await this.getUser(getCurrentUserId());
            if (refreshed?.id) {
              const merged = normalizeUserObject(
                { ...refreshed, courses: updatedCourses },
                updatedCourses
              );
              this.saveSession(merged);
              return merged;
            }
          } else if (typeof body.data === 'object' && body.data.id) {
            const updatedCourses = Array.isArray(body.data.courses)
              ? body.data.courses.map(mapCourseDto)
              : [];
            const updatedUser = normalizeUserObject(
              { ...body.data, courses: updatedCourses },
              updatedCourses
            );
            this.saveSession(updatedUser);
            return updatedUser;
          }
        }
        if (!res.ok) {
          const err = new Error(body.message || '과목 설정 저장에 실패했습니다.');
          err.isHttpError = true;
          err.status = res.status;
          throw err;
        }
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote updateUserCourses fallback:', e);
      }
    }

    const sessionUser = safeGetStorage(STORAGE_KEY_SESSION, null);
    if (!sessionUser) {
      throw new Error('로그인이 필요합니다.');
    }
    const allCourses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    const selectedCourses = allCourses.filter(c => ids.includes(c.id));
    if (selectedCourses.length === 0) {
      throw new Error('선택한 과목을 찾을 수 없습니다.');
    }
    const updatedUser = {
      ...sessionUser,
      courses: selectedCourses
    };
    safeSetStorage(STORAGE_KEY_SESSION, updatedUser);
    const users = safeGetStorage(STORAGE_KEY_USERS, []);
    const nextUsers = users.map(u => (u.id === sessionUser.id ? { ...u, courses: selectedCourses } : u));
    safeSetStorage(STORAGE_KEY_USERS, nextUsers);
    return normalizeUserObject(updatedUser, allCourses);
  },

  async setQuestionThreadOpen(questionId, isThreadOpen) {
    if (USE_REMOTE_BACKEND) {
      const res = await fetch(`${API_BASE_URL}/questions/${questionId}/thread-open`, {
        method: 'PATCH',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders()
        },
        body: JSON.stringify({ isThreadOpen: Boolean(isThreadOpen) })
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.success === false) {
        throw new Error(body.message || '공개 상태 변경에 실패했습니다.');
      }
      return body.data;
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const updated = questions.map(q =>
      q.id === questionId ? { ...q, isThreadOpen: Boolean(isThreadOpen) } : q
    );
    safeSetStorage(STORAGE_KEY_QUESTIONS, updated);
    return updated.find(q => q.id === questionId);
  },

  async toggleHideAnswerByQuestion(questionId, answerId, isHidden) {
    if (USE_REMOTE_BACKEND) {
      const res = await fetch(`${API_BASE_URL}/questions/${questionId}/answers/${answerId}/hide`, {
        method: 'PATCH',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders()
        },
        body: JSON.stringify({ isHidden: Boolean(isHidden) })
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.success === false) {
        throw new Error(body.message || '답변 가리기 처리에 실패했습니다.');
      }
      return body.data;
    }

    return this.toggleHideAnswer(answerId, isHidden, questionId);
  },

  async deleteAnswerByQuestion(questionId, answerId) {
    if (USE_REMOTE_BACKEND) {
      const res = await fetch(`${API_BASE_URL}/questions/${questionId}/answers/${answerId}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: getAuthHeaders()
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.success === false) {
        throw new Error(body.message || '답변 삭제에 실패했습니다.');
      }
      return true;
    }

    return this.deleteAnswer(answerId, questionId);
  },

  async toggleHideQuestion(questionId, isHidden) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/admin/questions/${questionId}/hide`, {
          method: 'PATCH',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({ isHidden })
        }, 5000);
        const body = await parseJsonSafe(res);
        if (res.ok) {
          return true;
        }
        const httpErr = new Error(body.message || '질문 가리기 권한이 없습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote toggleHideQuestion fallback:', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const target = questions.find(q => q.id === questionId);
    if (target) {
      target.isHidden = Boolean(isHidden);
      safeSetStorage(STORAGE_KEY_QUESTIONS, questions);
    }
    return true;
  },

  async toggleHideAnswer(answerId, isHidden, questionId = null) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/admin/answers/${answerId}/hide`, {
          method: 'PATCH',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({ isHidden })
        }, 5000);
        const body = await parseJsonSafe(res);
        if (res.ok) {
          return true;
        }
        const httpErr = new Error(body.message || '답변 가리기 권한이 없습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote toggleHideAnswer fallback:', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    questions.forEach(q => {
      if (!questionId || q.id === questionId) {
        if (Array.isArray(q.answers)) {
          const ans = q.answers.find(a => a.id === answerId);
          if (ans) ans.isHidden = Boolean(isHidden);
        }
      }
    });
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);
    return true;
  },

  async deleteQuestion(questionId, { isAdmin = false } = {}) {
    if (USE_REMOTE_BACKEND) {
      try {
        const endpoint = isAdmin
          ? `${API_BASE_URL}/admin/questions/${questionId}`
          : `${API_BASE_URL}/questions/${questionId}`;
        const res = await fetch(endpoint, {
          method: 'DELETE',
          credentials: 'include',
          headers: getAuthHeaders()
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok || body.success === false) {
          throw new Error(body.message || '게시물 삭제에 실패했습니다.');
        }
        return true;
      } catch (e) {
        if (e.name !== 'TypeError') throw e;
        console.warn('[API] Remote deleteQuestion fallback:', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    if (isAdmin) {
      const filtered = questions.filter(q => q.id !== questionId);
      safeSetStorage(STORAGE_KEY_QUESTIONS, filtered);
      return true;
    }

    const target = questions.find(q => q.id === questionId);
    if (!target || target.isDeleted) {
      throw new Error('질문을 찾을 수 없습니다.');
    }
    if (!sessionUser || sessionUser.role !== 'student' || target.studentId !== sessionUser.id) {
      throw new Error('질문을 작성한 학생 본인만 삭제할 수 있습니다.');
    }
    const deletedAt = new Date().toISOString();
    const updated = questions.map(q =>
      q.id === questionId
        ? {
            ...q,
            isDeleted: true,
            deletedAt,
            title: `[작성자 삭제] ${q.title || ''}`,
            content: '질문 작성자에 의해 삭제된 게시물입니다.',
            refinedContent: '질문 작성자에 의해 삭제된 게시물입니다.',
            originalContent: '',
            attachments: []
          }
        : q
    );
    safeSetStorage(STORAGE_KEY_QUESTIONS, updated);
    for (const profRecipientId of getLocalThreadProfRecipients(target, sessionUser.id)) {
      pushLocalNotification({
        userId: profRecipientId,
        type: 'QUESTION_DELETED',
        title: `[${target.courseName || '강의'}] 학생이 질문을 삭제했습니다.`,
        message: `질문 작성자가 등록했던 질문을 삭제했습니다: "${target.title || ''}"`,
        questionId
      });
    }
    return true;
  },

  async deleteAnswer(answerId, questionId = null) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetch(`${API_BASE_URL}/admin/answers/${answerId}`, {
          method: 'DELETE',
          credentials: 'include',
          headers: getAuthHeaders()
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok || body.success === false) {
          throw new Error(body.message || '답변 삭제에 실패했습니다.');
        }
        return true;
      } catch (e) {
        if (e.name !== 'TypeError') throw e;
        console.warn('[API] Remote deleteAnswer fallback:', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    questions.forEach(q => {
      if (!questionId || q.id === questionId) {
        if (Array.isArray(q.answers)) {
          q.answers = q.answers.filter(a => a.id !== answerId);
          if (q.answers.length === 0) {
            q.status = 'open';
          }
        }
      }
    });
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);
    return true;
  },

  // ============================================================================
  // 4. 환경 설정 및 이메일 로그
  // ============================================================================
  async getSettings(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1500);
        const res = await fetch(`${API_BASE_URL}/settings?userId=${uid}`, {
          headers: getAuthHeaders(uid),
          credentials: 'include',
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const data = body?.data || body;
          if (data && typeof data === 'object') {
            return { exhibitionMode: false, ...data };
          }
        }
      } catch (e) {
        console.warn('[API] Remote getSettings failed, using local fallback', e);
      }
    }

    const settingsMap = safeGetStorage(STORAGE_KEY_SETTINGS, {});
    const sessionUser = this.getCurrentSessionUser();
    return {
      theme: 'light',
      fontSize: 'md',
      helpLevel: 'SOME',
      exhibitionMode: false,
      dndEnabled: false,
      dndStartTime: '22:00',
      dndEndTime: '08:00',
      coProfNotification: true,
      emailEnabled: true,
      notificationEmail: sessionUser?.email || '',
      activeCategories: { grade: true, assignment: true, lecture: true },
      ...(settingsMap[uid] || {})
    };
  },

  async updateSettings(userId, newSettings) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1500);
        const res = await fetch(`${API_BASE_URL}/settings`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders(uid)
          },
          credentials: 'include',
          body: JSON.stringify({ userId: uid, ...newSettings }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.status === 403 || res.status === 401) {
          const body = await res.json().catch(() => ({}));
          const authErr = new Error(body.message || '설정을 변경할 권한이 없습니다.');
          authErr.isHttpError = true;
          throw authErr;
        }
        if (res.ok) {
          const body = await res.json().catch(() => ({}));
          const data = body?.data || body;
          if (data && typeof data === 'object' && body?.success !== false) {
            const current = this.getCurrentSessionUser();
            if (current && newSettings.notificationEmail !== undefined) {
              this.saveSession({ ...current, email: newSettings.notificationEmail });
            }
            return data;
          }
        }
      } catch (e) {
        if (e?.isHttpError) throw e;
        console.warn('[API] Remote updateSettings failed, using local storage', e);
      }
    }

    const settingsMap = safeGetStorage(STORAGE_KEY_SETTINGS, {});
    settingsMap[uid] = { ...(settingsMap[uid] || {}), ...newSettings };
    safeSetStorage(STORAGE_KEY_SETTINGS, settingsMap);
    return settingsMap[uid];
  },

  async getEmailLogs(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const res = await fetch(`${API_BASE_URL}/notifications/emails`, {
          headers: getAuthHeaders(uid),
          credentials: 'include'
        });
        if (res.ok) {
          const body = await res.json();
          const list = body?.data || body;
          if (Array.isArray(list)) return list;
        }
      } catch (e) {
        console.warn('[API] Remote getEmailLogs failed, using local fallback', e);
      }
    }
    return safeGetStorage(STORAGE_KEY_EMAIL_LOGS, []);
  },

  // ============================================================================
  // 5. 질문 및 답변 (포럼 토픽/포스트 스트림)
  // ============================================================================
  async getQuestions(userId) {
    const uid = getCurrentUserId(userId);
    if (!uid) return [];
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/questions`, {
          headers: getAuthHeaders(uid),
          credentials: 'include'
        }, 1800);
        if (res.status === 401 || res.status === 403) {
          return [];
        }
        if (res.ok) {
          const body = await parseJsonSafe(res);
          const remoteList = Array.isArray(body) ? body : body.data;
          if (Array.isArray(remoteList)) return remoteList;
        }
      } catch (e) {
        console.warn('[API] Remote getQuestions failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    if (!sessionUser?.id) return [];
    const isAdmin = Boolean(sessionUser?.isAdmin);
    const isProfessor = sessionUser?.role === 'professor';
    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return (Array.isArray(questions) ? questions : INITIAL_QUESTIONS)
      .filter(q => !(q.isDeleted && !isAdmin && !isProfessor))
      .filter(q => {
        if (String(q.category || '').toUpperCase() !== 'GRADE' || isAdmin) return true;
        return q.studentId === sessionUser.id || q.profId === sessionUser.id || q.assignedProfessorId === sessionUser.id;
      })
      .map(q => {
        const isMyQuestion = q.studentId === sessionUser?.id;
        const canViewDraft = !isProfessor && (isMyQuestion || isAdmin);
        const maskAnonIdentity = Boolean(q.isAnonymous && !isMyQuestion && !isAdmin);
        return {
          ...q,
          title: q.isHidden && !isAdmin ? '[관리자에 의해 가려진 질문입니다]' : q.title,
          content: q.isHidden && !isAdmin ? '관리자에 의해 비표시(가리기) 처리된 게시물입니다.' : q.content,
          originalContent: canViewDraft ? q.originalContent : '',
          rawContent: canViewDraft ? q.rawContent : '',
          studentId: maskAnonIdentity ? null : q.studentId,
          studentName: maskAnonIdentity ? '익명 수강생' : q.studentName,
          studentTag: maskAnonIdentity ? `${q.courseName || '수강'} 수강생` : q.studentTag,
          answers: (q.answers || []).map(a => {
            const isMyAns = a.authorId === sessionUser?.id;
            const canViewAnsDraft = !isProfessor && (isMyAns || isAdmin);
            return {
              ...a,
              originalContent: canViewAnsDraft ? a.originalContent : '',
              rawContent: canViewAnsDraft ? a.rawContent : ''
            };
          }),
          canDelete: !q.isDeleted && sessionUser?.role === 'student' && isMyQuestion,
          realStudentName: isAdmin ? q.realStudentName || q.studentName : null,
          realStudentTag: isAdmin ? q.realStudentTag || q.studentTag : null,
          realStudentIdentifier: isAdmin ? q.realStudentIdentifier || '' : null
        };
      });
  },

  async getQuestion(questionId, userId) {
    const uid = getCurrentUserId(userId);
    if (!uid) return null;
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/questions/${questionId}`, {
          headers: getAuthHeaders(uid),
          credentials: 'include'
        }, 1800);
        if (res.status === 401 || res.status === 403) {
          return null;
        }
        if (res.ok) {
          const body = await parseJsonSafe(res);
          return body.data || body;
        }
      } catch (e) {
        console.warn('[API] Remote getQuestion failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    if (!sessionUser?.id) return null;
    const isAdmin = Boolean(sessionUser?.isAdmin);
    const isProfessor = sessionUser?.role === 'professor';
    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const q = questions.find(item => item.id === questionId);
    if (!q || (q.isDeleted && !isAdmin && !isProfessor)) return null;
    if (
      String(q.category || '').toUpperCase() === 'GRADE' &&
      !isAdmin &&
      q.studentId !== sessionUser.id &&
      q.profId !== sessionUser.id &&
      q.assignedProfessorId !== sessionUser.id
    ) {
      return null;
    }
    const isMyQuestion = q.studentId === sessionUser.id;
    const canViewDraft = !isProfessor && (isMyQuestion || isAdmin);
    const maskAnonIdentity = Boolean(q.isAnonymous && !isMyQuestion && !isAdmin);
    return {
      ...q,
      title: q.isHidden && !isAdmin ? '[관리자에 의해 가려진 질문입니다]' : q.title,
      content: q.isHidden && !isAdmin ? '관리자에 의해 비표시(가리기) 처리된 게시물입니다.' : q.content,
      originalContent: canViewDraft ? q.originalContent : '',
      rawContent: canViewDraft ? q.rawContent : '',
      studentId: maskAnonIdentity ? null : q.studentId,
      studentName: maskAnonIdentity ? '익명 수강생' : q.studentName,
      studentTag: maskAnonIdentity ? `${q.courseName || '수강'} 수강생` : q.studentTag,
      canDelete: !q.isDeleted && sessionUser?.role === 'student' && isMyQuestion,
      realStudentName: isAdmin ? q.realStudentName || q.studentName : null,
      realStudentTag: isAdmin ? q.realStudentTag || q.studentTag : null,
      realStudentIdentifier: isAdmin ? q.realStudentIdentifier || '' : null,
      answers: (q.answers || []).map(a => {
        const isMyAns = a.authorId === sessionUser.id;
        const canViewAnsDraft = !isProfessor && (isMyAns || isAdmin);
        return {
          ...a,
          content: a.isHidden && !isAdmin ? '관리자에 의해 가려진 답변입니다.' : a.content,
          originalContent: canViewAnsDraft ? a.originalContent : '',
          rawContent: canViewAnsDraft ? a.rawContent : ''
        };
      })
    };
  },

  async uploadAttachment(file, meta = {}) {
    const uid = getCurrentUserId();
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const formData = new FormData();
        formData.append('file', file, file.name);

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 15000);
        const res = await fetch(`${API_BASE_URL}/attachments/upload`, {
          method: 'POST',
          credentials: 'include',
          headers: getAuthHeaders(uid),
          body: formData,
          signal: controller.signal
        });
        clearTimeout(timeoutId);

        if (res.ok) {
          const body = await res.json();
          const att = body.attachment || body.data || body;
          return {
            success: true,
            attachment: {
              ...att,
              url: att.url || `${API_BASE_URL}/attachments/${att.id}`,
              downloadUrl: att.downloadUrl || `${API_BASE_URL}/attachments/${att.id}/download`,
              previewUrl: meta.previewUrl || att.url || `${API_BASE_URL}/attachments/${att.id}`
            },
            message: body.message
          };
        } else {
          const errBody = await res.json().catch(() => ({}));
          throw new Error(errBody.message || '파일 보안 검사 실패 또는 업로드 오류입니다.');
        }
      } catch (e) {
        if (
          e.message &&
          (e.message.includes('보안') ||
            e.message.includes('시그니처') ||
            e.message.includes('허용되지 않는') ||
            e.message.includes('위조'))
        ) {
          throw e;
        }
      }
    }

    const localId = `att-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    const previewUrl = meta.previewUrl || (meta.isImage ? URL.createObjectURL(file) : '');
    return {
      success: true,
      attachment: {
        id: localId,
        originalName: file.name,
        savedName: `${localId}_${file.name}`,
        fileSize: file.size,
        originalSize: meta.originalSize || file.size,
        mimeType: file.type || 'application/octet-stream',
        isImage: Boolean(meta.isImage),
        isCompressed: Boolean(meta.isCompressed),
        compressionRatio: meta.compressionRatio || 0,
        url: previewUrl,
        downloadUrl: previewUrl,
        previewUrl: previewUrl,
        createdAt: new Date().toISOString()
      },
      message: meta.isCompressed
        ? `이미지가 성공적으로 압축되었습니다 (${meta.compressionRatio}% 절감).`
        : '파일이 첨부되었습니다.'
    };
  },

  async deleteAttachment(attachmentId) {
    if (USE_REMOTE_BACKEND) {
      try {
        await fetch(`${API_BASE_URL}/attachments/${attachmentId}`, {
          method: 'DELETE',
          credentials: 'include',
          headers: getAuthHeaders()
        });
      } catch (e) {
        console.warn('[API] Remote deleteAttachment failed', e);
      }
    }
    return true;
  },

  async createQuestion(questionData) {
    const rawTitle = questionData.title?.trim() || generateSummaryTitle(questionData.content);
    const questionPayload = {
      ...questionData,
      title: rawTitle
    };
    const uid = getCurrentUserId(questionData.studentId);

    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2500);
        const res = await fetch(`${API_BASE_URL}/questions`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(questionPayload),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await res.json().catch(() => ({}));
        if (res.ok) {
          return body?.data || body;
        }
        const httpErr = new Error(body.message || '질문 등록 권한이 제한되어 있습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) {
          throw e;
        }
        if (!import.meta.env.DEV) {
          throw new Error('서버에 연결할 수 없어 질문을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.', { cause: e });
        }
        console.warn('[API] Remote createQuestion failed, using local fallback', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    if (!sessionUser || sessionUser.role !== 'student') {
      throw new Error('학생 계정만 질문을 작성할 수 있습니다.');
    }
    if (sessionUser?.questionBanUntil && new Date(sessionUser.questionBanUntil) > new Date()) {
      throw new Error(`현재 질문 작성 제한 상태입니다. (해제 일시: ${sessionUser.questionBanUntil})`);
    }

    const sanitizedPayload = questionPayload.isAnonymous
      ? {
          ...questionPayload,
          title: sanitizeAnonymousStudentText(questionPayload.title, sessionUser, {
            professorNames: [questionPayload.profName]
          }),
          content: sanitizeAnonymousStudentText(questionPayload.content, sessionUser, {
            professorNames: [questionPayload.profName]
          }),
          originalContent: sanitizeAnonymousStudentText(
            questionPayload.originalContent || questionPayload.content,
            sessionUser,
            { professorNames: [questionPayload.profName] }
          )
        }
      : questionPayload;
    const summaryTitle = sanitizedPayload.title || generateSummaryTitle(sanitizedPayload.content);

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const newQuestion = {
      id: `q-${Date.now()}`,
      createdAt: new Date().toISOString(),
      answers: [],
      isHidden: false,
      realStudentName: sessionUser?.name || questionData.studentName,
      realStudentTag: sessionUser?.displayTag || questionData.studentTag,
      realStudentIdentifier: sessionUser?.identifier || '',
      ...sanitizedPayload,
      title: summaryTitle
    };
    questions.unshift(newQuestion);
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);

    // 로컬 폴백 이메일 발송 로그 기록
    const emailLogs = safeGetStorage(STORAGE_KEY_EMAIL_LOGS, []);
    emailLogs.unshift({
      id: `email-${Date.now()}`,
      recipientEmail: `${questionData.profName || 'prof'}@kw.ac.kr`,
      professorId: questionData.profId || '',
      questionId: newQuestion.id,
      subject: `[새 질문 알림] [${questionData.courseName || '강좌'} - ${questionData.category}] ${summaryTitle}`,
      body: newQuestion.content,
      status: 'LOGGED_LOCAL_DEV',
      createdAt: new Date().toISOString()
    });
    safeSetStorage(STORAGE_KEY_EMAIL_LOGS, emailLogs);

    if (questionData.profId) {
      pushLocalNotification({
        userId: questionData.profId,
        type: 'QUESTION_NEW',
        title: `[${questionData.courseName || '강의'}] 새로운 질문 등록`,
        message: `[${questionData.category || 'LECTURE'}] ${summaryTitle}`,
        questionId: newQuestion.id
      });
    }

    return newQuestion;
  },

  async updateQuestion(questionId, updatedData, userId) {
    const uid = getCurrentUserId(userId || updatedData.studentId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}`, {
          method: 'PUT',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(updatedData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await res.json().catch(() => ({}));
        if (res.ok) {
          return body?.data || body;
        }
        const httpErr = new Error(body.message || '질문 수정 권한이 없습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) throw e;
        if (!import.meta.env.DEV) {
          throw new Error('서버에 연결할 수 없어 질문을 수정하지 못했습니다. 잠시 후 다시 시도해 주세요.', { cause: e });
        }
        console.warn('[API] Remote updateQuestion failed, using local fallback', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const index = questions.findIndex(q => q.id === questionId);
    if (index === -1) throw new Error('질문을 찾을 수 없습니다.');

    const targetQ = questions[index];
    const effectiveAnon =
      updatedData.isAnonymous !== undefined ? Boolean(updatedData.isAnonymous) : Boolean(targetQ.isAnonymous);
    const sessionUser = this.getCurrentSessionUser();
    const profNames = [targetQ.profName, targetQ.assignedProfessorName].filter(Boolean);

    const updated = {
      ...targetQ,
      ...updatedData,
      isAnonymous: effectiveAnon,
      isEdited: true,
      updatedAt: new Date().toISOString()
    };
    if (effectiveAnon && sessionUser) {
      if (updated.title) updated.title = sanitizeAnonymousStudentText(updated.title, sessionUser, { professorNames: profNames });
      if (updated.content) updated.content = sanitizeAnonymousStudentText(updated.content, sessionUser, { professorNames: profNames });
      if (updated.originalContent) updated.originalContent = sanitizeAnonymousStudentText(updated.originalContent, sessionUser, { professorNames: profNames });
      if (updated.refinedContent) updated.refinedContent = sanitizeAnonymousStudentText(updated.refinedContent, sessionUser, { professorNames: profNames });
    }
    questions[index] = updated;
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);
    return updated;
  },

  async createAnswer(questionId, answerData) {
    const uid = getCurrentUserId(answerData.authorId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2500);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}/answers`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(answerData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await res.json().catch(() => ({}));
        if (res.ok) {
          return body?.data || body;
        }
        const httpErr = new Error(body.message || '답변 작성 권한이 제한되어 있습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) {
          throw e;
        }
        console.warn('[API] Remote createAnswer failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const targetQ = questions.find(q => q.id === questionId);
    if (!targetQ) throw new Error('질문을 찾을 수 없습니다.');

    if (!sessionUser || (!sessionUser.isAdmin && sessionUser.role !== 'professor' && !targetQ.isThreadOpen)) {
      throw new Error('답변을 등록할 권한이 없습니다.');
    }
    if (sessionUser?.answerBanUntil && new Date(sessionUser.answerBanUntil) > new Date()) {
      throw new Error(`현재 답변 작성 제한 상태입니다. (해제 일시: ${sessionUser.answerBanUntil})`);
    }

    const nextSeq = Math.max(targetQ.lastSequenceNumber || 1, 1) + 1;
    const newAnswer = {
      id: `ans-${Date.now()}`,
      createdAt: new Date().toISOString(),
      isHidden: false,
      sequenceNumber: nextSeq,
      isAdminAuthor: Boolean(sessionUser.isAdmin),
      authorTitle: sessionUser.isAdmin ? '관리자' : sessionUser.role === 'student' ? '학생 답변' : '담당교수',
      ...answerData
    };

    if (!Array.isArray(targetQ.answers)) targetQ.answers = [];
    targetQ.lastSequenceNumber = nextSeq;
    targetQ.answers.push(newAnswer);
    if (sessionUser.role === 'professor' || sessionUser.isAdmin) {
      targetQ.status = 'ANSWERED';
    }
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);

    const authorLabel = sessionUser.isAdmin
      ? `${sessionUser.nickname || sessionUser.name}님`
      : sessionUser.role === 'professor'
        ? `${sessionUser.name} 교수님`
        : '다른 수강생님';

    if (targetQ.studentId && targetQ.studentId !== sessionUser.id) {
      pushLocalNotification({
        userId: targetQ.studentId,
        type: 'ANSWER_NEW',
        title: `[${targetQ.courseName || '강의'}] 질문에 답변이 등록되었습니다.`,
        message: `${authorLabel}이 답변을 남겼습니다: "${targetQ.title || ''}"`,
        questionId
      });
    }

    for (const profRecipientId of getLocalThreadProfRecipients(targetQ, sessionUser.id)) {
      if (profRecipientId === targetQ.studentId) continue;
      pushLocalNotification({
        userId: profRecipientId,
        type: 'ANSWER_NEW',
        title: `[${targetQ.courseName || '강의'}] 질문에 새로운 답변이 등록되었습니다.`,
        message: `${authorLabel}이 답변을 남겼습니다: "${targetQ.title || ''}"`,
        questionId
      });
    }

    return newAnswer;
  },

  async createFollowUp(questionId, followUpData) {
    const uid = getCurrentUserId(followUpData?.authorId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2500);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}/followups`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({
            content: followUpData?.content || '',
            originalContent: followUpData?.originalContent || null,
            hasAiRefined: Boolean(followUpData?.hasAiRefined),
            attachmentIds: Array.isArray(followUpData?.attachmentIds) ? followUpData.attachmentIds : []
          }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await res.json().catch(() => ({}));
        if (res.ok) {
          return body?.data || body;
        }
        const httpErr = new Error(body.message || '추가 질문 등록 권한이 제한되어 있습니다.');
        httpErr.isHttpError = true;
        httpErr.status = res.status;
        throw httpErr;
      } catch (e) {
        if (e?.isHttpError) {
          throw e;
        }
        console.warn('[API] Remote createFollowUp failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    if (!sessionUser || sessionUser.role !== 'student') {
      throw new Error('학생 계정만 추가 질문을 작성할 수 있습니다.');
    }
    if (sessionUser?.questionBanUntil && new Date(sessionUser.questionBanUntil) > new Date()) {
      throw new Error(`현재 질문 작성 제한 상태입니다. (해제 일시: ${sessionUser.questionBanUntil})`);
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const targetQ = questions.find((q) => q.id === questionId);
    if (!targetQ) throw new Error('질문을 찾을 수 없습니다.');
    if (targetQ.studentId !== sessionUser.id) {
      throw new Error('질문을 작성한 학생 본인만 추가 질문을 남길 수 있습니다.');
    }

    const rawContent = (followUpData?.content || '').trim();
    const rawOriginal = (followUpData?.originalContent || '').trim() || rawContent;
    const profNames = [targetQ.profName, targetQ.assignedProfessorName].filter(Boolean);
    const trimmedContent = targetQ.isAnonymous
      ? sanitizeAnonymousStudentText(rawContent, sessionUser, { professorNames: profNames })
      : rawContent;
    const trimmedOriginal = targetQ.isAnonymous
      ? sanitizeAnonymousStudentText(rawOriginal, sessionUser, { professorNames: profNames })
      : rawOriginal;
    const nextSeq = Math.max(targetQ.lastSequenceNumber || 1, 1) + 1;
    const newFollowUp = {
      id: `followup-${Date.now()}`,
      questionId,
      authorId: sessionUser.id,
      authorName: targetQ.isAnonymous ? `${targetQ.courseName || '강의'} 수강생` : `${sessionUser.displayTag || sessionUser.name} (나)`,
      authorRole: 'STUDENT',
      authorTitle: '질문자 추가 질문',
      isAssignedProfessor: false,
      isPrimaryProf: false,
      sequenceNumber: nextSeq,
      content: trimmedContent,
      originalContent: trimmedOriginal,
      attachmentIds: Array.isArray(followUpData?.attachmentIds) ? followUpData.attachmentIds : [],
      attachments: Array.isArray(followUpData?.attachments) ? followUpData.attachments : [],
      createdAt: new Date().toISOString(),
      isHidden: false
    };

    if (!Array.isArray(targetQ.answers)) targetQ.answers = [];
    targetQ.lastSequenceNumber = nextSeq;
    targetQ.answers.push(newFollowUp);
    targetQ.status = 'OPEN';
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);

    const assignedProfId = targetQ.profId || targetQ.assignedProfessorId || null;
    const followUpAuthorDisplay = targetQ.isAnonymous
      ? `${targetQ.courseName || '강의'} 수강생`
      : (sessionUser.displayTag || sessionUser.name);
    for (const profRecipientId of getLocalThreadProfRecipients(targetQ, sessionUser.id)) {
      const isAssigned = profRecipientId === assignedProfId;
      pushLocalNotification({
        userId: profRecipientId,
        type: isAssigned ? 'QUESTION_NEW' : 'OTHER_PROFESSOR_QUESTION',
        title: isAssigned
          ? `[${targetQ.courseName || '강의'}] 질문에 추가 질문(꼬리질문)이 등록되었습니다.`
          : `[${targetQ.courseName || '강의'}] 참여한 질문에 추가 질문(꼬리질문)이 등록되었습니다.`,
        message: `${followUpAuthorDisplay}: "${targetQ.title || ''}"`,
        questionId
      });
    }
    return newFollowUp;
  },

  async getSimilarQuestions(queryText, category, courseId) {
    if (category?.toLowerCase() === 'grade') {
      return [];
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/questions/similar`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: queryText, category, courseId }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : body?.data;
          if (Array.isArray(list)) {
            return list
              .map(item => ({
                ...item,
                similarityScore:
                  item.similarityScore !== undefined
                    ? item.similarityScore
                    : Math.round((item.similarity || 0) * 100)
              }))
              .filter(item => item.similarityScore >= 25);
          }
        }
      } catch (e) {
        console.warn('[API] Remote similar search failed, using local engine', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return calculateSimilarQuestions(queryText, questions, 3);
  },

  async refineQuestionAI(
    rawText,
    category = 'lecture',
    studentInfo = {},
    rawTitle = '',
    courseContext = {}
  ) {
    const catLabel = (() => {
      const c = (category || '').toLowerCase();
      if (c === 'grade') return '성적';
      if (c === 'assignment') return '과제';
      return '강의';
    })();

    const courseName = (courseContext?.courseName || '').trim();
    const rawProfName = (courseContext?.professorName || '').trim();
    const profGreeting = (() => {
      if (!rawProfName) return '교수님';
      if (rawProfName.endsWith('교수님')) return rawProfName;
      if (rawProfName.endsWith('교수')) return `${rawProfName}님`;
      return `${rawProfName} 교수님`;
    })();
    const isAnonymous = Boolean(courseContext?.isAnonymous);
    const effectiveStudentInfo = isAnonymous ? {} : studentInfo;
    const sessionUser = safeGetStorage(STORAGE_KEY_SESSION, null);
    const studentTag = isAnonymous
      ? '학생'
      : (effectiveStudentInfo.name
          ? `${effectiveStudentInfo.department || '학부'} ${effectiveStudentInfo.studentId || ''} ${effectiveStudentInfo.name}`.replace(/\s+/g, ' ').trim()
          : '수강생');
    const placeholderCtx = {
      profGreeting,
      studentTag,
      studentInfo: effectiveStudentInfo,
      courseName,
      isAnonymous,
      anonymizeUser: sessionUser || studentInfo,
      professorNames: rawProfName ? [rawProfName] : []
    };

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 30000);
        const res = await fetch(`${API_BASE_URL}/ai/refine`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            text: rawText,
            rawContent: rawText,
            title: rawTitle,
            rawTitle: rawTitle,
            courseName: courseName || null,
            professorName: rawProfName || null,
            studentName: isAnonymous ? null : (effectiveStudentInfo?.name || null),
            studentDepartment: isAnonymous ? null : (effectiveStudentInfo?.department || null),
            studentIdentifier: isAnonymous ? null : (effectiveStudentInfo?.studentId || effectiveStudentInfo?.identifier || null),
            category,
            studentInfo: effectiveStudentInfo,
            isFollowUp: Boolean(courseContext?.isFollowUp),
            isAnonymous,
            previousAnswer: courseContext?.previousAnswer || null
          }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await parseJsonSafe(res);
        if (!res.ok || body.success === false) {
          const httpErr = new Error(body.message || 'AI 문장 다듬기 요청에 실패했습니다.');
          httpErr.isHttpError = true;
          httpErr.status = res.status;
          throw httpErr;
        }
        const result = body?.data || body;
        if (result && (result.refined || result.refinedContent)) {
          const rawRefinedTitle =
            result.refinedTitle ||
            (rawTitle
              ? `[${catLabel} 문의] ${rawTitle.replace(/[\[\]!?~]+/g, '').trim()} 관련 질의의 건`
              : `[${catLabel} 문의] 학업 및 학습 관련 질의의 건`);
          const finalRefinedText = replaceAiPlaceholders(result.refined || result.refinedContent, placeholderCtx);
          const syncedHighlights = [];
          let hlCursor = 0;
          if (Array.isArray(result.directiveHighlights)) {
            result.directiveHighlights.forEach((h, hIdx) => {
              const rawRep = typeof h?.replacement === 'string' ? h.replacement : '';
              if (!rawRep) return;
              const rep = replaceAiPlaceholders(rawRep, placeholderCtx);
              if (!rep) return;
              const dir = isAnonymous
                ? replaceAiPlaceholders(String(h?.directive || ''), placeholderCtx)
                : String(h?.directive || '');
              let pos =
                typeof h?.start === 'number' &&
                typeof h?.end === 'number' &&
                h.start >= hlCursor &&
                finalRefinedText.slice(h.start, h.end) === rep
                  ? h.start
                  : finalRefinedText.indexOf(rep, hlCursor);
              if (pos < 0) {
                pos = finalRefinedText.indexOf(rep);
              }
              if (pos >= 0) {
                const endPos = pos + rep.length;
                syncedHighlights.push({
                  index: typeof h?.index === 'number' ? h.index : hIdx,
                  directive: dir,
                  replacement: rep,
                  start: pos,
                  end: endPos
                });
                hlCursor = endPos;
              }
            });
          }
          return {
            original: result.original || result.rawContent || rawText,
            refined: finalRefinedText,
            originalTitle: result.originalTitle || rawTitle || '',
            refinedTitle: replaceAiPlaceholders(rawRefinedTitle, placeholderCtx),
            politenessScore: result.politenessScore || 98,
            changes: result.changes || [
              '원문 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
              '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
              '경어체 및 대학 질의응답 표준 예절 적용'
            ],
            directiveHighlights: syncedHighlights,
            processedDirectivesCount: syncedHighlights.length
          };
        }
      } catch (e) {
        if (e?.isHttpError) throw e;
        if (!import.meta.env.DEV) {
          throw new Error('서버에 연결할 수 없어 AI 문장 다듬기를 실행하지 못했습니다.', { cause: e });
        }
        console.warn('[API] Remote AI refinement failed, using local engine', e);
      }
    }

    const matched = AI_INQUIRY_TEMPLATES.find(
      t => rawText.trim() === t.rawText.trim() || (rawTitle && rawTitle.trim() === t.rawTitle?.trim())
    );
    if (matched) {
      return {
        original: rawText,
        refined: replaceAiPlaceholders(matched.refinedText, placeholderCtx),
        originalTitle: rawTitle || matched.rawTitle || '',
        refinedTitle: replaceAiPlaceholders(
          matched.refinedTitle || `[${catLabel} 문의] 학업 질의의 건`,
          placeholderCtx
        ),
        politenessScore: 98,
        changes: [
          '원문 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
          '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
          '건의 및 배려 표현 추가'
        ]
      };
    }

    const courseLabel = courseName ? `${courseName} 강의를` : '이번 학기 강의를';
    const cleanTitle = (rawTitle || '').replace(/[\[\]!?~]+/g, '').trim();

    const inlineDirectives = [];
    const directiveRegex = /(?:\[\[|［［)\s*([\s\S]{1,1000}?)\s*(?:\]\]|］］)/g;
    let dMatch;
    while ((dMatch = directiveRegex.exec(String(rawText || ''))) !== null && inlineDirectives.length < 10) {
      const inner = dMatch[1].replace(/[\r\n\t]+/g, ' ').replace(/\s{2,}/g, ' ').trim();
      if (inner) inlineDirectives.push(inner);
    }
    const directiveSentences = inlineDirectives.map((d) =>
      fulfillLocalInlineDirective(d, { rawProfName, category })
    );
    const directiveNote = directiveSentences.length > 0
      ? `\n\n${directiveSentences.join('\n\n')}`
      : '';

    const isFollowUp = Boolean(courseContext?.isFollowUp);
    let refinedTitle = '';
    let refinedBody = '';

    if (isFollowUp) {
      refinedTitle = rawTitle || '추가 질문';
      refinedBody = `{{PROFESSOR_TITLE}}, 정성스러운 답변을 남겨주셔서 진심으로 감사드립니다.\n\n남겨주신 답변 내용을 바탕으로 복습하던 중 추가로 명확히 확인하고 싶은 부분이 있어 조심스럽게 후속 질문을 올립니다.${directiveNote}\n\n바쁘신 일정 중에 거듭 여쭈어 송구하오며, 시간 되실 때 가볍게 추가 조언을 주시면 학업에 큰 도움이 될 것 같습니다. 감사합니다.`;
    } else if (category === 'grade' || rawText.includes('성적') || cleanTitle.includes('성적')) {
      refinedTitle = cleanTitle
        ? `[성적 문의] ${cleanTitle} 피드백 및 평가 기준 확인의 건`
        : '[성적 문의] 최종 평가 결과 및 피드백 문의의 건';
      refinedBody = `안녕하십니까, {{PROFESSOR_TITLE}}.\n${courseLabel} 성실히 수강하고 있는 {{STUDENT_INTRO}}입니다. 한 학기 동안 열정적인 가르침을 주셔서 감사드립니다.\n\n다름이 아니라 이번 학기 최종 성적 결과와 관련하여, 스스로 부족했던 부분을 점검하고 향후 학습 방향을 보완하고자 조심스럽게 피드백을 여쭙고자 연락을 드립니다.${directiveNote}\n\n바쁘신 일정 중 시간 되실 때 확인해 주시면 학업에 큰 도움이 될 것 같습니다. 감사합니다.`;
    } else if (
      category === 'assignment' ||
      rawText.includes('과제') ||
      rawText.includes('제출') ||
      cleanTitle.includes('과제')
    ) {
      refinedTitle = cleanTitle
        ? `[과제 문의] ${cleanTitle} 관련 일정 및 세부 사항 문의의 건`
        : '[과제 문의] 과제 수행 관련 일정 및 세부 사항 문의의 건';
      refinedBody = `안녕하십니까, {{PROFESSOR_TITLE}}.\n${courseLabel} 성실히 수강하고 있는 {{STUDENT_INTRO}}입니다. 평소 깊이 있고 유익한 강의를 이끌어 주셔서 감사드립니다.\n\n현재 안내해 주신 과제를 성실히 수행하는 과정에서 핵심 요구사항 및 일정과 관련하여 명확히 확인하고자 조심스럽게 문의를 남깁니다.${directiveNote}\n\n확인해 주시면 성실히 과제를 완수하도록 하겠습니다. 감사합니다.`;
    } else {
      refinedTitle = cleanTitle
        ? `[강의 문의] ${cleanTitle} 개념 이해 및 학습 질의의 건`
        : '[강의 문의] 강의 내용 개념 이해 및 학습 관련 질의의 건';
      refinedBody = `안녕하십니까, {{PROFESSOR_TITLE}}.\n${courseLabel} 수강하고 있는 {{STUDENT_INTRO}}입니다. 늘 알차고 유익한 강의를 준비해 주셔서 감사드립니다.\n\n수업 시간에 다루어 주신 강의 내용 및 핵심 개념을 복습하던 중 개념 정리와 원리 이해에 다소 부족한 부분이 있어 교수님의 고견과 조언을 조심스럽게 구하고자 질문을 올립니다.${directiveNote}\n\n바쁘신 일정 중 시간 내어 주셔서 감사드립니다.`;
    }

    const localRefinedText = replaceAiPlaceholders(refinedBody, placeholderCtx);
    const localHighlights = [];
    let localCursor = 0;
    inlineDirectives.forEach((dir, idx) => {
      const rep = replaceAiPlaceholders(directiveSentences[idx], placeholderCtx);
      const pos = localRefinedText.indexOf(rep, localCursor);
      if (pos >= 0) {
        localHighlights.push({
          index: idx,
          directive: isAnonymous ? replaceAiPlaceholders(dir, placeholderCtx) : dir,
          replacement: rep,
          start: pos,
          end: pos + rep.length
        });
        localCursor = pos + rep.length;
      }
    });

    return {
      original: rawText,
      refined: localRefinedText,
      originalTitle: rawTitle || '',
      refinedTitle: replaceAiPlaceholders(refinedTitle, placeholderCtx),
      politenessScore: 96,
      changes: [
        ...(inlineDirectives.length > 0 ? [`본문 내 [[지시사항]] ${inlineDirectives.length}건 AI 처리 및 대체`] : []),
        '원문 텍스트를 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
        '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
        '경어체 및 대학 질의응답 표준 예절 적용'
      ],
      directiveHighlights: localHighlights,
      processedDirectivesCount: localHighlights.length
    };
  },

  async generateFormulaAI(instruction, displayMode = 'inline') {
    const cleanInstruction = String(instruction || '').trim();
    if (!cleanInstruction) {
      throw new Error('원하는 수식을 설명해 주세요.');
    }
    const mode = displayMode === 'block' ? 'block' : 'inline';

    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/ai/formula`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders(getCurrentUserId())
          },
          body: JSON.stringify({ instruction: cleanInstruction, displayMode: mode })
        }, 25000);

        const body = await parseJsonSafe(res);
        if (!res.ok || body.success === false) {
          const httpErr = new Error(body.message || '수식 생성에 실패했습니다.');
          httpErr.isHttpError = true;
          httpErr.status = res.status;
          throw httpErr;
        }
        return body.data || body;
      } catch (e) {
        if (e?.isHttpError) throw e;
        if (!import.meta.env.DEV) {
          throw new Error('서버에 연결할 수 없어 수식을 생성하지 못했습니다.', { cause: e });
        }
        console.warn('[API] Remote generateFormulaAI failed, using local fallback', e);
      }
    }

    const safeText = cleanInstruction
      .replace(/[\r\n]+/g, ' ')
      .replace(/(\\*)\$/g, (_, bs) => {
        const kept = bs.length % 2 === 1 ? bs.slice(0, -1) : bs;
        return kept ? `${kept} \\text{\\textdollar}` : '\\text{\\textdollar}';
      })
      .slice(0, 120);
    const wrapped = mode === 'block' ? `$$\n${safeText}\n$$` : `$${safeText}$`;
    return {
      instruction: cleanInstruction,
      latex: safeText,
      wrapped,
      displayMode: mode,
      usedAi: false
    };
  },

  async getNotifications(userId) {
    const uid = getCurrentUserId(userId);
    if (!uid) return [];
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/notifications?userId=${uid}`, {
          credentials: 'include',
          headers: getAuthHeaders(uid)
        }, 5000);
        if (res.status === 401 || res.status === 403) {
          return [];
        }
        if (res.ok) {
          const body = await parseJsonSafe(res);
          const list = Array.isArray(body) ? body : body.data;
          if (Array.isArray(list)) return list;
        }
      } catch (e) {
        console.warn('[API] Remote getNotifications failed, using local storage', e);
      }
    }

    const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
    return Array.isArray(notifs) ? notifs.filter(n => n.userId === uid) : [];
  },

  async markNotificationRead(notifId, userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const res = await fetchWithTimeout(`${API_BASE_URL}/notifications/${notifId}/read`, {
          method: 'POST',
          credentials: 'include',
          headers: getAuthHeaders(uid)
        }, 2500);
        if (res.status === 401 || res.status === 403) {
          return false;
        }
      } catch (e) {
        console.warn('[API] Remote markNotificationRead failed, using local storage', e);
      }
    }

    const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
    const target = notifs.find(n => n.id === notifId);
    if (target) {
      target.isRead = true;
      safeSetStorage(STORAGE_KEY_NOTIFS, notifs);
    }
    return true;
  },

  async getVapidPublicKey() {
    if (!USE_REMOTE_BACKEND) return { publicKey: '', enabled: false };
    try {
      const res = await fetchWithTimeout(`${API_BASE_URL}/push/vapid-public-key`, {
        credentials: 'include',
        headers: getAuthHeaders()
      }, 3000);
      if (!res.ok) return { publicKey: '', enabled: false };
      const body = await parseJsonSafe(res);
      return body?.data || { publicKey: '', enabled: false };
    } catch {
      return { publicKey: '', enabled: false };
    }
  },

  async subscribeWebPush(subscriptionPayload) {
    if (!USE_REMOTE_BACKEND) return false;
    try {
      const res = await fetchWithTimeout(`${API_BASE_URL}/push/subscribe`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders()
        },
        body: JSON.stringify(subscriptionPayload)
      }, 3000);
      return res.ok;
    } catch {
      return false;
    }
  }
};
