// src/components/shared/NotificationDropdown.jsx
import React from 'react';
import { useNotifications } from '../../context/NotificationContext';
import { useAuth } from '../../context/AuthContext';
import { Bell, CheckCheck, MessageSquare, Clock, Globe } from 'lucide-react';

export default function NotificationDropdown({ isOpen, onClose, onSelectQuestion }) {
  const {
    notifications,
    unreadCount,
    markAsRead,
    markAllAsRead,
    isCurrentlyInDnd,
    profSettings,
    browserPermission,
    requestPermission
  } = useNotifications();
  const { isProfessor } = useAuth();

  if (!isOpen) return null;

  const inDnd = Boolean(isProfessor && isCurrentlyInDnd());

  const formatTime = (ts) => {
    try {
      if (!ts) return '';
      const d = new Date(ts);
      return isNaN(d.getTime()) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return '';
    }
  };

  return (
    <div className="notif-dropdown" onClick={(e) => e.stopPropagation()}>
      <div className="notif-header">
        <div className="notif-title">
          <Bell size={16} />
          <span>알림함</span>
          {unreadCount > 0 && <span className="unread-badge">{unreadCount}</span>}
        </div>
        {unreadCount > 0 && (
          <button className="mark-all-read-btn" onClick={markAllAsRead}>
            <CheckCheck size={14} /> 모두 읽음
          </button>
        )}
      </div>

      {/* 브라우저 시스템 알림 허용 안내 바 */}
      {browserPermission !== 'granted' && browserPermission !== 'unsupported' ? (
        <div className="browser-notif-bar">
          <Globe size={13} />
          <span>실제 브라우저 팝업 알림을 받으시겠습니까?</span>
          <button
            type="button"
            className="btn-enable-browser-notif"
            onClick={requestPermission}
          >
            알림 허용
          </button>
        </div>
      ) : browserPermission === 'granted' ? (
        <div className="browser-notif-active-bar">
          <span className="dot-active">●</span>
          <span>브라우저 시스템 알림 켜짐 (질문/답변 시 데스크톱 알림 수신)</span>
        </div>
      ) : null}

      {/* 교수 방해 금지 모드 활성화 알림 */}
      {inDnd && (
        <div className="dnd-notice-banner">
          <Clock size={14} />
          <div>
            <strong>방해 금지 시간대 작동 중</strong>
            <p>({profSettings.dndStartTime} ~ {profSettings.dndEndTime}) 질문 알림이 조용히 누적됩니다.</p>
          </div>
        </div>
      )}

      <div className="notif-list">
        {notifications.length === 0 ? (
          <div className="notif-empty">
            <MessageSquare size={28} className="empty-icon" />
            <p>도착한 알림이 없습니다.</p>
          </div>
        ) : (
          notifications.map((n) => (
            <div
              key={n.id}
              className={`notif-item ${!n.isRead ? 'unread' : ''}`}
              onClick={() => {
                markAsRead(n.id);
                if (n.questionId && onSelectQuestion) {
                  onSelectQuestion(n.questionId);
                  onClose();
                }
              }}
            >
              <div className="notif-item-header">
                <span className={`notif-type-tag ${n.type}`}>
                  {n.type === 'new_answer' && '답변 완료'}
                  {n.type === 'new_question' && '새 질문'}
                  {n.type === 'co_prof_question' && '동일 과목/그룹'}
                </span>
                <span className="notif-time">
                  {formatTime(n.createdAt)}
                </span>
              </div>
              <div className="notif-item-title">{n.title}</div>
              <div className="notif-item-message">{n.message}</div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
