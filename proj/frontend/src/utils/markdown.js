// src/utils/markdown.js
// Q&A Markdown 안전 URL 검증 및 목록/요약용 마크다운 평문 변환 유틸리티

import { isSafeAttachmentUrl } from './fileSecurity.js';

function hasControlOrInvisibleUrlChars(value) {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (
      code <= 0x1f ||
      (code >= 0x7f && code <= 0x9f) ||
      code === 0x00ad ||
      code === 0x034f ||
      code === 0x061c ||
      (code >= 0x200b && code <= 0x200f) ||
      (code >= 0x202a && code <= 0x202e) ||
      (code >= 0x2060 && code <= 0x206f) ||
      code === 0xfeff
    ) {
      return true;
    }
  }
  return false;
}

const ALLOWED_LINK_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);
const ALLOWED_IMAGE_PROTOCOLS = new Set(['https:']);
const SAFE_DATA_IMAGE_REGEX = /^data:image\/(?:png|jpeg|jpg|gif|webp);base64,[A-Za-z0-9+/=]+$/i;
const SAFE_ATTACHMENT_PATH_REGEX = /^\/api\/attachments\/[A-Za-z0-9_-]{1,64}(?:\/download)?$/;

/**
 * Markdown 하이퍼링크(a[href]) 허용 여부 검증
 * - http:, https:, mailto: 및 동일 출처 /api/attachments/<id> 경로만 허용
 * - javascript:, vbscript:, data:, file:, //, userinfo(@) 사칭, %2e%2e 경로 조작 및 제어문자 차단
 */
export function isSafeMarkdownHref(rawUrl) {
  if (!rawUrl || typeof rawUrl !== 'string') return false;
  if (hasControlOrInvisibleUrlChars(rawUrl)) return false;

  const trimmed = rawUrl.trim();
  if (!trimmed) return false;

  // 프로토콜 상대 URL(//evil.com), 역슬래시 우회(\\), 상위 경로 탐색(..) 차단
  if (
    trimmed.startsWith('//') ||
    trimmed.startsWith('\\\\') ||
    trimmed.includes('\\') ||
    trimmed.includes('..')
  ) {
    return false;
  }

  // 전체 퍼센트 디코딩 후 제어문자·BiDi 문자 재검증 (잘못된 인코딩은 즉시 거부)
  let decodedFull;
  try {
    decodedFull = decodeURIComponent(trimmed);
  } catch {
    return false;
  }
  if (hasControlOrInvisibleUrlChars(decodedFull) || decodedFull.includes('\\')) {
    return false;
  }

  // 내부 첨부파일 상대 경로만 예외 허용
  if (trimmed.startsWith('/')) {
    if (/%2e|%2f|%5c/i.test(trimmed) || decodedFull.includes('..')) {
      return false;
    }
    return SAFE_ATTACHMENT_PATH_REGEX.test(trimmed);
  }

  try {
    const base =
      typeof window !== 'undefined' && window.location?.origin
        ? window.location.origin
        : 'http://localhost';
    // 상대 경로가 http(s)로 오인되지 않도록 explicit scheme 여부 선검사
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed)) {
      return false;
    }
    const parsed = new URL(trimmed, base);
    const proto = parsed.protocol.toLowerCase();
    if (!ALLOWED_LINK_PROTOCOLS.has(proto)) {
      return false;
    }
    // URL userinfo(https://kw.ac.kr@evil.com) 사칭 피싱 차단
    if (parsed.username !== '' || parsed.password !== '') {
      return false;
    }
    if ((proto === 'http:' || proto === 'https:') && !parsed.hostname) {
      return false;
    }
    return true;
  } catch {
    return false;
  }
}

/**
 * Markdown 인라인 이미지(img[src]) 허용 여부 검증
 * - 내부 첨부파일(/api/attachments/<id>, blob:), 안전한 래스터 data:image/*, http(s):// 허용
 * - data:image/svg+xml, javascript:, vbscript:, userinfo(@) 사칭, 제어문자 차단
 */
export function isSafeMarkdownImageUrl(rawUrl) {
  if (!rawUrl || typeof rawUrl !== 'string') return false;
  if (isSafeAttachmentUrl(rawUrl)) return true;
  if (hasControlOrInvisibleUrlChars(rawUrl)) return false;

  const trimmed = rawUrl.trim();
  if (!trimmed) return false;

  if (SAFE_DATA_IMAGE_REGEX.test(trimmed)) {
    return true;
  }

  if (
    trimmed.startsWith('//') ||
    trimmed.startsWith('\\\\') ||
    trimmed.includes('\\') ||
    trimmed.includes('..')
  ) {
    return false;
  }

  let decodedFull;
  try {
    decodedFull = decodeURIComponent(trimmed);
  } catch {
    return false;
  }
  if (hasControlOrInvisibleUrlChars(decodedFull) || decodedFull.includes('\\')) {
    return false;
  }

  if (trimmed.startsWith('/')) {
    if (/%2e|%2f|%5c/i.test(trimmed) || decodedFull.includes('..')) {
      return false;
    }
    return SAFE_ATTACHMENT_PATH_REGEX.test(trimmed);
  }

  try {
    if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed)) {
      return false;
    }
    const base =
      typeof window !== 'undefined' && window.location?.origin
        ? window.location.origin
        : 'http://localhost';
    const parsed = new URL(trimmed, base);
    const proto = parsed.protocol.toLowerCase();
    if (!ALLOWED_IMAGE_PROTOCOLS.has(proto)) {
      return false;
    }
    if (parsed.username !== '' || parsed.password !== '') {
      return false;
    }
    return Boolean(parsed.hostname);
  } catch {
    return false;
  }
}

/**
 * react-markdown urlTransform 용 URL 정제 함수
 */
export function safeMarkdownUrlTransform(url) {
  if (!url || typeof url !== 'string') return '';
  if (isSafeMarkdownHref(url) || isSafeMarkdownImageUrl(url) || isSafeAttachmentUrl(url)) {
    return url.trim();
  }
  return '';
}

/**
 * 목록 미리보기 및 자동 요약 제목용 Markdown 기호 제거 평문 추출
 */
export function stripMarkdownForPreview(markdownText) {
  if (!markdownText || typeof markdownText !== 'string') return '';
  const bounded = markdownText.slice(0, 4000);

  return bounded
    .replace(/```[\s\S]*?```/g, (block) =>
      block
        .replace(/^```[^\n]*\n?/, '')
        .replace(/```$/, '')
        .trim()
    )
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, (_, alt) => (alt ? `[이미지: ${alt}]` : '[이미지]'))
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^>\s?/gm, '')
    .replace(/^[-*_]{3,}\s*$/gm, ' ')
    .replace(/^\s*[-*+]\s+\[[ xX]\]\s+/gm, '')
    .replace(/^\s*(?:[-*+]|\d+\.)\s+/gm, '')
    .replace(/(\*\*|__|\*|_|~~)/g, '')
    .replace(/\|/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
