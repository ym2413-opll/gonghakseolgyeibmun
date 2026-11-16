// src/utils/browserNotification.js
// 브라우저 Web Notification API 연동 유틸리티

export function isBrowserNotificationSecureContext() {
  if (typeof window === 'undefined') return false;
  if (window.isSecureContext) return true;
  const host = window.location?.hostname;
  return host === 'localhost' || host === '127.0.0.1' || host === '[::1]';
}

export function isBrowserNotificationSupported() {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function getBrowserNotificationPermission() {
  if (!isBrowserNotificationSupported()) {
    return isBrowserNotificationSecureContext() ? 'unsupported' : 'insecure';
  }
  const perm = Notification.permission;
  if (perm === 'granted') return 'granted';
  if (!isBrowserNotificationSecureContext()) return 'insecure';
  return perm;
}

export async function requestBrowserNotificationPermission() {
  if (!isBrowserNotificationSecureContext()) return 'insecure';
  if (!isBrowserNotificationSupported()) return 'unsupported';
  try {
    const permission = await new Promise((resolve, reject) => {
      const maybePromise = Notification.requestPermission((cbResult) => {
        resolve(cbResult);
      });
      if (maybePromise && typeof maybePromise.then === 'function') {
        maybePromise.then(resolve, reject);
      }
    });
    return permission || getBrowserNotificationPermission();
  } catch (err) {
    console.warn('[Notification] Failed to request permission:', err);
    return getBrowserNotificationPermission();
  }
}

export function sendBrowserNotification(title, options = {}) {
  if (!isBrowserNotificationSupported()) return null;
  if (Notification.permission !== 'granted') return null;

  try {
    const { onClick, ...rest } = options;
    const n = new Notification(title, {
      body: options.body || '',
      icon: options.icon || '/favicon.png',
      tag: options.tag || `qna-notif-${Date.now()}`,
      silent: Boolean(options.silent),
      ...rest
    });

    if (onClick) {
      n.onclick = (e) => {
        window.focus();
        onClick(e);
        n.close();
      };
    }

    return n;
  } catch (err) {
    console.warn('[Notification] Failed to create native Notification:', err);
    return null;
  }
}
