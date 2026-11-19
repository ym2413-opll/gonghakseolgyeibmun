// src/components/common/AiFormulaModal.jsx
// 자연어 설명을 KaTeX 호환 LaTeX 수식으로 변환해 본문 커서 위치에 삽입하는 AI 수식 입력 도우미 모달
import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Sigma, Sparkles, X, Loader2, Check } from 'lucide-react';
import { api } from '../../services/api';
import MarkdownContent from './MarkdownContent';

const FORMULA_EXAMPLES = [
  { label: '근의 공식', query: '이차방정식 근의 공식' },
  { label: '코사인 테일러 급수', query: '코사인 함수의 테일러 급수' },
  { label: '정적분', query: 'a부터 b까지 f(x)의 정적분' },
  { label: '2×2 행렬', query: '2x2 행렬' }
];

const BLOCKED_LATEX_COMMANDS = new Set([
  'href', 'url', 'includegraphics', 'htmldata', 'htmlstyle', 'htmlclass', 'htmlid',
  'def', 'gdef', 'edef', 'xdef', 'let', 'newcommand', 'renewcommand', 'providecommand',
  'global', 'futurelet', 'write', 'input', 'include', 'openin', 'openout'
]);
const MACRO_DEF_COMMANDS = new Set([
  'def', 'gdef', 'edef', 'xdef', 'let', 'newcommand', 'renewcommand', 'providecommand', 'global', 'futurelet'
]);

