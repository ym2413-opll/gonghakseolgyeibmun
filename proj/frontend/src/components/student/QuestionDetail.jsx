// src/components/student/QuestionDetail.jsx
import React, { useState, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import { CATEGORY_DEFINITIONS, CATEGORY_LIST } from '../../services/mockData';
import { api } from '../../services/api';
import AnswerForm from '../professor/AnswerForm';
import AiRefineModal from './AiRefineModal';
import {
  ArrowLeft,
  PenTool,
  User,
  EyeOff,
  Clock,
  MessageSquare,
  FileText,
  Edit3,
  Save,
  X,
  Lock,
  CheckCircle2,
  AlertCircle,
  Paperclip,
  Download,
  ExternalLink
} from 'lucide-react';
import { formatFileSize } from '../../utils/fileSecurity';
import { generateSummaryTitle } from '../../utils/textSummary';

function safeFormatDate(val) {
  try {
    if (!val) return '';
    const d = new Date(val);
    return isNaN(d.getTime()) ? '' : d.toLocaleString();
  } catch {
    return '';
  }
}

export default function QuestionDetail({ question, onBack, onUpdateQuestion }) {
  const { currentUser, isProfessor } = useAuth();
  const [showOriginal, setShowOriginal] = useState(false);
  const [activePreviewImage, setActivePreviewImage] = useState(null);

  // 수정(Edit) 모드 상태
  const [isEditing, setIsEditing] = useState(false);
  // editTitle removed - automatically summarized from content
  const [editContent, setEditContent] = useState('');
  const [editCategory, setEditCategory] = useState('lecture');
  const [editIsAnonymous, setEditIsAnonymous] = useState(false);
  const [editHasAiRefined, setEditHasAiRefined] = useState(false);
  const [editOriginalContent, setEditOriginalContent] = useState('');
  const [isAiModalOpen, setIsAiModalOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [editError, setEditError] = useState('');

  useEffect(() => {
    if (question) {
      // title omitted
      setEditContent(question.content || '');
      setEditCategory(question.category || 'lecture');
      setEditIsAnonymous(Boolean(question.isAnonymous));
      setEditHasAiRefined(Boolean(question.hasAiRefined));
      setEditOriginalContent(question.originalContent || question.content || '');
      setIsEditing(false);
      setShowOriginal(false);
    }
  }, [question]);

  if (!question) return null;

  const catDef = CATEGORY_DEFINITIONS[question.category] || CATEGORY_DEFINITIONS.lecture;
  const isMine = currentUser?.id === question.studentId;

  // 익명 표기 (조회 시)
  let authorDisplay = question.isAnonymous
    ? `${question.courseName} 수강생`
    : question.studentTag || question.studentName;

  if (question.isAnonymous && isMine) {
    authorDisplay = `${authorDisplay} (나 - 타인에게 익명 처리됨)`;
  }

  const handleAnswerAdded = (newAnswer) => {
    const updated = {
      ...question,
      answers: [...(question.answers || []), newAnswer]
    };
    if (onUpdateQuestion) onUpdateQuestion(updated, '답변이 등록되었습니다.');
  };

  // AI 교정 전 원문과의 차이 여부
  const hasOriginalDiff = Boolean(
    question.originalContent &&
    question.content &&
    question.originalContent.trim() !== question.content.trim()
  );

  const formattedDate = safeFormatDate(question.createdAt);
  const updatedDate = question.updatedAt ? safeFormatDate(question.updatedAt) : null;

  // 질문 수정 저장 처리
  const handleSaveEdit = async (e) => {
    e.preventDefault();
// title check removed
    if (!editContent.trim()) {
      setEditError('질문 내용을 입력해 주세요.');
      return;
    }

    setIsSaving(true);
    setEditError('');

    try {
      const payload = {
        title: generateSummaryTitle(editContent),
        content: editContent.trim(),
        category: editCategory,
        isAnonymous: editCategory === 'grade' ? false : editIsAnonymous,
        hasAiRefined: editHasAiRefined,
        originalContent: editOriginalContent || editContent.trim()
      };

      const updated = await api.updateQuestion(question.id, payload);
      if (onUpdateQuestion) onUpdateQuestion(updated, '질문이 성공적으로 수정되었습니다.');
      setIsEditing(false);
    } catch (err) {
      console.error('Failed to update question', err);
      setEditError('질문 수정 중 오류가 발생했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  const handleApplyAiInEdit = (refinedContent, originContent) => {
    if (refinedContent) setEditContent(refinedContent);
    if (originContent) setEditOriginalContent(originContent);
    setEditHasAiRefined(true);
  };

  const handleEditCategoryChange = (catKey) => {
    setEditCategory(catKey);
    if (catKey === 'grade') {
      setEditIsAnonymous(false);
    }
  };

  return (
    <div className="question-detail-container">
      {/* 상단 네비게이션 및 액션 버튼 바 */}
      <div className="detail-top-nav">
        <button className="btn-back" onClick={onBack}>
          <ArrowLeft size={16} /> 목록으로 돌아가기
        </button>

        {/* 작성자 본인 전용: 질문 수정 버튼 */}
        {isMine && !isEditing && (
          <button
            className="btn-edit-question"
            onClick={() => setIsEditing(true)}
            title="질문 내용을 수정합니다"
          >
            <Edit3 size={15} /> 질문 수정하기
          </button>
        )}
      </div>

      <div className="detail-main-card">
        {/* ========================================================
            수정 모드 (Edit Mode)
            ======================================================== */}
        {isEditing ? (
          <div className="question-edit-panel">
            <div className="edit-panel-header">
              <div className="edit-title-group">
                <Edit3 size={18} className="edit-icon" />
                <h3>질문 수정</h3>
              </div>
              <button
                type="button"
                className="btn-cancel-edit-icon"
                onClick={() => setIsEditing(false)}
                title="수정 취소"
              >
                <X size={18} />
              </button>
            </div>

            {editError && (
              <div className="form-error-banner">
                <AlertCircle size={15} />
                <span>{editError}</span>
              </div>
            )}

            <form onSubmit={handleSaveEdit} className="edit-form">
              {/* 카테고리 선택 */}
              <div className="form-group">
                <label className="form-label">질문 카테고리</label>
                <div className="edit-category-chips">
                  {CATEGORY_LIST.map((cat) => (
                    <button
                      key={cat.id}
                      type="button"
                      className={`cat-chip-btn ${editCategory === cat.id ? 'active' : ''}`}
                      onClick={() => handleEditCategoryChange(cat.id)}
                    >
                      <span className={`cat-pill ${cat.badgeClass}`}>{cat.name}</span>
                      <span className="cat-chip-name">{cat.description}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* 본문 수정 & 문장 다듬기 버튼 */}
              <div className="form-group">
                <div className="content-label-row">
                  <label className="form-label" htmlFor="edit-content">
                    질문 본문 <span className="req">*</span>
                  </label>
                  <button
                    type="button"
                    className="btn-ai-assist"
                    onClick={() => setIsAiModalOpen(true)}
                  >
                    <PenTool size={14} />
                    <span>문장 다듬기</span>
                  </button>
                </div>

                <textarea
                  id="edit-content"
                  rows={8}
                  className="textarea-content"
                  value={editContent}
                  onChange={(e) => {
                    setEditContent(e.target.value);
                    if (editHasAiRefined) setEditHasAiRefined(false);
                  }}
                />

                {editHasAiRefined && (
                  <div className="refined-status-hint">
                    <CheckCircle2 size={14} />
                    <span>정제된 문장이 반영되었습니다.</span>
                  </div>
                )}
              </div>

              {/* 익명 여부 수정 */}
              <div className="form-group anonymous-group">
                <label className={`checkbox-label ${editCategory === 'grade' ? 'disabled-label' : ''}`}>
                  <input
                    type="checkbox"
                    checked={editIsAnonymous}
                    disabled={editCategory === 'grade'}
                    onChange={(e) => setEditIsAnonymous(e.target.checked)}
                  />
                  <span className="checkbox-text">
                    익명으로 표시하기
                  </span>
                </label>
                {editCategory === 'grade' && (
                  <span className="anon-disabled-reason">
                    성적 카테고리는 익명으로 설정할 수 없습니다.
                  </span>
                )}
              </div>

              {/* 수정 액션 버튼 */}
              <div className="edit-actions-row">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setIsEditing(false)}
                >
                  취소
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={isSaving}
                >
                  <span>{isSaving ? '저장 중...' : '저장'}</span>
                </button>
              </div>
            </form>

            <AiRefineModal
              isOpen={isAiModalOpen}
              onClose={() => setIsAiModalOpen(false)}
              initialText={editContent}
              category={editCategory}
              onApplyRefined={handleApplyAiInEdit}
            />
          </div>
        ) : (
          /* ========================================================
             일반 조회 모드 (Read View)
             ======================================================== */
          <>
            <div className="detail-header">
              <div className="detail-badges">
                <span className={`cat-pill ${catDef.badgeClass}`}>{catDef.name}</span>
                <span className="course-tag">{question.courseName}</span>
                {question.isEdited && (
                  <span className="edited-badge" title={updatedDate ? `${updatedDate}에 수정됨` : '수정됨'}>
                    <Edit3 size={11} /> 수정됨
                  </span>
                )}
              </div>

              <h2 className="detail-title">{question.title || generateSummaryTitle(question.content)}</h2>

              <div className="detail-meta-row">
                <div className="meta-author-info">
                  {question.isAnonymous ? (
                    <span className="meta-author anon">
                      <EyeOff size={14} /> {authorDisplay}
                    </span>
                  ) : (
                    <span className="meta-author real">
                      <User size={14} /> {authorDisplay}
                    </span>
                  )}
                  <span className="meta-divider">|</span>
                  <span className="meta-prof">담당 교수: {question.profName}</span>
                </div>

                <div className="meta-date">
                  <Clock size={13} />
                  <span>작성: {formattedDate}</span>
                  {question.isEdited && updatedDate && (
                    <span className="meta-updated-time"> (최근 수정: {updatedDate})</span>
                  )}
                </div>
              </div>
            </div>

            {/* 본문 영역 */}
            <div className="detail-content-body">
              <p className="detail-text">{question.content}</p>

              {/* ========================================================
                  [요구사항 준수]
                  AI 교정 전 질문 원문은 작성자(isMine)만 확인 가능!
                  (교수 및 타 학생 화면에는 이 원문 박스가 아예 노출되지 않음)
                  ======================================================== */}
              {isMine && hasOriginalDiff && (
                <div className="original-diff-box author-only-box">
                  <button
                    type="button"
                    className="btn-toggle-original"
                    onClick={() => setShowOriginal(!showOriginal)}
                  >
                    <FileText size={14} />
                    {showOriginal ? '원문 접기' : '작성 원문 보기 (본인만 열람)'}
                  </button>

                  {showOriginal && (
                    <div className="original-diff-content">
                      <p className="diff-text">{question.originalContent}</p>
                    </div>
                  )}
                </div>
              )}

              {/* 첨부 파일 섹션 */}
              {question.attachments && question.attachments.length > 0 && (
                <div className="detail-attachments-box">
                  <div className="attachments-title-row">
                    <div className="attachments-title">
                      <Paperclip size={16} />
                      <span>첨부 파일 ({question.attachments.length})</span>
                    </div>
                  </div>

                  <div className="detail-attachments-grid">
                    {question.attachments.map((att, idx) => {
                      const downloadHref = att.downloadUrl || att.url || `/api/attachments/${att.id}/download`;
                      const previewSrc = att.previewUrl || att.url || `/api/attachments/${att.id}`;

                      return (
                        <div key={att.id || idx} className="detail-attachment-card">
                          {att.isImage ? (
                            <div
                              className="detail-att-thumb-wrap"
                              onClick={() => setActivePreviewImage({ url: previewSrc, name: att.originalName })}
                              title="클릭하여 원본 크기로 확대 보기"
                            >
                              <img src={previewSrc} alt={att.originalName} className="detail-att-thumb" />
                              <div className="thumb-zoom-overlay">
                                <ExternalLink size={14} />
                              </div>
                            </div>
                          ) : (
                            <div className="detail-att-icon-box">
                              <FileText size={24} className="text-indigo-600" />
                            </div>
                          )}

                          <div className="detail-att-meta">
                            <span className="att-file-name" title={att.originalName}>
                              {att.originalName}
                            </span>
                            <div className="att-size-info">
                              {att.isCompressed ? (
                                <span className="att-compressed-info">
                                  {formatFileSize(att.originalSize || att.fileSize)} → {formatFileSize(att.fileSize)} ({att.compressionRatio}% 압축)
                                </span>
                              ) : (
                                <span className="att-normal-size">{formatFileSize(att.fileSize)}</span>
                              )}
                            </div>
                          </div>

                          <a
                            href={downloadHref}
                            download={att.originalName}
                            target="_blank"
                            rel="noreferrer"
                            className="btn-download-att"
                            title="안전 다운로드"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <Download size={14} />
                            <span>다운로드</span>
                          </a>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          </>
        )}

        {/* 답변 목록 섹션 */}
        <div className="detail-answers-section">
          <div className="answers-section-header">
            <div className="answers-header-title">
              <MessageSquare size={18} />
              <span>교수님 답변 ({question.answers?.length || 0})</span>
            </div>
          </div>

          <div className="answers-list">
            {!question.answers || question.answers.length === 0 ? (
              <div className="no-answers-box">
                <p>등록된 답변이 없습니다.</p>
              </div>
            ) : (
              question.answers.map((ans) => (
                <div key={ans.id} className="answer-item-card">
                  <div className="answer-card-header">
                    <div className="ans-author-meta">
                      <strong>{ans.authorName}</strong>
                      <span className="ans-role-tag prof-tag">
                        {ans.authorTitle || '담당교수'}
                      </span>
                    </div>
                    <span className="ans-timestamp">
                      {safeFormatDate(ans.createdAt)}
                    </span>
                  </div>
                  <div className="answer-card-content">{ans.content}</div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* 교수용 답변 작성창 (로그인 사용자가 교수일 경우 노출) */}
        {isProfessor && (
          <div className="detail-prof-action-section">
            <h4 className="prof-action-title">교수 답변 작성</h4>
            <AnswerForm question={question} onAnswerAdded={handleAnswerAdded} />
          </div>
        )}

        {/* 이미지 고화질 확대 뷰어 모달 */}
        {activePreviewImage && (
          <div className="image-viewer-modal-backdrop" onClick={() => setActivePreviewImage(null)}>
            <div className="image-viewer-modal-box" onClick={(e) => e.stopPropagation()}>
              <div className="image-viewer-header">
                <span className="image-viewer-title">{activePreviewImage.name}</span>
                <button
                  type="button"
                  className="btn-close-viewer"
                  onClick={() => setActivePreviewImage(null)}
                >
                  <X size={18} />
                </button>
              </div>
              <div className="image-viewer-body">
                <img src={activePreviewImage.url} alt={activePreviewImage.name} />
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
