// src/context/NotificationContext.jsx
import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';
import { useTheme } from './ThemeContext';
import {
  getBrowserNotificationPermission,
  requestBrowserNotificationPermission,
  sendBrowserNotification
} from '../utils/browserNotification';

const NotificationContext = createContext();

const DEFAULT_PROF_SETTINGS = {
  dndEnabled: false,
  dndStartTime: '22:00',
  dndEndTime: '08:00',
  coProfNotification: true,
  activeCategories: {
    grade: true,
    assignment: true,
    lecture: true
  }
};

export function NotificationProvider({ children }) {
  const { currentUser, isProfessor } = useAuth();
  const { settings: themeSettings } = useTheme();
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [browserPermission, setBrowserPermission] = useState(getBrowserNotificationPermission());

  // 마지막으로 브라우저 시스템 알림을 띄운 알림 ID 목록 추적 (중복 발송 방지)
  const notifiedIdsRef = useRef(new Set());
  const lastPollAtRef = useRef(0);

  // 교수 전용 알림 & 카테고리 환경설정
  const [profSettings, setProfSettings] = useState(DEFAULT_PROF_SETTINGS);
  const effectiveProfSettings = themeSettings || profSettings;

  // 브라우저 권한 요청 함수
  const requestPermission = async () => {
    const result = await requestBrowserNotificationPermission();
    setBrowserPermission(result);
    return result;
  };

  const sendTestNotification = useCallback(() => {
    setBrowserPermission(getBrowserNotificationPermission());
    return sendBrowserNotification('교수질문센터 테스트 알림', {
      body: '새 질문·꼬리질문·답변이 등록되면 이와 같이 브라우저 알림이 표시됩니다.',
      tag: `cistus-test-${Date.now()}`
    });
  }, []);

  // 현재 시각이 방해금지 시간대에 해당하는지 안전하게 검사
  const isCurrentlyInDnd = useCallback(() => {
    const activeSettings = effectiveProfSettings;
    if (!activeSettings?.dndEnabled) return false;
    if (!activeSettings?.dndStartTime || !activeSettings?.dndEndTime) return false;

    try {
      const now = new Date();
      const currentMinutes = now.getHours() * 60 + now.getMinutes();

      const [startH, startM] = activeSettings.dndStartTime.split(':').map(Number);
      const [endH, endM] = activeSettings.dndEndTime.split(':').map(Number);

      if (isNaN(startH) || isNaN(startM) || isNaN(endH) || isNaN(endM)) {
        return false;
      }

      const startMinutes = startH * 60 + startM;
      const endMinutes = endH * 60 + endM;

      if (startMinutes <= endMinutes) {
        return currentMinutes >= startMinutes && currentMinutes < endMinutes;
      } else {
        // 자정을 걸치는 경우 (예: 22:00 ~ 08:00)
        return currentMinutes >= startMinutes || currentMinutes < endMinutes;
      }
    } catch {
      return false;
    }
  }, [effectiveProfSettings]);

  const markAsRead = useCallback(async (id) => {
    try {
      await api.markNotificationRead(id, currentUser?.id);
      setNotifications(prev => {
        const next = prev.map(n => (n.id === id ? { ...n, isRead: true } : n));
        setUnreadCount(next.filter(n => !n.isRead).length);
        return next;
      });
    } catch (err) {
      console.warn('Failed to mark notification read:', err);
    }
  }, [currentUser?.id]);

  const loadNotifications = useCallback(async (triggerBrowser = false) => {
    setBrowserPermission(getBrowserNotificationPermission());
    if (!currentUser?.id) {
      setNotifications([]);
      setUnreadCount(0);
      return;
    }
    lastPollAtRef.current = Date.now();
    try {
      const list = await api.getNotifications(currentUser.id);
      const safeList = Array.isArray(list) ? list : [];
      setNotifications(safeList);
      setUnreadCount(safeList.filter(n => !n.isRead).length);

      // 브라우저 시스템 알림 트리거 (새 미읽음 알림이 존재하고, 방해금지 시간대가 아닐 때)
      if (triggerBrowser) {
        const newUnreads = safeList.filter(n => !n.isRead && !notifiedIdsRef.current.has(n.id));
        newUnreads.forEach(n => notifiedIdsRef.current.add(n.id));

        if (newUnreads.length > 0) {
          window.dispatchEvent(
            new CustomEvent('cistus:notifications-updated', { detail: { count: newUnreads.length } })
          );
          const inDnd = isProfessor ? isCurrentlyInDnd() : false;
          if (!inDnd && getBrowserNotificationPermission() === 'granted') {
            newUnreads.slice(0, 3).forEach((item) => {
              sendBrowserNotification(item.title, {
                body: item.message,
                tag: item.id,
                onClick: () => {
                  void markAsRead(item.id);
                  if (item.questionId) {
                    window.dispatchEvent(
                      new CustomEvent('cistus:open-question', { detail: { questionId: item.questionId } })
                    );
                  }
                }
              });
            });
          }
        }
      } else {
        // 최초 로드 시에는 기존 알림들을 등록해 두어 중복 알림 방지
        safeList.forEach(n => notifiedIdsRef.current.add(n.id));
      }
    } catch (err) {
      console.error('Failed to load notifications', err);
      setNotifications([]);
      setUnreadCount(0);
    }
  }, [currentUser?.id, isProfessor, isCurrentlyInDnd, markAsRead]);

  const loadProfSettings = useCallback(async () => {
    if (!currentUser?.id) return;
    try {
      const s = await api.getSettings(currentUser.id);
      if (s && typeof s === 'object') {
        setProfSettings(prev => ({
          ...prev,
          dndEnabled: Boolean(s.dndEnabled),
          dndStartTime: s.dndStartTime || '22:00',
          dndEndTime: s.dndEndTime || '08:00',
          coProfNotification: s.coProfNotification !== undefined ? s.coProfNotification : true,
          activeCategories: s.activeCategories || { grade: true, assignment: true, lecture: true }
        }));
      }
    } catch (err) {
      console.error('Failed to load professor settings', err);
    }
  }, [currentUser?.id]);

  useEffect(() => {
    // 유저 변경 시 이미 발송한 알림 목록 초기화
    notifiedIdsRef.current = new Set();
    if (!currentUser?.id) {
      return;
    }
    void loadNotifications(false);
    if (isProfessor) {
      void loadProfSettings();
    }

    const pollInterval = setInterval(() => {
      if (!document.hidden) {
        void loadNotifications(true);
      }
    }, 15000);

    const handleWakePoll = () => {
      if (document.hidden) return;
      setBrowserPermission(getBrowserNotificationPermission());
      if (Date.now() - lastPollAtRef.current >= 3000) {
        void loadNotifications(true);
      }
    };

    window.addEventListener('focus', handleWakePoll);
    document.addEventListener('visibilitychange', handleWakePoll);
    return () => {
      clearInterval(pollInterval);
      window.removeEventListener('focus', handleWakePoll);
      document.removeEventListener('visibilitychange', handleWakePoll);
    };
  }, [currentUser?.id, isProfessor, loadNotifications, loadProfSettings]);

  const markAllAsRead = async () => {
    try {
      for (const n of notifications.filter(x => !x.isRead)) {
        await api.markNotificationRead(n.id, currentUser?.id);
      }
      setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
      setUnreadCount(0);
    } catch (err) {
      console.warn('Failed to mark all notifications read:', err);
    }
  };

  const updateProfSettings = async (partialSettings) => {
    const previous = profSettings;
    const updated = { ...profSettings, ...partialSettings };
    setProfSettings(updated);
    if (currentUser?.id) {
      try {
        await api.updateSettings(currentUser.id, updated);
      } catch (err) {
        setProfSettings(previous);
        throw err;
      }
    }
  };

  const exposedNotifications = currentUser?.id ? notifications : [];
  const exposedUnreadCount = currentUser?.id ? unreadCount : 0;

  return (
    <NotificationContext.Provider
      value={{
        notifications: exposedNotifications,
        unreadCount: exposedUnreadCount,
        loadNotifications,
        markAsRead,
        markAllAsRead,
        profSettings: effectiveProfSettings,
        updateProfSettings,
        isCurrentlyInDnd,
        browserPermission,
        requestPermission,
        sendTestNotification
      }}
    >
      {children}
    </NotificationContext.Provider>
  );
}

export function useNotifications() {
  const context = useContext(NotificationContext);
  if (!context) {
    throw new Error('useNotifications must be used within a NotificationProvider');
  }
  return context;
}
