// src/components/questions/QuestionDetail.jsx
// 서양식 포럼(Western-Style Forum) 스레드 포스트 스트림 (#1 OP -> #2, #3 Replies) 상세 컴포넌트
import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { CATEGORY_DEFINITIONS, isQuestionSolved } from '../../services/mockData';
import { formatFileSize, isSafeAttachmentUrl } from '../../utils/fileSecurity';
import AiRefineModal from '../student/AiRefineModal';
import {
  Send,
  Shield,
  Award,
  Users,
  Lock,
  Clock,
  BookOpen,
  MessageSquare,
  CheckCircle2,
  FileText,
  ArrowLeft,
  Edit3,
  X,
  Check,
  Paperclip,
  Download,
  Image as ImageIcon,
  ShieldCheck,
  EyeOff,
  Eye,
  AlertCircle,
  Trash2,
  PenTool
} from 'lucide-react';

const QuestionDetail = ({
  question,
  onAddAnswer,
  onAddFollowUp,
  onUpdateQuestion,
  onToggleHideQuestion,
  onToggleHideAnswer,
  onDeleteQuestion,
  onDeleteAnswer,
  onBack
}) => {
  const { currentUser, isProfessor, isAdmin, allCourses, refreshCurrentUser } = useAuth();
  const [answerText, setAnswerText] = useState('');
  const [followUpText, setFollowUpText] = useState('');
  const [followUpOriginalText, setFollowUpOriginalText] = useState('');
  const [hasFollowUpAiRefined, setHasFollowUpAiRefined] = useState(false);
  const [isFollowUpAiModalOpen, setIsFollowUpAiModalOpen] = useState(false);
  const [showOriginal, setShowOriginal] = useState(false);
  const [expandedFollowUpOriginals, setExpandedFollowUpOriginals] = useState({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  // 질문 수정 상태
  const [isEditing, setIsEditing] = useState(false);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editAnonymous, setEditAnonymous] = useState(false);
  const [isUpdating, setIsUpdating] = useState(false);

  if (!question) {
    return (
      <div className="question-detail-empty">
        <MessageSquare size={48} className="text-muted" />
        <h3>열람할 포럼 토픽(Thread)을 선택해주세요</h3>
        <p>좌측 토픽 보드에서 스레드를 클릭하면 #1 질문 원문과 교수님들의 순차 답변 스트림을 확인할 수 있습니다.</p>
      </div>
    );
  }

  const categoryKey = (question.category || 'lecture').toLowerCase();
  const category = CATEGORY_DEFINITIONS[categoryKey] || CATEGORY_DEFINITIONS.lecture;
  const answers = Array.isArray(question.answers) ? question.answers : [];

  const isPrimaryProf =
    isProfessor &&
    (currentUser?.id === question.profId ||
      currentUser?.id === question.assignedProfessorId ||
      currentUser?.name === question.profName ||
      currentUser?.name === question.assignedProfessorName);

  const profCourses =
    Array.isArray(currentUser?.courses) && currentUser.courses.length > 0
      ? currentUser.courses
      : allCourses || [];

  const isSameCourseOtherProf =
    isProfessor &&
    !isPrimaryProf &&
    profCourses.some(
      (c) => c.name === question.courseName || c.id === question.courseId
    );

  const hasPrimaryProfAnswered =
    question.isAnsweredByAssignedProfessor ||
    answers.some((a) => a.isPrimaryProf || a.isAssignedProfessor);

  let canAnswer = false;
  let answerLockReason = '';

  if (isProfessor) {
    if (question.isDeleted) {
      canAnswer = false;
      answerLockReason = '질문 작성자가 질문을 삭제하여 답변을 등록할 수 없습니다.';
    } else if (question.canAnswer !== undefined) {
      canAnswer = question.canAnswer;
    } else if (isPrimaryProf) {
      canAnswer = true;
    } else if (isSameCourseOtherProf) {
      if (categoryKey !== 'lecture') {
        canAnswer = false;
        answerLockReason = `'${category.name}' 카테고리는 배정된 담당 교수님(${
          question.profName || question.assignedProfessorName
        })만 답변할 수 있습니다.`;
      } else if (hasPrimaryProfAnswered) {
        canAnswer = false;
        answerLockReason = `담당 교수님(${
          question.profName || question.assignedProfessorName
        })께서 이미 답변을 완료하셨으므로 추가 공동 답변이 마감되었습니다.`;
      } else {
        canAnswer = true;
      }
    } else {
      canAnswer = false;
      answerLockReason = '본인이 담당하는 교과목의 토픽에만 답변할 수 있습니다.';
    }
  }

  const isAuthorStudent =
    currentUser?.role === 'student' && currentUser?.id === question.studentId;
  const profAnswers = answers.filter(
    (a) => String(a.authorRole || 'PROFESSOR').toUpperCase() !== 'STUDENT'
  );
  const isSolved = isQuestionSolved(question);
  const canEditQuestion = isAuthorStudent && !question.isDeleted && answers.length === 0;
  const canDeleteQuestion =
    !question.isDeleted &&
    !isProfessor &&
    (question.canDelete ||
      question.isMyQuestion ||
      (currentUser?.id && question.studentId === currentUser.id));
  const canFollowUp =
    !question.isDeleted &&
    (typeof question.canFollowUp === 'boolean'
      ? question.canFollowUp && isAuthorStudent
      : isAuthorStudent && !question.isHidden && profAnswers.length > 0);

  const handleStartEdit = () => {
    setEditTitle(question.title);
    setEditContent(question.content || question.refinedContent || '');
    setEditAnonymous(Boolean(question.isAnonymous));
    setIsEditing(true);
  };

  const handleCancelEdit = () => {
    setIsEditing(false);
  };

  const handleSaveEdit = async (e) => {
    e.preventDefault();
    if (!editTitle.trim() || !editContent.trim() || isUpdating) return;
    setIsUpdating(true);
    try {
      await onUpdateQuestion(question.id, {
        title: editTitle.trim(),
        content: editContent.trim(),
        isAnonymous: category.allowAnonymous ? editAnonymous : false
      });
      setIsEditing(false);
    } finally {
      setIsUpdating(false);
    }
  };

  const authorDisplay = question.isAnonymous
    ? '익명 수강생'
    : question.studentTag || question.studentName || '수강생';

  const realAuthorDisplay =
    isAdmin && question.isAnonymous
      ? question.realStudentTag ||
        question.realStudentName ||
        (question.studentName !== '익명 (수강생)' ? question.studentName : null)
      : null;

  const handleAnswerSubmit = async (e) => {
    e.preventDefault();
    if (!answerText.trim() || !canAnswer || isSubmitting) return;
    setErrorMsg('');
    setIsSubmitting(true);
    try {
      const latestUser = await refreshCurrentUser();
      if (!latestUser || latestUser.role !== 'professor' || latestUser.id !== currentUser?.id) {
        setErrorMsg(
          latestUser?.role === 'student'
            ? '학생 계정으로 변경되어 답변을 작성할 수 없습니다.'
            : '로그인 상태가 변경되었습니다. 계정을 확인한 뒤 다시 시도해 주세요.'
        );
        return;
      }
      await onAddAnswer(question.id, {
        authorId: currentUser.id,
        authorName: currentUser.name,
        authorTitle: isPrimaryProf ? '담당교수' : '동일과목 교수 (공동답변)',
        isPrimaryProf,
        content: answerText.trim()
      });
      setAnswerText('');
    } catch (err) {
      setErrorMsg(err.message || '답변 등록에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFollowUpSubmit = async (e) => {
    e.preventDefault();
    if (!followUpText.trim() || !canFollowUp || !onAddFollowUp || isSubmitting) return;
    setErrorMsg('');
    setIsSubmitting(true);
    try {
      const latestUser = await refreshCurrentUser();
      if (
        !latestUser ||
        latestUser.role !== 'student' ||
        latestUser.id !== currentUser?.id ||
        latestUser.id !== question.studentId
      ) {
        setErrorMsg(
          latestUser?.role === 'professor'
            ? '교수 계정으로 변경되어 추가 질문을 작성할 수 없습니다.'
            : '질문을 작성한 학생 계정만 추가 질문을 남길 수 있습니다.'
        );
        return;
      }
      await onAddFollowUp(question.id, {
        authorId: currentUser.id,
        content: followUpText.trim(),
        originalContent: followUpOriginalText || followUpText.trim(),
        hasAiRefined: hasFollowUpAiRefined
      });
      setFollowUpText('');
      setFollowUpOriginalText('');
      setHasFollowUpAiRefined(false);
    } catch (err) {
      setErrorMsg(err.message || '추가 질문 등록에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const mainContent = question.content || question.refinedContent || '';

  return (
    <div className="forum-thread-view">
      {/* 상단 내비게이션 & 스레드 헤더 */}
      <div className="forum-thread-topbar">
        {onBack && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={onBack}>
            <ArrowLeft size={15} /> 목록으로
          </button>
        )}

        <div className="forum-thread-actions-right">
          {isAdmin && onToggleHideQuestion && (
            <button
              type="button"
              className={`btn btn-sm ${question.isHidden ? 'btn-secondary' : 'btn-outline'}`}
              onClick={() => onToggleHideQuestion(question)}
            >
              {question.isHidden ? (
                <>
                  <Eye size={14} /> 가리기 해제
                </>
              ) : (
                <>
                  <EyeOff size={14} /> 가리기
                </>
              )}
            </button>
          )}

          {(isAdmin || canDeleteQuestion) && onDeleteQuestion && (
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={() => onDeleteQuestion(question)}
            >
              <Trash2 size={14} /> {isAdmin ? '영구 삭제' : '질문 삭제'}
            </button>
          )}

          {canEditQuestion && !isEditing && (
            <button
              type="button"
              className="btn btn-outline btn-sm"
              onClick={handleStartEdit}
            >
              <Edit3 size={14} /> 수정
            </button>
          )}
        </div>
      </div>

      {/* 토픽 타이틀 배너 */}
      <div className="forum-thread-title-box">
        <div className="detail-badges-row">
          <span className={`badge ${category.badgeClass}`}>{category.name}</span>
          <span className="course-pill">
            <BookOpen size={13} /> {question.courseName}
          </span>
          <span className="prof-pill">
            담당: {question.profName || question.assignedProfessorName || '담당교수'}
          </span>
          {question.isDeleted ? (
            <span className="status-pill danger">
              <Trash2 size={13} /> 질문자 삭제됨
            </span>
          ) : isSolved ? (
            <span className="status-pill success">
              <CheckCircle2 size={13} /> Solved ({profAnswers.length} Replies)
            </span>
          ) : (
            <span className="status-pill warning">
              {profAnswers.length > 0 ? 'Open Thread (추가 질문 답변 대기)' : 'Open Thread (미답변)'}
            </span>
          )}
          {question.isHidden && (
            <span className="status-pill danger">
              <EyeOff size={13} /> 관리자 가림 상태
            </span>
          )}
        </div>

        {!isEditing && <h2 className="forum-thread-h2">{question.title}</h2>}
      </div>

      {question.isDeleted && (
        <div className="deleted-question-notice">
          <AlertCircle size={18} />
          <div>
            <strong>작성자에 의해 삭제된 질문입니다.</strong>
            <p>
              질문자가 해당 질문을 삭제하여 질문 원문 및 첨부파일이 비공개 처리되었으며, 새로운 답변이나 추가 질문을 등록할 수 없습니다.
              {question.deletedAt
                ? ` (삭제 일시: ${new Date(question.deletedAt).toLocaleString('ko-KR')})`
                : ''}
            </p>
          </div>
        </div>
      )}

      {/* 서양식 포럼 크로놀로지컬 포스트 스트림 (#1 OP -> #2, #3 Replies) */}
      <div className="forum-post-stream">
        {/* Post #1: Topic Starter (OP) */}
        <article
          className={`forum-post-card op-post ${
            question.isHidden ? 'post-hidden-state' : ''
          }`}
        >
          <aside className="forum-post-sidebar">
            <div className="forum-post-avatar student">
              {question.isAnonymous ? '익' : (authorDisplay || '수').charAt(0)}
            </div>
            <div className="forum-post-author-meta">
              <div className="forum-post-author-name">{authorDisplay}</div>
              <span className="forum-role-tag op">TOPIC STARTER (OP)</span>
              {question.isAnonymous && (
                <span className="anon-badge">
                  <Shield size={11} /> 익명 보호됨
                </span>
              )}
              {realAuthorDisplay && (
                <div className="admin-real-author-badge" style={{ marginTop: 4 }}>
                  실명: {realAuthorDisplay}
                  {question.realStudentIdentifier
                    ? ` (${question.realStudentIdentifier})`
                    : ''}
                </div>
              )}
            </div>
          </aside>

          <div className="forum-post-body-col">
            <div className="forum-post-header-bar">
              <span className="post-timestamp">
                <Clock size={13} />{' '}
                {question.createdAt
                  ? new Date(question.createdAt).toLocaleString('ko-KR')
                  : '-'}
                {question.isEdited && <em className="edited-tag"> (수정됨)</em>}
              </span>
              <span className="post-index-badge">#1 · OP</span>
            </div>

            {isEditing ? (
              <form className="edit-question-form" onSubmit={handleSaveEdit}>
                <div className="form-group">
                  <label className="form-label">토픽 제목 수정</label>
                  <input
                    type="text"
                    className="form-input"
                    value={editTitle}
                    onChange={(e) => setEditTitle(e.target.value)}
                    required
                  />
                </div>
                <div className="form-group">
                  <label className="form-label">질문 본문 수정</label>
                  <textarea
                    className="form-textarea"
                    rows={6}
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    required
                  />
                </div>
                {category.allowAnonymous && (
                  <label className="anon-checkbox-row" style={{ marginBottom: 12 }}>
                    <input
                      type="checkbox"
                      checked={editAnonymous}
                      onChange={(e) => setEditAnonymous(e.target.checked)}
                    />
                    <span>익명으로 질문하기 (수강생으로 표기)</span>
                  </label>
                )}
                <div className="edit-actions-row">
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={handleCancelEdit}
                  >
                    <X size={15} /> 취소
                  </button>
                  <button
                    type="submit"
                    className="btn btn-primary btn-sm"
                    disabled={isUpdating}
                  >
                    <Check size={15} /> {isUpdating ? '저장 중...' : '수정 완료'}
                  </button>
                </div>
              </form>
            ) : (
              <>
                <div className="forum-post-text">
                  {mainContent.split('\n').map((line, idx) => (
                    <p key={idx}>{line || '\u00A0'}</p>
                  ))}
                </div>

                {/* 첨부파일 영역 */}
                {Array.isArray(question.attachments) &&
                  question.attachments.length > 0 && (
                    <div className="question-attachments-section">
                      <div className="attachments-header">
                        <Paperclip size={15} />
                        <span>첨부파일 ({question.attachments.length}개)</span>
                        <span className="security-verified-pill">
                          <ShieldCheck size={12} /> 보안 검증 완료
                        </span>
                      </div>
                      <div className="attachments-grid">
                        {question.attachments.map((att) => (
                          <div key={att.id} className="attachment-card">
                            {att.isImage && isSafeAttachmentUrl(att.previewUrl || att.url) ? (
                              <div className="attachment-image-preview">
                                <img
                                  src={att.previewUrl || att.url}
                                  alt={att.originalName}
                                  loading="lazy"
                                />
                              </div>
                            ) : null}
                            <div className="attachment-card-body">
                              <div className="attachment-icon-wrap">
                                {att.isImage ? (
                                  <ImageIcon size={18} />
                                ) : (
                                  <FileText size={18} />
                                )}
                              </div>
                              <div className="attachment-meta">
                                <span
                                  className="attachment-filename"
                                  title={att.originalName}
                                >
                                  {att.originalName}
                                </span>
                                <div className="attachment-submeta">
                                  <span>{formatFileSize(att.fileSize)}</span>
                                  {att.isCompressed && att.compressionRatio > 0 && (
                                    <span className="compression-pill">
                                      압축 -{att.compressionRatio}%
                                    </span>
                                  )}
                                </div>
                              </div>
                              {isSafeAttachmentUrl(att.downloadUrl || att.url) && (
                                <a
                                  href={att.downloadUrl || att.url}
                                  download={att.originalName}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="btn-icon attachment-download-btn"
                                  title="파일 다운로드"
                                >
                                  <Download size={16} />
                                </a>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                {/* AI 다듬기 전 원문 보기 */}
                {(question.originalContent || question.rawContent) &&
                  (question.originalContent || question.rawContent) !== mainContent && (
                    <div className="original-content-toggle">
                      <button
                        type="button"
                        className="btn-text-xs"
                        onClick={() => setShowOriginal((prev) => !prev)}
                      >
                        <FileText size={13} />
                        {showOriginal ? 'AI 교정 전 초안 접기' : 'AI 교정 전 초안 확인'}
                      </button>
                      {showOriginal && (
                        <div className="original-content-box">
                          <span className="original-label">AI 윤문 전 초안:</span>
                          <p>{question.originalContent || question.rawContent}</p>
                        </div>
                      )}
                    </div>
                  )}
              </>
            )}
          </div>
        </article>

        {/* 스레드 구분선 */}
        <div className="forum-stream-divider">
          <span>답변 및 추가 질문 ({answers.length})</span>
        </div>

        {/* Posts #2, #3...: Professor Replies & Student Follow-ups */}
        {answers.length === 0 ? (
          <div className="no-answer-box">
            <p>등록된 답변이 없습니다.</p>
          </div>
        ) : (
          answers.map((ans, index) => {
            const isStudentFollowUp =
              String(ans.authorRole || '').toUpperCase() === 'STUDENT';
            const postAuthorName =
              ans.authorName ||
              ans.professorName ||
              (isStudentFollowUp ? '수강생' : '교수');
            const isAssigned = !isStudentFollowUp && (ans.isPrimaryProf || ans.isAssignedProfessor);
            return (
              <article
                key={ans.id}
                className={`forum-post-card reply-post ${
                  isStudentFollowUp
                    ? 'student-followup-post'
                    : isAssigned
                      ? 'primary-prof-post'
                      : 'co-prof-post'
                } ${ans.isHidden ? 'post-hidden-state' : ''}`}
              >
                <aside className="forum-post-sidebar">
                  <div
                    className={`forum-post-avatar ${
                      isStudentFollowUp ? 'student' : 'professor'
                    }`}
                  >
                    {isStudentFollowUp && question.isAnonymous && !isAuthorStudent && !isAdmin
                      ? '익'
                      : postAuthorName.charAt(0)}
                  </div>
                  <div className="forum-post-author-meta">
                    <div className="forum-post-author-name">{postAuthorName}</div>
                    <span
                      className={`forum-role-tag ${
                        isStudentFollowUp
                          ? 'op'
                          : isAssigned
                            ? 'solution'
                            : 'coprof'
                      }`}
                    >
                      {isStudentFollowUp ? (
                        <>
                          <MessageSquare size={12} /> 추가 질문
                        </>
                      ) : isAssigned ? (
                        <>
                          <Award size={12} /> 담당교수
                        </>
                      ) : (
                        <>
                          <Users size={12} /> 공동답변 교수
                        </>
                      )}
                    </span>
                  </div>
                </aside>

                <div className="forum-post-body-col">
                  <div className="forum-post-header-bar">
                    <span className="post-timestamp">
                      <Clock size={13} />{' '}
                      {ans.createdAt
                        ? new Date(ans.createdAt).toLocaleString('ko-KR')
                        : '-'}
                      {ans.isHidden && (
                        <span className="status-pill danger" style={{ marginLeft: 8 }}>
                          가려진 답변
                        </span>
                      )}
                    </span>

                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {isAdmin && onToggleHideAnswer && (
                        <button
                          type="button"
                          className="btn-admin-inline-hide"
                          onClick={() => onToggleHideAnswer(ans, question.id)}
                        >
                          {ans.isHidden ? (
                            <>
                              <Eye size={12} /> 가리기 해제
                            </>
                          ) : (
                            <>
                              <EyeOff size={12} /> 가리기
                            </>
                          )}
                        </button>
                      )}
                      {isAdmin && onDeleteAnswer && (
                        <button
                          type="button"
                          className="btn-admin-inline-delete"
                          onClick={() => onDeleteAnswer(ans, question.id)}
                        >
                          <Trash2 size={12} /> 삭제
                        </button>
                      )}
                      <span className="post-index-badge">#{index + 2}</span>
                    </div>
                  </div>

                  <div className="forum-post-text">
                    {(ans.content || '').split('\n').map((line, idx) => (
                      <p key={idx}>{line || '\u00A0'}</p>
                    ))}
                  </div>

                  {(() => {
                    const followUpOrig = (ans.originalContent || ans.rawContent || '').trim();
                    const followUpCurrent = (ans.content || '').trim();
                    const hasFollowUpOriginalDraft =
                      isStudentFollowUp &&
                      !question.isDeleted &&
                      Boolean(followUpOrig) &&
                      followUpOrig !== followUpCurrent;
                    if (!hasFollowUpOriginalDraft) return null;
                    const isFollowUpOrigExpanded = Boolean(expandedFollowUpOriginals[ans.id]);
                    return (
                      <div className="original-content-toggle">
                        <button
                          type="button"
                          className="btn-text-xs"
                          onClick={() =>
                            setExpandedFollowUpOriginals((prev) => ({
                              ...prev,
                              [ans.id]: !prev[ans.id]
                            }))
                          }
                        >
                          <FileText size={13} />
                          {isFollowUpOrigExpanded ? 'AI 교정 전 초안 접기' : 'AI 교정 전 초안 확인'}
                        </button>
                        {isFollowUpOrigExpanded && (
                          <div className="original-content-box">
                            <span className="original-label">AI 윤문 전 초안:</span>
                            {followUpOrig.split('\n').map((line, idx) => (
                              <p key={idx}>{line || '\u00A0'}</p>
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })()}
                </div>
              </article>
            );
          })
        )}
      </div>

      {/* 질문 작성자 학생 전용 추가 질문(꼬리질문) 작성 컴포저 */}
      {!isProfessor && canFollowUp && onAddFollowUp && (
        <div className="answer-editor-section followup-editor-section">
          {errorMsg && (
            <div className="auth-gate-alert error" style={{ marginBottom: 12 }}>
              <AlertCircle size={16} />
              <span>{errorMsg}</span>
            </div>
          )}

          <form onSubmit={handleFollowUpSubmit} className="answer-form">
            <div className="answer-form-header followup-form-header">
              <span className="editor-role-indicator">
                <MessageSquare size={15} className="text-primary" /> 교수님 답변에 이어 추가 질문(Post #{answers.length + 2}) 작성
              </span>
              <button
                type="button"
                className={`btn-ai-assist ${hasFollowUpAiRefined ? 'refined-active' : ''}`}
                onClick={() => setIsFollowUpAiModalOpen(true)}
              >
                <PenTool size={14} />
                <span>{hasFollowUpAiRefined ? '문장 다듬기 다시 실행' : '문장 다듬기'}</span>
              </button>
            </div>
            <textarea
              className={`answer-textarea ${hasFollowUpAiRefined ? 'has-refined-border' : ''}`}
              rows={3}
              placeholder="답변 내용 중 더 궁금한 점이나 후속 질문을 구체적으로 작성해주세요..."
              value={followUpText}
              onChange={(e) => {
                setFollowUpText(e.target.value);
                if (hasFollowUpAiRefined) setHasFollowUpAiRefined(false);
              }}
              required
            />
            {hasFollowUpAiRefined && (
              <div className="refined-status-hint" style={{ marginTop: 6 }}>
                <CheckCircle2 size={14} />
                <span>정제된 문장이 적용되었습니다.</span>
              </div>
            )}
            <div className="answer-form-footer">
              <span className="answer-hint">
                추가 질문 등록 시 담당 교수님께 알림이 전송되며 답변 대기 상태로 전환됩니다.
              </span>
              <button
                type="submit"
                className="btn btn-primary"
                disabled={!followUpText.trim() || isSubmitting}
              >
                <Send size={16} />
                {isSubmitting ? '등록 중...' : '추가 질문 등록'}
              </button>
            </div>
          </form>

          <AiRefineModal
            isOpen={isFollowUpAiModalOpen}
            onClose={() => setIsFollowUpAiModalOpen(false)}
            initialTitle={question.title || ''}
            initialText={followUpText}
            category={categoryKey}
            courseName={question.courseName || ''}
            professorName={question.profName || question.assignedProfessorName || ''}
            isFollowUp={true}
            previousAnswer={
              profAnswers.length > 0 ? profAnswers[profAnswers.length - 1]?.content || '' : ''
            }
            isAnonymous={Boolean(question.isAnonymous)}
            onApplyRefined={(refinedText, origText) => {
              setFollowUpText(refinedText);
              setFollowUpOriginalText((prev) => prev || origText || followUpText);
              setHasFollowUpAiRefined(true);
            }}
          />
        </div>
      )}

      {/* 교수 전용 하단 스레드 답변 작성 컴포저 */}
      {isProfessor && (
        <div className="answer-editor-section">
          {errorMsg && (
            <div className="auth-gate-alert error" style={{ marginBottom: 12 }}>
              <AlertCircle size={16} />
              <span>{errorMsg}</span>
            </div>
          )}

          {canAnswer ? (
            <form onSubmit={handleAnswerSubmit} className="answer-form">
              <div className="answer-form-header">
                <span className="editor-role-indicator">
                  {isPrimaryProf ? (
                    <>
                      <Award size={15} className="text-primary" /> 담당 교수로서 스레드 답변(Post #{answers.length + 2}) 작성
                    </>
                  ) : (
                    <>
                      <Users size={15} className="text-primary" /> 같은 과목 교수로서 공동 답변(Post #{answers.length + 2}) 작성
                    </>
                  )}
                </span>
              </div>
              <textarea
                className="answer-textarea"
                rows={4}
                placeholder="학생의 질문 토픽에 대한 명쾌한 학술 답변을 작성해주세요..."
                value={answerText}
                onChange={(e) => setAnswerText(e.target.value)}
                required
              />
              <div className="answer-form-footer">
                <span className="answer-hint">
                  답변 등록 시 질문 학생에게 즉시 알림이 전송됩니다.
                </span>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={!answerText.trim() || isSubmitting}
                >
                  <Send size={16} />
                  {isSubmitting ? '등록 중...' : '스레드 답변 등록'}
                </button>
              </div>
            </form>
          ) : (
            <div className="answer-locked-banner">
              <Lock size={18} />
              <span>{answerLockReason || '현재 이 토픽에 답변할 권한이 없습니다.'}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default QuestionDetail;
