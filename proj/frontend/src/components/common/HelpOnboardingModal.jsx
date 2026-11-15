// src/components/common/HelpOnboardingModal.jsx
import React from 'react';
import { HelpCircle, Sparkles, SlidersHorizontal, EyeOff, X } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';

const HELP_OPTIONS = [
  {
    id: 'NONE',
    title: '필요 없음',
    badge: '숨김',
    desc: '도움말을 표시하지 않아요.',
    icon: EyeOff
  },
  {
    id: 'SOME',
    title: '조금',
    badge: '추천',
    desc: '핵심 기능만 표시해요.',
    icon: SlidersHorizontal
  },
  {
    id: 'ALL',
    title: '모두',
    badge: '전체',
    desc: '모든 기능에 표시해요.',
    icon: Sparkles
  }
];

const HelpOnboardingModal = () => {
  const { isAuthenticated, showHelpOnboarding, dismissHelpOnboarding } = useAuth();
  const { helpLevel, updateHelpLevel } = useTheme();

  if (!isAuthenticated || !showHelpOnboarding) return null;

  const handleSelect = async (level) => {
    try {
      await updateHelpLevel(level);
    } finally {
      dismissHelpOnboarding();
    }
  };

  return (
    <div className="modal-backdrop help-onboarding-backdrop" onClick={dismissHelpOnboarding}>
      <div
        className="modal-container modal-md help-onboarding-modal"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="help-onboarding-title"
      >
        <div className="modal-header">
          <div className="modal-title" id="help-onboarding-title">
            <HelpCircle size={20} className="text-primary" />
            <span>도움말 설정</span>
          </div>
          <button
            type="button"
            className="btn-icon"
            onClick={dismissHelpOnboarding}
            aria-label="닫기"
          >
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <p className="help-onboarding-intro">
            말풍선 도움말 표시 수준을 선택해요. 오른쪽 위 설정에서 언제든 바꿀 수 있어요.
          </p>

          <div className="help-onboarding-options">
            {HELP_OPTIONS.map((option) => {
              const Icon = option.icon;
              const isCurrent = helpLevel === option.id;
              return (
                <button
                  key={option.id}
                  type="button"
                  className={`help-onboarding-option ${isCurrent ? 'active' : ''}`}
                  onClick={() => handleSelect(option.id)}
                >
                  <div className="help-onboarding-option-icon">
                    <Icon size={20} />
                  </div>
                  <div className="help-onboarding-option-body">
                    <div className="help-onboarding-option-head">
                      <span className="help-onboarding-option-title">{option.title}</span>
                      <span className="help-onboarding-option-badge">{option.badge}</span>
                    </div>
                    <p className="help-onboarding-option-desc">{option.desc}</p>
                  </div>
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};

export default HelpOnboardingModal;
