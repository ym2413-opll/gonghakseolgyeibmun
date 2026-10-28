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
            <label className="section-label">화면 테마 (라이트 / 다크)</label>
            <p className="section-desc">설정값은 백엔드에 즉시 동기화되어 다음 로그인 시에도 유지됩니다.</p>
            <div className="theme-toggle-cards">
              <button
                className={`theme-card ${theme === 'light' ? 'selected' : ''}`}
                onClick={() => theme !== 'light' && toggleTheme()}
              >
                <div className="theme-icon light-icon">
                  <Sun size={24} />
                </div>
                <div className="theme-name">라이트 모드</div>
                <span className="theme-sub">선명하고 밝은 화면</span>
                {theme === 'light' && <Check size={16} className="check-icon" />}
              </button>

              <button
                className={`theme-card ${theme === 'dark' ? 'selected' : ''}`}
                onClick={() => theme !== 'dark' && toggleTheme()}
              >
                <div className="theme-icon dark-icon">
                  <Moon size={24} />
                </div>
                <div className="theme-name">다크 모드</div>
                <span className="theme-sub">눈이 편안한 어두운 화면</span>
                {theme === 'dark' && <Check size={16} className="check-icon" />}
              </button>
            </div>
          </div>

          <div className="settings-section">
            <label className="section-label">글자 크기 설정</label>
            <p className="section-desc">가독성을 위해 본문 및 카드 폰트 크기를 조절할 수 있습니다.</p>
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
            <label className="section-label">브라우저 시스템 알림 (Web Notification)</label>
            <p className="section-desc">질문이나 교수님 답변이 도착했을 때 OS/브라우저 팝업 알림을 수신합니다.</p>
            <div className="browser-notif-setting-box">
              <div className="notif-status-info">
                <span className="status-label">현재 상태:</span>
                {browserPermission === 'granted' ? (
                  <span className="badge-notif-granted">알림 허용됨</span>
                ) : browserPermission === 'denied' ? (
                  <span className="badge-notif-denied">브라우저 차단됨</span>
                ) : (
                  <span className="badge-notif-default">권한 요청 대기</span>
                )}
              </div>
              {browserPermission !== 'granted' && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={requestPermission}
                >
                  브라우저 알림 권한 허용하기
                </button>
              )}
            </div>
          </div>

          <div className="settings-preview-box">
            <span className="preview-label">미리보기 텍스트:</span>
            <p className="preview-sample">
              교수질문센터를 통해 언제 어디서나 학업 질문을 편리하게 전달하고 명확한 피드백을 받을 수 있습니다.
            </p>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn btn-primary" onClick={onClose}>
            설정 완료
          </button>
        </div>
      </div>
    </div>
  );
}
