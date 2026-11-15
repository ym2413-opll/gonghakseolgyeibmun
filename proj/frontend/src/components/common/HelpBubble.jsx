// src/components/common/HelpBubble.jsx
import React, { useEffect, useState } from 'react';
import { HelpCircle, X } from 'lucide-react';
import { useTheme } from '../../context/ThemeContext';

/**
 * R4 사용 도움말 말풍선 컴포넌트
 * - tier: 'SOME' (핵심 기능) | 'ALL' (상세 기능)
 * - mode: 'inline' (문서 흐름 내 말풍선) | 'floating' (기준 요소 아래/위 부동 말풍선)
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
  const { helpLevel } = useTheme();
  const [collapsed, setCollapsed] = useState(false);

  // helpLevel 변경 시 접힘 상태 초기화
  useEffect(() => {
    setCollapsed(false);
  }, [helpLevel]);

  const shouldShow =
    Boolean(text) &&
    (helpLevel === 'ALL' || (helpLevel === 'SOME' && tier === 'SOME'));

  if (!shouldShow) {
    return children ? <>{children}</> : null;
  }

  if (mode === 'floating') {
    return (
      <div className={`help-bubble-host ${className}`}>
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
          <div
            className={`help-bubble-callout help-bubble-floating placement-${placement}`}
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
          </div>
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
