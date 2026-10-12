// src/components/student/SimilarQuestionsModal.jsx
import React, { useState } from 'react';
import { Search, CheckCircle, ChevronDown, ChevronUp, AlertCircle, BookOpen, X } from 'lucide-react';
import { CATEGORY_DEFINITIONS } from '../../services/mockData';

export default function SimilarQuestionsModal({
  isOpen,
  onClose,
  similarList,
  queryText,
  onResolveWithSimilar
}) {
  const [expandedId, setExpandedId] = useState(similarList?.[0]?.id || null);

  if (!isOpen) return null;

  const toggleExpand = (id) => {
    setExpandedId(expandedId === id ? null : id);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content similar-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Search size={18} />
            <span>유사 질답 검색 결과 (Word2Vec 코사인 유사도 분석)</span>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <div className="similar-query-banner">
            <span className="query-label">입력된 질문 분석 키워드:</span>
            <p className="query-snippet">"{queryText.slice(0, 80)}{queryText.length > 80 ? '...' : ''}"</p>
          </div>

          <p className="similar-intro">
            기존에 동일하거나 유사한 내용으로 교수님께 질문하여 답변이 완료된 내용입니다. 먼저 확인하시면 질문을 올리지 않고도 즉시 궁금증을 해결하실 수 있습니다.
          </p>

          <div className="similar-cards-list">
            {similarList.length === 0 ? (
              <div className="similar-empty">
                <AlertCircle size={28} />
                <p>현재 입력하신 질문과 일치하는 유사 질답이 없습니다. 안심하고 새 질문을 등록하세요!</p>
              </div>
            ) : (
              similarList.map((item) => {
                const isExpanded = expandedId === item.id;
                const catDef = CATEGORY_DEFINITIONS[item.category?.toLowerCase()] || CATEGORY_DEFINITIONS[item.category] || {};
                const hasAnswer = item.answers && item.answers.length > 0;

                return (
                  <div key={item.id} className="similar-accordion-card">
                    <div className="accordion-summary" onClick={() => toggleExpand(item.id)}>
                      <div className="summary-left">
                        <span className={`sim-score-badge ${item.similarityScore >= 60 ? 'high' : 'medium'}`}>
                          유사도 {item.similarityScore}%
                        </span>
                        <span className={`cat-pill ${catDef.badgeClass || ''}`}>{catDef.name || item.category}</span>
                        <span className="sim-title">{item.title}</span>
                      </div>
                      <div className="summary-right">
                        <span className={`sim-ans-status ${hasAnswer ? 'answered' : 'waiting'}`}>
                          {hasAnswer ? `답변 ${item.answers.length}개` : '답변 대기'}
                        </span>
                        {isExpanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </div>
                    </div>

                    {isExpanded && (
                      <div className="accordion-details">
                        <div className="sim-detail-question">
                          <div className="sim-detail-label">
                            <BookOpen size={14} /> 이전 질문 내용:
                          </div>
                          <p className="sim-question-text">{item.content}</p>
                        </div>

                        {hasAnswer ? (
                          <div className="sim-detail-answers">
                            {item.answers.map((ans) => (
                              <div key={ans.id} className="sim-answer-bubble">
                                <div className="sim-ans-header">
                                  <strong>{ans.authorName}</strong>
                                  <span className="ans-tag">{ans.authorTitle || '담당교수'}</span>
                                  <span className="ans-date">
                                    {new Date(ans.createdAt).toLocaleDateString()}
                                  </span>
                                </div>
                                <div className="sim-ans-body">{ans.content}</div>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <div className="sim-no-ans-notice">
                            아직 교수님의 답변이 작성되지 않은 질문입니다.
                          </div>
                        )}

                        {hasAnswer && (
                          <div className="sim-resolve-bar">
                            <span>이 답변으로 궁금한 점이 해결되셨나요?</span>
                            <button
                              className="btn btn-resolve"
                              onClick={() => {
                                onResolveWithSimilar(item);
                                onClose();
                              }}
                            >
                              <CheckCircle size={14} />
                              궁금증 해결됨 (질문 작성 취소)
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn btn-secondary" onClick={onClose}>
            닫고 새 질문 계속 작성하기
          </button>
        </div>
      </div>
    </div>
  );
}
