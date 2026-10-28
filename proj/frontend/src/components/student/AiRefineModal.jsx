// src/components/student/AiRefineModal.jsx
import React, { useState, useEffect } from 'react';
import { api } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { AI_INQUIRY_TEMPLATES } from '../../services/mockData';
import { PenTool, Check, ArrowRight, RotateCw, AlertTriangle, Edit3, CheckCircle2, X, FileText, Loader2 } from 'lucide-react';

export default function AiRefineModal({
  isOpen,
  onClose,
  initialTitle = '',
  initialText = '',
  category = 'lecture',
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

  const handleTriggerAI = React.useCallback(async (textToRefine, titleToRefine) => {
    const text = textToRefine !== undefined ? textToRefine : originalText;
    const ttl = titleToRefine !== undefined ? titleToRefine : originalTitle;
    if ((!text || !text.trim()) && (!ttl || !ttl.trim())) return;

    setIsLoading(true);
    try {
      const studentInfo = {
        name: currentUser?.name || '학생',
        studentId: currentUser?.studentId || '',
        department: currentUser?.department || '학부'
      };
      const result = await api.refineQuestionAI(text, category, studentInfo, ttl);
      setAiResult(result);
      setRefinedText(result.refined || '');
      setRefinedTitle(result.refinedTitle || '');
    } catch (err) {
      console.error('Text refinement error', err);
    } finally {
      setIsLoading(false);
    }
  }, [originalText, originalTitle, currentUser, category]);

  useEffect(() => {
    if (isOpen) {
      const initTtl = initialTitle || '';
      const initTxt = initialText || '';
      setOriginalTitle(initTtl);
      setOriginalText(initTxt);
      setRefinedTitle('');
      setRefinedText('');
      setAiResult(null);
      setIsEditingRefined(false);
      if (initTxt.trim().length > 3 || initTtl.trim().length > 2) {
        handleTriggerAI(initTxt, initTtl);
      }
    }
  }, [isOpen, initialText, initialTitle, handleTriggerAI]);

  if (!isOpen) return null;

  const handleApply = () => {
    if (typeof onApplyRefined === 'function') {
      onApplyRefined(refinedText, originalText, refinedTitle, originalTitle);
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

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content ai-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header ai-modal-header">
          <div className="modal-title">
            <div className="refine-icon-wrap">
              <PenTool size={17} />
            </div>
            <span>학술 문장 다듬기 (제목 및 서식 정제)</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          {/* 학업 문의 추천 서식 템플릿 */}
          <div className="ai-quick-templates">
            <span className="template-label">학업 질의 표준 템플릿:</span>
            <div className="template-chips">
              {AI_INQUIRY_TEMPLATES.map((tmpl, idx) => (
                <button
                  key={idx}
                  className="template-chip-btn"
                  onClick={() => loadTemplate(tmpl)}
                  title={`${tmpl.rawTitle || tmpl.title}: ${tmpl.rawText}`}
                >
                  {tmpl.title}
                </button>
              ))}
            </div>
          </div>

          <div className="ai-compare-grid">
            {/* 좌측: 원문 (제목 & 본문) */}
            <div className="compare-card original-card">
              <div className="compare-header">
                <span className="compare-tag origin-tag">작성 원문</span>
                <button
                  className="re-run-btn"
                  onClick={() => handleTriggerAI(originalText, originalTitle)}
                  disabled={isLoading || (!originalText.trim() && !originalTitle.trim())}
                >
                  <RotateCw size={13} className={isLoading ? 'spinning' : ''} />
                  다시 다듬기
                </button>
              </div>

              <div className="compare-card-body">
                <div className="compare-field-wrap">
                  <label className="ai-sub-label">질문 제목 원문</label>
                  <input
                    type="text"
                    value={originalTitle}
                    onChange={(e) => setOriginalTitle(e.target.value)}
                    placeholder="작성 중인 제목을 입력하세요..."
                    className="ai-title-input origin-title-input"
                  />
                </div>

                <div className="compare-field-wrap">
                  <label className="ai-sub-label">질문 본문 원문</label>
                  <textarea
                    value={originalText}
                    onChange={(e) => setOriginalText(e.target.value)}
                    placeholder="질문하고 싶은 내용을 편하게 입력하세요..."
                    className="ai-textarea origin-textarea"
                    rows={8}
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
                  <span className="compare-tag ai-tag">
                    <FileText size={13} /> 정제 제안본
                  </span>
                  {aiResult && (
                    <span className="polite-score-badge">
                      표준 서식 일치도 {aiResult.politenessScore}%
                    </span>
                  )}
                </div>
                {aiResult && (
                  <button
                    className={`edit-toggle-btn ${isEditingRefined ? 'active' : ''}`}
                    onClick={() => setIsEditingRefined(!isEditingRefined)}
                  >
                    <Edit3 size={13} />
                    {isEditingRefined ? '수정 완료' : '직접 수정'}
                  </button>
                )}
              </div>

              <div className="compare-card-body">
                {isLoading ? (
                  <div className="ai-loading-box">
                    <Loader2 size={24} className="spinning ai-spinner" />
                    <p>표준 학술 서식과 문맥을 검토하여 정제안을 생성하고 있습니다...</p>
                  </div>
                ) : (refinedText || refinedTitle) ? (
                  <>
                    <div className="compare-field-wrap">
                      <label className="ai-sub-label">정제된 제목</label>
                      <input
                        type="text"
                        value={refinedTitle}
                        onChange={(e) => setRefinedTitle(e.target.value)}
                        readOnly={!isEditingRefined}
                        className={`ai-title-input refined-title-input ${isEditingRefined ? 'editable' : ''}`}
                        placeholder="정제된 제목이 표시됩니다..."
                      />
                    </div>

                    <div className="compare-field-wrap">
                      <label className="ai-sub-label">정제된 본문</label>
                      <textarea
                        value={refinedText}
                        onChange={(e) => setRefinedText(e.target.value)}
                        readOnly={!isEditingRefined}
                        className={`ai-textarea refined-textarea ${isEditingRefined ? 'editable' : ''}`}
                        rows={8}
                        placeholder="정제된 본문이 표시됩니다..."
                      />
                    </div>
                  </>
                ) : (
                  <div className="ai-empty-prompt">
                    <FileText size={24} />
                    <p>원문 제목이나 본문을 입력한 후 [다시 다듬기]를 누르면 표준 학술 서식으로 변환됩니다.</p>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* 교정 포인트 안내 */}
          {aiResult?.changes && (
            <div className="ai-improvements-box">
              <div className="improv-title">
                <CheckCircle2 size={15} />
                <span>주요 검토 및 정제 사항:</span>
              </div>
              <ul className="improv-list">
                {aiResult.changes.map((c, i) => (
                  <li key={i}>{c}</li>
                ))}
              </ul>
            </div>
          )}

          <div className="ai-disclaimer">
            <AlertTriangle size={14} />
            <span>
              <strong>확인 안내:</strong> 정제된 제목과 본문이 질문 의도와 맞는지 확인하신 후 적용해 주세요.
            </span>
          </div>
        </div>

        <div className="modal-footer ai-modal-footer">
          <button className="btn btn-secondary" onClick={onClose}>
            원문 유지하고 닫기
          </button>
          <button
            className="btn btn-primary"
            onClick={handleApply}
            disabled={(!refinedText && !refinedTitle) || isLoading}
          >
            <Check size={16} />
            정제안 적용하기
          </button>
        </div>
      </div>
    </div>
  );
}
