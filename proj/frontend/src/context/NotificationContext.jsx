// src/context/NotificationContext.jsx
import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';
import {
  isBrowserNotificationSupported,
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
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [browserPermission, setBrowserPermission] = useState(getBrowserNotificationPermission());

  // 마지막으로 브라우저 시스템 알림을 띄운 알림 ID 목록 추적 (중복 발송 방지)
  const notifiedIdsRef = useRef(new Set());

  // 교수 전용 알림 & 카테고리 환경설정
  const [profSettings, setProfSettings] = useState(DEFAULT_PROF_SETTINGS);

  // 브라우저 권한 요청 함수
  const requestPermission = async () => {
    const result = await requestBrowserNotificationPermission();
    setBrowserPermission(result);
    return result;
  };

  // 현재 시각이 방해금지 시간대에 해당하는지 안전하게 검사
  const isCurrentlyInDnd = useCallback(() => {
    if (!profSettings?.dndEnabled) return false;
    if (!profSettings?.dndStartTime || !profSettings?.dndEndTime) return false;

    try {
      const now = new Date();
      const currentMinutes = now.getHours() * 60 + now.getMinutes();

      const [startH, startM] = profSettings.dndStartTime.split(':').map(Number);
      const [endH, endM] = profSettings.dndEndTime.split(':').map(Number);

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
  }, [profSettings?.dndEnabled, profSettings?.dndStartTime, profSettings?.dndEndTime]);

  const loadNotifications = useCallback(async (triggerBrowser = false) => {
    try {
      const list = await api.getNotifications(currentUser.id);
      const safeList = Array.isArray(list) ? list : [];
      setNotifications(safeList);
      setUnreadCount(safeList.filter(n => !n.isRead).length);

      // 브라우저 시스템 알림 트리거 (새 미읽음 알림이 존재하고, 방해금지 시간대가 아닐 때)
      if (triggerBrowser) {
        const inDnd = isProfessor ? isCurrentlyInDnd() : false;
        if (!inDnd && getBrowserNotificationPermission() === 'granted') {
          const newUnreads = safeList.filter(n => !n.isRead && !notifiedIdsRef.current.has(n.id));
          if (newUnreads.length > 0) {
            const top = newUnreads[0];
            notifiedIdsRef.current.add(top.id);
            sendBrowserNotification(top.title, {
              body: top.message,
              tag: top.id
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
  }, [currentUser.id, isProfessor, isCurrentlyInDnd]);

  const loadProfSettings = useCallback(async () => {
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
  }, [currentUser.id]);

  useEffect(() => {
    // 유저 변경 시 이미 발송한 알림 목록 초기화
    notifiedIdsRef.current = new Set();
    loadNotifications(false);
    if (isProfessor) {
      loadProfSettings();
    }
  }, [currentUser.id, isProfessor, loadNotifications, loadProfSettings]);

  const markAsRead = async (id) => {
    await api.markNotificationRead(id);
    setNotifications(prev =>
      prev.map(n => (n.id === id ? { ...n, isRead: true } : n))
    );
    setUnreadCount(prev => Math.max(0, prev - 1));
  };

  const markAllAsRead = async () => {
    for (const n of notifications.filter(x => !x.isRead)) {
      await api.markNotificationRead(n.id);
    }
    setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
    setUnreadCount(0);
  };

  const updateProfSettings = async (partialSettings) => {
    const updated = { ...profSettings, ...partialSettings };
    setProfSettings(updated);
    await api.updateSettings(currentUser.id, updated);
  };

  return (
    <NotificationContext.Provider
      value={{
        notifications,
        unreadCount,
        loadNotifications,
        markAsRead,
        markAllAsRead,
        profSettings,
        updateProfSettings,
        isCurrentlyInDnd,
        browserPermission,
        requestPermission
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
