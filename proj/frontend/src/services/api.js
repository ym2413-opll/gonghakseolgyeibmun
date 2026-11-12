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
const STORAGE_KEY_DEPARTMENTS = 'prof_qa_departments_v3';
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

function getAuthHeaders(overrideUserId) {
  const sessionUser = safeGetStorage(STORAGE_KEY_SESSION, null);
  const uid = overrideUserId || sessionUser?.id || localStorage.getItem('prof_qa_current_user_id') || '';
  const token = localStorage.getItem(STORAGE_KEY_TOKEN) || '';
  const headers = {};
  if (uid) headers['X-User-Id'] = uid;
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
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
  // subtle 미지원 환경용 폴백
  let h = 2166136261;
  const combined = `${actualSaltHex}:${password}`;
  for (let i = 0; i < combined.length; i++) {
    h ^= combined.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return `PBKDF2$65536$${actualSaltHex}$${(h >>> 0).toString(16)}`;
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

function normalizeUserObject(u, allCourses = []) {
  if (!u) return null;
  const roleLower = (u.role || 'student').toLowerCase();
  const isProf = roleLower === 'professor';
  const isAdmin = Boolean(u.isAdmin || roleLower === 'admin');
  return {
    ...u,
    role: isProf ? 'professor' : 'student',
    isAdmin,
    studentId: u.identifier || u.studentId || '',
    identifier: u.identifier || u.studentId || '',
    avatar: u.avatar || (u.name ? u.name.trim().charAt(0) : 'U'),
    courses: Array.isArray(u.courses) && u.courses.length > 0 ? u.courses : allCourses
  };
}

export const api = {
  // ============================================================================
  // 1. 인증 / 회원가입 / 로그인 / 세션 관리 (R3 개편)
  // ============================================================================
  getCurrentSessionUser() {
    return safeGetStorage(STORAGE_KEY_SESSION, null);
  },

  saveSession(user, token) {
    if (user) {
      safeSetStorage(STORAGE_KEY_SESSION, user);
      localStorage.setItem('prof_qa_current_user_id', user.id);
    }
    if (token) {
      localStorage.setItem(STORAGE_KEY_TOKEN, token);
    }
  },

  logout() {
    localStorage.removeItem(STORAGE_KEY_SESSION);
    localStorage.removeItem(STORAGE_KEY_TOKEN);
    localStorage.removeItem('prof_qa_current_user_id');
  },

  async register(payload) {
    const cleanUsername = (payload.username || '').trim().toLowerCase();
    const cleanName = (payload.name || '').trim();
    const cleanDept = (payload.department || '').trim();
    const roleUpper = (payload.role || 'STUDENT').toUpperCase();

    if (!/^[a-zA-Z0-9_.-]{3,30}$/.test(cleanUsername)) {
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
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 3000);
        const res = await fetch(`${API_BASE_URL}/auth/register`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: cleanUsername,
            password: payload.password,
            role: roleUpper,
            name: cleanName,
            department: cleanDept,
            identifier: payload.identifier?.trim() || null,
            email: payload.email?.trim() || null,
            courseIds: Array.isArray(payload.courseIds) ? payload.courseIds : [],
            courseNames: Array.isArray(payload.courseNames) ? payload.courseNames : []
          }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);

        const body = await res.json().catch(() => ({}));
        if (!res.ok || body.success === false) {
          throw new Error(body.message || '회원가입 처리 중 오류가 발생했습니다.');
        }

        const authData = body.data || body;
        const courses = await this.getCourses(authData.user.id);
        const normalized = normalizeUserObject(authData.user, courses);
        this.saveSession(normalized, authData.token);
        return { user: normalized, token: authData.token };
      } catch (e) {
        if (
          e.message &&
          (e.message.includes('이미') ||
            e.message.includes('학번') ||
            e.message.includes('이메일') ||
            e.message.includes('비밀번호') ||
            e.message.includes('아이디') ||
            e.message.includes('학과') ||
            e.message.includes('과목'))
        ) {
          throw e;
        }
        console.warn('[API] Remote register unreachable, falling back to local crypto store:', e);
      }
    }

    // 로컬 암호화 스토리지 폴백
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    if (localUsers.some(u => u.username === cleanUsername)) {
      throw new Error('이미 사용 중인 아이디(ID)입니다.');
    }

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
    const deptObj = depts.find(d => d.name === cleanDept);
    const shortDept = deptObj?.shortName || cleanDept.replace(/학과|학부|전공/g, '').slice(0, 3);

    let identifier = '';
    let email = null;
    let displayTag = '';

    if (roleUpper === 'STUDENT') {
      const sid = (payload.identifier || '').trim();
      if (!/^\d{6,12}$/.test(sid)) {
        throw new Error('학번은 6~12자리 숫자로 입력해주세요. (예: 2026202001)');
      }
      if (localUsers.some(u => u.role === 'student' && u.identifier === sid)) {
        throw new Error('이미 가입된 학번입니다. 중복 가입이 불가능합니다.');
      }
      identifier = sid;
      const yearPrefix = sid.length >= 4 ? sid.slice(2, 4) : '26';
      displayTag = `${yearPrefix}_${shortDept}_${cleanName}`;
    } else {
      const rawEmail = (payload.email || '').trim().toLowerCase();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(rawEmail)) {
        throw new Error('유효한 이메일 주소를 입력해주세요.');
      }
      if (localUsers.some(u => u.email && u.email.toLowerCase() === rawEmail)) {
        throw new Error('이미 등록된 이메일 주소입니다. 중복 가입이 불가능합니다.');
      }
      email = rawEmail;
      identifier = `P${Date.now().toString().slice(-6)}`;
      displayTag = `${cleanDept} ${cleanName}`;
    }

    const passwordHash = await hashPasswordBrowser(payload.password);
    const allCourses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    let userCourses = allCourses;

    if (roleUpper === 'PROFESSOR' && Array.isArray(payload.courseIds) && payload.courseIds.length > 0) {
      userCourses = allCourses.filter(c => payload.courseIds.includes(c.id));
    }

    const newUser = {
      id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      username: cleanUsername,
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
      dndEnabled: false,
      dndStartTime: '22:00',
      dndEndTime: '08:00',
      coProfNotification: true,
      emailEnabled: true,
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
      throw new Error('아이디와 비밀번호를 모두 입력해주세요.');
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2500);
        const res = await fetch(`${API_BASE_URL}/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username: cleanUsername, password }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);

        const body = await res.json().catch(() => ({}));
        if (!res.ok || body.success === false) {
          throw new Error(body.message || '아이디 또는 비밀번호가 일치하지 않습니다.');
        }

        const authData = body.data || body;
        const courses = await this.getCourses(authData.user.id);
        const normalized = normalizeUserObject(authData.user, courses);
        this.saveSession(normalized, authData.token);
        return { user: normalized, token: authData.token };
      } catch (e) {
        if (
          e.message &&
          (e.message.includes('일치하지') || e.message.includes('탈퇴') || e.message.includes('아이디'))
        ) {
          throw e;
        }
        console.warn('[API] Remote login unreachable, checking local crypto store:', e);
      }
    }

    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    const target = localUsers.find(u => u.username === cleanUsername);
    if (!target) {
      throw new Error('아이디 또는 비밀번호가 일치하지 않습니다.');
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
        const timeoutId = setTimeout(() => controller.abort(), 1500);
        const res = await fetch(`${API_BASE_URL}/auth/me`, {
          headers: getAuthHeaders(uid),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
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
        const res = await fetch(`${API_BASE_URL}/courses/departments`, { signal: controller.signal });
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
        const timeoutId = setTimeout(() => controller.abort(), 1500);
        const res = await fetch(`${API_BASE_URL}/courses?all=true`, {
          headers: getAuthHeaders(),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const payload = body?.data || body;
          const list = Array.isArray(payload) ? payload : payload?.courses;
          if (Array.isArray(list) && list.length > 0) {
            const mapped = list.map(c => ({
              id: c.id,
              name: c.name,
              code: c.code,
              semester: c.semester || '2026-1',
              department: c.professors?.[0]?.department || '공과대학',
              group: c.group || 'COMMON',
              section: c.professors?.[0]?.section || '01분반',
              profId: c.assignedProfessorId || c.professors?.[0]?.id || '',
              profName: c.assignedProfessorName || c.professors?.[0]?.name || '담당교수',
              assignedProfessorId: c.assignedProfessorId || c.professors?.[0]?.id || '',
              assignedProfessorName: c.assignedProfessorName || c.professors?.[0]?.name || '담당교수',
              professors: c.professors || [],
              enabledCategories: c.enabledCategories || ['GRADE', 'ASSIGNMENT', 'LECTURE']
            }));
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
        const timeoutId = setTimeout(() => controller.abort(), 1500);
        const res = await fetch(`${API_BASE_URL}/courses`, {
          headers: getAuthHeaders(uid),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : body?.data;
          if (Array.isArray(list) && list.length > 0) {
            return list.map(c => ({
              id: c.id,
              name: c.name,
              code: c.code,
              group: c.group || 'COMMON',
              section: c.professors?.[0]?.section || '01분반',
              profId: c.assignedProfessorId || c.professors?.[0]?.id || '',
              profName: c.assignedProfessorName || c.professors?.[0]?.name || '담당교수',
              professors: c.professors || [],
              enabledCategories: c.enabledCategories || ['GRADE', 'ASSIGNMENT', 'LECTURE']
            }));
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
        const res = await fetch(`${API_BASE_URL}/admin/users`, {
          headers: getAuthHeaders()
        });
        if (res.ok) {
          const body = await res.json();
          const list = body?.data || body;
          if (Array.isArray(list)) return list;
        }
      } catch (e) {
        console.warn('[API] Remote getAdminUsers fallback:', e);
      }
    }
    const localUsers = safeGetStorage(STORAGE_KEY_USERS, []);
    return localUsers.map(({ passwordHash: _ph, ...u }) => u);
  },

  async sanctionUser(targetUserId, sanctionPayload) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetch(`${API_BASE_URL}/admin/users/${targetUserId}/sanction`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(sanctionPayload)
        });
        const body = await res.json().catch(() => ({}));
        if (res.ok && body.data) {
          return body.data;
        }
        if (!res.ok && body.message) {
          throw new Error(body.message);
        }
      } catch (e) {
        if (e.message && e.message.includes('관리자')) throw e;
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
        const res = await fetch(`${API_BASE_URL}/admin/departments`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify(deptPayload)
        });
        const body = await res.json().catch(() => ({}));
        if (res.ok && body.data) {
          return body.data;
        }
        if (!res.ok && body.message) {
          throw new Error(body.message);
        }
      } catch (e) {
        if (e.message && e.message.includes('이미')) throw e;
        console.warn('[API] Remote createDepartment fallback:', e);
      }
    }

    const depts = safeGetStorage(STORAGE_KEY_DEPARTMENTS, INITIAL_DEPARTMENTS);
    if (depts.some(d => d.name === deptPayload.name?.trim())) {
      throw new Error('이미 등록된 학과명입니다.');
    }
    const newDept = {
      id: `dept-${Date.now()}`,
      name: deptPayload.name.trim(),
      shortName: (deptPayload.shortName || deptPayload.name.slice(0, 3)).trim(),
      code: (deptPayload.code || `D${depts.length + 1}`).trim().toUpperCase()
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
        if (!res.ok && body.message) {
          throw new Error(body.message);
        }
      } catch (e) {
        if (e.message && e.message.includes('과목')) throw e;
        console.warn('[API] Remote createCourse fallback:', e);
      }
    }

    const courses = safeGetStorage(STORAGE_KEY_COURSES, INITIAL_COURSES);
    const newCourse = {
      id: `course-${Date.now()}`,
      code: coursePayload.code.trim().toUpperCase(),
      name: coursePayload.name.trim(),
      semester: coursePayload.semester || '2026-1',
      department: coursePayload.department || '소프트웨어학과',
      group: 'COMMON',
      section: '01분반',
      profId: coursePayload.professorId || '22222222-2222-2222-2222-222222222201',
      profName: coursePayload.professorName || '담당교수',
      assignedProfessorId: coursePayload.professorId || '22222222-2222-2222-2222-222222222201',
      assignedProfessorName: coursePayload.professorName || '담당교수',
      professors: []
    };
    courses.push(newCourse);
    safeSetStorage(STORAGE_KEY_COURSES, courses);
    return newCourse;
  },

  async toggleHideQuestion(questionId, isHidden) {
    if (USE_REMOTE_BACKEND) {
      try {
        const res = await fetch(`${API_BASE_URL}/admin/questions/${questionId}/hide`, {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({ isHidden })
        });
        if (res.ok) {
          return true;
        }
      } catch (e) {
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
        const res = await fetch(`${API_BASE_URL}/admin/answers/${answerId}/hide`, {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders()
          },
          body: JSON.stringify({ isHidden })
        });
        if (res.ok) {
          return true;
        }
      } catch (e) {
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
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const data = body?.data || body;
          if (data && typeof data === 'object') return data;
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
          body: JSON.stringify({ userId: uid, ...newSettings }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          if (body?.data) {
            const current = this.getCurrentSessionUser();
            if (current && newSettings.notificationEmail !== undefined) {
              this.saveSession({ ...current, email: newSettings.notificationEmail });
            }
            return body.data;
          }
        }
      } catch (e) {
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
          headers: getAuthHeaders(uid)
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
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1800);
        const res = await fetch(`${API_BASE_URL}/questions`, {
          headers: getAuthHeaders(uid),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const remoteList = Array.isArray(body) ? body : body?.data;
          if (Array.isArray(remoteList)) return remoteList;
        }
      } catch (e) {
        console.warn('[API] Remote getQuestions failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    const isAdmin = Boolean(sessionUser?.isAdmin);
    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return (Array.isArray(questions) ? questions : INITIAL_QUESTIONS).map(q => ({
      ...q,
      title: q.isHidden && !isAdmin ? '[관리자에 의해 가려진 질문입니다]' : q.title,
      content: q.isHidden && !isAdmin ? '관리자에 의해 비표시(가리기) 처리된 게시물입니다.' : q.content,
      realStudentName: isAdmin ? q.realStudentName || q.studentName : null,
      realStudentTag: isAdmin ? q.realStudentTag || q.studentTag : null,
      realStudentIdentifier: isAdmin ? q.realStudentIdentifier || '' : null
    }));
  },

  async getQuestion(questionId, userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND && uid) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1800);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}`, {
          headers: getAuthHeaders(uid),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          return body?.data || body;
        }
      } catch (e) {
        console.warn('[API] Remote getQuestion failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    const isAdmin = Boolean(sessionUser?.isAdmin);
    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const q = questions.find(item => item.id === questionId);
    if (!q) return null;
    return {
      ...q,
      title: q.isHidden && !isAdmin ? '[관리자에 의해 가려진 질문입니다]' : q.title,
      content: q.isHidden && !isAdmin ? '관리자에 의해 비표시(가리기) 처리된 게시물입니다.' : q.content,
      realStudentName: isAdmin ? q.realStudentName || q.studentName : null,
      realStudentTag: isAdmin ? q.realStudentTag || q.studentTag : null,
      realStudentIdentifier: isAdmin ? q.realStudentIdentifier || '' : null,
      answers: (q.answers || []).map(a => ({
        ...a,
        content: a.isHidden && !isAdmin ? '관리자에 의해 가려진 답변입니다.' : a.content
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
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders(uid)
          },
          body: JSON.stringify(questionPayload),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await res.json().catch(() => ({}));
        if (res.ok) {
          return body?.data || body;
        }
        if (res.status === 403 || res.status === 400) {
          throw new Error(body.message || '질문 등록 권한이 제한되어 있습니다.');
        }
      } catch (e) {
        if (e.message && (e.message.includes('제한') || e.message.includes('탈퇴') || e.message.includes('비활성화'))) {
          throw e;
        }
        console.warn('[API] Remote createQuestion failed, using local fallback', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
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
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders(uid)
          },
          body: JSON.stringify(updatedData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          return body?.data || body;
        }
      } catch (e) {
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
          headers: {
            'Content-Type': 'application/json',
            ...getAuthHeaders(uid)
          },
          body: JSON.stringify(answerData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        const body = await res.json().catch(() => ({}));
        if (res.ok) {
          return body?.data || body;
        }
        if (res.status === 403 || res.status === 400) {
          throw new Error(body.message || '답변 작성 권한이 제한되어 있습니다.');
        }
      } catch (e) {
        if (e.message && (e.message.includes('제한') || e.message.includes('탈퇴') || e.message.includes('권한'))) {
          throw e;
        }
        console.warn('[API] Remote createAnswer failed, using local storage', e);
      }
    }

    const sessionUser = this.getCurrentSessionUser();
    if (sessionUser?.answerBanUntil && new Date(sessionUser.answerBanUntil) > new Date()) {
      throw new Error(`현재 답변 작성 제한 상태입니다. (해제 일시: ${sessionUser.answerBanUntil})`);
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const targetQ = questions.find(q => q.id === questionId);
    if (!targetQ) throw new Error('질문을 찾을 수 없습니다.');

    const newAnswer = {
      id: `ans-${Date.now()}`,
      createdAt: new Date().toISOString(),
      isHidden: false,
      ...answerData
    };

    if (!Array.isArray(targetQ.answers)) targetQ.answers = [];
    targetQ.answers.push(newAnswer);
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);
    return newAnswer;
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

  async refineQuestionAI(rawText, category = 'lecture', studentInfo = {}, rawTitle = '') {
    const catLabel = (() => {
      const c = (category || '').toLowerCase();
      if (c === 'grade') return '성적';
      if (c === 'assignment') return '과제';
      return '강의';
    })();

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 3500);
        const res = await fetch(`${API_BASE_URL}/ai/refine`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            text: rawText,
            rawContent: rawText,
            title: rawTitle,
            rawTitle: rawTitle,
            category,
            studentInfo
          }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const result = body?.data || body;
          if (result && (result.refined || result.refinedContent)) {
            return {
              original: result.original || result.rawContent || rawText,
              refined: result.refined || result.refinedContent,
              originalTitle: result.originalTitle || rawTitle || '',
              refinedTitle:
                result.refinedTitle ||
                (rawTitle
                  ? `[${catLabel} 문의] ${rawTitle.replace(/[\[\]!?~]+/g, '').trim()} 관련 질의의 건`
                  : `[${catLabel} 문의] 학업 및 학습 관련 질의의 건`),
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
        refined: matched.refinedText,
        originalTitle: rawTitle || matched.rawTitle || '',
        refinedTitle: matched.refinedTitle || `[${catLabel} 문의] 학업 질의의 건`,
        politenessScore: 98,
        changes: [
          '원문 문장을 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
          '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
          '건의 및 배려 표현 추가'
        ]
      };
    }

    const studentTag = studentInfo.name
      ? `${studentInfo.department || '소프트웨어학과'} ${studentInfo.studentId || ''} ${studentInfo.name}`
      : '수강생';
    const cleanTitle = (rawTitle || '').replace(/[\[\]!?~]+/g, '').trim();

    let refinedTitle = '';
    let refinedBody = '';

    if (category === 'grade' || rawText.includes('성적') || cleanTitle.includes('성적')) {
      refinedTitle = cleanTitle
        ? `[성적 문의] ${cleanTitle} 피드백 및 평가 기준 확인의 건`
        : '[성적 문의] 최종 평가 결과 및 피드백 문의의 건';
      refinedBody = `안녕하십니까, 교수님.\n이번 학기 강의를 성실히 수강하고 있는 ${studentTag}입니다. 한 학기 동안 열정적인 가르침을 주셔서 감사드립니다.\n\n다름이 아니라 이번 학기 최종 성적 결과와 관련하여, 스스로 부족했던 부분을 점검하고 향후 학습 방향을 보완하고자 조심스럽게 피드백을 여쭙고자 연락을 드립니다.\n\n바쁘신 일정 중 시간 되실 때 확인해 주시면 학업에 큰 도움이 될 것 같습니다. 감사합니다.`;
    } else if (
      category === 'assignment' ||
      rawText.includes('과제') ||
      rawText.includes('제출') ||
      cleanTitle.includes('과제')
    ) {
      refinedTitle = cleanTitle
        ? `[과제 문의] ${cleanTitle} 관련 일정 및 세부 사항 문의의 건`
        : '[과제 문의] 과제 수행 관련 일정 및 세부 사항 문의의 건';
      refinedBody = `안녕하십니까 교수님.\n강의를 성실히 수강하고 있는 ${studentTag}입니다. 평소 깊이 있고 유익한 강의를 이끌어 주셔서 감사드립니다.\n\n현재 안내해 주신 과제를 성실히 수행하는 과정에서 핵심 요구사항 및 일정과 관련하여 명확히 확인하고자 조심스럽게 문의를 남깁니다.\n\n확인해 주시면 성실히 과제를 완수하도록 하겠습니다. 감사합니다.`;
    } else {
      refinedTitle = cleanTitle
        ? `[강의 문의] ${cleanTitle} 개념 이해 및 학습 질의의 건`
        : '[강의 문의] 강의 내용 개념 이해 및 학습 관련 질의의 건';
      refinedBody = `안녕하십니까 교수님.\n이번 학기 수업을 수강하고 있는 ${studentTag}입니다. 늘 알차고 유익한 강의를 준비해 주셔서 감사드립니다.\n\n수업 시간에 다루어 주신 강의 내용 및 핵심 개념을 복습하던 중 개념 정리와 원리 이해에 다소 부족한 부분이 있어 교수님의 고견과 조언을 조심스럽게 구하고자 질문을 올립니다.\n\n바쁘신 일정 중 시간 내어 주셔서 감사드립니다.`;
    }

    return {
      original: rawText,
      refined: refinedBody,
      originalTitle: rawTitle || '',
      refinedTitle,
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
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/notifications?userId=${uid}`, {
          headers: getAuthHeaders(uid),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : body?.data;
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
        await fetch(`${API_BASE_URL}/notifications/${notifId}/read`, {
          method: 'POST',
          headers: getAuthHeaders(uid)
        });
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
