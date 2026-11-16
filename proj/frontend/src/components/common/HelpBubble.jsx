// src/components/common/HelpBubble.jsx
import React, { useEffect, useLayoutEffect, useRef, useState, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { HelpCircle, X } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';

/**
 * R4 사용 도움말 말풍선 컴포넌트
 * - tier: 'SOME' (핵심 기능) | 'ALL' (상세 기능)
 * - mode: 'inline' (문서 흐름 내 말풍선) | 'floating' (기준 요소 위/아래 뷰포트 클램핑 부동 말풍선)
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

  // helpLevel 변경 시 접힘 상태 초기화
  useEffect(() => {
    setCollapsed(false);
  }, [helpLevel]);

  const shouldShow =
    isAuthenticated &&
    Boolean(text) &&
    (helpLevel === 'ALL' || (helpLevel === 'SOME' && tier === 'SOME'));

  const updateFloatingPosition = useCallback(() => {
    const hostEl = hostRef.current;
    const bubbleEl = bubbleRef.current;
    if (!hostEl || !shouldShow || collapsed || mode !== 'floating') {
      setFloatingPos(null);
      return;
    }

    const hostRect = hostEl.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;

    // 요소가 숨겨져 있거나 블러 잠금 영역 안에 있거나 뷰포트 밖으로 스크롤된 경우 숨김
    if (
      (hostRect.width === 0 && hostRect.height === 0) ||
      hostEl.closest('.is-blurred-locked, [aria-hidden="true"]') ||
      hostRect.bottom < 0 ||
      hostRect.top > vh
    ) {
      setFloatingPos((prev) => (prev ? { ...prev, visible: false } : null));
      return;
    }

    // 헤더 아래 스크롤 컨테이너에서 상단 고정 헤더 아래로 가려진 경우 숨김
    const inHeader = Boolean(hostEl.closest('.app-header'));
    if (!inHeader && hostRect.bottom < 68) {
      setFloatingPos((prev) => (prev ? { ...prev, visible: false } : null));
      return;
    }

    const margin = 12;
    const bubbleWidth = bubbleEl ? bubbleEl.offsetWidth : Math.min(250, vw - margin * 2);
    const bubbleHeight = bubbleEl ? bubbleEl.offsetHeight : 64;

    const desiredLeft =
      placement === 'bottom-end'
        ? hostRect.right - bubbleWidth
        : hostRect.left;
    const clampedLeft = Math.max(margin, Math.min(desiredLeft, vw - bubbleWidth - margin));

    let top = hostRect.bottom + 10;
    let isFlippedTop = false;
    if (top + bubbleHeight > vh - margin && hostRect.top - 10 - bubbleHeight >= margin) {
      top = hostRect.top - bubbleHeight - 10;
      isFlippedTop = true;
    }

    const hostCenterX = hostRect.left + hostRect.width / 2;
    const rawArrowLeft = hostCenterX - clampedLeft - 6;
    const arrowLeft = Math.max(12, Math.min(rawArrowLeft, Math.max(12, bubbleWidth - 24)));

    setFloatingPos({
      top: Math.round(top),
      left: Math.round(clampedLeft),
      arrowLeft: Math.round(arrowLeft),
      isFlippedTop,
      inHeader,
      visible: true
    });
  }, [shouldShow, collapsed, mode, placement]);

  useLayoutEffect(() => {
    if (mode !== 'floating' || !shouldShow || collapsed) return undefined;
    updateFloatingPosition();

    let rafId = null;
    const scheduleUpdate = () => {
      if (rafId !== null) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        rafId = null;
        updateFloatingPosition();
      });
    };

    window.addEventListener('resize', scheduleUpdate);
    window.addEventListener('scroll', scheduleUpdate, true);
    return () => {
      if (rafId !== null) cancelAnimationFrame(rafId);
      window.removeEventListener('resize', scheduleUpdate);
      window.removeEventListener('scroll', scheduleUpdate, true);
    };
  }, [mode, shouldShow, collapsed, text, updateFloatingPosition]);

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
              } ${floatingPos?.isFlippedTop ? 'flipped-top' : ''}`}
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
      <div ref={hostRef} className={`help-bubble-host ${className}`}>
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
            <HelpCircle size={13} />
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
