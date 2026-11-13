// src/components/common/SettingsModal.jsx
import React from 'react';
import { useTheme } from '../../context/ThemeContext';
import { Sun, Moon, Leaf, Type, X, Check } from 'lucide-react';

const FONT_SIZE_OPTIONS = [
  { id: 'sm', label: '작게', sample: '가나다' },
  { id: 'md', label: '보통', sample: '가나다' },
  { id: 'lg', label: '크게', sample: '가나다' },
  { id: 'xl', label: '아주 크게', sample: '가나다' }
];

const SettingsModal = ({ isOpen, onClose }) => {
  const { theme, fontSize, updateTheme, updateFontSize } = useTheme();

  if (!isOpen) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-container modal-md" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Type size={19} className="text-primary" />
            <span>화면 설정</span>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <div className="settings-section">
            <h4 className="settings-section-title">테마</h4>

            <div className="theme-selector-grid theme-selector-grid-3">
              <button
                type="button"
                className={`theme-option-card ${theme === 'light' ? 'active' : ''}`}
                onClick={() => updateTheme('light')}
              >
                <div className="theme-preview light-preview">
                  <Sun size={20} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">라이트</span>
                </div>
                {theme === 'light' && <Check size={16} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'green' ? 'active' : ''}`}
                onClick={() => updateTheme('green')}
              >
                <div className="theme-preview green-preview">
                  <Leaf size={20} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">그린</span>
                </div>
                {theme === 'green' && <Check size={16} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'dark' ? 'active' : ''}`}
                onClick={() => updateTheme('dark')}
              >
                <div className="theme-preview dark-preview">
                  <Moon size={20} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">다크</span>
                </div>
                {theme === 'dark' && <Check size={16} className="text-primary" />}
              </button>
            </div>
          </div>

          <div className="settings-divider" />

          <div className="settings-section" style={{ marginBottom: 0 }}>
            <h4 className="settings-section-title">글자 크기</h4>

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
                  {fontSize === option.id && <Check size={16} className="text-primary" />}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button type="button" className="btn btn-primary btn-sm" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
};

export default SettingsModal;
