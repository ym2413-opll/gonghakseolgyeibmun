// src/components/shared/SettingsModal.jsx
import { useTheme } from '../../context/ThemeContext';
import { useNotifications } from '../../context/NotificationContext';
import { Sun, Moon, Type, X, Check } from 'lucide-react';

export default function SettingsModal({ isOpen, onClose }) {
  const { theme, toggleTheme, fontSize, changeFontSize } = useTheme();
  const { browserPermission, requestPermission } = useNotifications();

  if (!isOpen) return null;

  const fontOptions = [
    { id: 'sm', label: '작게', px: '14px' },
    { id: 'md', label: '보통 (기본)', px: '16px' },
    { id: 'lg', label: '크게', px: '18px' },
    { id: 'xl', label: '아주 크게', px: '20px' }
  ];

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content settings-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Type size={18} />
            <span>화면 및 표시 설정</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <div className="settings-section">
            <label className="section-label">화면 테마</label>
            <div className="theme-toggle-cards">
              <button
                className={`theme-card ${theme === 'light' ? 'selected' : ''}`}
                onClick={() => theme !== 'light' && toggleTheme()}
              >
                <div className="theme-icon light-icon">
                  <Sun size={20} />
                </div>
                <div className="theme-name">라이트</div>
                {theme === 'light' && <Check size={16} className="check-icon" />}
              </button>

              <button
                className={`theme-card ${theme === 'dark' ? 'selected' : ''}`}
                onClick={() => theme !== 'dark' && toggleTheme()}
              >
                <div className="theme-icon dark-icon">
                  <Moon size={20} />
                </div>
                <div className="theme-name">다크</div>
                {theme === 'dark' && <Check size={16} className="check-icon" />}
              </button>
            </div>
          </div>

          <div className="settings-section">
            <label className="section-label">글자 크기</label>
            <div className="font-size-options">
              {fontOptions.map((opt) => (
                <button
                  key={opt.id}
                  className={`font-size-btn ${fontSize === opt.id ? 'active' : ''}`}
                  onClick={() => changeFontSize(opt.id)}
                >
                  <span className="font-size-name">{opt.label}</span>
                  <span className="font-size-val">{opt.px}</span>
                </button>
              ))}
            </div>
          </div>

          <div className="settings-section">
            <label className="section-label">브라우저 알림</label>
            <div className="browser-notif-setting-box">
              <div className="notif-status-info">
                <span className="status-label">상태:</span>
                {browserPermission === 'granted' ? (
                  <span className="badge-notif-granted">허용됨</span>
                ) : browserPermission === 'denied' ? (
                  <span className="badge-notif-denied">차단됨</span>
                ) : (
                  <span className="badge-notif-default">미설정</span>
                )}
              </div>
              {browserPermission !== 'granted' && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={requestPermission}
                >
                  권한 허용
                </button>
              )}
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn btn-primary" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}
