// src/utils/browserNotification.js
// 브라우저 Web Notification API 연동 유틸리티

export function isBrowserNotificationSupported() {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function getBrowserNotificationPermission() {
  if (!isBrowserNotificationSupported()) return 'unsupported';
  return Notification.permission;
}

export async function requestBrowserNotificationPermission() {
  if (!isBrowserNotificationSupported()) return 'unsupported';
  try {
    const permission = await Notification.requestPermission();
    return permission;
  } catch (err) {
    console.warn('[Notification] Failed to request permission:', err);
    return 'denied';
  }
}

export function sendBrowserNotification(title, options = {}) {
  if (!isBrowserNotificationSupported()) return null;
  if (Notification.permission !== 'granted') return null;

  try {
    const n = new Notification(title, {
      body: options.body || '',
      icon: options.icon || '/favicon.ico',
      tag: options.tag || `qna-notif-${Date.now()}`,
      silent: Boolean(options.silent),
      ...options
    });

    if (options.onClick) {
      n.onclick = (e) => {
        window.focus();
        options.onClick(e);
        n.close();
      };
    }

    return n;
  } catch (err) {
    console.warn('[Notification] Failed to create native Notification:', err);
    return null;
  }
}
