// src/components/common/MarkdownContent.jsx
// XSS 안전 GFM Markdown + 포럼 전용 토큰([사진 k], [#N 사진 k], #N 글 인용) 렌더러
import React, { useState, useEffect, useRef, useMemo } from 'react';
import { createPortal } from 'react-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import remarkBreaks from 'remark-breaks';
import rehypeKatex from 'rehype-katex';
import 'katex/dist/katex.min.css';
import { AlertTriangle, ExternalLink, X } from 'lucide-react';
import { useOptionalAuth } from '../../context/AuthContext';
import { isSafeAttachmentUrl } from '../../utils/fileSecurity';
import {
  buildHighlightedMarkdown,
  isSafeMarkdownHref,
  isSafeMarkdownImageUrl,
  preprocessKoreanMarkdown,
  remarkHighlightDirectiveSentinels,
  remarkStripKoreanSentinels,
  safeMarkdownUrlTransform
} from '../../utils/markdown';

const REMARK_PLUGINS = [remarkGfm, remarkMath, remarkBreaks, remarkStripKoreanSentinels];
const REMARK_PLUGINS_WITH_HIGHLIGHT = [
  remarkGfm,
  remarkMath,
  remarkBreaks,
  remarkStripKoreanSentinels,
  remarkHighlightDirectiveSentinels
];
const REHYPE_PLUGINS = [
  [
    rehypeKatex,
    {
      strict: false,
      throwOnError: false,
      trust: false,
      maxExpand: 200,
      maxSize: 100
    }
  ]
];

const TOKEN_PATTERN_SOURCE =
  '(\\[#\\d+\\s*[-:]?\\s*사진\\s*\\d+\\]|\\[사진\\s*#\\d+\\s*[-:\\s]\\s*\\d+\\]|\\[사진\\s*\\d+\\]|#\\d+(?!\\d))';

const SKIP_DESCENT_TAGS = new Set([
  'code',
  'pre',
  'a',
  'button',
  'figure',
  'img',
  'ul',
  'ol',
  'math',
  'annotation',
  'semantics',
  'mrow',
  'mi',
  'mo',
  'mn',
  'msup',
  'msub',
  'mfrac',
  'mtext',
  'mspace',
  'svg'
]);

function tokenizeString(text, ctx, collectedFigures, keyPrefix) {
  const regex = new RegExp(TOKEN_PATTERN_SOURCE, 'g');
  const parts = [];
  let lastIdx = 0;
  let match;
  let partIdx = 0;

  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIdx) {
      parts.push(text.slice(lastIdx, match.index));
    }

    const token = match[0];
    const anchoredPrefixMatch = token.match(/^\[#(\d+)\s*[-:]?\s*사진\s*(\d+)\]$/);
    const anchoredPhotoFirstMatch = token.match(/^\[사진\s*#(\d+)\s*[-:\s]\s*(\d+)\]$/);
    const localPhotoMatch = token.match(/^\[사진\s*(\d+)\]$/);

    if ((anchoredPrefixMatch || anchoredPhotoFirstMatch || localPhotoMatch) && ctx.imagesBySeq) {
      const isExplicitAnchor = Boolean(anchoredPrefixMatch || anchoredPhotoFirstMatch);
      const targetSeq = isExplicitAnchor
        ? Number((anchoredPrefixMatch || anchoredPhotoFirstMatch)[1])
        : ctx.currentSeq;
      const k = Number(
        (anchoredPrefixMatch || anchoredPhotoFirstMatch || localPhotoMatch)[2] ||
          localPhotoMatch[1]
      );
      const targetImages = ctx.imagesBySeq.get(targetSeq) || [];
      const targetAtt = k >= 1 && k <= targetImages.length ? targetImages[k - 1] : null;
      const rawUrl = targetAtt ? targetAtt.previewUrl || targetAtt.url || targetAtt.downloadUrl : '';

      if (targetAtt && isSafeAttachmentUrl(rawUrl)) {
        if (isExplicitAnchor) {
          parts.push(
            <button
              type="button"
              key={`${keyPrefix}-p-${partIdx++}`}
              className="inline-photo-token is-anchored"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                if (ctx.onJumpToSeq) ctx.onJumpToSeq(targetSeq);
              }}
              title={`#${targetSeq} 글의 [사진 ${k}] · 클릭하여 #${targetSeq}로 이동`}
            >
              [#{targetSeq} 사진 {k}]
            </button>
          );
        } else {
          parts.push(
            <span key={`${keyPrefix}-p-${partIdx++}`} className="inline-photo-token">
              [사진 {k}]
            </span>
          );
        }
        collectedFigures.push({
          key: `${keyPrefix}-fig-${targetSeq}-${k}-${partIdx}`,
          k,
          targetSeq: isExplicitAnchor ? targetSeq : null,
          url: rawUrl,
          name: targetAtt.originalName || targetAtt.fileName || `사진 ${k}`
        });
      } else {
        parts.push(token);
      }
    } else if (token.startsWith('#') && ctx.validSeqSet) {
      const seqNum = Number(token.slice(1));
      if (ctx.validSeqSet.has(seqNum)) {
        parts.push(
          <button
            type="button"
            key={`${keyPrefix}-seq-${partIdx++}`}
            className="seq-anchor-link"
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              if (ctx.onJumpToSeq) ctx.onJumpToSeq(seqNum);
            }}
            title={`#${seqNum} 글로 이동`}
          >
            #{seqNum}
          </button>
        );
      } else {
        parts.push(token);
      }
    } else {
      parts.push(token);
    }

    lastIdx = regex.lastIndex;
  }

  if (lastIdx < text.length) {
    parts.push(text.slice(lastIdx));
  }

  return parts.length === 1 ? parts[0] : parts;
}

