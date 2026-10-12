// src/components/professor/CategorySettingsModal.jsx
import React, { useState } from 'react';
import { useNotifications } from '../../context/NotificationContext';
import { CATEGORY_LIST } from '../../services/mockData';
import { SlidersHorizontal, X, Check, Save, ShieldAlert } from 'lucide-react';

export default function CategorySettingsModal({ isOpen, onClose }) {
  const { profSettings, updateProfSettings } = useNotifications();
  const [categories, setCategories] = useState({
    grade: profSettings.activeCategories?.grade ?? true,
    assignment: profSettings.activeCategories?.assignment ?? true,
    lecture: profSettings.activeCategories?.lecture ?? true
  });
  const [isSaved, setIsSaved] = useState(false);

  if (!isOpen) return null;

  const handleToggle = (catKey) => {
    setCategories((prev) => ({
      ...prev,
      [catKey]: !prev[catKey]
    }));
  };

  const handleSave = async () => {
    await updateProfSettings({
      activeCategories: categories
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
            <SlidersHorizontal size={18} />
            <span>카테고리별 질문 활성화/비활성화 설정</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <p className="modal-intro-text">
            학기 운영 일정(예: 성적 정정 기간 종료, 과제 마감 후 등)에 따라 특정 카테고리로의 질문 접수를 활성화하거나 차단할 수 있습니다.
          </p>

          <div className="category-toggle-list">
            {CATEGORY_LIST.map((cat) => {
              const isEnabled = categories[cat.id];
              return (
                <div key={cat.id} className={`category-toggle-card ${isEnabled ? 'active' : 'disabled'}`}>
                  <div className="cat-card-info">
                    <div className="cat-badge-wrap">
                      <span className={`cat-badge ${cat.badgeClass}`}>{cat.name}</span>
                      <span className="cat-status-badge">{isEnabled ? '접수 중' : '접수 마감'}</span>
                    </div>
                    <div className="cat-card-desc">{cat.description}</div>
                    <ul className="cat-rule-hints">
                      {cat.rules.map((r, idx) => (
                        <li key={idx}>• {r}</li>
                      ))}
                    </ul>
                  </div>

                  <div className="cat-card-action">
                    <label className="switch">
                      <input
                        type="checkbox"
                        checked={isEnabled}
                        onChange={() => handleToggle(cat.id)}
                      />
                      <span className="slider round"></span>
                    </label>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="backend-spec-notice">
            <ShieldAlert size={14} />
            <span>
              비활성화된 카테고리는 학생들이 질문을 등록할 때 선택창에서 자동으로 비활성화 처리됩니다.
            </span>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn btn-secondary" onClick={onClose}>
            취소
          </button>
          <button className="btn btn-primary" onClick={handleSave} disabled={isSaved}>
            {isSaved ? (
              <>
                <Check size={16} /> 저장 완료
              </>
            ) : (
              <>
                <Save size={16} /> 카테고리 설정 적용
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
