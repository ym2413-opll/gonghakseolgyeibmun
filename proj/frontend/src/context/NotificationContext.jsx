// src/context/NotificationContext.jsx
import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';
import { useTheme } from './ThemeContext';
import {
  getBrowserNotificationPermission,
  requestBrowserNotificationPermission,
  sendBrowserNotification,
  syncBrowserPushSubscription
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

const NOTIFIED_STORAGE_PREFIX = 'cistus_notified_ids_v1_';
const RECENT_INITIAL_WINDOW_MS = 30 * 60 * 1000; // 최초 로그인/재접속 시 최근 30분 이내 미읽음 알림은 안내

function getNotifiedStorageKey(userId) {
  return `${NOTIFIED_STORAGE_PREFIX}${userId}`;
}

function hasPersistedNotifiedHistory(userId) {
  if (!userId || typeof localStorage === 'undefined') return false;
  try {
    return localStorage.getItem(getNotifiedStorageKey(userId)) !== null;
  } catch {
    return false;
  }
}

function readPersistedNotifiedIds(userId) {
  if (!userId || typeof localStorage === 'undefined') return new Set();
  try {
    const raw = localStorage.getItem(getNotifiedStorageKey(userId));
    const parsed = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(parsed) ? parsed : []);
  } catch {
    return new Set();
  }
}

function writePersistedNotifiedIds(userId, idSet) {
  if (!userId || typeof localStorage === 'undefined') return;
  try {
    const arr = Array.from(idSet).slice(-200);
    localStorage.setItem(getNotifiedStorageKey(userId), JSON.stringify(arr));
  } catch {
    // Ignore storage quota issues
  }
}

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
    if (result === 'granted' && currentUser?.id) {
      void syncBrowserPushSubscription(api);
    }
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

  const isProfessorRef = useRef(isProfessor);
  const isCurrentlyInDndRef = useRef(isCurrentlyInDnd);
  useEffect(() => {
    isProfessorRef.current = isProfessor;
    isCurrentlyInDndRef.current = isCurrentlyInDnd;
  }, [isProfessor, isCurrentlyInDnd]);

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

      const nowMs = Date.now();
      const hadHistory = hasPersistedNotifiedHistory(currentUser.id);
      const unnotifiedUnreads = safeList.filter(
        n => !n.isRead && !notifiedIdsRef.current.has(n.id)
      );

      // 이전 세션 기록이 있는 사용자는 방해금지 해제 후 도착한 누적 알림(>30분)도 놓치지 않도록 즉시 안내하고,
      // 최초 접속 기기에서만 최근 도착 알림(30분 이내)만 안내함
      const toDispatch = triggerBrowser || hadHistory
        ? unnotifiedUnreads
        : unnotifiedUnreads.filter((n) => {
            const ts = n.createdAt ? new Date(n.createdAt).getTime() : NaN;
            return !Number.isNaN(ts) && Math.abs(nowMs - ts) <= RECENT_INITIAL_WINDOW_MS;
          });

      safeList.forEach(n => notifiedIdsRef.current.add(n.id));
      writePersistedNotifiedIds(currentUser.id, notifiedIdsRef.current);

      if (toDispatch.length > 0) {
        const top = toDispatch[0];
        window.dispatchEvent(
          new CustomEvent('cistus:notifications-updated', {
            detail: {
              count: toDispatch.length,
              latestTitle: top.title,
              latestMessage: top.message
            }
          })
        );
        const inDnd = isProfessorRef.current ? isCurrentlyInDndRef.current() : false;
        if (!inDnd && getBrowserNotificationPermission() === 'granted') {
          toDispatch.slice(0, 3).forEach((item) => {
            sendBrowserNotification(item.title, {
              body: item.message,
              tag: `cistus-notif-${item.id}`,
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
    } catch (err) {
      console.error('Failed to load notifications', err);
    }
  }, [currentUser?.id, markAsRead]);

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
    if (!currentUser?.id) {
      notifiedIdsRef.current = new Set();
      return;
    }
    notifiedIdsRef.current = readPersistedNotifiedIds(currentUser.id);
    void loadNotifications(false);
    if (getBrowserNotificationPermission() === 'granted') {
      void syncBrowserPushSubscription(api);
    }
    if (isProfessor) {
      void loadProfSettings();
    }

    const handleServiceWorkerMessage = (event) => {
      if (event?.data?.type === 'CISTUS_OPEN_QUESTION' && event.data.questionId) {
        window.dispatchEvent(
          new CustomEvent('cistus:open-question', { detail: { questionId: event.data.questionId } })
        );
      }
    };
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
      navigator.serviceWorker.addEventListener('message', handleServiceWorkerMessage);
    }

    // 백그라운드 탭에서도 새 답변/질문을 감지해 OS 브라우저 알림을 띄울 수 있도록 8초마다 폴링
    const pollInterval = setInterval(() => {
      void loadNotifications(true);
    }, 8000);

    const handleWakePoll = () => {
      if (document.hidden) return;
      const currentPerm = getBrowserNotificationPermission();
      setBrowserPermission(currentPerm);
      if (currentPerm === 'granted') {
        void syncBrowserPushSubscription(api);
      }
      if (Date.now() - lastPollAtRef.current >= 2000) {
        void loadNotifications(true);
      }
    };

    window.addEventListener('focus', handleWakePoll);
    document.addEventListener('visibilitychange', handleWakePoll);
    return () => {
      clearInterval(pollInterval);
      window.removeEventListener('focus', handleWakePoll);
      document.removeEventListener('visibilitychange', handleWakePoll);
      if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
        navigator.serviceWorker.removeEventListener('message', handleServiceWorkerMessage);
      }
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
