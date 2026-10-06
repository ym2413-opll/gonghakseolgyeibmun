// src/components/common/Header.jsx
import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useNotifications } from '../../context/NotificationContext';
import NotificationDropdown from '../shared/NotificationDropdown';
import SettingsModal from '../shared/SettingsModal';
import {
  GraduationCap,
  Bell,
  Settings,
  Plus
} from 'lucide-react';

export default function Header({
  onGoHome,
  onOpenNewQuestion,
  onSelectQuestion,
  currentView
}) {
  const { currentUser, isProfessor } = useAuth();
  const { unreadCount } = useNotifications();

  const [isNotifOpen, setIsNotifOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  return (
    <header className="main-header">
      <div className="header-container">
        {/* 좌측: 로고 및 서비스 명칭 */}
        <div className="header-brand" onClick={onGoHome} role="button" tabIndex={0}>
          <div className="brand-logo-icon">
            <GraduationCap size={24} />
          </div>
          <div className="brand-text">
            <h1 className="brand-title">교수질문센터</h1>
            <span className="brand-subtitle">
              {isProfessor ? '교수용 질문관리 포털' : '대학생 학업 질답 서비스'}
            </span>
          </div>
        </div>

        {/* 우측 유틸리티 액션 */}
        <div className="header-actions">
          {/* 학생 전용: 상단 질문 작성 버튼 */}
          {!isProfessor && (
            <button
              className="header-action-btn write-btn hide-on-mobile-strict"
              onClick={onOpenNewQuestion}
            >
              <Plus size={16} />
              <span>질문하기</span>
            </button>
          )}

          {/* 알림 버튼 & 드롭다운 */}
          <div className="notif-wrapper">
            <button
              className={`header-icon-btn ${isNotifOpen ? 'active' : ''}`}
              onClick={() => setIsNotifOpen(!isNotifOpen)}
              title="알림함"
            >
              <Bell size={19} />
              {unreadCount > 0 && <span className="header-notif-dot">{unreadCount}</span>}
            </button>

            <NotificationDropdown
              isOpen={isNotifOpen}
              onClose={() => setIsNotifOpen(false)}
              onSelectQuestion={onSelectQuestion}
            />
          </div>

          {/* 화면 설정 버튼 (글자 크기, 테마) */}
          <button
            className="header-icon-btn"
            onClick={() => setIsSettingsOpen(true)}
            title="화면 및 글자 크기 설정"
          >
            <Settings size={19} />
          </button>

          {/* 현재 로그인 프로필 칩 */}
          <div className="header-user-chip">
            <div className="chip-avatar">{currentUser?.avatar}</div>
            <div className="chip-details hide-on-mobile-strict">
              <span className="chip-name">{currentUser?.name}</span>
              <span className="chip-role">
                {isProfessor ? `${currentUser?.title || '교수'}` : `${currentUser?.department || '학생'}`}
              </span>
            </div>
          </div>
        </div>
      </div>

      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
      />
    </header>
  );
}
