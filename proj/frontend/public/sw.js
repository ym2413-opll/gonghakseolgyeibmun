// Service Worker for background Web Push notifications when all portal tabs are closed.

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
  if (!event.data) return;

  let payload = {};
  try {
    payload = event.data.json();
  } catch {
    payload = { title: '교수질문센터 새 알림', body: event.data.text() };
  }

  const title = payload.title || '교수질문센터 새 알림';
  const options = {
    body: payload.body || '',
    icon: payload.icon || '/favicon.png',
    badge: '/favicon.png',
    tag: payload.tag || (payload.id ? `cistus-notif-${payload.id}` : `cistus-push-${Date.now()}`),
    data: {
      url: payload.url || '/',
      questionId: payload.questionId || null,
      notificationId: payload.id || null
    }
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

function sanitizeSameOriginPath(rawUrl) {
  if (typeof rawUrl !== 'string') return '/';
  const trimmed = rawUrl.trim();
  if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.startsWith('/\\')) {
    return '/';
  }
  for (let i = 0; i < trimmed.length; i++) {
    const c = trimmed.charCodeAt(i);
    if (c <= 0x1f || c === 0x7f) return '/';
  }
  return trimmed;
}

function sanitizeQuestionId(rawId) {
  if (typeof rawId !== 'string') return null;
  const trimmed = rawId.trim();
  return /^[a-zA-Z0-9_-]{1,64}$/.test(trimmed) ? trimmed : null;
}

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const targetUrl = sanitizeSameOriginPath(data.url);
  const questionId = sanitizeQuestionId(data.questionId);

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) {
          return client.focus().then((focused) => {
            if (focused && questionId) {
              focused.postMessage({ type: 'CISTUS_OPEN_QUESTION', questionId });
            }
            return focused;
          });
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow(targetUrl);
      }
      return undefined;
    })
  );
});
