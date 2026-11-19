// src/components/student/AiRefineModal.jsx
import React, { useState, useEffect, useRef, useMemo } from 'react';
import { createPortal } from 'react-dom';
import { api } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { AI_INQUIRY_TEMPLATES } from '../../services/mockData';
import { sanitizeAnonymousStudentText } from '../../utils/anonymize';
import MarkdownContent, { MarkdownModeTabs } from '../common/MarkdownContent';
import HelpBubble from '../common/HelpBubble';
import { PenTool, Check, ArrowRight, RotateCw, AlertTriangle, Edit3, CheckCircle2, X, FileText, Loader2, Sparkles } from 'lucide-react';

export default function AiRefineModal({
  isOpen,
  onClose,
  initialTitle = '',
  initialText = '',
  category = 'lecture',
  courseName = '',
  professorName = '',
  isFollowUp = false,
  previousAnswer = '',
  isAnonymous = false,
  onApplyRefined
}) {
  const { currentUser } = useAuth();
  const [originalTitle, setOriginalTitle] = useState(initialTitle || '');
  const [originalText, setOriginalText] = useState(initialText || '');
  const [refinedTitle, setRefinedTitle] = useState('');
  const [refinedText, setRefinedText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [aiResult, setAiResult] = useState(null);
  const [isEditingRefined, setIsEditingRefined] = useState(false);
  const [isPreviewRefined, setIsPreviewRefined] = useState(false);
  const [directiveHighlights, setDirectiveHighlights] = useState([]);
  const [errorMsg, setErrorMsg] = useState('');
  const requestIdRef = useRef(0);
  const isMountedRef = useRef(true);
  const wasOpenRef = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      requestIdRef.current += 1;
    };
  }, []);

  useEffect(() => {
    if (!isOpen) return undefined;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        onCloseRef.current?.();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  const userName = currentUser?.name || '';
  const userStudentId = currentUser?.identifier || currentUser?.studentId || '';
  const userDept = currentUser?.department || '';

  const handleTriggerAI = React.useCallback(async (textToRefine, titleToRefine = '') => {
    const text = textToRefine !== undefined ? textToRefine : '';
    const ttl = titleToRefine !== undefined ? titleToRefine : '';
    if ((!text || !text.trim()) && (!ttl || !ttl.trim())) return;

    const reqId = ++requestIdRef.current;
    setIsLoading(true);
    setErrorMsg('');
    try {
      const studentInfo = isAnonymous
        ? {}
        : {
            name: userName,
            studentId: userStudentId,
            department: userDept
          };
      const result = await api.refineQuestionAI(text, category, studentInfo, ttl, {
        courseName,
        professorName,
        isFollowUp,
        isAnonymous: Boolean(isAnonymous),
        previousAnswer
      });
      if (!isMountedRef.current || reqId !== requestIdRef.current) return;
      const profList = professorName ? [professorName] : [];
      const finalRefined = isAnonymous
        ? sanitizeAnonymousStudentText(result.refined || '', currentUser, { professorNames: profList })
        : (result.refined || '');
      const finalRefinedTitle = isAnonymous
        ? sanitizeAnonymousStudentText(result.refinedTitle || '', currentUser, { professorNames: profList })
        : (result.refinedTitle || '');
      const nextHighlights = [];
      let hlCursor = 0;
      if (Array.isArray(result.directiveHighlights)) {
        result.directiveHighlights.forEach((h, hIdx) => {
          const rawRep = typeof h?.replacement === 'string' ? h.replacement : '';
          if (!rawRep) return;
          const cleanRep = isAnonymous
            ? sanitizeAnonymousStudentText(rawRep, currentUser, { professorNames: profList })
            : rawRep;
          if (!cleanRep) return;
          const cleanDir = isAnonymous
            ? sanitizeAnonymousStudentText(String(h?.directive || ''), currentUser, { professorNames: profList })
            : String(h?.directive || '');
          let pos =
            typeof h?.start === 'number' &&
            typeof h?.end === 'number' &&
            h.start >= hlCursor &&
            finalRefined.slice(h.start, h.end) === cleanRep
              ? h.start
              : finalRefined.indexOf(cleanRep, hlCursor);
          if (pos < 0) pos = finalRefined.indexOf(cleanRep);
          if (pos >= 0) {
            const endPos = pos + cleanRep.length;
            nextHighlights.push({
              index: typeof h?.index === 'number' ? h.index : hIdx,
              directive: cleanDir,
              replacement: cleanRep,
              start: pos,
              end: endPos
            });
            hlCursor = endPos;
          }
        });
      }
      setAiResult({ ...result, refined: finalRefined, refinedTitle: finalRefinedTitle });
      setRefinedText(finalRefined);
      setRefinedTitle(finalRefinedTitle);
      setDirectiveHighlights(nextHighlights);
      if (nextHighlights.length > 0) {
        setIsPreviewRefined(true);
        setIsEditingRefined(false);
      }
    } catch (err) {
      if (!isMountedRef.current || reqId !== requestIdRef.current) return;
      console.error('Text refinement error', err);
      setErrorMsg(err.message || '문장 다듬기 중 오류가 발생했습니다.');
    } finally {
      if (isMountedRef.current && reqId === requestIdRef.current) {
        setIsLoading(false);
      }
    }
  }, [currentUser, userName, userStudentId, userDept, category, courseName, professorName, isFollowUp, previousAnswer, isAnonymous]);

  const handleTriggerAIRef = useRef(handleTriggerAI);
  handleTriggerAIRef.current = handleTriggerAI;

  useEffect(() => {
    if (isOpen && !wasOpenRef.current) {
      const initTtl = initialTitle || '';
      const initTxt = initialText || '';
      setIsLoading(false);
      setOriginalTitle(initTtl);
      setOriginalText(initTxt);
      setRefinedTitle('');
      setRefinedText('');
      setAiResult(null);
      setDirectiveHighlights([]);
      setErrorMsg('');
      setIsEditingRefined(false);
      setIsPreviewRefined(false);
      if (initTxt.trim().length > 3 || initTtl.trim().length > 2) {
        handleTriggerAIRef.current(initTxt, initTtl);
      }
    } else if (!isOpen && wasOpenRef.current) {
      requestIdRef.current += 1;
      setIsLoading(false);
    }
    wasOpenRef.current = isOpen;
  }, [isOpen, initialText, initialTitle]);

  const activeHighlights = useMemo(() => {
    if (!refinedText || directiveHighlights.length === 0) return [];
    const resolved = [];
    let cursor = 0;
    for (const h of directiveHighlights) {
      if (!h.replacement) continue;
      if (
        typeof h.start === 'number' &&
        typeof h.end === 'number' &&
        h.start >= cursor &&
        refinedText.slice(h.start, h.end) === h.replacement
      ) {
        resolved.push(h);
        cursor = h.end;
      } else {
        const pos = refinedText.indexOf(h.replacement, cursor);
        if (pos >= 0) {
          const endPos = pos + h.replacement.length;
          resolved.push({ ...h, start: pos, end: endPos });
          cursor = endPos;
        }
      }
    }
    return resolved;
  }, [refinedText, directiveHighlights]);

  if (!isOpen || typeof document === 'undefined') return null;

  const directiveCount = (() => {
    const matches = String(originalText || '').match(/(?:\[\[|［［)\s*([\s\S]{1,1000}?)\s*(?:\]\]|］］)/g);
    return matches ? Math.min(matches.length, 10) : 0;
  })();

  const handleApply = () => {
    if (typeof onApplyRefined === 'function') {
      const profList = professorName ? [professorName] : [];
      const outText = isAnonymous
        ? sanitizeAnonymousStudentText(refinedText, currentUser, { professorNames: profList })
        : refinedText;
      const outTitle = isAnonymous
        ? sanitizeAnonymousStudentText(refinedTitle, currentUser, { professorNames: profList })
        : refinedTitle;
      const outOrigText = isAnonymous
        ? sanitizeAnonymousStudentText(originalText, currentUser, { professorNames: profList })
        : originalText;
      const outOrigTitle = isAnonymous
        ? sanitizeAnonymousStudentText(originalTitle, currentUser, { professorNames: profList })
        : originalTitle;
      onApplyRefined(outText, outOrigText, outTitle, outOrigTitle);
    }
    onClose();
  };

  const loadTemplate = (template) => {
    const tmplTitle = template.rawTitle || template.title || '';
    const tmplText = template.rawText || '';
    setOriginalTitle(tmplTitle);
    setOriginalText(tmplText);
    handleTriggerAI(tmplText, tmplTitle);
  };

  return createPortal(
    <div
      className="modal-overlay"
      onClick={(e) => {
        e.stopPropagation();
        onClose();
      }}
    >
      <div className="modal-content ai-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header ai-modal-header">
          <div className="modal-title">
            <span>{isFollowUp ? '추가 질문 문장 다듬기' : '문장 다듬기'}</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <HelpBubble
            tier="ALL"
            mode="inline"
            text="원문과 다듬어진 글을 비교하고, 미리보기·수정으로 손본 뒤 적용해요. 본문의 [[지시사항]]은 AI 처리 결과로 바뀌어요."
            className="mb-help-bubble"
          />

          {directiveCount > 0 ? (
            <div className="ai-directive-notice">
              <Sparkles size={14} />
              <span>본문 내 <code>[[지시사항]]</code> {directiveCount}건을 AI가 처리하여 결과로 대체합니다.</span>
            </div>
          ) : (
            <div className="ai-directive-tip">
              <span>본문에 <code>[[지시사항]]</code>을 넣으면 AI가 해당 부분을 처리 결과로 대체합니다.</span>
            </div>
          )}

          {/* 학업 문의 추천 서식 템플릿 (일반 질문에서만 표시) */}
          {!isFollowUp && (
            <div className="ai-quick-templates">
              <span className="template-label">예시 문장:</span>
              <div className="template-chips">
                {AI_INQUIRY_TEMPLATES.map((tmpl, idx) => (
                  <button
                    key={idx}
                    type="button"
                    className="template-chip-btn"
                    disabled={isLoading}
                    onClick={() => loadTemplate(tmpl)}
                  >
                    {tmpl.title}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="ai-compare-grid">
            {/* 좌측: 원문 */}
            <div className="compare-card original-card">
              <div className="compare-header">
                <span className="compare-tag origin-tag">원문</span>
                <button
                  className="re-run-btn"
                  onClick={() => handleTriggerAI(originalText, originalTitle)}
                  disabled={isLoading || (!originalText.trim() && !originalTitle.trim())}
                >
                  <RotateCw size={13} className={isLoading ? 'spinning' : ''} />
                  다시 생성
                </button>
              </div>

              <div className="compare-card-body">
                <div className="compare-field-wrap">
                  <label className="ai-sub-label">질문 본문 (수정 불가)</label>
                  <textarea
                    value={originalText}
                    readOnly
                    placeholder="작성 화면에서 입력한 원문이 표시됩니다."
                    className="ai-textarea origin-textarea"
                    rows={11}
                  />
                </div>
              </div>
            </div>

            {/* 중간 화살표 */}
            <div className="compare-arrow">
              <div className="arrow-circle">
                <ArrowRight size={16} />
              </div>
            </div>

            {/* 우측: 교정 제안본 (제목 & 본문) */}
            <div className="compare-card refined-card">
              <div className="compare-header">
                <div className="refined-tag-group">
                  <span className="compare-tag ai-tag">정제 결과</span>
                </div>
                {aiResult && (
                  <div className="refined-header-actions">
                    <MarkdownModeTabs
                      isPreview={isPreviewRefined}
                      onTogglePreview={(nextPreview) => {
                        setIsPreviewRefined(nextPreview);
                        if (nextPreview) setIsEditingRefined(false);
                      }}
                    />
                    <button
                      type="button"
                      className={`edit-toggle-btn ${isEditingRefined ? 'active' : ''}`}
                      onClick={() => {
                        setIsPreviewRefined(false);
                        setIsEditingRefined(!isEditingRefined);
                      }}
                    >
                      <Edit3 size={13} />
                      {isEditingRefined ? '완료' : '수정'}
                    </button>
                  </div>
                )}
              </div>

              <div className="compare-card-body">
                {isLoading ? (
                  <div className="ai-loading-box">
                    <Loader2 size={22} className="spinning ai-spinner" />
                    <p>문장을 다듬는 중입니다...</p>
                  </div>
                ) : errorMsg ? (
                  <div className="ai-empty-prompt">
                    <p style={{ color: 'var(--danger-color, #dc2626)' }}>{errorMsg}</p>
                  </div>
                ) : (refinedText || refinedTitle) ? (
                  <>
                    {!isFollowUp && (
                      <div className="compare-field-wrap">
                        <label className="ai-sub-label">제목</label>
                        <input
                          type="text"
                          value={refinedTitle}
                          onChange={(e) => setRefinedTitle(e.target.value)}
                          readOnly={!isEditingRefined}
                          className={`ai-title-input refined-title-input ${isEditingRefined ? 'editable' : ''}`}
                        />
                      </div>
                    )}

                    <div className="compare-field-wrap">
                      <div className="ai-refined-body-label-row">
                        <label className="ai-sub-label" style={{ marginBottom: 0 }}>본문</label>
                        {isPreviewRefined && activeHighlights.length > 0 && (
                          <span className="ai-directive-legend">
                            강조 표시: 지시사항 반영 ({activeHighlights.length}건)
                          </span>
                        )}
                      </div>
                      {isPreviewRefined ? (
                        <div className="md-preview-box ai-refined-preview-box">
                          <MarkdownContent
                            content={refinedText}
                            highlightRanges={activeHighlights}
                          />
                        </div>
                      ) : (
                        <textarea
                          value={refinedText}
                          onChange={(e) => setRefinedText(e.target.value)}
                          readOnly={!isEditingRefined}
                          className={`ai-textarea refined-textarea ${isEditingRefined ? 'editable' : ''}`}
                          rows={8}
                        />
                      )}
                    </div>
                  </>
                ) : (
                  <div className="ai-empty-prompt">
                    <p>원문이 입력되면 정제된 문장이 표시됩니다.</p>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="modal-footer ai-modal-footer">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            취소
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleApply}
            disabled={(!refinedText && !refinedTitle) || isLoading}
          >
            적용
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
