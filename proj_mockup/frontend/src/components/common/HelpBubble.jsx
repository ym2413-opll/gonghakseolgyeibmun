// src/components/common/HelpBubble.jsx
import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { HelpCircle, X } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';

// 전역 부동 말풍선 레지스트리 (인접 요소 위 말풍선/버튼이 옆으로 겹치지 않고 아래로 정돈 배치)
const floatingRegistry = new Map();
let globalRafId = null;
let sharedResizeObserver = null;
let sharedMutationObserver = null;
let fontsHooked = false;

function scheduleGlobalRecalc() {
  if (typeof window === 'undefined') return;
  if (globalRafId !== null) cancelAnimationFrame(globalRafId);
  globalRafId = requestAnimationFrame(() => {
    globalRafId = null;
    recalculateAllFloatingBubbles();
  });
}

function ensureGlobalObservers() {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  if (!fontsHooked && document.fonts?.ready) {
    fontsHooked = true;
    document.fonts.ready.then(() => scheduleGlobalRecalc()).catch(() => {});
  }

  if (!sharedResizeObserver && typeof ResizeObserver !== 'undefined') {
    sharedResizeObserver = new ResizeObserver(() => {
      scheduleGlobalRecalc();
    });
    if (document.body) {
      sharedResizeObserver.observe(document.body);
    }
  }

  if (!sharedMutationObserver && typeof MutationObserver !== 'undefined' && document.body) {
    sharedMutationObserver = new MutationObserver((mutations) => {
      // 말풍선 자체 포털 DOM 변경은 무시하고, 모달/비동기 리스트 등 외부 레이아웃 변경만 감지
      const hasExternalLayoutChange = mutations.some((m) => {
        const targetEl = m.target instanceof Element ? m.target : null;
        if (targetEl?.closest('.help-bubble-floating, .help-bubble-host')) return false;
        const nodes = [...m.addedNodes, ...m.removedNodes];
        if (nodes.length === 0) return false;
        return nodes.some((n) => {
          if (!(n instanceof Element)) return false;
          return !n.classList.contains('help-bubble-floating');
        });
      });
      if (hasExternalLayoutChange) {
        scheduleGlobalRecalc();
      }
    });
    sharedMutationObserver.observe(document.body, {
      childList: true,
      subtree: true
    });
  }
}

function isBlockingOverlayOpen() {
  if (typeof document === 'undefined') return false;
  return Boolean(
    document.querySelector(
      '.modal-backdrop, .modal-overlay, .image-viewer-modal-backdrop, .notif-dropdown, .user-account-dropdown'
    )
  );
}

function arePositionsEqual(a, b) {
  if (a === b) return true;
  if (!a || !b) return false;
  return (
    a.top === b.top &&
    a.left === b.left &&
    a.arrowLeft === b.arrowLeft &&
    a.isFlippedTop === b.isFlippedTop &&
    a.hasArrow === b.hasArrow &&
    a.inHeader === b.inHeader &&
    a.visible === b.visible
  );
}