function transformInlineChildren(children, ctx, collectedFigures, keyPrefix, depth = 0) {
  if (depth > 20 || children === null || children === undefined || typeof children === 'boolean') {
    return children;
  }
  if (typeof children === 'string') {
    return tokenizeString(children, ctx, collectedFigures, keyPrefix);
  }
  if (Array.isArray(children)) {
    return children.map((child, idx) =>
      transformInlineChildren(child, ctx, collectedFigures, `${keyPrefix}-${idx}`, depth + 1)
    );
  }
  if (React.isValidElement(children)) {
    const tagType =
      typeof children.type === 'string'
        ? children.type.toLowerCase()
        : (children.props?.node?.tagName || '').toLowerCase();
    const elClass = typeof children.props?.className === 'string' ? children.props.className : '';
    // 커스텀 컴포넌트(a, img, li, table 등), KaTeX 수식 및 SKIP_DESCENT_TAGS 내부로는 재귀 진입하지 않음
    if (
      SKIP_DESCENT_TAGS.has(tagType) ||
      typeof children.type === 'function' ||
      elClass.includes('katex')
    ) {
      return children;
    }
    if (children.props && children.props.children !== undefined) {
      const nextChildren = transformInlineChildren(
        children.props.children,
        ctx,
        collectedFigures,
        `${keyPrefix}-el`,
        depth + 1
      );
      return React.cloneElement(children, undefined, nextChildren);
    }
  }
  return children;
}

function InlinePhotoFigures({ figures, onJumpToSeq }) {
  if (!figures || figures.length === 0) return null;
  return figures.map((fig, idx) => (
    <figure key={`${fig.key}-${idx}`} className="inline-photo-figure">
      <img src={fig.url} alt={fig.name} loading="lazy" />
      <figcaption>
        {fig.targetSeq ? (
          <>
            [
            <button
              type="button"
              className="seq-anchor-link inline-fig-anchor"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                if (onJumpToSeq) onJumpToSeq(fig.targetSeq);
              }}
              title={`#${fig.targetSeq} 글로 이동`}
            >
              #{fig.targetSeq}
            </button>{' '}
            사진 {fig.k}] {fig.name}
          </>
        ) : (
          `[사진 ${fig.k}] ${fig.name}`
        )}
      </figcaption>
    </figure>
  ));
}

export function MarkdownModeTabs({ isPreview, onTogglePreview }) {
  return (
    <div className="md-mode-tabs" role="tablist" aria-label="입력 보기 전환">
      <button
        type="button"
        role="tab"
        aria-selected={!isPreview}
        className={`md-mode-tab ${!isPreview ? 'active' : ''}`}
        onClick={() => onTogglePreview(false)}
      >
        편집
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={isPreview}
        className={`md-mode-tab ${isPreview ? 'active' : ''}`}
        onClick={() => onTogglePreview(true)}
      >
        미리보기
      </button>
    </div>
  );
}

