// src/components/common/SettingsModal.jsx
import React from 'react';
import { useTheme } from '../../context/ThemeContext';
import { X, Sun, Moon, Leaf, Type, Check } from 'lucide-react';

const FONT_SIZE_OPTIONS = [
  { id: 'sm', label: '작게 (Small)', sample: '14px 표준 텍스트' },
  { id: 'md', label: '보통 (Medium - 기본)', sample: '16px 기본 가독성 텍스트' },
  { id: 'lg', label: '크게 (Large)', sample: '18px 확대 가독성 텍스트' },
  { id: 'xl', label: '매우 크게 (Extra Large)', sample: '20px 고대비 큰 글씨' }
];

const SettingsModal = ({ isOpen, onClose }) => {
  const { theme, fontSize, updateTheme, updateFontSize } = useTheme();

  if (!isOpen) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-container modal-md" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Type size={20} className="text-primary" />
            <span>화면 표시 설정 (테마 및 글자 크기)</span>
          </div>
          <button className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={20} />
          </button>
        </div>

        <div className="modal-body">
          <div className="settings-section">
            <h4 className="settings-section-title">화면 색상 테마</h4>
            <p className="settings-section-desc">
              학습 환경과 시력 보호에 맞춰 라이트, 다크, 또는 눈이 편한 초록색조(세이지 그린) 테마를 선택할 수 있습니다.
            </p>

            <div className="theme-selector-grid theme-selector-grid-3">
              <button
                type="button"
                className={`theme-option-card ${theme === 'light' ? 'active' : ''}`}
                onClick={() => updateTheme('light')}
              >
                <div className="theme-preview light-preview">
                  <Sun size={24} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">라이트 모드</span>
                  <span className="theme-option-sub">밝고 선명한 캠퍼스 화이트</span>
                </div>
                {theme === 'light' && <Check size={18} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'green' ? 'active' : ''}`}
                onClick={() => updateTheme('green')}
              >
                <div className="theme-preview green-preview">
                  <Leaf size={24} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">초록색조 테마</span>
                  <span className="theme-option-sub">눈이 편한 세이지·포레스트 그린</span>
                </div>
                {theme === 'green' && <Check size={18} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'dark' ? 'active' : ''}`}
                onClick={() => updateTheme('dark')}
              >
                <div className="theme-preview dark-preview">
                  <Moon size={24} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">다크 모드</span>
                  <span className="theme-option-sub">야간 눈부심 방지 다크 슬레이트</span>
                </div>
                {theme === 'dark' && <Check size={18} className="text-primary" />}
              </button>
            </div>
          </div>

          <div className="settings-divider" />

          <div className="settings-section">
            <h4 className="settings-section-title">글자 크기 조절</h4>
            <p className="settings-section-desc">
              포럼 토픽 목록과 스레드 본문 전체의 글자 크기를 즉시 조정합니다.
            </p>

            <div className="fontsize-option-list">
              {FONT_SIZE_OPTIONS.map((option) => (
                <button
                  type="button"
                  key={option.id}
                  className={`fontsize-option-item ${fontSize === option.id ? 'active' : ''}`}
                  onClick={() => updateFontSize(option.id)}
                >
                  <div>
                    <div className="fontsize-label">{option.label}</div>
                    <div className={`fontsize-sample sample-${option.id}`}>{option.sample}</div>
                  </div>
                  {fontSize === option.id && <Check size={18} className="text-primary" />}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn btn-primary" onClick={onClose}>
            확인
          </button>
        </div>
      </div>
    </div>
  );
};

export default SettingsModal;
