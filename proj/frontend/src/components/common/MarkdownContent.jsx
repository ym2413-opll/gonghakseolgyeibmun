// src/components/common/MarkdownContent.jsx
// XSS 안전 GFM Markdown + 포럼 전용 토큰([사진 k], [#N 사진 k], #N 글 인용) 렌더러
import React from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { isSafeAttachmentUrl } from '../../utils/fileSecurity';
import { isSafeMarkdownHref, safeMarkdownUrlTransform } from '../../utils/markdown';

const TOKEN_PATTERN_SOURCE =
  '(\\[#\\d+\\s*[-:]?\\s*사진\\s*\\d+\\]|\\[사진\\s*#\\d+\\s*[-:\\s]\\s*\\d+\\]|\\[사진\\s*\\d+\\]|#\\d+(?!\\d))';

const SKIP_DESCENT_TAGS = new Set(['code', 'pre', 'a', 'button', 'figure', 'img', 'ul', 'ol']);

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
    // 커스텀 컴포넌트(a, img, li, table 등) 및 SKIP_DESCENT_TAGS 내부로는 재귀 진입하지 않음
    if (SKIP_DESCENT_TAGS.has(tagType) || typeof children.type === 'function') {
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

export default function MarkdownContent({
  content,
  currentSeq = 1,
  imagesBySeq = null,
  validSeqSet = null,
  onJumpToSeq = null,
  className = ''
}) {
  const safeContent = typeof content === 'string' ? content : String(content || '');
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

  const components = {
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
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer nofollow"
          className="md-link"
          onClick={(e) => e.stopPropagation()}
        >
          {children}
        </a>
      );
    },
    img: ({ node: _node, src, alt }) => {
      // 외부 tracking pixel, data:image/svg+xml, javascript: 등 비인가 이미지 차단
      if (!isSafeAttachmentUrl(src)) {
        return (
          <span
            className="md-blocked-image"
            title="보안 정책상 외부 이미지는 표시되지 않습니다. 파일 첨부 후 [사진 N] 태그를 이용해 주세요."
          >
            [이미지: {alt || '외부 이미지 차단됨'}]
          </span>
        );
      }
      return (
        <figure className="inline-photo-figure">
          <img src={src} alt={alt || '첨부 이미지'} loading="lazy" />
          {alt && <figcaption>{alt}</figcaption>}
        </figure>
      );
    }
  };

  return (
    <div className={`md-content ${className}`.trim()}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkBreaks]}
        urlTransform={safeMarkdownUrlTransform}
        components={components}
      >
        {safeContent}
      </ReactMarkdown>
    </div>
  );
}