function MarkdownInlineImage({ src, alt }) {
  const [failed, setFailed] = useState(false);

  if (!isSafeMarkdownImageUrl(src)) {
    return (
      <span
        className="md-blocked-image"
        title="보안 정책상 허용되지 않은 이미지 주소입니다."
      >
        [이미지: {alt || '차단된 이미지'}]
      </span>
    );
  }

  if (failed) {
    return (
      <span className="md-blocked-image" title={src}>
        [이미지를 불러올 수 없음: {alt || '이미지'}]
      </span>
    );
  }

  return (
    <figure className="inline-photo-figure md-inline-image-figure">
      <img
        src={src}
        alt={alt || '첨부 이미지'}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
      />
      {alt && <figcaption>{alt}</figcaption>}
    </figure>
  );
}

function normalizeHighlightRanges(rawText, highlightRanges) {
  if (!rawText || !Array.isArray(highlightRanges) || highlightRanges.length === 0) return [];
  const candidates = highlightRanges
    .map((h) => ({
      start: Number(h?.start),
      end: Number(h?.end),
      directive: typeof h?.directive === 'string' ? h.directive : ''
    }))
    .filter((h) => Number.isFinite(h.start) && Number.isFinite(h.end))
    .map((h) => ({
      start: Math.max(0, Math.min(h.start, rawText.length)),
      end: Math.max(0, Math.min(h.end, rawText.length)),
      directive: h.directive
    }))
    .filter((h) => h.end > h.start)
    .sort((a, b) => a.start - b.start || a.end - b.end);

  const nonOverlapping = [];
  let lastEnd = 0;
  for (const r of candidates) {
    if (r.start >= lastEnd) {
      nonOverlapping.push(r);
      lastEnd = r.end;
    }
  }
  return nonOverlapping;
}

