// src/components/questions/QuestionDetail.jsx
// 서양식 포럼(Western-Style Forum) 스레드 포스트 스트림 (#1 OP -> #2, #3 Replies) 상세 컴포넌트
import React, { useState, useRef } from 'react';
import { useAuth } from '../../context/AuthContext';
import { CATEGORY_DEFINITIONS, isQuestionSolved } from '../../services/mockData';
import { formatFileSize, isSafeAttachmentUrl } from '../../utils/fileSecurity';
import { sanitizeAnonymousStudentText } from '../../utils/anonymize';
import AttachmentUploader, { insertSnippetAtCursor } from '../common/AttachmentUploader';
import AiRefineModal from '../student/AiRefineModal';
import HelpBubble from '../common/HelpBubble';
import {
  Send,
  Shield,
  Award,
  Users,
  Lock,
  Unlock,
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

function getImageAttachments(attachments) {
  if (!Array.isArray(attachments)) return [];
  return attachments.filter(
    (att) =>
      att &&
      (att.isImage ||
        (att.mimeType && att.mimeType.startsWith('image/')) ||
        /\.(png|jpe?g|gif|webp)$/i.test(att.originalName || att.fileName || att.name || ''))
  );
}

function renderInlineTokens(line, currentSeq, imagesBySeq, validSeqSet, onJumpToSeq, lineKey) {
  const tokenRegex = /(\[#\d+\s*[-:]?\s*사진\s*\d+\]|\[사진\s*#\d+\s*[-:\s]\s*\d+\]|\[사진\s*\d+\]|#\d+(?!\d))/g;
  const parts = [];
  const inlineFigures = [];
  let lastIdx = 0;
  let match;
  let partIdx = 0;

  while ((match = tokenRegex.exec(line)) !== null) {
    if (match.index > lastIdx) {
      parts.push(
        <React.Fragment key={`${lineKey}-t-${partIdx++}`}>
          {line.slice(lastIdx, match.index)}
        </React.Fragment>
      );
    }

    const token = match[0];
    const anchoredPrefixMatch = token.match(/^\[#(\d+)\s*[-:]?\s*사진\s*(\d+)\]$/);
    const anchoredPhotoFirstMatch = token.match(/^\[사진\s*#(\d+)\s*[-:\s]\s*(\d+)\]$/);
    const localPhotoMatch = token.match(/^\[사진\s*(\d+)\]$/);

    if (anchoredPrefixMatch || anchoredPhotoFirstMatch || localPhotoMatch) {
      const isExplicitAnchor = Boolean(anchoredPrefixMatch || anchoredPhotoFirstMatch);
      const targetSeq = isExplicitAnchor
        ? Number((anchoredPrefixMatch || anchoredPhotoFirstMatch)[1])
        : currentSeq;
      const k = Number((anchoredPrefixMatch || anchoredPhotoFirstMatch || localPhotoMatch)[2] || localPhotoMatch[1]);
      const targetImages = imagesBySeq.get(targetSeq) || [];
      const targetAtt = k >= 1 && k <= targetImages.length ? targetImages[k - 1] : null;
      const rawUrl = targetAtt ? targetAtt.previewUrl || targetAtt.url || targetAtt.downloadUrl : '';

      if (targetAtt && isSafeAttachmentUrl(rawUrl)) {
        if (isExplicitAnchor) {
          parts.push(
            <button
              type="button"
              key={`${lineKey}-p-${partIdx++}`}
              className="inline-photo-token is-anchored"
              onClick={() => onJumpToSeq(targetSeq)}
              title={`#${targetSeq} 글의 [사진 ${k}] · 클릭하여 #${targetSeq}로 이동`}
            >
              [#{targetSeq} 사진 {k}]
            </button>
          );
        } else {
          parts.push(
            <span key={`${lineKey}-p-${partIdx++}`} className="inline-photo-token">
              [사진 {k}]
            </span>
          );
        }
        inlineFigures.push({
          key: `${lineKey}-fig-${targetSeq}-${k}-${partIdx}`,
          k,
          targetSeq: isExplicitAnchor ? targetSeq : null,
          url: rawUrl,
          name: targetAtt.originalName || targetAtt.fileName || `사진 ${k}`
        });
      } else {
        parts.push(<React.Fragment key={`${lineKey}-pt-${partIdx++}`}>{token}</React.Fragment>);
      }
    } else if (token.startsWith('#')) {
      const seqNum = Number(token.slice(1));
      if (validSeqSet.has(seqNum)) {
        parts.push(
          <button
            type="button"
            key={`${lineKey}-seq-${partIdx++}`}
            className="seq-anchor-link"
            onClick={() => onJumpToSeq(seqNum)}
            title={`#${seqNum} 글로 이동`}
          >
            #{seqNum}
          </button>
        );
      } else {
        parts.push(<React.Fragment key={`${lineKey}-st-${partIdx++}`}>{token}</React.Fragment>);
      }
    }

    lastIdx = tokenRegex.lastIndex;
  }

  if (lastIdx < line.length) {
    parts.push(<React.Fragment key={`${lineKey}-end`}>{line.slice(lastIdx)}</React.Fragment>);
  }

  return { parts, inlineFigures };
}

function renderFormattedPostContent(text, currentSeq, imagesBySeq, validSeqSet, onJumpToSeq) {
  const lines = String(text || '').split('\n');

  return lines.map((line, lineIdx) => {
    if (!line) {
      return (
        <div key={`line-${lineIdx}`} className="forum-post-line">
          {'\u00A0'}
        </div>
      );
    }

    const { parts, inlineFigures } = renderInlineTokens(
      line,
      currentSeq,
      imagesBySeq,
      validSeqSet,
      onJumpToSeq,
      `l-${lineIdx}`
    );

    return (
      <React.Fragment key={`line-${lineIdx}`}>
        <div className="forum-post-line">{parts}</div>
        {inlineFigures.map((fig) => (
          <figure key={fig.key} className="inline-photo-figure">
            <img src={fig.url} alt={fig.name} loading="lazy" />
            <figcaption>
              {fig.targetSeq ? (
                <>
                  [
                  <button
                    type="button"
                    className="seq-anchor-link inline-fig-anchor"
                    onClick={() => onJumpToSeq(fig.targetSeq)}
                    title={`#${fig.targetSeq} 글로 이동`}
                  >
                    #{fig.targetSeq}
                  </button>{' '}
                  사진 {fig.k}] {fig.name}
                </>
              ) : (
                `[사진 ${fig.k}] ${fig.name}`
              )}
            </figcaption>
          </figure>
        ))}
      </React.Fragment>
    );
  });
}

function AttachmentCardsGrid({ attachments }) {
  if (!Array.isArray(attachments) || attachments.length === 0) return null;
  let photoOrder = 0;

  return (
    <div className="question-attachments-section">
      <div className="attachments-header">
        <Paperclip size={15} />
        <span>첨부파일 ({attachments.length}개)</span>
        <span className="security-verified-pill">
          <ShieldCheck size={12} /> 보안 검증 완료
        </span>
      </div>
      <div className="attachments-grid">
        {attachments.map((att) => {
          const photoNum = att.isImage ? ++photoOrder : null;
          return (
            <div key={att.id} className="attachment-card">
              {att.isImage && isSafeAttachmentUrl(att.previewUrl || att.url) ? (
                <div className="attachment-image-preview">
                  <img src={att.previewUrl || att.url} alt={att.originalName} loading="lazy" />
                </div>
              ) : null}
              <div className="attachment-card-body">
                <div className="attachment-icon-wrap">
                  {att.isImage ? <ImageIcon size={18} /> : <FileText size={18} />}
                </div>
                <div className="attachment-meta">
                  <span className="attachment-filename" title={att.originalName}>
                    {photoNum ? `[사진 ${photoNum}] ` : ''}
                    {att.originalName}
                  </span>
                  <div className="attachment-submeta">
                    <span>{formatFileSize(att.fileSize)}</span>
                    {att.isCompressed && att.compressionRatio > 0 && (
                      <span className="compression-pill">압축 -{att.compressionRatio}%</span>
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
          );
        })}
      </div>
    </div>
  );
}

const QuestionDetail = ({
  question,
  onAddAnswer,
  onAddFollowUp,
  onUpdateQuestion,
  onToggleThreadOpen,
  onToggleHideQuestion,
  onToggleHideAnswer,
  onDeleteQuestion,
  onDeleteAnswer,
  onBack
}) => {
  const { currentUser, isProfessor, isAdmin, allCourses, refreshCurrentUser } = useAuth();
  const [answerText, setAnswerText] = useState('');
  const [answerAttachments, setAnswerAttachments] = useState([]);
  const [isProcessingAnswerFiles, setIsProcessingAnswerFiles] = useState(false);
  const answerTextareaRef = useRef(null);

  const [followUpText, setFollowUpText] = useState('');
  const [followUpOriginalText, setFollowUpOriginalText] = useState('');
  const [hasFollowUpAiRefined, setHasFollowUpAiRefined] = useState(false);
  const [isFollowUpAiModalOpen, setIsFollowUpAiModalOpen] = useState(false);
  const [followUpAttachments, setFollowUpAttachments] = useState([]);
  const [isProcessingFollowUpFiles, setIsProcessingFollowUpFiles] = useState(false);
  const followUpTextareaRef = useRef(null);

  const [showOriginal, setShowOriginal] = useState(false);
  const [expandedFollowUpOriginals, setExpandedFollowUpOriginals] = useState({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [highlightedSeq, setHighlightedSeq] = useState(null);

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
        <h3>열람할 질문을 선택해 주세요</h3>
        <p>왼쪽 목록에서 질문을 선택하면 본문과 답변을 확인할 수 있습니다.</p>
      </div>
    );
  }

  const categoryKey = (question.category || 'lecture').toLowerCase();
  const category = CATEGORY_DEFINITIONS[categoryKey] || CATEGORY_DEFINITIONS.lecture;
  const answers = Array.isArray(question.answers) ? question.answers : [];
  const isThreadOpen = Boolean(question.isThreadOpen);

  const validSeqSet = new Set([1]);
  const imagesBySeq = new Map();
  imagesBySeq.set(1, getImageAttachments(question.attachments));

  let maxSeq = Number(question.lastSequenceNumber || 1);
  answers.forEach((ans, idx) => {
    const seq = Number(ans.sequenceNumber || idx + 2);
    validSeqSet.add(seq);
    if (seq > maxSeq) maxSeq = seq;
    imagesBySeq.set(seq, getImageAttachments(ans.attachments));
  });
  const nextSequenceNumber = maxSeq + 1;

  const handleJumpToSeq = (seqNum) => {
    const target = document.getElementById(`post-seq-${seqNum}`);
    if (!target) return;
    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setHighlightedSeq(seqNum);
    setTimeout(() => {
      setHighlightedSeq((prev) => (prev === seqNum ? null : prev));
    }, 1800);
  };

  const isPrimaryProf =
    isProfessor &&
    (currentUser?.id === question.profId ||
      currentUser?.id === question.assignedProfessorId);

  const profCourses =
    Array.isArray(currentUser?.courses) && currentUser.courses.length > 0
      ? currentUser.courses
      : allCourses || [];

  const isSameCourseOtherProf =
    isProfessor &&
    !isPrimaryProf &&
    profCourses.some(
      (c) =>
        question.courseId ? c.id === question.courseId : c.name === question.courseName
    );

  const hasPrimaryProfAnswered =
    question.isAnsweredByAssignedProfessor ||
    answers.some((a) => a.isPrimaryProf || a.isAssignedProfessor);

  const isAuthorStudent =
    currentUser?.role === 'student' && currentUser?.id === question.studentId;

  let canAnswer = false;
  let answerLockReason = '';

  if (question.isDeleted) {
    canAnswer = false;
    answerLockReason = '삭제된 질문입니다.';
  } else if (isAdmin) {
    canAnswer = true;
  } else if (typeof question.canAnswer === 'boolean') {
    canAnswer = question.canAnswer;
    if (!canAnswer && isSameCourseOtherProf) {
      answerLockReason = !isThreadOpen
        ? '닫힌 질문입니다.'
        : hasPrimaryProfAnswered
          ? '담당교수 답변이 완료되었습니다.'
          : '공동 답변 권한이 없습니다.';
    } else if (!canAnswer && isProfessor) {
      answerLockReason = '담당 과목만 답변할 수 있습니다.';
    }
  } else if (isPrimaryProf) {
    canAnswer = true;
  } else if (isSameCourseOtherProf) {
    if (!isThreadOpen) {
      canAnswer = false;
      answerLockReason = '닫힌 질문입니다.';
    } else if (hasPrimaryProfAnswered) {
      canAnswer = false;
      answerLockReason = '담당교수 답변이 완료되었습니다.';
    } else {
      canAnswer = true;
    }
  } else if (currentUser?.role === 'student' && !isAuthorStudent) {
    canAnswer = isThreadOpen;
    if (!isThreadOpen) {
      answerLockReason = '닫힌 질문입니다.';
    }
  } else if (isProfessor) {
    canAnswer = false;
    answerLockReason = '담당 과목만 답변할 수 있습니다.';
  }

  const canToggleThread = (isAdmin || isPrimaryProf) && !question.isDeleted && Boolean(onToggleThreadOpen);

  const profAnswers = answers.filter(
    (a) =>
      String(a.authorRole || 'PROFESSOR').toUpperCase() !== 'STUDENT' &&
      !a.isAdminAuthor &&
      a.authorTitle !== '관리자'
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
    setErrorMsg('');
    setIsUpdating(true);
    try {
      const nextAnonymous = category.allowAnonymous ? editAnonymous : false;
      const profList = [question.profName, question.assignedProfessorName].filter(Boolean);
      const nextTitle = nextAnonymous
        ? sanitizeAnonymousStudentText(editTitle.trim(), currentUser, { professorNames: profList })
        : editTitle.trim();
      const nextContent = nextAnonymous
        ? sanitizeAnonymousStudentText(editContent.trim(), currentUser, { professorNames: profList })
        : editContent.trim();
      await onUpdateQuestion(question.id, {
        title: nextTitle,
        content: nextContent,
        isAnonymous: nextAnonymous
      });
      setIsEditing(false);
    } catch (err) {
      setErrorMsg(err.message || '질문 수정 중 오류가 발생했습니다.');
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
    if (isProcessingAnswerFiles) {
      setErrorMsg('첨부 파일 보안 검사 및 업로드가 진행 중입니다. 잠시만 기다려 주세요.');
      return;
    }
    setErrorMsg('');
    setIsSubmitting(true);
    try {
      const latestUser = await refreshCurrentUser();
      if (!latestUser || latestUser.id !== currentUser?.id) {
        setErrorMsg('로그인 상태가 변경되었습니다. 계정을 확인한 뒤 다시 시도해 주세요.');
        return;
      }
      const resolvedTitle = isAdmin
        ? '관리자'
        : isPrimaryProf
          ? '담당교수'
          : isSameCourseOtherProf
            ? '공동답변 교수'
            : '학생 답변';
      await onAddAnswer(question.id, {
        authorId: currentUser.id,
        authorName: currentUser.name,
        authorTitle: resolvedTitle,
        isPrimaryProf: !isAdmin && isPrimaryProf,
        content: answerText.trim(),
        attachmentIds: answerAttachments.map((a) => a.id),
        attachments: answerAttachments
      });
      setAnswerText('');
      setAnswerAttachments([]);
    } catch (err) {
      setErrorMsg(err.message || '답변 등록에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFollowUpSubmit = async (e) => {
    e.preventDefault();
    if (!followUpText.trim() || !canFollowUp || !onAddFollowUp || isSubmitting) return;
    if (isProcessingFollowUpFiles) {
      setErrorMsg('첨부 파일 보안 검사 및 업로드가 진행 중입니다. 잠시만 기다려 주세요.');
      return;
    }
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
      const profList = [question.profName, question.assignedProfessorName].filter(Boolean);
      const finalFollowUp = question.isAnonymous
        ? sanitizeAnonymousStudentText(followUpText.trim(), currentUser, { professorNames: profList })
        : followUpText.trim();
      const finalOriginalFollowUp = question.isAnonymous
        ? sanitizeAnonymousStudentText(followUpOriginalText || followUpText.trim(), currentUser, {
            professorNames: profList
          })
        : (followUpOriginalText || followUpText.trim());
      await onAddFollowUp(question.id, {
        authorId: currentUser.id,
        content: finalFollowUp,
        originalContent: finalOriginalFollowUp,
        hasAiRefined: hasFollowUpAiRefined,
        attachmentIds: followUpAttachments.map((a) => a.id),
        attachments: followUpAttachments
      });
      setFollowUpText('');
      setFollowUpOriginalText('');
      setHasFollowUpAiRefined(false);
      setFollowUpAttachments([]);
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
          {canToggleThread && (
            <HelpBubble
              tier="ALL"
              mode="floating"
              placement="bottom"
              text="열림 상태면 공동교수·다른 학생도 답변할 수 있어요."
            >
              <button
                type="button"
                className={`btn btn-sm thread-open-toggle-btn ${
                  isThreadOpen ? 'is-open' : 'is-closed'
                }`}
                onClick={() => onToggleThreadOpen(question.id, !isThreadOpen)}
              >
                {isThreadOpen ? (
                  <>
                    <Unlock size={14} /> 열림
                  </>
                ) : (
                  <>
                    <Lock size={14} /> 질문자, 담당교수 외 닫힘
                  </>
                )}
              </button>
            </HelpBubble>
          )}

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
          <span
            className={`thread-lock-badge ${isThreadOpen ? 'is-open' : 'is-closed'}`}
            title={
              isThreadOpen
                ? '열림 상태: 같은 과목 교수(담당교수 답변 전) 및 다른 학생도 답변에 참여할 수 있습니다.'
                : '질문자, 담당교수 외 닫힘 상태입니다.'
            }
          >
            {isThreadOpen ? (
              <>
                <Unlock size={12} /> 열림
              </>
            ) : (
              <>
                <Lock size={12} /> 질문자, 담당교수 외 닫힘
              </>
            )}
          </span>
          {question.isDeleted ? (
            <span className="status-pill danger">
              <Trash2 size={13} /> 질문자 삭제됨
            </span>
          ) : isSolved ? (
            <span className="status-pill success">
              <CheckCircle2 size={13} /> 답변 완료 ({profAnswers.length})
            </span>
          ) : (
            <span className="status-pill warning">
              {profAnswers.length > 0 ? '추가 질문 대기' : '답변 대기'}
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
      <HelpBubble
        tier="SOME"
        mode="inline"
        text="#번호를 눌러 해당 글로 이동해요. 열림 상태면 다른 학생도 답변할 수 있어요."
        className="mb-help-bubble"
      />

      <div className="forum-post-stream">
        {/* Post #1: Topic Starter (OP) */}
        <article
          id="post-seq-1"
          className={`forum-post-card op-post ${
            question.isAdminAuthor ? 'admin-post-card' : ''
          } ${question.isHidden ? 'post-hidden-state' : ''} ${
            highlightedSeq === 1 ? 'post-seq-highlighted' : ''
          }`}
        >
          <aside className="forum-post-sidebar">
            <div
              className={`forum-post-avatar ${
                question.isAdminAuthor ? 'admin' : 'student'
              }`}
            >
              {question.isAnonymous
                ? '익'
                : (authorDisplay || (question.isAdminAuthor ? '관' : '수')).charAt(0)}
            </div>
            <div className="forum-post-author-meta">
              <div className="forum-post-author-name">{authorDisplay}</div>
              {question.isAdminAuthor ? (
                <span className="forum-role-tag admin">
                  <ShieldCheck size={12} /> 관리자
                </span>
              ) : (
                <span className="forum-role-tag op">질문 작성자</span>
              )}
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
              <button
                type="button"
                className="post-index-badge post-seq-badge"
                onClick={() => handleJumpToSeq(1)}
                title="#1 원문 글"
              >
                #1 · 질문
              </button>
            </div>

            {isEditing ? (
              <form className="edit-question-form" onSubmit={handleSaveEdit}>
                <div className="form-group">
                  <label className="form-label">질문 제목 수정</label>
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
                  {renderFormattedPostContent(
                    mainContent,
                    1,
                    imagesBySeq,
                    validSeqSet,
                    handleJumpToSeq
                  )}
                </div>

                {/* 첨부파일 영역 */}
                <AttachmentCardsGrid attachments={question.attachments} />

                {/* AI 다듬기 전 원문 보기 (질문 작성 학생 본인 및 관리자에게만 표시) */}
                {!isProfessor &&
                  (isAuthorStudent || isAdmin) &&
                  (question.originalContent || question.rawContent) &&
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
            const seq = Number(ans.sequenceNumber || index + 2);
            const isAdminPost = Boolean(ans.isAdminAuthor) || ans.authorTitle === '관리자';
            const isStudentPost =
              !isAdminPost && String(ans.authorRole || '').toUpperCase() === 'STUDENT';
            const isQuestionOpFollowUp =
              isStudentPost &&
              (Boolean(ans.isQuestionAuthor) ||
                ans.authorTitle === '질문자 추가 질문' ||
                ans.authorId === question.studentId);
            const isPeerStudentReply = isStudentPost && !isQuestionOpFollowUp;
            const isAssigned =
              !isAdminPost && !isStudentPost && (ans.isPrimaryProf || ans.isAssignedProfessor);
            const rawPostAuthorName =
              ans.authorName ||
              ans.professorName ||
              (isAdminPost ? '관리자' : isStudentPost ? '수강생' : '교수');
            const shouldMaskAnonStudentPost =
              isStudentPost && question.isAnonymous && !isAuthorStudent && !isAdmin;
            const postAuthorName = shouldMaskAnonStudentPost
              ? `${question.courseName || '강의'} 수강생`
              : rawPostAuthorName;
            const canModerateAns =
              Boolean(ans.canModerate) ||
              isAdmin ||
              (isPrimaryProf && isPeerStudentReply && !isAdminPost);

            return (
              <article
                id={`post-seq-${seq}`}
                key={ans.id}
                className={`forum-post-card reply-post ${
                  isAdminPost
                    ? 'admin-post-card'
                    : isStudentPost
                      ? 'student-followup-post'
                      : isAssigned
                        ? 'primary-prof-post'
                        : 'co-prof-post'
                } ${ans.isHidden ? 'post-hidden-state' : ''} ${
                  highlightedSeq === seq ? 'post-seq-highlighted' : ''
                }`}
              >
                <aside className="forum-post-sidebar">
                  <div
                    className={`forum-post-avatar ${
                      isAdminPost ? 'admin' : isStudentPost ? 'student' : 'professor'
                    }`}
                  >
                    {shouldMaskAnonStudentPost
                      ? '익'
                      : postAuthorName.charAt(0)}
                  </div>
                  <div className="forum-post-author-meta">
                    <div className="forum-post-author-name">{postAuthorName}</div>
                    <span
                      className={`forum-role-tag ${
                        isAdminPost
                          ? 'admin'
                          : isQuestionOpFollowUp
                            ? 'op'
                            : isPeerStudentReply
                              ? 'student-reply'
                              : isAssigned
                                ? 'solution'
                                : 'coprof'
                      }`}
                    >
                      {isAdminPost ? (
                        <>
                          <ShieldCheck size={12} /> 관리자
                        </>
                      ) : isQuestionOpFollowUp ? (
                        <>
                          <MessageSquare size={12} /> 추가 질문
                        </>
                      ) : isPeerStudentReply ? (
                        <>
                          <Users size={12} /> 학생 답변
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
                      {canModerateAns && onToggleHideAnswer && (
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
                      {canModerateAns && onDeleteAnswer && (
                        <button
                          type="button"
                          className="btn-admin-inline-delete"
                          onClick={() => onDeleteAnswer(ans, question.id)}
                        >
                          <Trash2 size={12} /> 삭제
                        </button>
                      )}
                      <button
                        type="button"
                        className="post-index-badge post-seq-badge"
                        onClick={() => handleJumpToSeq(seq)}
                        title={`#${seq} 글로 이동`}
                      >
                        #{seq}
                      </button>
                    </div>
                  </div>

                  <div className="forum-post-text">
                    {renderFormattedPostContent(
                      ans.content || '',
                      seq,
                      imagesBySeq,
                      validSeqSet,
                      handleJumpToSeq
                    )}
                  </div>

                  <AttachmentCardsGrid attachments={ans.attachments} />

                  {(() => {
                    const followUpOrig = (ans.originalContent || ans.rawContent || '').trim();
                    const followUpCurrent = (ans.content || '').trim();
                    const hasFollowUpOriginalDraft =
                      !isProfessor &&
                      (isAuthorStudent || isAdmin) &&
                      isQuestionOpFollowUp &&
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

          <HelpBubble
            tier="ALL"
            mode="inline"
            text="#번호로 글을 인용하고, 파일 첨부 및 [사진 1] · [#1 사진 1] 태그로 본인 또는 다른 글의 이미지를 삽입해요."
            className="mb-help-bubble"
          />

          <form onSubmit={handleFollowUpSubmit} className="answer-form">
            <div className="answer-form-header followup-form-header">
              <span className="editor-role-indicator">
                <MessageSquare size={15} className="text-primary" /> 추가 질문 (#{nextSequenceNumber})
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
              ref={followUpTextareaRef}
              className={`answer-textarea ${hasFollowUpAiRefined ? 'has-refined-border' : ''}`}
              rows={3}
              placeholder="추가 질문을 입력하세요."
              value={followUpText}
              onChange={(e) => {
                setFollowUpText(e.target.value);
                if (hasFollowUpAiRefined) setHasFollowUpAiRefined(false);
              }}
              required
            />
            <AttachmentUploader
              attachments={followUpAttachments}
              onChangeAttachments={setFollowUpAttachments}
              isProcessing={isProcessingFollowUpFiles}
              onProcessingChange={setIsProcessingFollowUpFiles}
              onInsertPhotoTag={(photoNum) =>
                insertSnippetAtCursor(
                  followUpTextareaRef,
                  followUpText,
                  setFollowUpText,
                  `[사진 ${photoNum}]`,
                  () => {
                    if (hasFollowUpAiRefined) setHasFollowUpAiRefined(false);
                  }
                )
              }
              onError={setErrorMsg}
              compact
            />
            {hasFollowUpAiRefined && (
              <div className="refined-status-hint" style={{ marginTop: 6 }}>
                <CheckCircle2 size={14} />
                <span>정제된 문장이 적용되었습니다.</span>
              </div>
            )}
            <div className="answer-form-footer">
              <span className="answer-hint">
                등록 시 담당교수에게 알림이 전송됩니다.
              </span>
              <button
                type="submit"
                className="btn btn-primary"
                disabled={!followUpText.trim() || isSubmitting || isProcessingFollowUpFiles}
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
              const profList = [question.profName, question.assignedProfessorName].filter(Boolean);
              const nextText = question.isAnonymous
                ? sanitizeAnonymousStudentText(refinedText, currentUser, { professorNames: profList })
                : refinedText;
              const nextOrig = question.isAnonymous
                ? sanitizeAnonymousStudentText(origText || followUpText, currentUser, {
                    professorNames: profList
                  })
                : (origText || followUpText);
              setFollowUpText(nextText);
              setFollowUpOriginalText((prev) => prev || nextOrig);
              setHasFollowUpAiRefined(true);
            }}
          />
        </div>
      )}

      {/* 스레드 답변 작성 컴포저 (교수 · 관리자 · 열림 스레드 타 학생) */}
      {(isProfessor || isAdmin || (!isAuthorStudent && (canAnswer || isThreadOpen))) && (
        <div className="answer-editor-section">
          {errorMsg && (
            <div className="auth-gate-alert error" style={{ marginBottom: 12 }}>
              <AlertCircle size={16} />
              <span>{errorMsg}</span>
            </div>
          )}

          {canAnswer ? (
            <form onSubmit={handleAnswerSubmit} className="answer-form">
              <HelpBubble
                tier="ALL"
                mode="inline"
                text="#번호로 글을 인용하고, 파일 첨부 및 [사진 1] · [#1 사진 1] 태그로 본인 또는 다른 글의 이미지를 삽입해요."
                className="mb-help-bubble"
              />
              <div className="answer-form-header">
                <span className="editor-role-indicator">
                  {isAdmin ? (
                    <>
                      <ShieldCheck size={15} className="text-primary" /> 관리자 답변 (#{nextSequenceNumber})
                    </>
                  ) : isPrimaryProf ? (
                    <>
                      <Award size={15} className="text-primary" /> 담당교수 답변 (#{nextSequenceNumber})
                    </>
                  ) : isSameCourseOtherProf ? (
                    <>
                      <Users size={15} className="text-primary" /> 공동답변 (#{nextSequenceNumber})
                    </>
                  ) : (
                    <>
                      <Users size={15} className="text-primary" /> 학생 답변 (#{nextSequenceNumber})
                    </>
                  )}
                </span>
              </div>
              <textarea
                ref={answerTextareaRef}
                className="answer-textarea"
                rows={4}
                placeholder="답변을 입력하세요."
                value={answerText}
                onChange={(e) => setAnswerText(e.target.value)}
                required
              />
              <AttachmentUploader
                attachments={answerAttachments}
                onChangeAttachments={setAnswerAttachments}
                isProcessing={isProcessingAnswerFiles}
                onProcessingChange={setIsProcessingAnswerFiles}
                onInsertPhotoTag={(photoNum) =>
                  insertSnippetAtCursor(
                    answerTextareaRef,
                    answerText,
                    setAnswerText,
                    `[사진 ${photoNum}]`
                  )
                }
                onError={setErrorMsg}
                compact
              />
              <div className="answer-form-footer">
                <span className="answer-hint">
                  {isAdmin
                    ? '관리자 배지와 함께 등록됩니다.'
                    : '등록 시 질문자에게 알림이 전송됩니다.'}
                </span>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={!answerText.trim() || isSubmitting || isProcessingAnswerFiles}
                >
                  <Send size={16} />
                  {isSubmitting ? '등록 중...' : '답변 등록'}
                </button>
              </div>
            </form>
          ) : (
            <div className="answer-locked-banner">
              <Lock size={18} />
              <span>{answerLockReason || '현재 이 질문에 답변할 권한이 없습니다.'}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default QuestionDetail;
