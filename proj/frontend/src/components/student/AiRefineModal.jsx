// src/components/student/AiRefineModal.jsx
import React, { useState, useEffect } from 'react';
import { api } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { AI_DEMO_TEMPLATES } from '../../services/mockData';
import { Sparkles, Check, ArrowRight, RotateCw, AlertTriangle, Edit3, CheckCircle2, X } from 'lucide-react';

export default function AiRefineModal({
  isOpen,
  onClose,
  initialText,
  category,
  onApplyRefined
}) {
  const { currentUser } = useAuth();
  const [originalText, setOriginalText] = useState(initialText || '');
  const [refinedText, setRefinedText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [aiResult, setAiResult] = useState(null);
  const [isEditingRefined, setIsEditingRefined] = useState(false);

  const handleTriggerAI = React.useCallback(async (textToRefine) => {
    const text = textToRefine ?? originalText;
    if (!text || !text.trim()) return;

    setIsLoading(true);
    try {
      const studentInfo = {
        name: currentUser.name,
        studentId: currentUser.studentId,
        department: currentUser.department
      };
      const result = await api.refineQuestionAI(text, category, studentInfo);
      setAiResult(result);
      setRefinedText(result.refined);
    } catch (err) {
      console.error('AI refinement error', err);
    } finally {
      setIsLoading(false);
    }
  }, [originalText, currentUser, category]);

  useEffect(() => {
    if (isOpen) {
      setOriginalText(initialText || '');
      setRefinedText('');
      setAiResult(null);
      setIsEditingRefined(false);
      if (initialText && initialText.trim().length > 3) {
        handleTriggerAI(initialText);
      }
    }
  }, [isOpen, initialText, handleTriggerAI]);

  if (!isOpen) return null;

  const handleApply = () => {
    onApplyRefined(refinedText, originalText);
    onClose();
  };

  const loadTemplate = (template) => {
    setOriginalText(template.rawText);
    handleTriggerAI(template.rawText);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content ai-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header ai-modal-header">
          <div className="modal-title">
            <div className="ai-icon-pulse">
              <Sparkles size={18} />
            </div>
            <span>AI 질문 도우미 (말투 교정 및 공손한 윤문)</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          {/* 전시용 퀵 템플릿 (발표 및 시연 시 매우 유용) */}
          <div className="ai-quick-templates">
            <span className="template-label">💡 시연용 예시 문구 입력:</span>
            <div className="template-chips">
              {AI_DEMO_TEMPLATES.map((tmpl, idx) => (
                <button
                  key={idx}
                  className="template-chip-btn"
                  onClick={() => loadTemplate(tmpl)}
                  title={tmpl.rawText}
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
                <span className="compare-tag origin-tag">학생 작성 원문</span>
                <button
                  className="re-run-btn"
                  onClick={() => handleTriggerAI(originalText)}
                  disabled={isLoading || !originalText.trim()}
                >
                  <RotateCw size={13} className={isLoading ? 'spinning' : ''} />
                  다시 교정
                </button>
              </div>
              <textarea
                value={originalText}
                onChange={(e) => setOriginalText(e.target.value)}
                placeholder="교수님께 질문하고 싶은 내용을 편하게 입력하세요..."
                className="ai-textarea origin-textarea"
                rows={9}
              />
            </div>

            {/* 중간 화살표 */}
            <div className="compare-arrow">
              <div className="arrow-circle">
                <ArrowRight size={18} />
              </div>
            </div>

            {/* 우측: AI 교정 제안본 */}
            <div className="compare-card refined-card">
              <div className="compare-header">
                <div className="refined-tag-group">
                  <span className="compare-tag ai-tag">
                    <Sparkles size={13} /> AI 정중한 교정본
                  </span>
                  {aiResult && (
                    <span className="polite-score-badge">
                      예의도 점수 {aiResult.politenessScore}%
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

              {isLoading ? (
                <div className="ai-loading-box">
                  <Sparkles size={32} className="spinning ai-spinner" />
                  <p>AI가 질문 의도를 분석하여 예의 바르고 명확한 학술 서식으로 윤문하고 있습니다...</p>
                </div>
              ) : refinedText ? (
                <textarea
                  value={refinedText}
                  onChange={(e) => setRefinedText(e.target.value)}
                  readOnly={!isEditingRefined}
                  className={`ai-textarea refined-textarea ${isEditingRefined ? 'editable' : ''}`}
                  rows={9}
                />
              ) : (
                <div className="ai-empty-prompt">
                  <Sparkles size={28} />
                  <p>원문을 입력한 후 [교정하기]를 누르면 정중한 표현으로 변환됩니다.</p>
                </div>
              )}
            </div>
          </div>

          {/* AI 교정 포인트 안내 */}
          {aiResult?.changes && (
            <div className="ai-improvements-box">
              <div className="improv-title">
                <CheckCircle2 size={15} />
                <span>AI가 적용한 주요 개선점:</span>
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
              <strong>사용자 확인 필수:</strong> AI가 교정한 내용이 질문자의 의도와 맞는지 직접 확인한 후 적용 버튼을 눌러주세요.
            </span>
          </div>
        </div>

        <div className="modal-footer ai-modal-footer">
          <button className="btn btn-secondary" onClick={onClose}>
            원문 유지하고 닫기
          </button>
          <button
            className="btn btn-ai-apply"
            onClick={handleApply}
            disabled={!refinedText || isLoading}
          >
            <Check size={16} />
            교정본 본문에 적용하기
          </button>
        </div>
      </div>
    </div>
  );
}
