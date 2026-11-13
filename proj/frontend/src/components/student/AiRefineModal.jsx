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
  courseName = '',
  professorName = '',
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

  const handleTriggerAI = React.useCallback(async (textToRefine, titleToRefine = '') => {
    const text = textToRefine !== undefined ? textToRefine : '';
    const ttl = titleToRefine !== undefined ? titleToRefine : '';
    if ((!text || !text.trim()) && (!ttl || !ttl.trim())) return;

    setIsLoading(true);
    try {
      const studentInfo = {
        name: currentUser?.name || '',
        studentId: currentUser?.identifier || currentUser?.studentId || '',
        department: currentUser?.department || ''
      };
      const result = await api.refineQuestionAI(text, category, studentInfo, ttl, {
        courseName,
        professorName
      });
      setAiResult(result);
      setRefinedText(result.refined || '');
      setRefinedTitle(result.refinedTitle || '');
    } catch (err) {
      console.error('Text refinement error', err);
    } finally {
      setIsLoading(false);
    }
  }, [currentUser, category, courseName, professorName]);

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
            <span>문장 다듬기</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          {/* 학업 문의 추천 서식 템플릿 */}
          <div className="ai-quick-templates">
            <span className="template-label">예시 문장:</span>
            <div className="template-chips">
              {AI_INQUIRY_TEMPLATES.map((tmpl, idx) => (
                <button
                  key={idx}
                  className="template-chip-btn"
                  onClick={() => loadTemplate(tmpl)}
                >
                  {tmpl.title}
                </button>
              ))}
            </div>
          </div>

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
                  <button
                    className={`edit-toggle-btn ${isEditingRefined ? 'active' : ''}`}
                    onClick={() => setIsEditingRefined(!isEditingRefined)}
                  >
                    <Edit3 size={13} />
                    {isEditingRefined ? '완료' : '수정'}
                  </button>
                )}
              </div>

              <div className="compare-card-body">
                {isLoading ? (
                  <div className="ai-loading-box">
                    <Loader2 size={22} className="spinning ai-spinner" />
                    <p>문장을 다듬는 중입니다...</p>
                  </div>
                ) : (refinedText || refinedTitle) ? (
                  <>
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

                    <div className="compare-field-wrap">
                      <label className="ai-sub-label">본문</label>
                      <textarea
                        value={refinedText}
                        onChange={(e) => setRefinedText(e.target.value)}
                        readOnly={!isEditingRefined}
                        className={`ai-textarea refined-textarea ${isEditingRefined ? 'editable' : ''}`}
                        rows={8}
                      />
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
          <button className="btn btn-secondary" onClick={onClose}>
            취소
          </button>
          <button
            className="btn btn-primary"
            onClick={handleApply}
            disabled={(!refinedText && !refinedTitle) || isLoading}
          >
            적용
          </button>
        </div>
      </div>
    </div>
  );
}
