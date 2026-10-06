// src/services/api.js
// 백엔드 Ktor API 통신 모듈 (실제 서버 연동 및 전시용 Mock 백엔드 Fallback 지원)

import { PRESET_USERS, INITIAL_QUESTIONS, INITIAL_NOTIFICATIONS, AI_DEMO_TEMPLATES } from './mockData';
import { calculateSimilarQuestions } from '../utils/wordSimilarity';

const STORAGE_KEY_QUESTIONS = 'prof_qa_questions_v2';
const STORAGE_KEY_SETTINGS = 'prof_qa_settings_v2';
const STORAGE_KEY_NOTIFS = 'prof_qa_notifications_v2';

// VITE_API_BASE_URL 환경변수가 있을 때만 실 서버로 요청, 없으면 안정적인 로컬 Mock 엔진 작동
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '';
const USE_REMOTE_BACKEND = Boolean(API_BASE_URL);

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

// 로컬 스토리지 초기화 및 무결성 검증
function initializeStorage() {
  const existingQuestions = safeGetStorage(STORAGE_KEY_QUESTIONS, null);
  if (!Array.isArray(existingQuestions) || existingQuestions.length === 0) {
    safeSetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
  }

  const existingNotifs = safeGetStorage(STORAGE_KEY_NOTIFS, null);
  if (!Array.isArray(existingNotifs) || existingNotifs.length === 0) {
    safeSetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
  }

  const existingSettings = safeGetStorage(STORAGE_KEY_SETTINGS, null);
  if (!existingSettings || typeof existingSettings !== 'object') {
    const defaultSettings = {};
    PRESET_USERS.forEach(u => {
      defaultSettings[u.id] = {
        theme: 'light',
        fontSize: 'md',
        dndEnabled: false,
        dndStartTime: '22:00',
        dndEndTime: '08:00',
        coProfNotification: true,
        activeCategories: { grade: true, assignment: true, lecture: true },
        ...(u.settings || {})
      };
    });
    safeSetStorage(STORAGE_KEY_SETTINGS, defaultSettings);
  }
}

initializeStorage();

