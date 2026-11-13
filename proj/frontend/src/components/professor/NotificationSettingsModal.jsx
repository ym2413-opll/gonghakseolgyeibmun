// src/components/professor/NotificationSettingsModal.jsx
import React, { useState, useEffect } from 'react';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { api } from '../../services/api';
import { CATEGORY_LIST } from '../../services/mockData';
import CoursePicker from '../common/CoursePicker';
import {
  X,
  BellOff,
  Clock,
  Sliders,
  Users,
  Save,
  CheckCircle2,
  Mail,
  BookOpen
} from 'lucide-react';

const NotificationSettingsModal = ({ isOpen, onClose, onCoursesUpdated }) => {
  const { settings, updateAllSettings } = useTheme();
  const { currentUser, allCourses, refreshCurrentUser } = useAuth();

  const [dndEnabled, setDndEnabled] = useState(true);
  const [dndStartTime, setDndStartTime] = useState('22:00');
  const [dndEndTime, setDndEndTime] = useState('08:00');
  const [coProfNotification, setCoProfNotification] = useState(true);
  const [emailEnabled, setEmailEnabled] = useState(true);
  const [notificationEmail, setNotificationEmail] = useState('');
  const [activeCategories, setActiveCategories] = useState({
    grade: true,
    assignment: true,
    lecture: true
  });
  const [emailLogs, setEmailLogs] = useState([]);
  const [savedToast, setSavedToast] = useState(false);
  const [selectedCourseIds, setSelectedCourseIds] = useState([]);
  const [courseError, setCourseError] = useState('');

  useEffect(() => {
    if (!isOpen) return;
    const ids = Array.isArray(currentUser?.courses)
      ? currentUser.courses.map((c) => c.id).filter(Boolean)
      : [];
    setSelectedCourseIds(ids);
    setCourseError('');
  }, [isOpen, currentUser?.courses]);

  useEffect(() => {
    if (settings) {
      setDndEnabled(settings.dndEnabled ?? true);
      setDndStartTime(settings.dndStartTime || '22:00');
      setDndEndTime(settings.dndEndTime || '08:00');
      setCoProfNotification(settings.coProfNotification ?? true);
      setEmailEnabled(settings.emailEnabled ?? true);
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
    if (isOpen && currentUser?.id) {
      api.getEmailLogs(currentUser.id).then((logs) => {
        setEmailLogs(Array.isArray(logs) ? logs.slice(0, 5) : []);
      });
    }
  }, [isOpen, currentUser?.id]);

  if (!isOpen) return null;

  const handleCategoryToggle = (catId) => {
    setActiveCategories((prev) => ({
      ...prev,
      [catId]: !prev[catId]
    }));
  };

  const handleSave = async () => {
    setCourseError('');
    if (selectedCourseIds.length === 0) {
      setCourseError('담당 교과목은 최소 1개 이상 선택해야 합니다.');
      return;
    }
    try {
      await api.updateUserCourses(selectedCourseIds);
      await updateAllSettings({
        dndEnabled,
        dndStartTime,
        dndEndTime,
        coProfNotification,
        emailEnabled,
        notificationEmail: notificationEmail.trim(),
        activeCategories
      });
      await refreshCurrentUser();
      if (onCoursesUpdated) await onCoursesUpdated();
      setSavedToast(true);
      setTimeout(() => {
        setSavedToast(false);
        onClose();
      }, 900);
    } catch (err) {
      setCourseError(err.message || '설정 저장 중 오류가 발생했습니다.');
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
          <div className="settings-section">
            <div className="settings-title-group" style={{ marginBottom: 10 }}>
              <BookOpen size={17} className="text-primary" />
              <div>
                <h4 className="settings-section-title">담당 교과목 설정</h4>
                <p className="settings-section-desc">
                  강의를 담당하며 질문을 수신할 교과목을 선택합니다. (최소 1개 이상)
                </p>
              </div>
            </div>

            <CoursePicker
              courses={allCourses}
              selectedIds={selectedCourseIds}
              onChange={setSelectedCourseIds}
              label="담당 교과목"
              helperText="최소 1개 이상"
            />
          </div>

          <div className="settings-divider" />

          <div className="settings-section">
            <div className="settings-header-row">
              <div className="settings-title-group">
                <Mail size={17} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">이메일 알림</h4>
                  <p className="settings-section-desc">새 질문 등록 시 이메일로 전달합니다.</p>
                </div>
              </div>
              <label className="toggle-switch">
                <input
                  type="checkbox"
                  checked={emailEnabled}
                  onChange={(e) => setEmailEnabled(e.target.checked)}
                />
                <span className="toggle-slider" />
              </label>
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
                      <span className="text-muted">→ {log.recipientEmail}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="settings-divider" />

          {/* 2. 방해금지 시간대 설정 */}
          <div className="settings-section">
            <div className="settings-header-row">
              <div className="settings-title-group">
                <BellOff size={17} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">방해금지 시간대</h4>
                  <p className="settings-section-desc">지정 시간대 알림을 보류한 뒤 종료 시각에 일괄 전달합니다.</p>
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

          {/* 3. 같은 과목 다른 교수 질문 알림 수신 설정 */}
          <div className="settings-section">
            <div className="settings-header-row">
              <div className="settings-title-group">
                <Users size={17} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">동일 과목 타 분반 강의 질문 알림</h4>
                  <p className="settings-section-desc">타 분반 강의 카테고리의 미답변 질문 알림도 함께 받습니다.</p>
                </div>
              </div>
              <label className="toggle-switch">
                <input
                  type="checkbox"
                  checked={coProfNotification}
                  onChange={(e) => setCoProfNotification(e.target.checked)}
                />
                <span className="toggle-slider" />
              </label>
            </div>
          </div>

          <div className="settings-divider" />

          <div className="settings-section">
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
              <button className="btn btn-secondary" onClick={onClose}>
                취소
              </button>
              <button className="btn btn-primary" onClick={handleSave}>
                <Save size={16} />
                설정 저장하기
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default NotificationSettingsModal;
