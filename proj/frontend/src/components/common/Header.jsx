// src/components/common/Header.jsx
// 광운대학교 학업 Q&A 센터 공식 상단 헤더 및 계정 관리 유틸리티

import React, { useState, useRef, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { useNotifications } from '../../context/NotificationContext';
import NotificationDropdown from '../shared/NotificationDropdown';
import SettingsModal from '../shared/SettingsModal';
import { api } from '../../services/api';
import {
  GraduationCap,
  Bell,
  Settings,
  Plus,
  Monitor,
  Smartphone,
  Sun,
  Moon,
  ChevronDown,
  UserCheck,
  RotateCcw,
  Check,
  ShieldCheck,
  BookOpen
} from 'lucide-react';

export default function Header({
  onGoHome,
  onOpenNewQuestion,
  onSelectQuestion,
  currentView,
  isMobile,
  onResetData
}) {
  const { currentUser, presetUsers, switchUser, isProfessor } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const { unreadCount } = useNotifications();

  const [isNotifOpen, setIsNotifOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isUserMenuOpen, setIsUserMenuOpen] = useState(false);

  const userMenuRef = useRef(null);

  // 외부 클릭 시 사용자 메뉴 닫기
  useEffect(() => {
    function handleClickOutside(e) {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target)) {
        setIsUserMenuOpen(false);
      }
    }
    if (isUserMenuOpen) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isUserMenuOpen]);

  const handleSyncData = async () => {
    if (window.confirm('로컬 캐시를 초기화하고 서버의 최신 학사 및 질답 데이터를 새로고침하시겠습니까?')) {
      await api.resetToDefaults();
      if (onResetData) await onResetData();
      window.location.reload();
    }
  };

  const studentUsers = presetUsers.filter(u => u.role === 'student' || u.role === 'STUDENT');
  const profUsers = presetUsers.filter(u => u.role === 'professor' || u.role === 'PROFESSOR');

  return (
    <header className="main-header">
      <div className="header-container">
        <div className="header-brand" onClick={onGoHome} role="button" tabIndex={0}>
          <div className="brand-text">
            <h1 className="brand-title">학업 Q&A</h1>
          </div>
        </div>

        {/* 우측 유틸리티 액션 */}
        <div className="header-actions">
          {/* 학생 전용: 상단 질문 작성 버튼 (데스크톱 화면용) */}
          {!isProfessor && (
            <button
              className="header-action-btn write-btn hide-on-mobile-strict"
              onClick={onOpenNewQuestion}
            >
              <Plus size={16} />
              <span>질문하기</span>
            </button>
          )}

          {/* 테마 간편 전환 버튼 */}
          <button
            className="header-icon-btn theme-toggle-btn"
            onClick={toggleTheme}
            title={`화면 테마 전환 (현재: ${theme === 'dark' ? '다크 모드' : '라이트 모드'})`}
          >
            {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
          </button>

          {/* 알림 버튼 & 드롭다운 */}
          <div className="notif-wrapper">
            <button
              className={`header-icon-btn ${isNotifOpen ? 'active' : ''}`}
              onClick={() => setIsNotifOpen(!isNotifOpen)}
              title="학업 알림함"
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

          {/* 화면 설정 버튼 (글자 크기, 테마 상세) */}
          <button
            className="header-icon-btn"
            onClick={() => setIsSettingsOpen(true)}
            title="화면 및 글자 크기 설정"
          >
            <Settings size={19} />
          </button>

          {/* 포털 로그인 프로필 칩 및 사용자 전환 드롭다운 */}
          <div className="user-profile-menu-container" ref={userMenuRef}>
            <div
              className={`header-user-chip ${isUserMenuOpen ? 'active' : ''}`}
              onClick={() => setIsUserMenuOpen(!isUserMenuOpen)}
              role="button"
              tabIndex={0}
              title="포털 계정 정보 및 사용자 전환"
            >
              <div className="chip-avatar">{currentUser?.avatar}</div>
              <div className="chip-details hide-on-mobile-strict">
                <span className="chip-name">{currentUser?.name}</span>
                <span className="chip-role">
                  {isProfessor ? `${currentUser?.department || ''} 교수` : `${currentUser?.department || '학생'}`}
                </span>
              </div>
              <ChevronDown size={14} className={`chip-chevron ${isUserMenuOpen ? 'rotated' : ''}`} />
            </div>

            {/* 통합포털 계정 관리 드롭다운 메뉴 */}
            {isUserMenuOpen && (
              <div className="user-account-dropdown">
                {/* 1. 현재 로그인 사용자 카드 */}
                <div className="current-user-card">
                  <div className="card-avatar-large">{currentUser?.avatar}</div>
                  <div className="card-info">
                    <div className="card-name-row">
                      <span className="card-name">{currentUser?.name}</span>
                      <span className={`card-role-badge ${isProfessor ? 'prof-badge' : 'student-badge'}`}>
                        {isProfessor ? '교원' : '학부생'}
                      </span>
                    </div>
                    <div className="card-sub-info">
                      <span>{currentUser?.department}</span>
                      {(currentUser?.studentId || currentUser?.identifier) && (
                        <>
                          <span> · </span>
                          <span>{currentUser?.studentId || currentUser?.identifier}</span>
                        </>
                      )}
                    </div>
                  </div>
                </div>

                <div className="account-dropdown-divider" />

                {/* 2. 등록 계정 빠른 전환 목록 */}
                <div className="account-switcher-section">
                  <div className="switcher-header">
                    <span>계정 전환</span>
                  </div>

                  {/* 학부생 계정 그룹 */}
                  <div className="account-group">
                    <div className="group-title">학부생 (수강생)</div>
                    {studentUsers.map((u) => {
                      const isCurrent = u.id === currentUser?.id;
                      return (
                        <button
                          key={u.id}
                          className={`account-switch-item ${isCurrent ? 'selected' : ''}`}
                          onClick={() => {
                            switchUser(u.id);
                            setIsUserMenuOpen(false);
                          }}
                        >
                          <span className="switch-item-avatar">{u.avatar}</span>
                          <div className="switch-item-details">
                            <span className="switch-item-name">{u.name}</span>
                            <span className="switch-item-meta">
                              {u.department} · {u.studentId || u.identifier}
                            </span>
                          </div>
                          {isCurrent && <Check size={15} className="switch-item-check" />}
                        </button>
                      );
                    })}
                  </div>

                  {/* 교수자 계정 그룹 */}
                  <div className="account-group">
                    <div className="group-title">교원 (담당교수)</div>
                    {profUsers.map((u) => {
                      const isCurrent = u.id === currentUser?.id;
                      return (
                        <button
                          key={u.id}
                          className={`account-switch-item ${isCurrent ? 'selected' : ''}`}
                          onClick={() => {
                            switchUser(u.id);
                            setIsUserMenuOpen(false);
                          }}
                        >
                          <span className="switch-item-avatar">{u.avatar}</span>
                          <div className="switch-item-details">
                            <span className="switch-item-name">{u.name}</span>
                            <span className="switch-item-meta">
                              {u.department} · {u.displayTag || '교수'}
                            </span>
                          </div>
                          {isCurrent && <Check size={15} className="switch-item-check" />}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="account-dropdown-divider" />

                {/* 3. 데이터 동기화 액션 */}
                <div className="dropdown-footer-actions">
                  <button
                    className="dropdown-footer-btn"
                    onClick={handleSyncData}
                  >
                    <RotateCcw size={13} />
                    <span>초기화</span>
                  </button>
                </div>
              </div>
            )}
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
