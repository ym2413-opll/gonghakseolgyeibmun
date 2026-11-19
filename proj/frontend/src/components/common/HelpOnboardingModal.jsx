// src/components/common/HelpOnboardingModal.jsx
import React, { useState } from 'react';
import {
  HelpCircle,
  Sparkles,
  SlidersHorizontal,
  EyeOff,
  X,
  Bell,
  Sliders,
  Check
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { useNotifications } from '../../context/NotificationContext';

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
    desc: '새 질문·설정 등 핵심 화면 안내만 표시해요.',
    icon: SlidersHorizontal
  },
  {
    id: 'ALL',
    title: '모두',
    badge: '전체',
    desc: '수식 입력·문장 다듬기·필터·알림·설정·관리자 기능까지 모든 안내를 표시해요.',
    icon: Sparkles
  }
];

const HelpOnboardingModal = ({ onOpenSettings, onOpenProfSettings }) => {
  const { isAuthenticated, isProfessor, showHelpOnboarding, dismissHelpOnboarding } = useAuth();
  const { helpLevel, updateHelpLevel } = useTheme();
  const { browserPermission, requestPermission, sendTestNotification } = useNotifications();
  const [notifFeedback, setNotifFeedback] = useState({ type: '', text: '' });

  if (!isAuthenticated || !showHelpOnboarding) return null;

  const handleSelectHelp = async (level) => {
    try {
      await updateHelpLevel(level);
    } catch (err) {
      console.warn('Failed to update help level:', err);
    }
  };

  const handleEnableNotifications = async () => {
    setNotifFeedback({ type: '', text: '' });
    const perm = await requestPermission();
    if (perm === 'granted') {
      const sent = await sendTestNotification();
      setNotifFeedback({
        type: 'success',
        text: sent
          ? '브라우저 알림이 허용되었고 테스트 알림을 전송했습니다.'
          : '브라우저 알림 권한이 허용되었습니다.'
      });
    } else if (perm === 'denied') {
      setNotifFeedback({
        type: 'error',
        text: '브라우저 알림이 차단되어 있습니다. 주소창 사이트 설정에서 허용해 주세요.'
      });
    } else if (perm === 'insecure') {
      setNotifFeedback({
        type: 'error',
        text: '보안 연결(HTTPS 또는 localhost)에서 OS 브라우저 알림을 사용할 수 있습니다.'
      });
    }
  };

  const handleTestNotification = async () => {
    const sent = await sendTestNotification();
    setNotifFeedback(
      sent
        ? { type: 'success', text: '테스트 브라우저 알림을 전송했습니다.' }
        : { type: 'error', text: '브라우저 알림 생성에 실패했습니다.' }
    );
  };

  const handleOpenDetailedNotifSettings = () => {
    dismissHelpOnboarding();
    if (isProfessor && onOpenProfSettings) {
      onOpenProfSettings();
    } else if (onOpenSettings) {
      onOpenSettings();
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
            <Bell size={20} className="text-primary" />
            <span>가입 환영 · 알림 및 도움말 초기 설정</span>
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
          <div className="onboarding-notif-card">
            <div className="onboarding-section-heading">
              <Bell size={17} className="text-primary" />
              <span>알림 설정</span>
              {browserPermission === 'granted' ? (
                <span className="badge-notif-granted">허용됨</span>
              ) : browserPermission === 'denied' ? (
                <span className="badge-notif-denied">차단됨</span>
              ) : browserPermission === 'insecure' ? (
                <span className="badge-notif-denied">HTTPS 필요</span>
              ) : browserPermission === 'unsupported' ? (
                <span className="badge-notif-denied">미지원</span>
              ) : (
                <span className="badge-notif-default">미설정</span>
              )}
            </div>
            <p className="onboarding-notif-desc">
              {isProfessor
                ? '새 질문·꼬리질문 도착 시 브라우저 알림과 이메일·방해금지 시간대 알림을 받을 수 있습니다. 상단 "알림" 메뉴에서도 언제든 변경할 수 있어요.'
                : '교수님 답변이나 새 글이 올라오면 즉시 알림을 받습니다. 상단 "알림" 메뉴와 설정에서 언제든 변경할 수 있어요.'}
            </p>
            <div className="onboarding-notif-actions">
              {browserPermission === 'default' && (
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={handleEnableNotifications}
                >
                  <Bell size={15} />
                  브라우저 알림 허용하기
                </button>
              )}
              {browserPermission === 'granted' && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={handleTestNotification}
                >
                  <Bell size={15} />
                  테스트 알림 보내기
                </button>
              )}
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={handleOpenDetailedNotifSettings}
              >
                <Sliders size={14} />
                {isProfessor ? '알림·이메일·방해금지 세부 설정' : '알림 및 화면 설정 열기'}
              </button>
            </div>
            {notifFeedback.text && (
              <div
                className={`settings-inline-status ${
                  notifFeedback.type === 'error' ? 'is-error' : 'is-success'
                }`}
                style={{ marginTop: 8 }}
              >
                {notifFeedback.text}
              </div>
            )}
          </div>

          <div className="settings-divider" />

          <div className="onboarding-section-heading" style={{ marginBottom: 8 }}>
            <HelpCircle size={17} className="text-primary" />
            <span>도움말 표시 수준</span>
          </div>
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
                  onClick={() => handleSelectHelp(option.id)}
                >
                  <div className="help-onboarding-option-icon">
                    <Icon size={20} />
                  </div>
                  <div className="help-onboarding-option-body">
                    <div className="help-onboarding-option-head">
                      <span className="help-onboarding-option-title">{option.title}</span>
                      <span className="help-onboarding-option-badge">{option.badge}</span>
                      {isCurrent && <Check size={15} className="text-primary" style={{ marginLeft: 'auto' }} />}
                    </div>
                    <p className="help-onboarding-option-desc">{option.desc}</p>
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        <div className="modal-footer">
          <button type="button" className="btn btn-primary btn-sm" onClick={dismissHelpOnboarding}>
            <Check size={15} />
            설정 완료 · 시작하기
          </button>
        </div>
      </div>
    </div>
  );
};

export default HelpOnboardingModal;
