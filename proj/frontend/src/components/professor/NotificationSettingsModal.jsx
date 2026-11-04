// src/components/professor/NotificationSettingsModal.jsx
import React, { useState } from 'react';
import { useNotifications } from '../../context/NotificationContext';
import { BellOff, Users, Clock, X, Check, Save } from 'lucide-react';

export default function NotificationSettingsModal({ isOpen, onClose }) {
  const { profSettings, updateProfSettings } = useNotifications();

  const [dndEnabled, setDndEnabled] = useState(profSettings.dndEnabled);
  const [dndStartTime, setDndStartTime] = useState(profSettings.dndStartTime || '22:00');
  const [dndEndTime, setDndEndTime] = useState(profSettings.dndEndTime || '08:00');
  const [coProfNotification, setCoProfNotification] = useState(profSettings.coProfNotification);
  const [isSaved, setIsSaved] = useState(false);

  if (!isOpen) return null;

  const handleSave = async () => {
    await updateProfSettings({
      dndEnabled,
      dndStartTime,
      dndEndTime,
      coProfNotification
    });
    setIsSaved(true);
    setTimeout(() => {
      setIsSaved(false);
      onClose();
    }, 600);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content prof-settings-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <BellOff size={18} />
            <span>교수 알림 및 수신 시간대 설정</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          {/* 1. 알림 받지 않을 시간대 설정 */}
          <div className="settings-card-item">
            <div className="settings-row-between">
              <div>
                <label className="item-title">방해 금지 시간대 설정 (수면/연구 시간)</label>
                <p className="item-desc">
                  설정한 시간대에는 새 질문 알림이 푸시되지 않고 백엔드에 누적되며, 종료 시간 이후 한 번에 확인하실 수 있습니다.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={dndEnabled}
                  onChange={(e) => setDndEnabled(e.target.checked)}
                />
                <span className="slider round"></span>
              </label>
            </div>

            {dndEnabled && (
              <div className="time-range-inputs">
                <div className="time-field">
                  <span className="time-label">시작 시각</span>
                  <input
                    type="time"
                    value={dndStartTime}
                    onChange={(e) => setDndStartTime(e.target.value)}
                    className="time-picker"
                  />
                </div>
                <span className="time-separator">~</span>
                <div className="time-field">
                  <span className="time-label">종료 시각</span>
                  <input
                    type="time"
                    value={dndEndTime}
                    onChange={(e) => setDndEndTime(e.target.value)}
                    className="time-picker"
                  />
                </div>
              </div>
            )}
          </div>

          {/* 2. 같은 과목 다른 교수 알림 수신 설정 */}
          <div className="settings-card-item">
            <div className="settings-row-between">
              <div>
                <div className="item-title-with-badge">
                  <span className="item-title">동일 과목 타 분반 질문 알림 수신</span>
                </div>
                <p className="item-desc">
                  동일 과목의 타 분반 강의 질문 등록 시 알림을 수신합니다.
                </p>
              </div>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={coProfNotification}
                  onChange={(e) => setCoProfNotification(e.target.checked)}
                />
                <span className="slider round"></span>
              </label>
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn btn-secondary" onClick={onClose}>
            취소
          </button>
          <button className="btn btn-primary" onClick={handleSave} disabled={isSaved}>
            {isSaved ? '저장됨' : '저장'}
          </button>
        </div>
      </div>
    </div>
  );
}