function isBlockHighlightSpan(rawText, start, end) {
  const span = rawText.slice(start, end);
  if (span.includes('\n')) return true;
  if (/^(?:\$\$|\||[-*+]\s|\d+\.\s|>|#{1,6}\s|```)/.test(span.trim())) return true;
  const before = rawText.slice(0, start);
  const after = rawText.slice(end);
  const atParagraphStart = start === 0 || /(?:^|\n)[ \t]*\n[ \t]*$/.test(before);
  const atParagraphEnd = end === rawText.length || /^[ \t]*\n[ \t]*(?:\n|$)/.test(after);
  return atParagraphStart && atParagraphEnd;
}

function partitionHighlightedSegments(rawText, ranges) {
  const blockRanges = [];
  const inlineRanges = [];
  for (const r of ranges) {
    if (isBlockHighlightSpan(rawText, r.start, r.end)) {
      blockRanges.push(r);
    } else {
      inlineRanges.push(r);
    }
  }

  if (blockRanges.length === 0) {
    return [{ type: 'normal', text: rawText, inlineRanges }];
  }

  const segments = [];
  let cursor = 0;
  let inlineIdx = 0;

  const pushNormalSegment = (segStart, segEnd) => {
    if (segEnd <= segStart) {
      while (inlineIdx < inlineRanges.length && inlineRanges[inlineIdx].start < segEnd) {
        inlineIdx++;
      }
      return;
    }
    const segText = rawText.slice(segStart, segEnd);
    const localInline = [];
    while (inlineIdx < inlineRanges.length && inlineRanges[inlineIdx].start < segEnd) {
      const ir = inlineRanges[inlineIdx];
      if (ir.start >= segStart && ir.end <= segEnd) {
        localInline.push({
          start: ir.start - segStart,
          end: ir.end - segStart,
          directive: ir.directive
        });
      }
      inlineIdx++;
    }
    if (segText.trim()) {
      segments.push({ type: 'normal', text: segText, inlineRanges: localInline });
    }
  };

  for (const br of blockRanges) {
    pushNormalSegment(cursor, br.start);
    const blockText = rawText.slice(br.start, br.end).trim();
    if (blockText) {
      segments.push({ type: 'block', text: blockText, directive: br.directive });
    }
    cursor = br.end;
  }
  pushNormalSegment(cursor, rawText.length);
  return segments;
}

function MarkdownContentInner({
  content,
  currentSeq = 1,
  imagesBySeq = null,
  validSeqSet = null,
  onJumpToSeq = null,
  highlightRanges = null,
  className = ''
}) {
  const auth = useOptionalAuth();
  const isAdmin = Boolean(auth?.isAdmin);
  const [pendingLinkHref, setPendingLinkHref] = useState(null);
  const cancelBtnRef = useRef(null);

  useEffect(() => {
    if (!pendingLinkHref) return undefined;
    cancelBtnRef.current?.focus();
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        setPendingLinkHref(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [pendingLinkHref]);

  const handleConfirmNavigate = () => {
    const target = pendingLinkHref;
    setPendingLinkHref(null);
    if (!target || !isSafeMarkdownHref(target)) return;
    if (target.toLowerCase().startsWith('mailto:')) {
      window.location.assign(target);
      return;
    }
    window.open(target, '_blank', 'noopener,noreferrer');
  };

  const rawText = typeof content === 'string' ? content : String(content || '');
  const normalizedHighlights = useMemo(
    () => normalizeHighlightRanges(rawText, highlightRanges),
    [rawText, highlightRanges]
  );
  const highlightedSegments = useMemo(() => {
    if (normalizedHighlights.length === 0) return null;
    return partitionHighlightedSegments(rawText, normalizedHighlights).map((seg) => ({
      ...seg,
      rendered:
        seg.type === 'normal' && seg.inlineRanges.length > 0
          ? buildHighlightedMarkdown(seg.text, seg.inlineRanges)
          : preprocessKoreanMarkdown(seg.text)
    }));
  }, [rawText, normalizedHighlights]);

  const safeContent = useMemo(
    () => (normalizedHighlights.length === 0 ? preprocessKoreanMarkdown(rawText) : ''),
    [rawText, normalizedHighlights.length]
  );

  const components = useMemo(() => {
    const ctx = { currentSeq, imagesBySeq, validSeqSet, onJumpToSeq };
    const renderBlockWithTokens = (Tag, children, appendFigures = true) => {
      const figures = [];
      const transformed =
        imagesBySeq || validSeqSet
          ? transformInlineChildren(children, ctx, figures, Tag)
          : children;
      return (
        <>
          <Tag>{transformed}</Tag>
          {appendFigures && <InlinePhotoFigures figures={figures} onJumpToSeq={onJumpToSeq} />}
        </>
      );
    };

    return {
      p: ({ children }) => renderBlockWithTokens('p', children, true),
      h1: ({ children }) => renderBlockWithTokens('h1', children, true),
      h2: ({ children }) => renderBlockWithTokens('h2', children, true),
      h3: ({ children }) => renderBlockWithTokens('h3', children, true),
      h4: ({ children }) => renderBlockWithTokens('h4', children, true),
      h5: ({ children }) => renderBlockWithTokens('h5', children, true),
      h6: ({ children }) => renderBlockWithTokens('h6', children, true),
      li: ({ node, className: liClass, children }) => {
        const safeLiClass = typeof liClass === 'string' && liClass === 'task-list-item' ? liClass : undefined;
        const hasParagraphChild = Boolean(
          node?.children?.some((c) => c.type === 'element' && c.tagName === 'p')
        );
        if (hasParagraphChild || (!imagesBySeq && !validSeqSet)) {
          return <li className={safeLiClass}>{children}</li>;
        }
        const figures = [];
        const transformed = transformInlineChildren(children, ctx, figures, 'li');
        return (
          <li className={safeLiClass}>
            {transformed}
            <InlinePhotoFigures figures={figures} onJumpToSeq={onJumpToSeq} />
          </li>
        );
      },
      td: ({ align, children }) => {
        const safeAlign = align === 'left' || align === 'center' || align === 'right' ? align : undefined;
        const figures = [];
        const transformed =
          imagesBySeq || validSeqSet
            ? transformInlineChildren(children, ctx, figures, 'td')
            : children;
        return (
          <td style={safeAlign ? { textAlign: safeAlign } : undefined}>
            {transformed}
            <InlinePhotoFigures figures={figures} onJumpToSeq={onJumpToSeq} />
          </td>
        );
      },
      th: ({ align, children }) => {
        const safeAlign = align === 'left' || align === 'center' || align === 'right' ? align : undefined;
        const figures = [];
        const transformed =
          imagesBySeq || validSeqSet
            ? transformInlineChildren(children, ctx, figures, 'th')
            : children;
        return <th style={safeAlign ? { textAlign: safeAlign } : undefined}>{transformed}</th>;
      },
      table: ({ children }) => (
        <div className="md-table-wrap">
          <table>{children}</table>
        </div>
      ),
      a: ({ href, children }) => {
        if (!isSafeMarkdownHref(href)) {
          return (
            <span className="md-blocked-link" title="보안 정책상 허용되지 않은 링크입니다.">
              {children}
            </span>
          );
        }
        return (
          <span className="md-link-wrap">
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer nofollow"
              className="md-link"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                setPendingLinkHref(href);
              }}
            >
              {children}
            </a>
            {isAdmin && (
              <span className="md-admin-link-url" title={`실제 이동 경로: ${href}`}>
                ({href})
              </span>
            )}
          </span>
        );
      },
      img: ({ node: _node, src, alt }) => <MarkdownInlineImage key={src} src={src} alt={alt} />
    };
  }, [currentSeq, imagesBySeq, validSeqSet, onJumpToSeq, isAdmin]);

  const isMailto = Boolean(pendingLinkHref && pendingLinkHref.toLowerCase().startsWith('mailto:'));

  return (
    <div className={`md-content ${className}`.trim()}>
      {highlightedSegments ? (
        highlightedSegments.map((seg, idx) =>
          seg.type === 'block' ? (
            <div
              key={`hl-block-${idx}`}
              className="ai-directive-highlight-block"
              title={seg.directive ? `지시사항: ${seg.directive}` : '지시사항 반영'}
            >
              <ReactMarkdown
                remarkPlugins={REMARK_PLUGINS}
                rehypePlugins={REHYPE_PLUGINS}
                urlTransform={safeMarkdownUrlTransform}
                components={components}
              >
                {seg.rendered}
              </ReactMarkdown>
            </div>
          ) : (
            <ReactMarkdown
              key={`hl-norm-${idx}`}
              remarkPlugins={
                seg.inlineRanges.length > 0 ? REMARK_PLUGINS_WITH_HIGHLIGHT : REMARK_PLUGINS
              }
              rehypePlugins={REHYPE_PLUGINS}
              urlTransform={safeMarkdownUrlTransform}
              components={components}
            >
              {seg.rendered}
            </ReactMarkdown>
          )
        )
      ) : (
        <ReactMarkdown
          remarkPlugins={REMARK_PLUGINS}
          rehypePlugins={REHYPE_PLUGINS}
          urlTransform={safeMarkdownUrlTransform}
          components={components}
        >
          {safeContent}
        </ReactMarkdown>
      )}
      {pendingLinkHref &&
        typeof document !== 'undefined' &&
        createPortal(
          <div
            className="modal-overlay md-link-confirm-overlay"
            onClick={(e) => {
              e.stopPropagation();
              setPendingLinkHref(null);
            }}
          >
            <div
              className="modal-card md-link-confirm-modal"
              role="dialog"
              aria-modal="true"
              aria-labelledby="md-link-confirm-title"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="md-link-confirm-header">
                <span className="md-link-confirm-title-wrap" id="md-link-confirm-title">
                  <AlertTriangle size={18} className="md-link-confirm-icon" />
                  {isMailto ? '이메일 링크 열기 확인' : '외부 링크 이동 경고'}
                </span>
                <button
                  type="button"
                  className="md-link-confirm-close"
                  onClick={() => setPendingLinkHref(null)}
                  aria-label="닫기"
                >
                  <X size={16} />
                </button>
              </div>
              <p className="md-link-confirm-desc">
                {isMailto
                  ? '메일 앱으로 아래 이메일 주소를 엽니다. 주소를 확인한 뒤 이동해 주세요.'
                  : '작성자가 첨부한 외부 링크로 이동합니다. 피싱이나 악성 사이트일 수 있으니 이동 전 주소를 확인해 주세요.'}
              </p>
              <div className="md-link-confirm-url" title={pendingLinkHref}>
                {pendingLinkHref}
              </div>
              <div className="md-link-confirm-actions">
                <button
                  ref={cancelBtnRef}
                  type="button"
                  className="btn-secondary"
                  onClick={() => setPendingLinkHref(null)}
                >
                  취소
                </button>
                <button
                  type="button"
                  className="btn-primary md-link-confirm-go"
                  onClick={handleConfirmNavigate}
                >
                  <ExternalLink size={14} />
                  이동하기
                </button>
              </div>
            </div>
          </div>,
          document.body
        )}
    </div>
  );
}

const MarkdownContent = React.memo(MarkdownContentInner);
export default MarkdownContent;
