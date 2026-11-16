// src/components/common/Header.jsx
import React, { useState, useEffect, useRef } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { useNotifications } from '../../context/NotificationContext';
import HelpBubble from './HelpBubble';
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
  const { isGreen, isDark, toggleColorScheme, toggleBrightness, settings } = useTheme();
  const {
    notifications,
    unreadCount,
    loadNotifications,
    markAsRead,
    markAllAsRead,
    isCurrentlyInDnd,
    profSettings,
    browserPermission,
    requestPermission,
    sendTestNotification
  } = useNotifications();

  const [showNotifMenu, setShowNotifMenu] = useState(false);

  const notifRef = useRef(null);

  useEffect(() => {
    if (refreshTrigger > 0 && isAuthenticated && currentUser?.id) {
      void loadNotifications(false);
    }
  }, [refreshTrigger, isAuthenticated, currentUser?.id, loadNotifications]);

  useEffect(() => {
    const handleClickOutside = (e) => {
      if (notifRef.current && !notifRef.current.contains(e.target)) {
        setShowNotifMenu(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const inDnd = Boolean(isProfessor && isCurrentlyInDnd());

  const handleNotifClick = async (notif) => {
    await markAsRead(notif.id);
    setShowNotifMenu(false);
    if (notif.questionId && onSelectQuestion) {
      onSelectQuestion(notif.questionId);
    }
  };

  const handleMarkAllRead = async () => {
    await markAllAsRead();
  };

  const colorSchemeTooltip = isGreen
    ? '무채색 테마로 전환'
    : '초록 배색 테마로 전환';

  const brightnessTooltip = isDark
    ? '라이트 모드로 전환'
    : '다크 모드로 전환';

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
            <h1 className="brand-title">교수질문센터</h1>
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
            <HelpBubble
              tier="ALL"
              mode="floating"
              placement="bottom-end"
              text="방해금지 시간, 이메일·카테고리별 알림을 설정해요."
            >
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
            </HelpBubble>
          )}

          {/* 분리된 아이콘 전용 테마 전환 버튼 + 화면 설정 모달 버튼 */}
          <HelpBubble
            tier="SOME"
            mode="floating"
            placement="bottom-end"
            text="테마, 글자 크기, 과목, 도움말 표시를 설정해요."
          >
            <div className="header-theme-settings-group">
              <div className="theme-toggle-group" role="group" aria-label="화면 테마 전환">
                <button
                  type="button"
                  className={`btn-theme-chip ${isGreen ? 'active' : ''}`}
                  onClick={toggleColorScheme}
                  title={colorSchemeTooltip}
                  aria-label={colorSchemeTooltip}
                  aria-pressed={isGreen}
                >
                  <Leaf size={17} />
                </button>
                <button
                  type="button"
                  className={`btn-theme-chip ${isDark ? 'active' : ''}`}
                  onClick={toggleBrightness}
                  title={brightnessTooltip}
                  aria-label={brightnessTooltip}
                  aria-pressed={isDark}
                >
                  {isDark ? <Moon size={17} /> : <Sun size={17} />}
                </button>
              </div>

              <button
                type="button"
                className="btn-icon"
                onClick={onOpenSettings}
                title="화면 테마, 글자 크기 및 도움말 설정"
                aria-label="화면 설정"
              >
                <Settings size={18} />
              </button>
            </div>
          </HelpBubble>

          {/* 로그인 상태일 때만 알림 드롭다운 및 프로필/로그아웃 표시 */}
          {isAuthenticated ? (
            <>
              <div className="dropdown-wrapper" ref={notifRef}>
                <HelpBubble
                  tier="ALL"
                  mode="floating"
                  placement="bottom"
                  text="새 질문·답변 내역 확인과 브라우저 OS 알림 권한을 설정해요."
                >
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
                </HelpBubble>

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

                    {browserPermission === 'default' && (
                      <div className="browser-notif-bar">
                        <span>OS 브라우저 알림 권한이 꺼져 있습니다.</span>
                        <button
                          type="button"
                          className="btn-enable-browser-notif"
                          onClick={requestPermission}
                        >
                          허용
                        </button>
                      </div>
                    )}
                    {browserPermission === 'denied' && (
                      <div className="browser-notif-bar is-warn">
                        <span>브라우저 알림이 차단됨 — 주소창 권한 설정에서 허용해 주세요.</span>
                      </div>
                    )}
                    {browserPermission === 'insecure' && (
                      <div className="browser-notif-bar is-warn">
                        <span>HTTP(비보안 연결)에서는 브라우저 보안 정책상 알림이 차단됩니다. (HTTPS 필요)</span>
                      </div>
                    )}
                    {browserPermission === 'granted' && (
                      <div className="browser-notif-active-bar">
                        <span className="dot-active">●</span>
                        <span>브라우저 OS 알림 켜짐</span>
                        <button
                          type="button"
                          className="btn-text-xs"
                          style={{ marginLeft: 'auto' }}
                          onClick={sendTestNotification}
                        >
                          테스트
                        </button>
                      </div>
                    )}
                    {inDnd && (
                      <div className="dnd-notice-banner">
                        <div>
                          <strong>방해 금지 시간대 작동 중</strong>
                          <span style={{ display: 'block', fontSize: '0.74rem' }}>
                            ({profSettings?.dndStartTime} ~ {profSettings?.dndEndTime}) 알림이 조용히 누적됩니다.
                          </span>
                        </div>
                      </div>
                    )}

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
                  {(isAdmin && currentUser.nickname ? currentUser.nickname : currentUser.avatar || currentUser.name || 'U').charAt(0)}
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
                  <span className="user-display-tag">
                    {isAdmin && currentUser.nickname ? currentUser.nickname : currentUser.displayTag}
                  </span>
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
                <span>회원가입</span>
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};

export default Header;
