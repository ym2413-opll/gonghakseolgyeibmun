// src/services/api.js
// 광운대학교 학업 Q&A 포럼 Ktor 백엔드 API 통신 모듈 (R3 Overhaul: 인증/해시/관리모드/학과·과목 동적 관리/이메일 알림)

import {
  INITIAL_DEPARTMENTS,
  INITIAL_COURSES,
  INITIAL_QUESTIONS,
  INITIAL_NOTIFICATIONS,
  AI_INQUIRY_TEMPLATES
} from './mockData';
import { calculateSimilarQuestions } from '../utils/wordSimilarity';
import { generateSummaryTitle } from '../utils/textSummary';

const STORAGE_KEY_QUESTIONS = 'prof_qa_questions_v3';
const STORAGE_KEY_SETTINGS = 'prof_qa_settings_v3';
const STORAGE_KEY_NOTIFS = 'prof_qa_notifications_v3';
const STORAGE_KEY_USERS = 'prof_qa_registered_users_v3';
const STORAGE_KEY_SESSION = 'prof_qa_session_user_v3';
const STORAGE_KEY_TOKEN = 'prof_qa_auth_token_v3';
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
  { profGreeting = '교수님', studentTag = '수강생', studentInfo = {}, courseName = '' } = {}
) {
  if (!text) return '';
  const cleanProf = stripAiZeroWidthAndControl(profGreeting).trim() || '교수님';
  const cleanTag = stripAiZeroWidthAndControl(studentTag).trim() || '수강생';
  const studentName = stripAiZeroWidthAndControl(studentInfo?.name || '').trim() || '학생';
  const studentId = stripAiZeroWidthAndControl(studentInfo?.studentId || studentInfo?.identifier || '').trim();
  const studentDept = stripAiZeroWidthAndControl(studentInfo?.department || '').trim();
  const courseDisplay = stripAiZeroWidthAndControl(courseName || '').trim() || '이번 학기 강의';

  // Single-pass simultaneous replacement prevents second-order placeholder expansion
  return stripAiZeroWidthAndControl(text)
    .replace(
      COMBINED_AI_PLACEHOLDER_REGEX,
      (match, profHit, studentBeforeProfHit, introHit, nameHit, idHit, deptHit, courseHit, piiHit) => {
        if (profHit || studentBeforeProfHit) return cleanProf;
        if (introHit) return cleanTag;
        if (nameHit) return studentName;
        if (idHit) return studentId;
        if (deptHit) return studentDept;
        if (courseHit) return courseDisplay;
        if (piiHit) return '';
        return match;
      }
    )
    .replace(/교수님 교수님/g, '교수님')
    .replace(/교수님님/g, '교수님')
    .replace(/ {2,}/g, ' ')
    .trim();
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

// Web Crypto API PBKDF2-HMAC-SHA256 해시 유틸리티 (오프라인/로컬 폴백 시에도 암호화 해시 보장)
async function hashPasswordBrowser(password, saltHex = null) {
  const enc = new TextEncoder();
  let saltBytes;
  if (saltHex) {
    saltBytes = new Uint8Array(saltHex.match(/.{1,2}/g).map(byte => parseInt(byte, 16)));
  } else {
    saltBytes = new Uint8Array(16);
    window.crypto.getRandomValues(saltBytes);
  }
  const actualSaltHex = Array.from(saltBytes).map(b => b.toString(16).padStart(2, '0')).join('');

  if (window.crypto?.subtle) {
    const keyMaterial = await window.crypto.subtle.importKey(
      'raw',
      enc.encode(password),
      { name: 'PBKDF2' },
      false,
      ['deriveBits']
    );
    const derivedBits = await window.crypto.subtle.deriveBits(
      {
        name: 'PBKDF2',
        salt: saltBytes,
        iterations: 65536,
        hash: 'SHA-256'
      },
      keyMaterial,
      256
    );
    const hashArray = Array.from(new Uint8Array(derivedBits));
    const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    return `PBKDF2$65536$${actualSaltHex}$${hashHex}`;
  }
  throw new Error('안전한 암호화 Web Crypto API를 사용할 수 없는 브라우저 환경입니다.');
}

async function verifyPasswordBrowser(password, storedHash) {
  if (!storedHash || !storedHash.startsWith('PBKDF2$')) return false;
  const parts = storedHash.split('$');
  if (parts.length !== 4) return false;
  const saltHex = parts[2];
  const computed = await hashPasswordBrowser(password, saltHex);
  return computed === storedHash;
}

function initializeStorage() {
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
  const professors = Array.isArray(c.professors) ? c.professors : [];
  const rawAssignedId = c.assignedProfessorId || c.profId || '';
  const matchedProf = rawAssignedId
    ? professors.find((p) => p.id === rawAssignedId)
    : null;
  const primaryProf = matchedProf || professors[0] || null;
  const explicitProfName = [c.assignedProfessorName, c.profName]
    .map((n) => (n || '').trim())
    .find((n) => n && n !== '미배정');
  const resolvedProfId = rawAssignedId || primaryProf?.id || '';
  const resolvedProfName = explicitProfName || primaryProf?.name || '미배정';
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

  async getNextStudentId({ year = 2026, department = '', isForeign = false, random = false } = {}) {
    const safeYear = Math.min(2099, Math.max(1900, Number(year) || 2026));
    if (USE_REMOTE_BACKEND) {
      try {
        const params = new URLSearchParams({
          year: String(safeYear),
          department: department || '',
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

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
    const deptObj = depts.find(d => d.name === department) || INITIAL_DEPARTMENTS.find(d => d.name === department);
    const deptCode = /^[1-9](?:0[1-9]|[1-9]\d)$/.test(String(deptObj?.code || '')) ? String(deptObj.code) : '101';
    const prefix = `${safeYear}${deptCode}`;
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const serials = localUsers
      .map(u => String(u.identifier || ''))
      .filter(id => id.length === 10 && id.startsWith(prefix))
      .map(id => Number(id.slice(7, 10)))
      .filter(n => !Number.isNaN(n));
    let nextNum;
    if (random) {
      const start = isForeign ? 501 : 1;
      const end = isForeign ? 999 : 500;
      const usedSet = new Set(serials);
      const candidates = [];
      for (let i = start; i <= end; i += 1) {
        if (!usedSet.has(i)) candidates.push(i);
      }
      if (candidates.length === 0) {
        throw new Error('사용 가능한 학번 순번이 남아있지 않습니다.');
      }
      nextNum = candidates[Math.floor(Math.random() * candidates.length)];
    } else {
      nextNum = isForeign
        ? Math.max(500, ...serials.filter(n => n >= 501 && n <= 999)) + 1
        : Math.max(0, ...serials.filter(n => n >= 1 && n <= 500)) + 1;
    }
    const serial = String(nextNum).padStart(3, '0');
    return {
      studentId: `${prefix}${serial}`,
      year: safeYear,
      departmentCode: deptCode,
      serial,
      isForeign: Boolean(isForeign)
    };
  },

  async logout(options = {}) {
    const headers = getAuthHeaders();
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
      if (!isAutoId && !/^(19\d\d|20\d\d)[1-9](?:0[1-9]|[1-9]\d)(?:00[1-9]|0[1-9]\d|[1-9]\d\d)$/.test(rawIdentifier)) {
        throw new Error('학번은 (연도 4자리)(단과대 1~9 + 학과 01~99)(순번 3자리: 001~999) 형식의 10자리 숫자로 입력해주세요.');
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

    // 로컬 암호화 스토리지 폴백
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    if (cleanUsername && localUsers.some(u => u.username === cleanUsername || (u.role === 'student' && u.identifier === cleanUsername))) {
      throw new Error(roleUpper === 'STUDENT' ? '이미 가입된 학번입니다.' : '이미 사용 중인 아이디(ID)입니다.');
    }

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
    const deptObj = depts.find(d => d.name === cleanDept) || INITIAL_DEPARTMENTS.find(d => d.name === cleanDept);
    const shortDept = deptObj?.shortName || cleanDept.replace(/학과|학부|전공/g, '').slice(0, 3);

    let identifier = '';
    let email = null;
    let displayTag = '';

    if (roleUpper === 'STUDENT') {
      let sid = rawIdentifier;
      if (isAutoId) {
        const preview = await this.getNextStudentId({
          year: payload.entryYear || 2026,
          department: cleanDept,
          isForeign: Boolean(payload.isForeign)
        });
        sid = preview.studentId;
      }
      if (!/^(19\d\d|20\d\d)[1-9](?:0[1-9]|[1-9]\d)(?:00[1-9]|0[1-9]\d|[1-9]\d\d)$/.test(sid)) {
        throw new Error('학번은 (연도 4자리)(단과대 1~9 + 학과 01~99)(순번 3자리: 001~999) 형식의 10자리 숫자로 입력해주세요.');
      }
      if (localUsers.some(u => u.role === 'student' && u.identifier === sid)) {
        throw new Error('이미 가입된 학번입니다. 중복 가입이 불가능합니다.');
      }
      identifier = sid;
      const yearPrefix = sid.slice(2, 4);
      displayTag = `${yearPrefix}_${shortDept}_${cleanName}`;
    } else {
      const rawEmail = (payload.email || '').trim().toLowerCase();
      if (rawEmail) {
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(rawEmail)) {
          throw new Error('유효한 이메일 주소를 입력해주세요.');
        }
        if (localUsers.some(u => u.email && u.email.toLowerCase() === rawEmail)) {
          throw new Error('이미 등록된 이메일 주소입니다. 중복 가입이 불가능합니다.');
        }
        email = rawEmail;
      }
      identifier = `P${Date.now().toString().slice(-6)}`;
      displayTag = `${cleanDept} ${cleanName}`;
    }

    const passwordHash = await hashPasswordBrowser(payload.password);
    const allCourses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    let userCourses = allCourses;

    if (Array.isArray(payload.courseIds) && payload.courseIds.length > 0) {
      userCourses = allCourses.filter(c => payload.courseIds.includes(c.id));
    }

    const newUser = {
      id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      username: cleanUsername || identifier,
      passwordHash,
      role: roleUpper === 'PROFESSOR' ? 'professor' : 'student',
      name: cleanName,
      identifier,
      studentId: identifier,
      department: cleanDept,
      email,
      displayTag,
      avatar: cleanName.charAt(0),
      isAdmin: false,
      isWithdrawn: false,
      questionBanUntil: null,
      answerBanUntil: null,
      courses: userCourses.map(c => ({
        id: c.id,
        name: c.name,
        code: c.code,
        group: c.group || 'COMMON',
        section: '01분반',
        profId: c.assignedProfessorId || '',
        profName: c.assignedProfessorName || '담당교수'
      }))
    };

    localUsers.push(newUser);
    safeSetStorage(STORAGE_KEY_USERS, localUsers);

    const settingsMap = safeGetStorage(STORAGE_KEY_SETTINGS, {});
    settingsMap[newUser.id] = {
      theme: 'light',
      fontSize: 'md',
      helpLevel: 'SOME',
      dndEnabled: false,
      dndStartTime: '22:00',
      dndEndTime: '08:00',
      coProfNotification: true,
      emailEnabled: Boolean(email),
      notificationEmail: email || '',
      activeCategories: { grade: true, assignment: true, lecture: true }
    };
    safeSetStorage(STORAGE_KEY_SETTINGS, settingsMap);

    const { passwordHash: _ph, ...safeUser } = newUser;
    const token = `local-token-${newUser.id}`;
    this.saveSession(safeUser, token);
    return { user: safeUser, token };
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

    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const target = localUsers.find(
      u =>
        (u.username || '').trim().toLowerCase() === cleanUsername ||
        (u.role === 'student' && (u.identifier || '').trim().toLowerCase() === cleanUsername)
    );
    if (!target) {
      throw new Error('아이디(학번) 또는 비밀번호가 일치하지 않습니다.');
    }
    if (target.isWithdrawn) {
      throw new Error('관리자에 의해 탈퇴(비활성화) 처리된 계정입니다.');
    }
    const valid = await verifyPasswordBrowser(password, target.passwordHash);
    if (!valid) {
      throw new Error('아이디 또는 비밀번호가 일치하지 않습니다.');
    }

    const { passwordHash: _ph, ...safeUser } = target;
    const token = `local-token-${target.id}`;
    this.saveSession(safeUser, token);
    return { user: safeUser, token };
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
            safeSetStorage(STORAGE_KEY_DEPARTMENTS, list);
            return list;
          }
        }
      } catch (e) {
        console.warn('[API] Remote getDepartments failed, using local fallback', e);
      }
    }
    return safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
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
          if (Array.isArray(list) && list.length > 0) {
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

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
    if (depts.some(d => d.name === deptPayload.name?.trim())) {
      throw new Error('이미 등록된 학과명입니다.');
    }
    const collegeName = (deptPayload.college || '광운대학교').trim();
    const knownCollegeDigits = {
      '인공지능융합대학': 1,
      '전자정보공과대학': 2,
      '공과대학': 3,
      '자연과학대학': 4,
      '인문사회과학대학': 5,
      '경영대학': 6,
      '정책법학대학': 7,
      '참빛인재대학': 8
    };
    let assignedCode = (deptPayload.code || '').trim();
    if (assignedCode) {
      if (!/^[1-9](?:0[1-9]|[1-9]\d)$/.test(assignedCode)) {
        throw new Error('학과 고유번호는 (단과대 1~9)(학과 01~99) 형식의 3자리 숫자여야 합니다.');
      }
      if (depts.some(d => String(d.code) === assignedCode)) {
        throw new Error(`이미 사용 중인 학과 고유번호입니다: ${assignedCode}`);
      }
    } else {
      const existingCollegeDept = depts.find(
        d => d.college === collegeName && /^[1-9](?:0[1-9]|[1-9]\d)$/.test(String(d.code || ''))
      );
      const usedDigits = new Set(
        depts
          .map(d => String(d.code || ''))
          .filter(c => /^[1-9]\d{2}$/.test(c))
          .map(c => Number(c[0]))
      );
      const collegeDigit = knownCollegeDigits[collegeName]
        ?? (existingCollegeDept ? Number(String(existingCollegeDept.code)[0]) : null)
        ?? ([1, 2, 3, 4, 5, 6, 7, 8, 9].find(n => !usedDigits.has(n)) ?? 9);
      const maxDeptIdx = depts
        .map(d => String(d.code || ''))
        .filter(c => /^[1-9](?:0[1-9]|[1-9]\d)$/.test(c) && Number(c[0]) === collegeDigit)
        .reduce((max, c) => Math.max(max, Number(c.slice(1))), 0);
      assignedCode = `${collegeDigit}${String(Math.min(99, maxDeptIdx + 1)).padStart(2, '0')}`;
    }
    const newDept = {
      id: `dept-${Date.now()}`,
      name: deptPayload.name.trim(),
      shortName: (deptPayload.shortName || deptPayload.name.slice(0, 3)).trim(),
      college: collegeName,
      code: assignedCode
    };
    depts.push(newDept);
    safeSetStorage(STORAGE_KEY_DEPARTMENTS, depts);
    return newDept;
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
          return body.data;
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
    const scopeType = (coursePayload.scopeType || 'DEPARTMENT').toUpperCase();
    const resolvedCollege = scopeType === 'ALL'
      ? '전체'
      : (coursePayload.college || '');
    const resolvedDepartment = scopeType === 'ALL'
      ? '전체'
      : scopeType === 'COLLEGE'
        ? (resolvedCollege ? `${resolvedCollege} 전체` : '단과대 전체')
        : (coursePayload.department || '소프트웨어학과');
    const newCourse = {
      id: `course-${Date.now()}`,
      code: coursePayload.code.trim().toUpperCase(),
      name: coursePayload.name.trim(),
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
        throw new Error(body.message || '스레드 상태 변경에 실패했습니다.');
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
      .map(q => ({
        ...q,
        title: q.isHidden && !isAdmin ? '[관리자에 의해 가려진 질문입니다]' : q.title,
        content: q.isHidden && !isAdmin ? '관리자에 의해 비표시(가리기) 처리된 게시물입니다.' : q.content,
        originalContent: isProfessor ? '' : q.originalContent,
        rawContent: isProfessor ? '' : q.rawContent,
        answers: (q.answers || []).map(a => ({
          ...a,
          originalContent: isProfessor ? '' : a.originalContent,
          rawContent: isProfessor ? '' : a.rawContent
        })),
        canDelete: !q.isDeleted && sessionUser?.role === 'student' && q.studentId === sessionUser?.id,
        realStudentName: isAdmin ? q.realStudentName || q.studentName : null,
        realStudentTag: isAdmin ? q.realStudentTag || q.studentTag : null,
        realStudentIdentifier: isAdmin ? q.realStudentIdentifier || '' : null
      }));
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
    return {
      ...q,
      title: q.isHidden && !isAdmin ? '[관리자에 의해 가려진 질문입니다]' : q.title,
      content: q.isHidden && !isAdmin ? '관리자에 의해 비표시(가리기) 처리된 게시물입니다.' : q.content,
      originalContent: isProfessor ? '' : q.originalContent,
      rawContent: isProfessor ? '' : q.rawContent,
      canDelete: !q.isDeleted && sessionUser?.role === 'student' && q.studentId === sessionUser?.id,
      realStudentName: isAdmin ? q.realStudentName || q.studentName : null,
      realStudentTag: isAdmin ? q.realStudentTag || q.studentTag : null,
      realStudentIdentifier: isAdmin ? q.realStudentIdentifier || '' : null,
      answers: (q.answers || []).map(a => ({
        ...a,
        content: a.isHidden && !isAdmin ? '관리자에 의해 가려진 답변입니다.' : a.content,
        originalContent: isProfessor ? '' : a.originalContent,
        rawContent: isProfessor ? '' : a.rawContent
      }))
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
    const summaryTitle = questionData.title?.trim() || generateSummaryTitle(questionData.content);
    const questionPayload = {
      ...questionData,
      title: summaryTitle
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

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const newQuestion = {
      id: `q-${Date.now()}`,
      createdAt: new Date().toISOString(),
      answers: [],
      isHidden: false,
      realStudentName: sessionUser?.name || questionData.studentName,
      realStudentTag: sessionUser?.displayTag || questionData.studentTag,
      realStudentIdentifier: sessionUser?.identifier || '',
      ...questionPayload
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
        console.warn('[API] Remote updateQuestion failed, using local fallback', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const index = questions.findIndex(q => q.id === questionId);
    if (index === -1) throw new Error('질문을 찾을 수 없습니다.');

    const updated = {
      ...questions[index],
      ...updatedData,
      isEdited: true,
      updatedAt: new Date().toISOString()
    };
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

    const authorLabel = sessionUser.role === 'professor'
      ? `${sessionUser.name} 교수님`
      : sessionUser.isAdmin
        ? `관리자 (${sessionUser.name})님`
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
        title: `[${targetQ.courseName || '강의'}] 스레드에 새로운 답변이 등록되었습니다.`,
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
            hasAiRefined: Boolean(followUpData?.hasAiRefined)
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

    const trimmedContent = (followUpData?.content || '').trim();
    const trimmedOriginal = (followUpData?.originalContent || '').trim() || trimmedContent;
    const newFollowUp = {
      id: `followup-${Date.now()}`,
      questionId,
      authorId: sessionUser.id,
      authorName: targetQ.isAnonymous ? `${targetQ.courseName || '강의'} 수강생` : `${sessionUser.displayTag || sessionUser.name} (나)`,
      authorRole: 'STUDENT',
      authorTitle: '질문자 추가 질문',
      isAssignedProfessor: false,
      isPrimaryProf: false,
      content: trimmedContent,
      originalContent: trimmedOriginal,
      createdAt: new Date().toISOString(),
      isHidden: false
    };

    if (!Array.isArray(targetQ.answers)) targetQ.answers = [];
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
          : `[${targetQ.courseName || '강의'}] 답변 참여 스레드에 추가 질문(꼬리질문)이 등록되었습니다.`,
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
    const studentTag = studentInfo.name
      ? `${studentInfo.department || '학부'} ${studentInfo.studentId || ''} ${studentInfo.name}`.replace(/\s+/g, ' ').trim()
      : '수강생';
    const placeholderCtx = { profGreeting, studentTag, studentInfo, courseName };

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 15000);
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
            studentName: studentInfo?.name || null,
            studentDepartment: studentInfo?.department || null,
            studentIdentifier: studentInfo?.studentId || studentInfo?.identifier || null,
            category,
            studentInfo,
            isFollowUp: Boolean(courseContext?.isFollowUp),
            previousAnswer: courseContext?.previousAnswer || null
          }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const result = body?.data || body;
          if (result && (result.refined || result.refinedContent)) {
            const rawRefinedTitle =
              result.refinedTitle ||
              (rawTitle
                ? `[${catLabel} 문의] ${rawTitle.replace(/[\[\]!?~]+/g, '').trim()} 관련 질의의 건`
                : `[${catLabel} 문의] 학업 및 학습 관련 질의의 건`);
            return {
              original: result.original || result.rawContent || rawText,
              refined: replaceAiPlaceholders(result.refined || result.refinedContent, placeholderCtx),
              originalTitle: result.originalTitle || rawTitle || '',
              refinedTitle: replaceAiPlaceholders(rawRefinedTitle, placeholderCtx),
              politenessScore: result.politenessScore || 98,
              changes: result.changes || [
                '원문 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
                '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
                '경어체 및 대학 질의응답 표준 예절 적용'
              ]
            };
          }
        }
      } catch (e) {
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

    const isFollowUp = Boolean(courseContext?.isFollowUp);
    let refinedTitle = '';
    let refinedBody = '';

    if (isFollowUp) {
      refinedTitle = rawTitle || '추가 질문';
      refinedBody = `{{PROFESSOR_TITLE}}, 정성스러운 답변을 남겨주셔서 진심으로 감사드립니다.\n\n남겨주신 답변 내용을 바탕으로 복습하던 중 추가로 명확히 확인하고 싶은 부분이 있어 조심스럽게 후속 질문을 올립니다.\n\n바쁘신 일정 중에 거듭 여쭈어 송구하오며, 시간 되실 때 가볍게 추가 조언을 주시면 학업에 큰 도움이 될 것 같습니다. 감사합니다.`;
    } else if (category === 'grade' || rawText.includes('성적') || cleanTitle.includes('성적')) {
      refinedTitle = cleanTitle
        ? `[성적 문의] ${cleanTitle} 피드백 및 평가 기준 확인의 건`
        : '[성적 문의] 최종 평가 결과 및 피드백 문의의 건';
      refinedBody = `안녕하십니까, {{PROFESSOR_TITLE}}.\n${courseLabel} 성실히 수강하고 있는 {{STUDENT_INTRO}}입니다. 한 학기 동안 열정적인 가르침을 주셔서 감사드립니다.\n\n다름이 아니라 이번 학기 최종 성적 결과와 관련하여, 스스로 부족했던 부분을 점검하고 향후 학습 방향을 보완하고자 조심스럽게 피드백을 여쭙고자 연락을 드립니다.\n\n바쁘신 일정 중 시간 되실 때 확인해 주시면 학업에 큰 도움이 될 것 같습니다. 감사합니다.`;
    } else if (
      category === 'assignment' ||
      rawText.includes('과제') ||
      rawText.includes('제출') ||
      cleanTitle.includes('과제')
    ) {
      refinedTitle = cleanTitle
        ? `[과제 문의] ${cleanTitle} 관련 일정 및 세부 사항 문의의 건`
        : '[과제 문의] 과제 수행 관련 일정 및 세부 사항 문의의 건';
      refinedBody = `안녕하십니까, {{PROFESSOR_TITLE}}.\n${courseLabel} 성실히 수강하고 있는 {{STUDENT_INTRO}}입니다. 평소 깊이 있고 유익한 강의를 이끌어 주셔서 감사드립니다.\n\n현재 안내해 주신 과제를 성실히 수행하는 과정에서 핵심 요구사항 및 일정과 관련하여 명확히 확인하고자 조심스럽게 문의를 남깁니다.\n\n확인해 주시면 성실히 과제를 완수하도록 하겠습니다. 감사합니다.`;
    } else {
      refinedTitle = cleanTitle
        ? `[강의 문의] ${cleanTitle} 개념 이해 및 학습 질의의 건`
        : '[강의 문의] 강의 내용 개념 이해 및 학습 관련 질의의 건';
      refinedBody = `안녕하십니까, {{PROFESSOR_TITLE}}.\n${courseLabel} 수강하고 있는 {{STUDENT_INTRO}}입니다. 늘 알차고 유익한 강의를 준비해 주셔서 감사드립니다.\n\n수업 시간에 다루어 주신 강의 내용 및 핵심 개념을 복습하던 중 개념 정리와 원리 이해에 다소 부족한 부분이 있어 교수님의 고견과 조언을 조심스럽게 구하고자 질문을 올립니다.\n\n바쁘신 일정 중 시간 내어 주셔서 감사드립니다.`;
    }

    return {
      original: rawText,
      refined: replaceAiPlaceholders(refinedBody, placeholderCtx),
      originalTitle: rawTitle || '',
      refinedTitle: replaceAiPlaceholders(refinedTitle, placeholderCtx),
      politenessScore: 96,
      changes: [
        '원문 텍스트를 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
        '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
        '경어체 및 대학 질의응답 표준 예절 적용'
      ]
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
  }
};
