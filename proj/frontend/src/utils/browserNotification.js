// src/utils/browserNotification.js
// 브라우저 Web Notification API 및 Service Worker Web Push 연동 유틸리티

export function isBrowserNotificationSecureContext() {
  if (typeof window === 'undefined') return false;
  if (window.isSecureContext) return true;
  const host = window.location?.hostname;
  return host === 'localhost' || host === '127.0.0.1' || host === '[::1]';
}

export function isBrowserNotificationSupported() {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export function isWebPushSupported() {
  return (
    typeof window !== 'undefined' &&
    isBrowserNotificationSecureContext() &&
    'serviceWorker' in navigator &&
    'PushManager' in window
  );
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

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const rawData = window.atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; i++) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

let swRegistrationPromise = null;

export async function registerNotificationServiceWorker() {
  if (!isWebPushSupported()) return null;
  if (!swRegistrationPromise) {
    swRegistrationPromise = navigator.serviceWorker
      .register('/sw.js', { scope: '/' })
      .catch((err) => {
        console.warn('[WebPush] Service Worker registration failed:', err);
        swRegistrationPromise = null;
        return null;
      });
  }
  return swRegistrationPromise;
}

export async function syncBrowserPushSubscription(apiClient) {
  if (!isWebPushSupported()) return false;
  if (getBrowserNotificationPermission() !== 'granted') return false;
  try {
    const registration = await registerNotificationServiceWorker();
    if (!registration || !registration.pushManager) return false;

    await navigator.serviceWorker.ready;
    const vapidInfo = await apiClient.getVapidPublicKey();
    if (!vapidInfo?.enabled || !vapidInfo?.publicKey) return false;

    let subscription = await registration.pushManager.getSubscription();
    if (!subscription) {
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(vapidInfo.publicKey)
      });
    }

    const subJson = typeof subscription.toJSON === 'function' ? subscription.toJSON() : null;
    if (!subJson?.endpoint || !subJson?.keys?.p256dh || !subJson?.keys?.auth) {
      return false;
    }

    return await apiClient.subscribeWebPush({
      endpoint: subJson.endpoint,
      keys: {
        p256dh: subJson.keys.p256dh,
        auth: subJson.keys.auth
      }
    });
  } catch (err) {
    console.warn('[WebPush] Failed to sync push subscription:', err);
    return false;
  }
}

export async function getActivePushEndpoint() {
  if (!isWebPushSupported()) return null;
  try {
    const registration = await navigator.serviceWorker.getRegistration('/');
    const sub = await registration?.pushManager?.getSubscription();
    return sub?.endpoint || null;
  } catch {
    return null;
  }
}

export async function clearBrowserPushSubscription() {
  if (!isWebPushSupported()) return null;
  try {
    const registration = await navigator.serviceWorker.getRegistration('/');
    const sub = await registration?.pushManager?.getSubscription();
    const endpoint = sub?.endpoint || null;
    if (sub) {
      await sub.unsubscribe().catch(() => {});
    }
    return endpoint;
  } catch {
    return null;
  }
}

export function sendBrowserNotification(title, options = {}) {
  if (!isBrowserNotificationSupported()) return null;
  if (Notification.permission !== 'granted') return null;

  const { onClick, ...rest } = options;
  const tag = options.tag || `qna-notif-${Date.now()}`;
  const notifOptions = {
    body: options.body || '',
    icon: options.icon || '/favicon.png',
    badge: '/favicon.png',
    tag,
    silent: Boolean(options.silent),
    ...rest
  };

  try {
    const n = new Notification(title, notifOptions);

    if (onClick) {
      n.onclick = (e) => {
        window.focus();
        onClick(e);
        n.close();
      };
    }

    return n;
  } catch (err) {
    // Fallback for mobile browsers that forbid window.Notification constructor and require SW showNotification
    if (isWebPushSupported()) {
      navigator.serviceWorker
        .getRegistration('/')
        .then((reg) => reg?.showNotification(title, notifOptions))
        .catch(() => {});
    } else {
      console.warn('[Notification] Failed to create native Notification:', err);
    }
    return null;
  }
}