function stripUnsafeLatexCommandsClient(raw) {
  let current = String(raw || '');
  if (!current.includes('\\')) return current;

  const isLetter = (ch) => (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
  const isWs = (ch) => ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r';

  for (let pass = 0; pass < 5; pass++) {
    let out = '';
    const len = current.length;
    let i = 0;

    const skipBalanced = (idx, openCh, closeCh) => {
      let k = idx;
      while (k < len && isWs(current[k])) k++;
      if (k >= len || current[k] !== openCh) return idx;
      let depth = 0;
      while (k < len) {
        const c = current[k];
        if (c === '\\' && k + 1 < len) {
          k += 2;
          continue;
        }
        if (c === openCh) depth++;
        else if (c === closeCh) {
          depth--;
          if (depth === 0) return k + 1;
        }
        k++;
      }
      return len;
    };

    const skipUnbracedCtrl = (idx) => {
      let k = idx;
      while (k < len && isWs(current[k])) k++;
      if (k < len && current[k] === '\\') {
        k++;
        if (k < len && isLetter(current[k])) {
          while (k < len && isLetter(current[k])) k++;
        } else if (k < len) {
          k++;
        }
        return k;
      }
      return idx;
    };

    while (i < len) {
      if (current[i] === '\\' && i + 1 < len && isLetter(current[i + 1])) {
        let j = i + 1;
        while (j < len && isLetter(current[j])) j++;
        const cmd = current.slice(i + 1, j).toLowerCase();
        if (BLOCKED_LATEX_COMMANDS.has(cmd)) {
          let nextIdx = j;
          if (nextIdx < len && current[nextIdx] === '*') nextIdx++;
          if (MACRO_DEF_COMMANDS.has(cmd)) {
            const afterCtrl = skipUnbracedCtrl(nextIdx);
            if (afterCtrl > nextIdx) {
              nextIdx = afterCtrl;
              let kEq = nextIdx;
              while (kEq < len && isWs(current[kEq])) kEq++;
              if (kEq < len && current[kEq] === '=') {
                nextIdx = skipUnbracedCtrl(kEq + 1);
              }
            }
          }
          for (let g = 0; g < 4; g++) {
            const afterOpt = skipBalanced(nextIdx, '[', ']');
            if (afterOpt > nextIdx) nextIdx = afterOpt;
            else {
              const afterBr = skipBalanced(nextIdx, '{', '}');
              if (afterBr > nextIdx) nextIdx = afterBr;
            }
          }
          i = nextIdx;
          continue;
        }
      }
      out += current[i];
      i++;
    }

    if (out === current) return out;
    current = out;
  }
  return current;
}

function sanitizeEditableLatex(raw, displayMode) {
  let cleaned = stripUnsafeLatexCommandsClient(String(raw || '')).trim();
  if (!cleaned) return '';
  cleaned = cleaned.replace(/(\\*)\$/g, (_, bs) => {
    const kept = bs.length % 2 === 1 ? bs.slice(0, -1) : bs;
    return kept ? `${kept} \\text{\\textdollar}` : '\\text{\\textdollar}';
  });
  if (displayMode !== 'block') {
    cleaned = cleaned.replace(/[\r\n\t]+/g, ' ');
  }
  return cleaned.slice(0, 2000);
}

function AiFormulaModalInner({ onClose, onInsertFormula }) {
  const [instruction, setInstruction] = useState('');
  const [displayMode, setDisplayMode] = useState('inline');
  const [latex, setLatex] = useState('');
  const [hasResult, setHasResult] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [usedAi, setUsedAi] = useState(null);

  const instructionRef = useRef(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const reqIdRef = useRef(0);
  const isMountedRef = useRef(true);

  useEffect(() => {
    isMountedRef.current = true;
    instructionRef.current?.focus();
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        onCloseRef.current?.();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      isMountedRef.current = false;
      reqIdRef.current += 1;
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  const handleGenerate = async (customQuery, customMode) => {
    if (isLoading) return;
    const query = (customQuery !== undefined ? customQuery : instruction).trim();
    const mode = customMode || displayMode;
    if (!query) {
      setErrorMsg('원하는 수식을 문장으로 입력해 주세요.');
      return;
    }

    const reqId = ++reqIdRef.current;
    setIsLoading(true);
    setErrorMsg('');
    try {
      const result = await api.generateFormulaAI(query, mode);
      if (!isMountedRef.current || reqId !== reqIdRef.current) return;
      setLatex(result.latex || '');
      setHasResult(true);
      setUsedAi(Boolean(result.usedAi));
    } catch (err) {
      if (!isMountedRef.current || reqId !== reqIdRef.current) return;
      setErrorMsg(err.message || '수식을 생성하지 못했습니다.');
    } finally {
      if (isMountedRef.current && reqId === reqIdRef.current) {
        setIsLoading(false);
      }
    }
  };

  const safeLatex = sanitizeEditableLatex(latex, displayMode);
  const wrappedFormula = safeLatex
    ? displayMode === 'block'
      ? `$$\n${safeLatex}\n$$`
      : `$${safeLatex}$`
    : '';

  const handleInsert = () => {
    if (!wrappedFormula || isLoading) return;
    if (typeof onInsertFormula === 'function') {
      onInsertFormula(wrappedFormula);
    }
    onCloseRef.current?.();
  };

  if (typeof document === 'undefined') return null;

  return createPortal(
    <div
      className="modal-overlay ai-formula-overlay"
      onClick={(e) => {
        e.stopPropagation();
        onCloseRef.current?.();
      }}
    >
      <div
        className="modal-content ai-formula-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ai-formula-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header ai-modal-header">
          <div className="modal-title" id="ai-formula-modal-title">
            <Sigma size={18} />
            <span>AI 수식 입력 도우미</span>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            onClick={() => onCloseRef.current?.()}
            aria-label="닫기"
          >
            <X size={18} />
          </button>
        </div>

        <div className="modal-body ai-formula-body">
          <div className="formula-quick-row">
            <span className="template-label">예시:</span>
            <div className="template-chips">
              {FORMULA_EXAMPLES.map((ex) => (
                <button
                  key={ex.label}
                  type="button"
                  className="template-chip-btn"
                  disabled={isLoading}
                  onClick={() => {
                    setInstruction(ex.query);
                    handleGenerate(ex.query, displayMode);
                  }}
                >
                  {ex.label}
                </button>
              ))}
            </div>
          </div>

          <div className="formula-mode-row">
            <span className="ai-sub-label" style={{ marginBottom: 0 }}>표시 형식</span>
            <div className="formula-mode-tabs" role="radiogroup" aria-label="수식 표시 형식">
              <button
                type="button"
                className={`formula-mode-btn ${displayMode === 'inline' ? 'active' : ''}`}
                onClick={() => setDisplayMode('inline')}
              >
                인라인 ($...$)
              </button>
              <button
                type="button"
                className={`formula-mode-btn ${displayMode === 'block' ? 'active' : ''}`}
                onClick={() => setDisplayMode('block')}
              >
                블록 ($$...$$)
              </button>
            </div>
          </div>

          <div className="formula-input-group">
            <label className="ai-sub-label" htmlFor="ai-formula-instruction">
              수식 설명
            </label>
            <div className="formula-instruction-row">
              <textarea
                ref={instructionRef}
                id="ai-formula-instruction"
                className="form-textarea formula-instruction-input"
                rows={2}
                maxLength={500}
                placeholder="원하는 수식을 문장으로 입력하세요."
                value={instruction}
                onChange={(e) => setInstruction(e.target.value)}
                onKeyDown={(e) => {
                  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                    e.preventDefault();
                    handleGenerate();
                  }
                }}
              />
              <button
                type="button"
                className="btn btn-primary formula-generate-btn"
                disabled={isLoading || !instruction.trim()}
                onClick={() => handleGenerate()}
              >
                {isLoading ? (
                  <>
                    <Loader2 size={14} className="spinning" />
                    <span>생성 중...</span>
                  </>
                ) : (
                  <>
                    <Sparkles size={14} />
                    <span>수식 변환</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {errorMsg && (
            <div className="auth-gate-alert error" style={{ marginTop: 4 }}>
              <span>{errorMsg}</span>
            </div>
          )}

          {hasResult && (
            <div className="formula-result-section">
              <div className="formula-result-header">
                <span className="ai-sub-label" style={{ marginBottom: 0 }}>수식 미리보기</span>
                {usedAi !== null && (
                  <span className="formula-engine-badge">
                    {usedAi ? 'AI 변환' : '기본 변환'}
                  </span>
                )}
              </div>
              <div className="formula-preview-box">
                {wrappedFormula ? (
                  <MarkdownContent content={wrappedFormula} />
                ) : (
                  <span className="md-preview-empty">LaTeX 수식을 입력해 주세요.</span>
                )}
              </div>

              <label className="ai-sub-label" htmlFor="ai-formula-latex" style={{ marginTop: 10 }}>
                LaTeX 결과 (직접 수정 가능)
              </label>
              <textarea
                id="ai-formula-latex"
                className="form-textarea formula-latex-input"
                rows={2}
                maxLength={2000}
                value={latex}
                onChange={(e) => setLatex(e.target.value)}
              />
            </div>
          )}
        </div>

        <div className="modal-footer ai-modal-footer">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => onCloseRef.current?.()}
          >
            취소
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!wrappedFormula || isLoading}
            onClick={handleInsert}
          >
            <Check size={15} />
            <span>본문에 삽입</span>
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

export default function AiFormulaModal({ isOpen, onClose, onInsertFormula }) {
  if (!isOpen) return null;
  return <AiFormulaModalInner onClose={onClose} onInsertFormula={onInsertFormula} />;
}
