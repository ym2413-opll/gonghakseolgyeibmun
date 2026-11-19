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

const KOREAN_BOUNDARY_SENTINEL = '\u2063';
export const DIRECTIVE_HL_START = '\uE000';
export const DIRECTIVE_HL_END = '\uE001';
const WORD_OR_HANGUL_REGEX = /[\uAC00-\uD7A3\u1100-\u11FF\u3130-\u318F\u4E00-\u9FFF0-9A-Za-z]/;
const HANGUL_OR_CJK_REGEX = /[\uAC00-\uD7A3\u1100-\u11FF\u3130-\u318F\u4E00-\u9FFF]/;
const PUNCT_BOUNDARY_REGEX = /[!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~「」『』【】〈〉《》〔〕‘’“”·…]/;

function fixKoreanEmphasisInPlainSegment(segment) {
  if (!segment) return segment;
  // URL이나 수식($...$) 내부는 건드리지 않고 일반 텍스트 구간만 보정
  return segment.replace(
    /https?:\/\/[^\s)]+|\$\$[\s\S]*?\$\$|\$[^$\r\n]+?\$|(\*\*|~~|__|\*|_)/g,
    (match, delim, offset, fullStr) => {
      if (!delim) return match;
      // 이스케이프된 기호(\*)는 그대로 유지
      if (offset > 0 && fullStr[offset - 1] === '\\') return match;

      const prev = offset > 0 ? fullStr[offset - 1] : '';
      const nextIdx = offset + delim.length;
      const next = nextIdx < fullStr.length ? fullStr[nextIdx] : '';

      const isSingleUnderscoreOrStar = delim === '*' || delim === '_' || delim === '__';

      // 닫는 구분자: 문장부호 뒤 + 한글/단어가 바로 이어지는 경우 (예: **테스트(1)**입니다)
      if (prev && next && PUNCT_BOUNDARY_REGEX.test(prev) && WORD_OR_HANGUL_REGEX.test(next)) {
        if (!isSingleUnderscoreOrStar || HANGUL_OR_CJK_REGEX.test(next)) {
          return `${delim} ${KOREAN_BOUNDARY_SENTINEL}`;
        }
      }

      // 여는 구분자: 한글/단어 뒤 + 문장부호로 시작하는 경우 (예: 한글**(테스트)**)
      if (prev && next && WORD_OR_HANGUL_REGEX.test(prev) && PUNCT_BOUNDARY_REGEX.test(next)) {
        if (!isSingleUnderscoreOrStar || HANGUL_OR_CJK_REGEX.test(prev)) {
          return `${KOREAN_BOUNDARY_SENTINEL} ${delim}`;
        }
      }

      return match;
    }
  );
}

function convertLatexDelimitersLinear(text) {
  if (!text) return '';
  let out = '';
  let i = 0;
  const len = text.length;
  let nextCloseBracket = text.indexOf('\\]');
  let nextCloseParen = text.indexOf('\\)');
  let nextNewline = text.indexOf('\n');

  while (i < len) {
    if (text[i] === '\\' && i + 1 < len) {
      const next = text[i + 1];
      const isEscaped = i > 0 && text[i - 1] === '\\';
      if (!isEscaped && next === '[' && nextCloseBracket !== -1) {
        if (nextCloseBracket < i + 2) {
          nextCloseBracket = text.indexOf('\\]', i + 2);
        }
        if (nextCloseBracket !== -1) {
          const expr = text.slice(i + 2, nextCloseBracket).trim();
          out += `\n$$\n${expr}\n$$\n`;
          i = nextCloseBracket + 2;
          continue;
        }
      } else if (!isEscaped && next === '(' && nextCloseParen !== -1) {
        if (nextCloseParen < i + 2) {
          nextCloseParen = text.indexOf('\\)', i + 2);
        }
        if (nextNewline !== -1 && nextNewline < i + 2) {
          nextNewline = text.indexOf('\n', i + 2);
        }
        if (nextCloseParen !== -1 && (nextNewline === -1 || nextCloseParen < nextNewline)) {
          const expr = text.slice(i + 2, nextCloseParen).trim();
          if (expr.length > 0) {
            out += `$${expr}$`;
            i = nextCloseParen + 2;
            continue;
          }
        }
      }
    }
    out += text[i];
    i++;
  }

  return out;
}

