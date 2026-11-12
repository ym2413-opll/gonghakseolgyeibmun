// src/components/common/Header.jsx
import React, { useState, useEffect, useRef } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { api } from '../../services/api';
import {
  Bell,
  Settings,
  Sun,
  Moon,
  Leaf,
  GraduationCap,
  BookOpen,
  ShieldAlert,
  Sliders,
  CheckCheck,
  LogIn,
  UserPlus,
  LogOut
} from 'lucide-react';

const Header = ({
  onOpenSettings,
  onOpenProfSettings,
  onOpenAdminModal,
  onSelectQuestion,
  refreshTrigger
}) => {
  const {
    currentUser,
    isAuthenticated,
    isProfessor,
    isAdmin,
    logout,
    setAuthModalMode
  } = useAuth();
  const { theme, toggleTheme, settings } = useTheme();

  const [notifications, setNotifications] = useState([]);
  const [showNotifMenu, setShowNotifMenu] = useState(false);

  const notifRef = useRef(null);

  useEffect(() => {
    let mounted = true;
    async function loadNotifs() {
      if (!isAuthenticated || !currentUser?.id) {
        if (mounted) setNotifications([]);
        return;
      }
      const list = await api.getNotifications(currentUser.id);
      if (mounted) setNotifications(list);
    }
    loadNotifs();
    return () => {
      mounted = false;
    };
  }, [isAuthenticated, currentUser?.id, refreshTrigger]);

  useEffect(() => {
    const handleClickOutside = (e) => {
      if (notifRef.current && !notifRef.current.contains(e.target)) {
        setShowNotifMenu(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const unreadCount = notifications.filter((n) => !n.isRead).length;

  const handleNotifClick = async (notif) => {
    await api.markNotificationRead(notif.id, currentUser?.id);
    setNotifications((prev) =>
      prev.map((n) => (n.id === notif.id ? { ...n, isRead: true } : n))
    );
    setShowNotifMenu(false);
    if (notif.questionId && onSelectQuestion) {
      onSelectQuestion(notif.questionId);
    }
  };

  const handleMarkAllRead = async () => {
    for (const n of notifications) {
      if (!n.isRead) {
        await api.markNotificationRead(n.id, currentUser?.id);
      }
    }
    setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
  };

  const renderThemeIcon = () => {
    if (theme === 'light') return <Leaf size={18} />;
    if (theme === 'green') return <Moon size={18} />;
    return <Sun size={18} />;
  };

  const themeTooltip =
    theme === 'light'
      ? '초록색조(눈 보호) 테마로 전환'
      : theme === 'green'
      ? '다크 모드로 전환'
      : '라이트 모드로 전환';

  return (
    <header className="app-header">
      <div className="header-inner">
        {/* 좌측 브랜드 로고 */}
        <div
          className="brand-area"
          onClick={() => onSelectQuestion && onSelectQuestion(null)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter') onSelectQuestion && onSelectQuestion(null);
          }}
        >
          <div className="brand-logo">
            <GraduationCap size={24} />
          </div>
          <div className="brand-text">
            <span className="brand-univ">KWANGWOON UNIVERSITY FORUM</span>
            <h1 className="brand-title">광운 학업 Q&amp;A 포럼</h1>
          </div>
        </div>

        {/* 우측 컨트롤 바 */}
        <div className="header-actions">
          {/* 관리자 모드 버튼 (관리자 권한 보유 시 표시) */}
          {isAdmin && (
            <button
              type="button"
              className="btn btn-danger btn-sm header-admin-btn"
              onClick={onOpenAdminModal}
              title="관리자 모드 (과목/학과 추가, 회원 제재, 익명 실명 열람, 게시물 가리기)"
            >
              <ShieldAlert size={16} />
              <span className="hide-mobile">관리 모드</span>
            </button>
          )}

          {/* 교수 전용 알림·이메일·카테고리 설정 버튼 */}
          {isProfessor && (
            <button
              type="button"
              className="btn btn-outline btn-sm prof-setting-btn"
              onClick={onOpenProfSettings}
              title="교수용 질문 알림·이메일 및 카테고리 설정"
            >
              <Sliders size={16} />
              <span className="hide-mobile">알림·이메일 설정</span>
              {settings?.dndEnabled && (
                <span className="dnd-badge">
                  DND {settings.dndStartTime}~{settings.dndEndTime}
                </span>
              )}
            </button>
          )}

          {/* 빠른 테마 전환 버튼 (Light -> Green -> Dark) */}
          <button
            type="button"
            className="btn-icon"
            onClick={toggleTheme}
            title={themeTooltip}
            aria-label="테마 전환"
          >
            {renderThemeIcon()}
          </button>

          {/* 글자 크기 및 테마 상세 설정 모달 버튼 */}
          <button
            type="button"
            className="btn-icon"
            onClick={onOpenSettings}
            title="화면 테마 및 글자 크기 설정"
            aria-label="화면 설정"
          >
            <Settings size={18} />
          </button>

          {/* 로그인 상태일 때만 알림 드롭다운 및 프로필/로그아웃 표시 */}
          {isAuthenticated ? (
            <>
              <div className="dropdown-wrapper" ref={notifRef}>
                <button
                  type="button"
                  className="btn-icon notif-trigger"
                  onClick={() => setShowNotifMenu((prev) => !prev)}
                  title="알림 목록"
                  aria-label="알림"
                >
                  <Bell size={18} />
                  {unreadCount > 0 && (
                    <span className="notif-count">{unreadCount}</span>
                  )}
                </button>

                {showNotifMenu && (
                  <div className="dropdown-menu notif-dropdown">
                    <div className="dropdown-header">
                      <span className="dropdown-title">알림 센터</span>
                      {unreadCount > 0 && (
                        <button
                          type="button"
                          className="btn-text-xs"
                          onClick={handleMarkAllRead}
                        >
                          <CheckCheck size={14} /> 모두 읽음
                        </button>
                      )}
                    </div>

                    <div className="notif-list">
                      {notifications.length === 0 ? (
                        <div className="notif-empty">새로운 알림이 없습니다.</div>
                      ) : (
                        notifications.map((n) => (
                          <div
                            key={n.id}
                            className={`notif-item ${!n.isRead ? 'unread' : ''}`}
                            onClick={() => handleNotifClick(n)}
                          >
                            <div className="notif-item-header">
                              <span className="notif-item-title">{n.title}</span>
                              {!n.isRead && <span className="unread-dot" />}
                            </div>
                            <p className="notif-item-msg">{n.message}</p>
                            <span className="notif-item-time">
                              {new Date(n.createdAt).toLocaleString('ko-KR', {
                                month: 'short',
                                day: 'numeric',
                                hour: '2-digit',
                                minute: '2-digit'
                              })}
                            </span>
                          </div>
                        ))
                      )}
                    </div>
                  </div>
                )}
              </div>

              <div className="auth-user-pill">
                <div className={`user-avatar role-${currentUser.role}`}>
                  {currentUser.avatar || currentUser.name?.charAt(0) || 'U'}
                </div>
                <div className="user-info-text hide-mobile">
                  <span className="user-role-badge">
                    {isProfessor ? (
                      <>
                        <BookOpen size={11} /> 교수
                      </>
                    ) : (
                      <>
                        <GraduationCap size={11} /> 학생
                      </>
                    )}
                    {isAdmin && <strong className="admin-mini-tag"> · ADMIN</strong>}
                  </span>
                  <span className="user-display-tag">{currentUser.displayTag}</span>
                </div>
                <button
                  type="button"
                  className="btn btn-secondary btn-xs logout-btn"
                  onClick={logout}
                  title="로그아웃"
                >
                  <LogOut size={14} />
                  <span className="hide-mobile">로그아웃</span>
                </button>
              </div>
            </>
          ) : (
            <div className="header-guest-btns">
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => setAuthModalMode('login')}
              >
                <LogIn size={15} />
                <span>로그인</span>
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() => setAuthModalMode('register')}
              >
                <UserPlus size={15} />
                <span className="hide-mobile">전시용 </span>회원가입
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};

export default Header;