export const api = {
  // 1. 사용자 관련
  async getPresetUsers() {
    return PRESET_USERS;
  },

  async getUser(userId) {
    return PRESET_USERS.find(u => u.id === userId) || PRESET_USERS[0];
  },

  // 2. 환경 설정 관련 (테마, 글자크기, 방해금지 시간대, 카테고리 활성화, 다른교수 알림 수신여부)
  async getSettings(userId) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        const res = await fetch(`${API_BASE_URL}/settings?userId=${userId}`, { signal: controller.signal });
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
    const userPreset = PRESET_USERS.find(u => u.id === userId);

    return {
      theme: 'light',
      fontSize: 'md',
      dndEnabled: false,
      dndStartTime: '22:00',
      dndEndTime: '08:00',
      coProfNotification: true,
      activeCategories: { grade: true, assignment: true, lecture: true },
      ...(userPreset?.settings || {}),
      ...(settingsMap[userId] || {})
    };
  },

  async updateSettings(userId, newSettings) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        await fetch(`${API_BASE_URL}/settings`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': userId
          },
          body: JSON.stringify({ userId, ...newSettings }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
      } catch (e) {
        console.warn('[API] Remote updateSettings failed, using local storage', e);
      }
    }

    const settingsMap = safeGetStorage(STORAGE_KEY_SETTINGS, {});
    settingsMap[userId] = { ...(settingsMap[userId] || {}), ...newSettings };
    safeSetStorage(STORAGE_KEY_SETTINGS, settingsMap);
    return settingsMap[userId];
  },

  // 3. 질문 관련 (목록, 상세, 등록)
  async getQuestions() {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        const res = await fetch(`${API_BASE_URL}/questions`, { signal: controller.signal });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const remoteList = Array.isArray(body) ? body : (Array.isArray(body?.data) ? body.data : null);
          if (Array.isArray(remoteList)) return remoteList;
        }
      } catch (e) {
        console.warn('[API] Remote getQuestions failed, using local storage', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return Array.isArray(questions) ? questions : INITIAL_QUESTIONS;
  },

  async getQuestion(questionId) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}`, { signal: controller.signal });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          return body?.data || body;
        }
      } catch (e) {
        console.warn('[API] Remote getQuestion failed, using local storage', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return questions.find(q => q.id === questionId) || null;
  },

  async createQuestion(questionData) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/questions`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': questionData.studentId || ''
          },
          body: JSON.stringify(questionData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          return body?.data || body;
        }
      } catch (e) {
        console.warn('[API] Remote createQuestion failed, using local fallback', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const newQuestion = {
      id: `q-${Date.now()}`,
      createdAt: new Date().toISOString(),
      answers: [],
      ...questionData
    };
    questions.unshift(newQuestion);
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);

    // 교수 알림 자동 생성
    const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
    // 1) 담당 교수 알림
    notifs.unshift({
      id: `notif-${Date.now()}-1`,
      userId: questionData.profId,
      type: 'new_question',
      title: '새 질문이 도착했습니다',
      message: `[${questionData.courseName}] ${questionData.isAnonymous ? '익명 수강생' : questionData.studentName}님의 질문: ${questionData.title}`,
      questionId: newQuestion.id,
      isRead: false,
      createdAt: new Date().toISOString()
    });

    // 2) 만약 강의 카테고리라면 같은 과목 또는 동일 그룹의 다른 교수에게도 알림 등록
    if (questionData.category === 'lecture') {
      const otherProf = PRESET_USERS.find(
        u =>
          u.role === 'professor' &&
          u.id !== questionData.profId &&
          u.courses.some(
            c =>
              c.name === questionData.courseName ||
              (c.group && (c.group === questionData.courseGroup || c.group === questionData.group))
          )
      );
      if (otherProf && otherProf.settings?.coProfNotification !== false) {
        notifs.unshift({
          id: `notif-${Date.now()}-2`,
          userId: otherProf.id,
          type: 'co_prof_question',
          title: '동일 과목/교과목군 질문 알림 (답변 가능)',
          message: `[${questionData.courseName}] 강의 질답에 새 질문이 있습니다. (답변 가능)`,
          questionId: newQuestion.id,
          isRead: false,
          createdAt: new Date().toISOString()
        });
      }
    }

    safeSetStorage(STORAGE_KEY_NOTIFS, notifs);
    return newQuestion;
  },

  async updateQuestion(questionId, updatedData) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(updatedData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) return await res.json();
      } catch (e) {
        console.warn('[API] Remote updateQuestion failed, using local fallback', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const index = questions.findIndex(q => q.id === questionId);
    if (index === -1) throw new Error('질문을 찾을 수 없습니다.');

    const target = questions[index];
    const updated = {
      ...target,
      ...updatedData,
      isEdited: true,
      updatedAt: new Date().toISOString()
    };
    questions[index] = updated;
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);
    return updated;
  },

  // 4. 답변 등록
  async createAnswer(questionId, answerData) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}/answers`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': answerData.authorId || ''
          },
          body: JSON.stringify(answerData),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          return body?.data || body;
        }
      } catch (e) {
        console.warn('[API] Remote createAnswer failed, using local storage', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    const targetQ = questions.find(q => q.id === questionId);
    if (!targetQ) throw new Error('질문을 찾을 수 없습니다.');

    const newAnswer = {
      id: `ans-${Date.now()}`,
      createdAt: new Date().toISOString(),
      ...answerData
    };

    if (!Array.isArray(targetQ.answers)) {
      targetQ.answers = [];
    }
    targetQ.answers.push(newAnswer);
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);

    // 학생에게 답변 알림 발송
    const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
    notifs.unshift({
      id: `notif-${Date.now()}-ans`,
      userId: targetQ.studentId,
      type: 'new_answer',
      title: '새 답변이 등록되었습니다',
      message: `${answerData.authorName}님이 [${targetQ.title}]에 답변을 남기셨습니다.`,
      questionId: targetQ.id,
      isRead: false,
      createdAt: new Date().toISOString()
    });
    safeSetStorage(STORAGE_KEY_NOTIFS, notifs);

    return newAnswer;
  },

  // 5. 유사 질답 검색 (Word2Vec / Cosine Similarity 알고리즘)
  async getSimilarQuestions(queryText, category) {
    if (category === 'grade') {
      return []; // 성적 카테고리는 사용 불가 명세
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        const res = await fetch(`${API_BASE_URL}/questions/similar`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: queryText, category }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : (Array.isArray(body?.data) ? body.data : null);
          if (Array.isArray(list)) return list;
        }
      } catch (e) {
        console.warn('[API] Remote similar search failed, using local engine', e);
      }
    }

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return calculateSimilarQuestions(queryText, questions, 3);
  },

  // 6. AI 질문 도우미 (말투/표현 교정 및 윤문 - Gemini 연동 규격)
  async refineQuestionAI(rawText, category = 'lecture', studentInfo = {}) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${API_BASE_URL}/ai/refine`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: rawText, category, studentInfo }),
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
              politenessScore: result.politenessScore || 95,
              changes: result.changes || ['공손하고 격식 있는 존댓말로 전환', '학업 문의 표준 서식 적용']
            };
          }
        }
      } catch (e) {
        console.warn('[API] Remote AI refinement failed, using local simulator', e);
      }
    }

    // 모의 AI 윤문 로직 (Gemini 시뮬레이터)
    const matched = AI_DEMO_TEMPLATES.find(t => rawText.trim() === t.rawText.trim());
    if (matched) {
      return {
        original: rawText,
        refined: matched.refinedText,
        politenessScore: 98,
        changes: ['격양된 표현을 공손하고 격식 있는 존댓말로 전환', '학업 문의 표준 서식 적용', '건의 및 배려 표현 추가']
      };
    }

    const studentTag = studentInfo.name ? `${studentInfo.department || '학과'} ${studentInfo.studentId || ''} ${studentInfo.name}` : '수강생';
    const lines = rawText.split('\n').filter(Boolean);
    const mainInquiry = lines.join(' ').replace(/[!?~]+/g, '.');

    const refined = `안녕하십니까 교수님.\n이번 학기 강의를 성실히 수강하고 있는 ${studentTag}입니다. 늘 알차고 유익한 강의를 이끌어 주셔서 감사드립니다.\n\n강의 및 학습 내용을 점검하던 중, 다음과 같은 부분에 대해 교수님의 고견과 설명을 조심스럽게 여쭙고자 질문을 드립니다.\n\n[문의 내용]\n${mainInquiry}\n\n바쁘신 연구 및 강의 일정 중 번거로운 질문을 드려 송구하오며, 시간 되실 때 가르침을 주시면 학업에 큰 도움이 될 것 같습니다. 환절기 건강 유의하시기 바랍니다. 감사합니다.`;

    return {
      original: rawText,
      refined,
      politenessScore: 95,
      changes: ['경어체 및 대학 학술 문의 표준 예절 적용', '인사말과 맺음말 보강', '정확한 질의 의도 전달 구조화']
    };
  },

  // 7. 알림 관련
  async getNotifications(userId) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        const res = await fetch(`${API_BASE_URL}/notifications?userId=${userId}`, {
          headers: { 'X-User-Id': userId },
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : (Array.isArray(body?.data) ? body.data : null);
          if (Array.isArray(list)) return list;
        }
      } catch (e) {
        console.warn('[API] Remote getNotifications failed, using local storage', e);
      }
    }

    const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
    return Array.isArray(notifs) ? notifs.filter(n => n.userId === userId) : [];
  },

  async markNotificationRead(notifId) {
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);
        await fetch(`${API_BASE_URL}/notifications/${notifId}/read`, {
          method: 'POST',
          signal: controller.signal
        });
        clearTimeout(timeoutId);
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

  async resetToDefaults() {
    localStorage.removeItem(STORAGE_KEY_QUESTIONS);
    localStorage.removeItem(STORAGE_KEY_NOTIFS);
    localStorage.removeItem(STORAGE_KEY_SETTINGS);
    localStorage.removeItem('prof_qa_questions');
    localStorage.removeItem('prof_qa_notifications');
    localStorage.removeItem('prof_qa_settings');
    localStorage.removeItem('prof_qa_current_user_id');
    initializeStorage();
  }
};