function recalculateAllFloatingBubbles() {
  if (typeof window === 'undefined') return;

  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const margin = vw <= 640 ? 10 : 12;
  const stackGap = 8;
  const modalBlocking = isBlockingOverlayOpen();

  // DOM 순서대로 정렬하여 상단(헤더) -> 본문(좌->우, 위->아래) 순으로 배치
  const entries = Array.from(floatingRegistry.values()).sort((a, b) => {
    const elA = a.hostRef.current;
    const elB = b.hostRef.current;
    if (!elA || !elB || elA === elB) return 0;
    const rel = elA.compareDocumentPosition(elB);
    if (rel & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
    if (rel & Node.DOCUMENT_POSITION_PRECEDING) return 1;
    return 0;
  });

  const placedRects = [];

  entries.forEach((entry) => {
    const hostEl = entry.hostRef.current;
    const bubbleEl = entry.bubbleRef.current;

    if (!hostEl || !entry.active || modalBlocking) {
      entry.commitPos((prev) => (prev && !prev.visible ? prev : { top: -9999, left: -9999, arrowLeft: 16, isFlippedTop: false, hasArrow: false, inHeader: false, visible: false }));
      return;
    }

    const hostRect = hostEl.getBoundingClientRect();
    const inHeader = Boolean(hostEl.closest('.app-header'));

    if (
      (hostRect.width === 0 && hostRect.height === 0) ||
      hostEl.closest('.is-blurred-locked, [aria-hidden="true"]') ||
      hostRect.bottom < 0 ||
      hostRect.top > vh ||
      (!inHeader && hostRect.bottom < 68)
    ) {
      entry.commitPos((prev) => (prev && !prev.visible ? prev : { top: -9999, left: -9999, arrowLeft: 16, isFlippedTop: false, hasArrow: false, inHeader, visible: false }));
      return;
    }

    const bubbleWidth = bubbleEl ? bubbleEl.offsetWidth : Math.min(250, vw - margin * 2);
    const bubbleHeight = bubbleEl ? bubbleEl.offsetHeight : 58;

    const desiredLeft =
      entry.placement === 'bottom-end'
        ? hostRect.right - bubbleWidth
        : hostRect.left;
    const clampedLeft = Math.max(margin, Math.min(desiredLeft, vw - bubbleWidth - margin));

    let top = hostRect.bottom + 10;
    let isFlippedTop = false;
    let wasStacked = false;

    if (top + bubbleHeight > vh - margin && hostRect.top - 10 - bubbleHeight >= margin) {
      top = hostRect.top - bubbleHeight - 10;
      isFlippedTop = true;
    }

    // 이미 배치된 말풍선과 수평/수직 영역이 겹치면 옆에 겹치지 않고 아래(또는 위)로 내려 쌓기
    let guard = 0;
    while (guard < 12) {
      guard += 1;
      const collider = placedRects.find((rect) => {
        const horizOverlap =
          clampedLeft < rect.right + stackGap && clampedLeft + bubbleWidth + stackGap > rect.left;
        const vertOverlap =
          top < rect.bottom + stackGap && top + bubbleHeight + stackGap > rect.top;
        return horizOverlap && vertOverlap;
      });

      if (!collider) break;

      wasStacked = true;
      if (isFlippedTop) {
        const candidateAbove = collider.top - bubbleHeight - stackGap;
        if (candidateAbove >= margin) {
          top = candidateAbove;
        } else {
          isFlippedTop = false;
          top = collider.bottom + stackGap;
        }
      } else {
        const candidateBelow = collider.bottom + stackGap;
        if (candidateBelow + bubbleHeight <= vh - margin || inHeader) {
          top = candidateBelow;
        } else {
          // 뷰포트 하단이 좁으면 위로 반전 시도
          const aboveHost = hostRect.top - bubbleHeight - 10;
          if (aboveHost >= margin && !placedRects.some((r) => clampedLeft < r.right + stackGap && clampedLeft + bubbleWidth + stackGap > r.left && aboveHost < r.bottom + stackGap && aboveHost + bubbleHeight + stackGap > r.top)) {
            top = aboveHost;
            isFlippedTop = true;
          } else {
            top = candidateBelow;
          }
        }
      }
    }

    const hostCenterX = hostRect.left + hostRect.width / 2;
    const rawArrowLeft = hostCenterX - clampedLeft - 6;
    const arrowLeft = Math.max(12, Math.min(rawArrowLeft, Math.max(12, bubbleWidth - 24)));

    placedRects.push({
      left: clampedLeft,
      right: clampedLeft + bubbleWidth,
      top,
      bottom: top + bubbleHeight
    });

    entry.commitPos({
      top: Math.round(top),
      left: Math.round(clampedLeft),
      arrowLeft: Math.round(arrowLeft),
      isFlippedTop,
      hasArrow: !wasStacked,
      inHeader,
      visible: true
    });
  });
}

/**
 * R4 사용 도움말 말풍선 컴포넌트
 * - tier: 'SOME' (핵심 기능) | 'ALL' (상세 기능)
 * - mode: 'inline' (문서 흐름 내 말풍선) | 'floating' (겹침 시 아래로 정렬되는 부동 말풍선)
 * - placement: 'bottom' | 'bottom-end' | 'top' | 'right'
 */
const HelpBubble = ({
  tier = 'SOME',
  text,
  mode = 'inline',
  placement = 'bottom',
  className = '',
  children
}) => {
  const { isAuthenticated } = useAuth();
  const { helpLevel } = useTheme();
  const [collapsed, setCollapsed] = useState(false);
  const [floatingPos, setFloatingPos] = useState(null);
  const hostRef = useRef(null);
  const bubbleRef = useRef(null);
  const instanceIdRef = useRef(Symbol('help-bubble'));

  // helpLevel 변경 시 접힘 상태 초기화
  useEffect(() => {
    setCollapsed(false);
  }, [helpLevel]);

  const shouldShow =
    isAuthenticated &&
    Boolean(text) &&
    (helpLevel === 'ALL' || (helpLevel === 'SOME' && tier === 'SOME'));

  useLayoutEffect(() => {
    const id = instanceIdRef.current;
    if (mode !== 'floating' || !shouldShow || collapsed) {
      floatingRegistry.delete(id);
      scheduleGlobalRecalc();
      return undefined;
    }

    floatingRegistry.set(id, {
      hostRef,
      bubbleRef,
      placement,
      active: true,
      commitPos: (nextOrUpdater) => {
        setFloatingPos((prev) => {
          const next = typeof nextOrUpdater === 'function' ? nextOrUpdater(prev) : nextOrUpdater;
          return arePositionsEqual(prev, next) ? prev : next;
        });
      }
    });

    ensureGlobalObservers();

    const hostEl = hostRef.current;
    const bubbleEl = bubbleRef.current;
    if (sharedResizeObserver) {
      if (hostEl) sharedResizeObserver.observe(hostEl);
      if (bubbleEl) sharedResizeObserver.observe(bubbleEl);
    }

    recalculateAllFloatingBubbles();
    scheduleGlobalRecalc();
    const settleTimer1 = window.setTimeout(scheduleGlobalRecalc, 60);
    const settleTimer2 = window.setTimeout(scheduleGlobalRecalc, 240);

    const handleTrigger = () => {
      scheduleGlobalRecalc();
      window.setTimeout(scheduleGlobalRecalc, 0);
    };
    window.addEventListener('resize', handleTrigger);
    window.addEventListener('scroll', handleTrigger, true);
    window.addEventListener('click', handleTrigger, false);

    return () => {
      window.clearTimeout(settleTimer1);
      window.clearTimeout(settleTimer2);
      floatingRegistry.delete(id);
      if (sharedResizeObserver) {
        if (hostEl) sharedResizeObserver.unobserve(hostEl);
        if (bubbleEl) sharedResizeObserver.unobserve(bubbleEl);
      }
      window.removeEventListener('resize', handleTrigger);
      window.removeEventListener('scroll', handleTrigger, true);
      window.removeEventListener('click', handleTrigger, false);
      scheduleGlobalRecalc();
    };
  }, [mode, shouldShow, collapsed, placement, text]);

  if (!shouldShow) {
    return children ? <>{children}</> : null;
  }

  if (mode === 'floating') {
    const portalNode =
      !collapsed && typeof document !== 'undefined'
        ? createPortal(
            <div
              ref={bubbleRef}
              className={`help-bubble-callout help-bubble-floating ${
                floatingPos?.inHeader ? 'in-header' : ''
              } ${floatingPos?.isFlippedTop ? 'flipped-top' : ''} ${
                floatingPos && !floatingPos.hasArrow ? 'no-arrow' : ''
              }`}
              style={{
                position: 'fixed',
                top: floatingPos ? `${floatingPos.top}px` : '-9999px',
                left: floatingPos ? `${floatingPos.left}px` : '-9999px',
                visibility: floatingPos?.visible ? 'visible' : 'hidden',
                '--help-arrow-left': `${floatingPos?.arrowLeft ?? 16}px`
              }}
              role="note"
            >
              <span className="help-bubble-badge">TIP</span>
              <span className="help-bubble-text">{text}</span>
              <button
                type="button"
                className="help-bubble-close"
                onClick={(e) => {
                  e.stopPropagation();
                  setCollapsed(true);
                }}
                aria-label="말풍선 닫기"
                title="말풍선 접기"
              >
                <X size={12} />
              </button>
            </div>,
            document.body
          )
        : null;

    return (
      <div ref={hostRef} className={`help-bubble-host ${collapsed ? 'is-collapsed' : ''} ${className}`}>
        {children}
        {collapsed ? (
          <button
            type="button"
            className="help-bubble-reopen-btn"
            onClick={(e) => {
              e.stopPropagation();
              setCollapsed(false);
            }}
            title={text}
            aria-label="도움말 열기"
          >
            <HelpCircle size={12} />
          </button>
        ) : (
          portalNode
        )}
      </div>
    );
  }

  // inline mode: children와 함께 배치하거나 단독 말풍선 카드로 렌더링
  return (
    <div className={`help-bubble-inline-wrap ${className}`}>
      {children}
      {collapsed ? (
        <button
          type="button"
          className="help-bubble-reopen-pill"
          onClick={(e) => {
            e.stopPropagation();
            setCollapsed(false);
          }}
          title={text}
        >
          <HelpCircle size={12} />
          <span>도움말</span>
        </button>
      ) : (
        <div className={`help-bubble-callout help-bubble-inline placement-${placement}`} role="note">
          <HelpCircle size={14} className="help-bubble-icon" />
          <span className="help-bubble-text">{text}</span>
          <button
            type="button"
            className="help-bubble-close"
            onClick={(e) => {
              e.stopPropagation();
              setCollapsed(true);
            }}
            aria-label="말풍선 닫기"
            title="말풍선 접기"
          >
            <X size={12} />
          </button>
        </div>
      )}
    </div>
  );
};

export default HelpBubble;
