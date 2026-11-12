// src/components/professor/NotificationSettingsModal.jsx
import React, { useState, useEffect } from 'react';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { api } from '../../services/api';
import { CATEGORY_LIST } from '../../services/mockData';
import {
  X,
  BellOff,
  Clock,
  Sliders,
  Users,
  Save,
  CheckCircle2,
  Mail
} from 'lucide-react';

const NotificationSettingsModal = ({ isOpen, onClose }) => {
  const { settings, updateAllSettings } = useTheme();
  const { currentUser, refreshCurrentUser } = useAuth();

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
    setSavedToast(true);
    setTimeout(() => {
      setSavedToast(false);
      onClose();
    }, 900);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-container modal-lg" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Sliders size={20} className="text-primary" />
            <span>교수용 질문 알림 · 이메일 연동 및 카테고리 설정</span>
          </div>
          <button className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={20} />
          </button>
        </div>

        <div className="modal-body">
          {/* 1. 이메일 질문 알림 발송 설정 (R3 신규 요구사항) */}
          <div className="settings-section">
            <div className="settings-header-row">
              <div className="settings-title-group">
                <Mail size={18} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">
                    새 질문 등록 시 이메일 알림 발송 연동
                  </h4>
                  <p className="settings-section-desc">
                    담당 과목에 새로운 질문이 등록되면 아래에 연동된 교수님의 이메일 주소로 질문 제목과 본문을 즉시 발송합니다.
                  </p>
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
                <BellOff size={18} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">
                    질문 알림 비활성화 시간대 (방해금지 모드)
                  </h4>
                  <p className="settings-section-desc">
                    지정된 야간/휴식 시간 동안 도착한 질문 알림을 보류하고, 종료 시각에 일괄 전달합니다.
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

          {/* 3. 같은 과목 다른 교수 질문 알림 수신 설정 */}
          <div className="settings-section">
            <div className="settings-header-row">
              <div className="settings-title-group">
                <Users size={18} className="text-primary" />
                <div>
                  <h4 className="settings-section-title">
                    같은 과목 다른 교수에게 온 질문 알림 수신
                  </h4>
                  <p className="settings-section-desc">
                    동일 교과목(타 분반)의 &apos;강의&apos; 카테고리 미답변 질문 알림을 함께 받아 공동 답변에 참여합니다.
                  </p>
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

          {/* 4. 질문 카테고리 활성화/비활성화 */}
          <div className="settings-section">
            <h4 className="settings-section-title">질문 카테고리 활성화 관리</h4>
            <p className="settings-section-desc">
              강의 운영 시기에 맞춰 학생이 등록할 수 있는 질문 카테고리를 활성화하거나 비활성화합니다.
            </p>

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
