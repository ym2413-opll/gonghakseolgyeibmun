// src/components/professor/NotificationSettingsModal.jsx
import React, { useState, useEffect, useRef } from 'react';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { useNotifications } from '../../context/NotificationContext';
import { api } from '../../services/api';
import { CATEGORY_LIST } from '../../services/mockData';
import CoursePicker, { buildCourseSelectionsPayload } from '../common/CoursePicker';
import HelpBubble from '../common/HelpBubble';
import {
  X,
  Bell,
  BellOff,
  Clock,
  Sliders,
  Save,
  CheckCircle2,
  Mail,
  BookOpen
} from 'lucide-react';

const SCOPE_OPTIONS = [
  {
    value: 'NONE',
    label: '없음',
    desc: '수신하지 않음'
  },
  {
    value: 'ASSIGNED_SECTION',
    label: '담당 분반만',
    desc: '내 담당 분반 질문만 수신'
  },
  {
    value: 'ALL_SECTIONS',
    label: '다른 분반도',
    desc: '같은 강의 타 분반 질문도 수신'
  }
];

const NotificationSettingsModal = ({ isOpen, onClose, onCoursesUpdated }) => {
  const { settings, updateAllSettings } = useTheme();
  const { currentUser, allCourses, updateUserCourses } = useAuth();
  const { updateProfSettings } = useNotifications();

  const [dndEnabled, setDndEnabled] = useState(true);
  const [dndStartTime, setDndStartTime] = useState('22:00');
  const [dndEndTime, setDndEndTime] = useState('08:00');
  const [notificationScope, setNotificationScope] = useState('ALL_SECTIONS');
  const [emailScope, setEmailScope] = useState('ASSIGNED_SECTION');
  const [notificationEmail, setNotificationEmail] = useState('');
  const [activeCategories, setActiveCategories] = useState({
    grade: true,
    assignment: true,
    lecture: true
  });
  const [emailLogs, setEmailLogs] = useState([]);
  const [savedToast, setSavedToast] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [selectedCourseIds, setSelectedCourseIds] = useState([]);
  const [sectionSelections, setSectionSelections] = useState({});
  const [courseError, setCourseError] = useState('');
  const savedTimerRef = useRef(null);

  useEffect(() => {
    return () => {
      if (savedTimerRef.current) clearTimeout(savedTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    const userCourses = Array.isArray(currentUser?.courses) ? currentUser.courses : [];
    const ids = userCourses.map((c) => c.id).filter(Boolean);
    const initSecMap = {};
    userCourses.forEach((c) => {
      if (!c?.id) return;
      if (Array.isArray(c.myCourseProfessorIds) && c.myCourseProfessorIds.length > 0) {
        initSecMap[c.id] = c.myCourseProfessorIds;
      } else if (c.assignedCourseProfessorId) {
        initSecMap[c.id] = [c.assignedCourseProfessorId];
      }
    });
    setSelectedCourseIds(ids);
    setSectionSelections(initSecMap);
    setCourseError('');
  }, [isOpen, currentUser?.courses]);

  useEffect(() => {
    if (settings) {
      setDndEnabled(settings.dndEnabled ?? false);
      setDndStartTime(settings.dndStartTime || '22:00');
      setDndEndTime(settings.dndEndTime || '08:00');

      const resolvedNotifScope =
        settings.notificationScope ||
        (settings.coProfNotification === false ? 'ASSIGNED_SECTION' : 'ALL_SECTIONS');
      const resolvedEmailScope =
        settings.emailScope ||
        (settings.emailEnabled === false
          ? 'NONE'
          : settings.coProfNotification
            ? 'ALL_SECTIONS'
            : 'ASSIGNED_SECTION');

      setNotificationScope(resolvedNotifScope);
      setEmailScope(resolvedEmailScope);
      setNotificationEmail(settings.notificationEmail || currentUser?.email || '');
      setActiveCategories(
        settings.activeCategories || {
          grade: true,
          assignment: true,
          lecture: true
        }
      );
    } else if (currentUser?.email) {
      setNotificationEmail(currentUser.email);
    }
  }, [settings, isOpen, currentUser?.email]);

  useEffect(() => {
    let active = true;
    if (isOpen && currentUser?.id) {
      api
        .getEmailLogs(currentUser.id)
        .then((logs) => {
          if (active) setEmailLogs(Array.isArray(logs) ? logs.slice(0, 5) : []);
        })
        .catch((err) => {
          console.warn('Failed to fetch email logs:', err);
        });
    }
    return () => {
      active = false;
    };
  }, [isOpen, currentUser?.id]);

  if (!isOpen) return null;

  const emailEnabled = emailScope !== 'NONE';

  const handleCategoryToggle = (catId) => {
    setActiveCategories((prev) => ({
      ...prev,
      [catId]: !prev[catId]
    }));
  };

  const handleSave = async () => {
    if (isSaving) return;
    setCourseError('');
    if (selectedCourseIds.length === 0) {
      setCourseError('담당 교과목은 최소 1개 이상 선택해야 합니다.');
      return;
    }
    if (emailScope !== 'NONE') {
      const trimmedEmail = notificationEmail.trim();
      if (!trimmedEmail) {
        setCourseError('이메일 알림을 받으시려면 수신용 이메일 주소를 입력하거나 이메일 범위를 "없음"으로 선택해 주세요.');
        return;
      }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
        setCourseError('올바른 이메일 주소 형식을 입력해 주세요.');
        return;
      }
    }
    setIsSaving(true);
    try {
      const selectionsPayload = buildCourseSelectionsPayload(
        selectedCourseIds,
        sectionSelections,
        allCourses,
        true
      );
      await updateUserCourses(selectedCourseIds, selectionsPayload);

      const settingsPayload = {
        dndEnabled,
        dndStartTime,
        dndEndTime,
        notificationScope,
        emailScope,
        emailEnabled: emailScope !== 'NONE',
        coProfNotification: notificationScope === 'ALL_SECTIONS' || emailScope === 'ALL_SECTIONS',
        notificationEmail: notificationEmail.trim(),
        activeCategories
      };
      await updateAllSettings(settingsPayload);
      if (updateProfSettings) {
        await updateProfSettings(settingsPayload);
      }
      if (onCoursesUpdated) await onCoursesUpdated();
      setSavedToast(true);
      if (savedTimerRef.current) clearTimeout(savedTimerRef.current);
      savedTimerRef.current = setTimeout(() => {
        setSavedToast(false);
        onClose();
      }, 900);
    } catch (err) {
      setCourseError(err.message || '설정 저장 중 오류가 발생했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-container modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Sliders size={19} className="text-primary" />
            <span>알림 및 카테고리 설정</span>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <HelpBubble
            tier="ALL"
            mode="inline"
            text="포털 알림과 이메일 수신 범위를 각각 '없음 / 담당 분반만 / 다른 분반도' 중에서 독립적으로 선택할 수 있어요."
            className="mb-help-bubble"
          />

          {/* 1. 담당 교과목 설정 */}
          <div className="settings-section">
            <div className="settings-title-group" style={{ marginBottom: 10 }}>
              <BookOpen size={17} className="text-primary" />
              <div>
                <h4 className="settings-section-title">담당 교과목 설정</h4>
                <p className="settings-section-desc">
                  강의를 담당하며 질문을 수신할 교과목과 분반을 선택합니다. (최소 1개 이상)
                </p>
              </div>
            </div>

            <HelpBubble
              tier="ALL"
              mode="inline"
              text="여러 분반이 개설된 과목은 담당하는 분반을 복수로 선택할 수 있어요. 이전 학기 강의가 필요하면 '이전 학기 포함'을 체크해 보세요."
              className="mb-help-bubble"
            />

            <CoursePicker
              courses={allCourses}
              selectedIds={selectedCourseIds}
              onChange={setSelectedCourseIds}
              sectionMode="multi"
              sectionSelections={sectionSelections}
              onSectionSelectionsChange={setSectionSelections}
              label="담당 교과목"
              helperText="최소 1개 이상"
            />
          </div>

          <div className="settings-divider" />

          {/* 2. 알림 수신 범위 ('없음' | '담당 분반만' | '다른 분반도') */}
          <div className="settings-section">
            <div className="settings-title-group" style={{ marginBottom: 10 }}>
              <Bell size={17} className="text-primary" />
              <div>
                <h4 className="settings-section-title">알림 수신 범위</h4>
                <p className="settings-section-desc">
                  새 질문 등록 시 포털 및 브라우저 알림 수신 범위를 선택합니다.
                </p>
              </div>
            </div>

            <div className="scope-selector-grid" role="radiogroup" aria-label="알림 수신 범위">
              {SCOPE_OPTIONS.map((opt) => {
                const active = notificationScope === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    role="radio"
                    aria-checked={active}
                    className={`scope-option-card ${active ? 'active' : ''}`}
                    onClick={() => setNotificationScope(opt.value)}
                  >
                    <span className="scope-option-title">{opt.label}</span>
                    <span className="scope-option-desc">{opt.desc}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="settings-divider" />

          {/* 3. 이메일 수신 범위 ('없음' | '담당 분반만' | '다른 분반도') */}
          <div className="settings-section">
            <div className="settings-title-group" style={{ marginBottom: 10 }}>
              <Mail size={17} className="text-primary" />
              <div>
                <h4 className="settings-section-title">이메일 수신 범위</h4>
                <p className="settings-section-desc">
                  새 질문 등록 시 이메일 수신 범위를 선택합니다.
                </p>
              </div>
            </div>

            <div
              className="scope-selector-grid"
              role="radiogroup"
              aria-label="이메일 수신 범위"
              style={{ marginBottom: 12 }}
            >
              {SCOPE_OPTIONS.map((opt) => {
                const active = emailScope === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    role="radio"
                    aria-checked={active}
                    className={`scope-option-card ${active ? 'active' : ''}`}
                    onClick={() => setEmailScope(opt.value)}
                  >
                    <span className="scope-option-title">{opt.label}</span>
                    <span className="scope-option-desc">{opt.desc}</span>
                  </button>
                );
              })}
            </div>

            <div className={`dnd-time-box ${!emailEnabled ? 'disabled' : ''}`}>
              <div className="form-group" style={{ marginBottom: 8 }}>
                <label className="form-label">알림 수신용 이메일 주소</label>
                <input
                  type="email"
                  className="form-input"
                  placeholder="예: professor@kw.ac.kr"
                  value={notificationEmail}
                  onChange={(e) => setNotificationEmail(e.target.value)}
                  disabled={!emailEnabled}
                />
              </div>
              {emailLogs.length > 0 && (
                <div className="email-log-mini-box">
                  <div className="text-xs font-semibold" style={{ marginBottom: 4 }}>
                    최근 발송된 질문 알림 이메일 ({emailLogs.length}건)
                  </div>
                  {emailLogs.map((log) => (
                    <div key={log.id} className="email-log-mini-row">
                      <span>{log.subject}</span>
                      <span className="text-muted">
                        → {log.recipientEmail} (
                        {log.status === 'SENT_SMTP'
                          ? '발송 완료'
                          : log.status === 'SENT_LOGGED'
                            ? '발송 실패'
                            : log.status === 'QUEUED'
                              ? '대기 중'
                              : 'SMTP 미설정'}
                        )
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="settings-divider" />

          {/* 4. 방해금지 시간대 설정 */}
          <div className="settings-section">
            <div className="settings-header-row">
              <div className="settings-title-group">
                <BellOff size={17} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">방해금지 시간대</h4>
                  <p className="settings-section-desc">
                    지정 시간대 포털·브라우저 알림을 보류한 뒤 종료 시각에 일괄 전달합니다.
                  </p>
                </div>
              </div>
              <label className="toggle-switch">
                <input
                  type="checkbox"
                  checked={dndEnabled}
                  onChange={(e) => setDndEnabled(e.target.checked)}
                />
                <span className="toggle-slider" />
              </label>
            </div>

            <div className={`dnd-time-box ${!dndEnabled ? 'disabled' : ''}`}>
              <div className="time-input-group">
                <label>시작 시각</label>
                <div className="time-input-wrapper">
                  <Clock size={16} />
                  <input
                    type="time"
                    value={dndStartTime}
                    onChange={(e) => setDndStartTime(e.target.value)}
                    disabled={!dndEnabled}
                  />
                </div>
              </div>
              <span className="time-range-separator">~</span>
              <div className="time-input-group">
                <label>종료 시각 (일괄 알림 시각)</label>
                <div className="time-input-wrapper">
                  <Clock size={16} />
                  <input
                    type="time"
                    value={dndEndTime}
                    onChange={(e) => setDndEndTime(e.target.value)}
                    disabled={!dndEnabled}
                  />
                </div>
              </div>
            </div>
          </div>

          <div className="settings-divider" />

          {/* 5. 질문 카테고리 활성화 */}
          <div className="settings-section">
            <HelpBubble
              tier="ALL"
              mode="inline"
              text="성적 질문은 타 분반 알림을 켜더라도 보안상 담당 교수님께만 전달돼요. 원치 않는 질문 유형은 잠시 끌 수 있어요."
              className="mb-help-bubble"
            />
            <h4 className="settings-section-title">질문 카테고리 활성화</h4>
            <p className="settings-section-desc">허용할 질문 카테고리를 선택합니다.</p>

            <div className="category-toggle-list">
              {CATEGORY_LIST.map((cat) => (
                <div key={cat.id} className="category-toggle-card">
                  <div className="category-toggle-info">
                    <span className={`badge ${cat.badgeClass}`}>{cat.name}</span>
                    <div>
                      <div className="category-toggle-title">{cat.name} 관련 질의응답</div>
                      <div className="category-toggle-desc">{cat.description}</div>
                    </div>
                  </div>
                  <label className="toggle-switch">
                    <input
                      type="checkbox"
                      checked={Boolean(activeCategories[cat.id])}
                      onChange={() => handleCategoryToggle(cat.id)}
                    />
                    <span className="toggle-slider" />
                  </label>
                </div>
              ))}
            </div>
          </div>
        </div>

        {courseError && (
          <div className="settings-inline-status is-error" style={{ margin: '0 22px 12px' }}>
            {courseError}
          </div>
        )}

        <div className="modal-footer">
          {savedToast ? (
            <div className="save-toast">
              <CheckCircle2 size={18} />
              <span>설정이 저장되었습니다!</span>
            </div>
          ) : (
            <>
              <button className="btn btn-secondary" onClick={onClose} disabled={isSaving}>
                취소
              </button>
              <button className="btn btn-primary" onClick={handleSave} disabled={isSaving}>
                <Save size={16} />
                {isSaving ? '저장 중...' : '설정 저장하기'}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default NotificationSettingsModal;
