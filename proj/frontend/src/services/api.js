// src/services/api.js
// 광운대학교 학업 Q&A 센터 Ktor 백엔드 API 통신 모듈 (실제 서버 통신 및 로컬 캐시 연동)

import { PRESET_USERS, INITIAL_QUESTIONS, INITIAL_NOTIFICATIONS, AI_INQUIRY_TEMPLATES } from './mockData';
import { calculateSimilarQuestions } from '../utils/wordSimilarity';
import { generateSummaryTitle } from '../utils/textSummary';

const STORAGE_KEY_QUESTIONS = 'prof_qa_questions_v2';
const STORAGE_KEY_SETTINGS = 'prof_qa_settings_v2';
const STORAGE_KEY_NOTIFS = 'prof_qa_notifications_v2';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';
const USE_REMOTE_BACKEND = true;

function getCurrentUserId(overrideId) {
  if (overrideId && typeof overrideId === 'string') return overrideId;
  return localStorage.getItem('prof_qa_current_user_id') || 'user-stu-001';
}

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
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/auth/users`, { signal: controller.signal });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const remoteList = Array.isArray(body) ? body : (Array.isArray(body?.data) ? body.data : null);
          if (Array.isArray(remoteList) && remoteList.length > 0) {
            return remoteList.map(remoteUser => {
              const localUser = PRESET_USERS.find(u => u.id === remoteUser.id || u.name === remoteUser.name);
              return {
                ...localUser,
                ...remoteUser,
                avatar: localUser?.avatar || (remoteUser.name ? remoteUser.name.charAt(0) : 'U'),
                courses: localUser?.courses || []
              };
            });
          }
        }
      } catch (e) {
        console.warn('[API] Remote getPresetUsers failed, using local fallback', e);
      }
    }
    return PRESET_USERS;
  },

  async getUser(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/auth/me`, {
          headers: { 'X-User-Id': uid },
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const data = body?.data || body;
          if (data && typeof data === 'object') {
            const local = PRESET_USERS.find(u => u.id === uid);
            return { ...local, ...data };
          }
        }
      } catch (e) {
        console.warn('[API] Remote getUser failed, using local fallback', e);
      }
    }
    return PRESET_USERS.find(u => u.id === uid) || PRESET_USERS[0];
  },

  // 2. 강좌 목록 조회
  async getCourses(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/courses`, {
          headers: { 'X-User-Id': uid },
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : (Array.isArray(body?.data) ? body.data : null);
          if (Array.isArray(list) && list.length > 0) {
            return list.map(c => ({
              id: c.id,
              name: c.name,
              code: c.code,
              group: c.group || 'COMMON',
              section: c.professors?.[0]?.section || '01분반',
              profId: c.assignedProfessorId || c.professors?.[0]?.id || '',
              profName: c.professors?.[0]?.name || ''
            }));
          }
        }
      } catch (e) {
        console.warn('[API] Remote getCourses failed, using local fallback', e);
      }
    }
    const user = PRESET_USERS.find(u => u.id === uid);
    return user?.courses || [];
  },

  // 3. 환경 설정 관련 (테마, 글자크기, 방해금지 시간대, 카테고리 활성화, 다른교수 알림 수신여부)
  async getSettings(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/settings?userId=${uid}`, {
          headers: { 'X-User-Id': uid },
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
    const userPreset = PRESET_USERS.find(u => u.id === uid);

    return {
      theme: 'light',
      fontSize: 'md',
      dndEnabled: false,
      dndStartTime: '22:00',
      dndEndTime: '08:00',
      coProfNotification: true,
      activeCategories: { grade: true, assignment: true, lecture: true },
      ...(userPreset?.settings || {}),
      ...(settingsMap[uid] || {})
    };
  },

  async updateSettings(userId, newSettings) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        await fetch(`${API_BASE_URL}/settings`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': uid
          },
          body: JSON.stringify({ userId: uid, ...newSettings }),
          signal: controller.signal
        });
        clearTimeout(timeoutId);
      } catch (e) {
        console.warn('[API] Remote updateSettings failed, using local storage', e);
      }
    }

    const settingsMap = safeGetStorage(STORAGE_KEY_SETTINGS, {});
    settingsMap[uid] = { ...(settingsMap[uid] || {}), ...newSettings };
    safeSetStorage(STORAGE_KEY_SETTINGS, settingsMap);
    return settingsMap[uid];
  },

  // 4. 질문 관련 (목록, 상세, 등록, 수정)
  async getQuestions(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/questions`, {
          headers: { 'X-User-Id': uid },
          signal: controller.signal
        });
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

  async getQuestion(questionId, userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}`, {
          headers: { 'X-User-Id': uid },
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

    const questions = safeGetStorage(STORAGE_KEY_QUESTIONS, INITIAL_QUESTIONS);
    return questions.find(q => q.id === questionId) || null;
  },

  // 4-1. 파일 첨부 (엄격 보안 검증 및 이미지 압축 업로드)
  async uploadAttachment(file, meta = {}) {
    const uid = getCurrentUserId();
    if (USE_REMOTE_BACKEND) {
      try {
        const formData = new FormData();
        formData.append('file', file, file.name);

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 15000);
        const res = await fetch(`${API_BASE_URL}/attachments/upload`, {
          method: 'POST',
          headers: {
            'X-User-Id': uid
          },
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
        console.warn('[API] Remote uploadAttachment failed or rejected:', e);
        if (e.message && (e.message.includes('보안') || e.message.includes('시그니처') || e.message.includes('허용되지 않는') || e.message.includes('위조'))) {
          throw e;
        }
      }
    }

    // 로컬 스토리지 / 오프라인 Fallback
    const localId = `att-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    const previewUrl = meta.previewUrl || (meta.isImage ? URL.createObjectURL(file) : '');
    const localAttachment = {
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
    };

    return {
      success: true,
      attachment: localAttachment,
      message: meta.isCompressed ? `이미지가 성공적으로 압축되었습니다 (${meta.compressionRatio}% 절감).` : '파일이 첨부되었습니다.'
    };
  },

  async deleteAttachment(attachmentId) {
    if (USE_REMOTE_BACKEND) {
      try {
        const uid = getCurrentUserId();
        await fetch(`${API_BASE_URL}/attachments/${attachmentId}`, {
          method: 'DELETE',
          headers: { 'X-User-Id': uid }
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
      ...questionPayload,
      title: summaryTitle
    };
    const uid = getCurrentUserId(questionData.studentId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${API_BASE_URL}/questions`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': uid
          },
          body: JSON.stringify(questionPayload),
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
      ...questionPayload
    };
    questions.unshift(newQuestion);
    safeSetStorage(STORAGE_KEY_QUESTIONS, questions);

    // 교수 알림 자동 생성
    const notifs = safeGetStorage(STORAGE_KEY_NOTIFS, INITIAL_NOTIFICATIONS);
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

    if (questionData.category?.toLowerCase() === 'lecture') {
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

  async updateQuestion(questionId, updatedData, userId) {
    const uid = getCurrentUserId(userId || updatedData.studentId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': uid
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

  // 5. 답변 등록
  async createAnswer(questionId, answerData) {
    const uid = getCurrentUserId(answerData.authorId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${API_BASE_URL}/questions/${questionId}/answers`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-User-Id': uid
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

  // 6. 유사 질답 검색 (Word2Vec / Cosine Similarity)
  async getSimilarQuestions(queryText, category) {
    if (category?.toLowerCase() === 'grade') {
      return []; // 성적 카테고리는 사용 불가 명세
    }

    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
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

  // 7. AI 질문 도우미 (말투/표현 교정 및 제목 윤문 - Gemini 연동)
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
              refinedTitle: result.refinedTitle || (rawTitle ? `[${catLabel} 문의] ${rawTitle.replace(/[\[\]!?~]+/g, '').trim()} 관련 질의의 건` : `[${catLabel} 문의] 학업 및 학습 관련 질의의 건`),
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

    const studentTag = studentInfo.name ? `${studentInfo.department || '소프트웨어학과'} ${studentInfo.studentId || ''} ${studentInfo.name}` : '수강생';
    const cleanTitle = (rawTitle || '').replace(/[\[\]!?~]+/g, '').trim();

    let refinedTitle = '';
    let refinedBody = '';

    if (category === 'grade' || rawText.includes('성적') || cleanTitle.includes('성적')) {
      refinedTitle = cleanTitle ? `[성적 문의] ${cleanTitle} 피드백 및 평가 기준 확인의 건` : '[성적 문의] 2026학년도 2학기 최종 평가 결과 및 피드백 문의의 건';
      refinedBody = `안녕하십니까, 교수님.\n이번 학기 강의를 성실히 수강하고 있는 ${studentTag}입니다. 한 학기 동안 열정적인 가르침을 주셔서 감사드립니다.\n\n다름이 아니라 이번 학기 최종 성적 결과와 관련하여, 스스로 부족했던 부분을 점검하고 향후 학습 방향을 보완하고자 조심스럽게 피드백을 여쭙고자 연락을 드립니다.\n\n아울러 현재 부여된 성적과 관련하여 재수강 기준 충족 여부나 세부 평가 기준에 관해 교수님의 소중한 가르침을 청하고자 하오니, 바쁘신 일정 중 시간 되실 때 확인해 주시면 학업에 큰 도움이 될 것 같습니다. 건강 유의하시기 바랍니다. 감사합니다.`;
    } else if (category === 'assignment' || rawText.includes('과제') || rawText.includes('제출') || cleanTitle.includes('과제')) {
      refinedTitle = cleanTitle ? `[과제 문의] ${cleanTitle} 관련 일정 및 세부 사항 문의의 건` : '[과제 문의] 과제 수행 관련 일정 및 세부 사항 문의의 건';
      refinedBody = `안녕하십니까 교수님.\n강의를 성실히 수강하고 있는 ${studentTag}입니다. 평소 깊이 있고 유익한 강의를 이끌어 주셔서 감사드립니다.\n\n현재 안내해 주신 과제를 성실히 수행하는 과정에서, 핵심 요구사항 및 일정과 관련하여 명확히 확인하고자 조심스럽게 문의를 남깁니다. 보다 완성도 높은 결과물 제출을 위해 혹시 제출 기한이나 제출 요건에 대해 검토해 주실 수 있는지 여쭙고자 합니다.\n\n바쁘신 연구 및 강의 일정 중에 번거로운 문의를 드려 송구하오며, 확인해 주시면 성실히 과제를 완수하도록 하겠습니다. 감사합니다.`;
    } else {
      refinedTitle = cleanTitle ? `[강의 문의] ${cleanTitle} 개념 이해 및 학습 질의의 건` : '[강의 문의] 강의 내용 개념 이해 및 학습 관련 질의의 건';
      refinedBody = `안녕하십니까 교수님.\n이번 학기 수업을 수강하고 있는 ${studentTag}입니다. 늘 알차고 유익한 강의를 준비해 주셔서 감사드립니다.\n\n수업 시간에 다루어 주신 강의 내용 및 핵심 개념을 복습하던 중, 개념 정리와 원리 이해에 다소 부족한 부분이 있어 교수님의 고견과 조언을 조심스럽게 구하고자 질문을 올립니다.\n\n시간 되실 때 핵심 직관이나 참고할 만한 자료를 말씀해 주시면 학업에 큰 도움이 될 것 같습니다. 바쁘신 일정 중 시간 내어 주셔서 감사드립니다.`;
    }

    return {
      original: rawText,
      refined: refinedBody,
      originalTitle: rawTitle || '',
      refinedTitle: refinedTitle,
      politenessScore: 96,
      changes: [
        '원문 텍스트를 직접 인용하지 않고 정중한 학술 문장으로 전면 재구성',
        '질문 제목을 명확하고 품격 있는 학업 문의 양식으로 윤색',
        '경어체 및 대학 질의응답 표준 예절 적용'
      ]
    };
  },

  // 8. 알림 관련
  async getNotifications(userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        const res = await fetch(`${API_BASE_URL}/notifications?userId=${uid}`, {
          headers: { 'X-User-Id': uid },
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
    return Array.isArray(notifs) ? notifs.filter(n => n.userId === uid) : [];
  },

  async markNotificationRead(notifId, userId) {
    const uid = getCurrentUserId(userId);
    if (USE_REMOTE_BACKEND) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1200);
        await fetch(`${API_BASE_URL}/notifications/${notifId}/read`, {
          method: 'POST',
          headers: { 'X-User-Id': uid },
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