/**
 * 한글 인접 Markdown 강조/취소선 및 LaTeX(\(...\), \[...\]) 구문 전처리
 * - 코드 블록(```...```)과 인라인 코드(`...`)는 수정하지 않음
 * - CommonMark 경계 규칙으로 깨지는 **테스트(1)**입니다 등을 안전하게 보정
 */
export function preprocessKoreanMarkdown(markdownText) {
  if (!markdownText || typeof markdownText !== 'string') return '';
  const sanitized = markdownText
    .replaceAll(KOREAN_BOUNDARY_SENTINEL, '')
    .replaceAll(DIRECTIVE_HL_START, '')
    .replaceAll(DIRECTIVE_HL_END, '');

  // 코드 블록 / 인라인 코드 / 기존 $$...$$, $...$ 수식은 원형 보존
  return sanitized
    .split(/(```[\s\S]*?```|`[^`\n]+`|\$\$[\s\S]*?\$\$|\$[^$\r\n]+?\$)/g)
    .map((part, idx) => {
      if (!part) return '';
      // 홀수 인덱스는 위 split 캡처 그룹(코드 블록, 인라인 코드, 수식)
      if (idx % 2 === 1) {
        // $ ... $ 앞뒤 공백 때문에 인라인 수식이 무시되는 경우를 방지 (수식 기호 포함 시에만 트림)
        if (part.startsWith('$') && !part.startsWith('$$') && part.endsWith('$') && part.length >= 3) {
          const inner = part.slice(1, -1);
          const trimmed = inner.trim();
          if (trimmed && inner !== trimmed && /[\\^_={}+\-*/<>()|]/.test(trimmed)) {
            return `$${trimmed}$`;
          }
        }
        return part;
      }

      // LaTeX 괄호 수식 \[...\] -> $$...$$, \(...\) -> $...$ 선형 변환
      const withDollarMath = convertLatexDelimitersLinear(part);

      return fixKoreanEmphasisInPlainSegment(withDollarMath);
    })
    .join('');
}

/**
 * AI 지시사항 반영 인라인 구간에 내부 센티넬(\uE000..\uE001)을 삽입하여 강조용 Markdown 생성
 */
export function buildHighlightedMarkdown(text, inlineRanges = []) {
  if (!text || typeof text !== 'string') return '';
  if (!Array.isArray(inlineRanges) || inlineRanges.length === 0) {
    return preprocessKoreanMarkdown(text);
  }

  let out = '';
  let cursor = 0;
  for (const range of inlineRanges) {
    const s = Math.max(cursor, Math.min(range.start, text.length));
    const e = Math.max(s, Math.min(range.end, text.length));
    if (s > cursor) {
      out += preprocessKoreanMarkdown(text.slice(cursor, s));
    }
    const rawSlice = text.slice(s, e);
    const leadMatch = rawSlice.match(/^([ \t]*)/);
    const trailMatch = rawSlice.match(/([ \t]*)$/);
    const leadWs = leadMatch ? leadMatch[1] : '';
    const trailWs = trailMatch ? trailMatch[1] : '';
    const inner = rawSlice.slice(leadWs.length, rawSlice.length - trailWs.length);
    if (inner) {
      out +=
        leadWs +
        DIRECTIVE_HL_START +
        KOREAN_BOUNDARY_SENTINEL +
        ' ' +
        preprocessKoreanMarkdown(inner) +
        ' ' +
        KOREAN_BOUNDARY_SENTINEL +
        DIRECTIVE_HL_END +
        trailWs;
    } else {
      out += rawSlice;
    }
    cursor = e;
  }
  if (cursor < text.length) {
    out += preprocessKoreanMarkdown(text.slice(cursor));
  }
  return out;
}

/**
 * preprocessKoreanMarkdown이 삽입한 경계 센티넬(\u2063)을 MDAST 텍스트 노드에서 제거하는 remark 플러그인
 */
export function remarkStripKoreanSentinels() {
  const visitText = (node) => {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'text' && typeof node.value === 'string' && node.value.includes(KOREAN_BOUNDARY_SENTINEL)) {
      node.value = node.value
        .replace(/ ?\u2063 ?/g, '')
        .replaceAll(KOREAN_BOUNDARY_SENTINEL, '');
    }
    if (Array.isArray(node.children)) {
      for (const child of node.children) {
        visitText(child);
      }
    }
  };
  return (tree) => {
    visitText(tree);
  };
}

function splitTextByHighlightSentinels(node) {
  if (!node || node.type !== 'text' || typeof node.value !== 'string') return [node];
  const val = node.value;
  if (!val.includes(DIRECTIVE_HL_START) && !val.includes(DIRECTIVE_HL_END)) return [node];

  const pieces = [];
  let buf = '';
  for (let i = 0; i < val.length; i++) {
    const ch = val[i];
    if (ch === DIRECTIVE_HL_START) {
      if (buf) {
        pieces.push({ ...node, value: buf });
        buf = '';
      }
      pieces.push({ __hlMarker: 'START' });
    } else if (ch === DIRECTIVE_HL_END) {
      if (buf) {
        pieces.push({ ...node, value: buf });
        buf = '';
      }
      pieces.push({ __hlMarker: 'END' });
    } else {
      buf += ch;
    }
  }
  if (buf) {
    pieces.push({ ...node, value: buf });
  }
  return pieces;
}

function stripRemainingHighlightSentinels(node) {
  if (!node || typeof node !== 'object') return;
  if (typeof node.value === 'string' && (node.value.includes(DIRECTIVE_HL_START) || node.value.includes(DIRECTIVE_HL_END))) {
    node.value = node.value
      .replaceAll(DIRECTIVE_HL_START, '')
      .replaceAll(DIRECTIVE_HL_END, '');
  }
  if (Array.isArray(node.children)) {
    for (const child of node.children) {
      stripRemainingHighlightSentinels(child);
    }
  }
}

/**
 * \uE000...\uE001 마커로 감싼 AI 지시사항 반영 구간을 <mark class="ai-directive-highlight-inline"> MDAST 노드로 변환
 */
export function remarkHighlightDirectiveSentinels() {
  const processChildren = (children) => {
    const expanded = [];
    for (const child of children) {
      if (child && child.type === 'text') {
        const splitParts = splitTextByHighlightSentinels(child);
        expanded.push(...splitParts);
      } else {
        if (child && Array.isArray(child.children)) {
          child.children = processChildren(child.children);
        }
        expanded.push(child);
      }
    }

    const result = [];
    let active = false;
    let captured = [];
    for (const item of expanded) {
      if (item && item.__hlMarker === 'START') {
        if (!active) {
          active = true;
          captured = [];
        }
      } else if (item && item.__hlMarker === 'END') {
        if (active) {
          if (captured.length > 0) {
            result.push({
              type: 'directiveHighlight',
              data: {
                hName: 'mark',
                hProperties: { className: ['ai-directive-highlight-inline'] }
              },
              children: captured
            });
          }
          active = false;
          captured = [];
        }
      } else if (active) {
        captured.push(item);
      } else {
        result.push(item);
      }
    }
    if (active && captured.length > 0) {
      result.push(...captured);
    }
    return result;
  };

  return (tree) => {
    if (tree && Array.isArray(tree.children)) {
      tree.children = processChildren(tree.children);
    }
    stripRemainingHighlightSentinels(tree);
  };
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
    .replace(/\$\$([\s\S]*?)\$\$/g, ' $1 ')
    .replace(/\$([^$\r\n]+?)\$/g, '$1')
    .replace(/\\\[([\s\S]*?)\\\]/g, ' $1 ')
    .replace(/\\\(([^\r\n]+?)\\\)/g, '$1')
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
