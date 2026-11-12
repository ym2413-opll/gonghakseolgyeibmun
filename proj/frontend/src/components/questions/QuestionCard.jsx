// src/components/questions/QuestionCard.jsx
// 서양식 포럼(Western-Style Forum: Discourse / GitHub Discussions 스타일) 토픽 행(Topic Row) 컴포넌트
import React from 'react';
import { CATEGORY_DEFINITIONS } from '../../services/mockData';
import { useAuth } from '../../context/AuthContext';
import {
  MessageSquare,
  CheckCircle2,
  Clock,
  Shield,
  Users,
  EyeOff,
  Eye,
  Paperclip
} from 'lucide-react';

const QuestionCard = ({
  question,
  isSelected,
  onClick,
  canCoProfAnswer,
  onToggleHide
}) => {
  const { isAdmin } = useAuth();
  const categoryKey = (question.category || 'lecture').toLowerCase();
  const category = CATEGORY_DEFINITIONS[categoryKey] || CATEGORY_DEFINITIONS.lecture;
  const answers = Array.isArray(question.answers) ? question.answers : [];
  const answerCount = question.answerCount !== undefined ? question.answerCount : answers.length;
  const isAnswered =
    answerCount > 0 || String(question.status || '').toUpperCase() === 'ANSWERED';
  const hasPrimaryAnswer =
    question.isAnsweredByAssignedProfessor ||
    answers.some((a) => a.isPrimaryProf || a.isAssignedProfessor);

  const authorDisplay = question.isAnonymous
    ? '익명 수강생'
    : question.studentTag || question.studentName || '수강생';

  const realAuthorDisplay =
    isAdmin && question.isAnonymous
      ? question.realStudentTag ||
        question.realStudentName ||
        (question.studentName !== '익명 (수강생)' ? question.studentName : null)
      : null;

  // 참여자 아바타 스택 (OP + 답변 교수진)
  const participants = React.useMemo(() => {
    const list = [
      {
        label: question.isAnonymous ? '익' : (authorDisplay || '수').charAt(0),
        title: `질문자: ${authorDisplay}`,
        role: 'student'
      }
    ];
    const seen = new Set();
    answers.forEach((ans) => {
      const name = ans.authorName || ans.professorName || '교수';
      if (!seen.has(name)) {
        seen.add(name);
        list.push({
          label: name.charAt(0),
          title: `답변: ${name}`,
          role: 'professor'
        });
      }
    });
    if (list.length === 1 && (question.profName || question.assignedProfessorName)) {
      const pName = question.profName || question.assignedProfessorName;
      list.push({
        label: pName.charAt(0),
        title: `담당교수: ${pName}`,
        role: 'assigned-pending'
      });
    }
    return list.slice(0, 4);
  }, [question, answers, authorDisplay]);

  const lastActivityDate =
    answers.length > 0
      ? answers[answers.length - 1].createdAt || question.createdAt
      : question.createdAt;

  const formattedActivity = lastActivityDate
    ? new Date(lastActivityDate).toLocaleString('ko-KR', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      })
    : '-';

  return (
    <article
      className={`forum-topic-row ${isSelected ? 'selected' : ''} ${
        isAnswered ? 'solved' : 'open'
      } ${question.isHidden ? 'is-hidden-topic' : ''}`}
      onClick={onClick}
    >
      {/* 1. 좌측: 토픽 상태 아이콘 + 제목 + 메타 태그 + 본문 요약 */}
      <div className="forum-topic-main">
        <div
          className={`forum-topic-status-icon ${isAnswered ? 'solved' : 'open'}`}
          title={isAnswered ? '해결됨 (답변 완료)' : '진행 중 (미답변)'}
        >
          {isAnswered ? <CheckCircle2 size={18} /> : <MessageSquare size={18} />}
        </div>

        <div className="forum-topic-content">
          <div className="forum-topic-title-line">
            <span className={`badge ${category.badgeClass}`}>{category.name}</span>
            <span className="forum-course-pill">{question.courseName}</span>
            {question.isHidden && (
              <span className="status-pill danger">
                <EyeOff size={11} /> 가려진 게시물
              </span>
            )}
            {canCoProfAnswer && (
              <span className="co-prof-available-badge">
                <Users size={12} /> 공동 답변 가능
              </span>
            )}
            <h3 className="forum-topic-title">{question.title}</h3>
          </div>

          <p className="forum-topic-excerpt">
            {question.content || question.refinedContent || ''}
          </p>

          <div className="forum-topic-submeta">
            <span className="forum-op-tag">
              OP: <strong>{authorDisplay}</strong>
              {question.isAnonymous && (
                <span className="anon-tag">
                  <Shield size={11} /> 익명
                </span>
              )}
            </span>

            {realAuthorDisplay && (
              <span
                className="admin-real-author-badge"
                title="관리자 전용 익명 질문자 실명 표시"
              >
                실명: {realAuthorDisplay}
                {question.realStudentIdentifier
                  ? ` (${question.realStudentIdentifier})`
                  : ''}
              </span>
            )}

            <span className="meta-dot">·</span>
            <span>
              담당: {question.profName || question.assignedProfessorName || '담당교수'}
            </span>

            {Array.isArray(question.attachments) && question.attachments.length > 0 && (
              <>
                <span className="meta-dot">·</span>
                <span className="attachment-count-chip">
                  <Paperclip size={11} /> 첨부 {question.attachments.length}
                </span>
              </>
            )}

            {isAdmin && onToggleHide && (
              <button
                type="button"
                className="btn-admin-inline-hide"
                onClick={(e) => {
                  e.stopPropagation();
                  onToggleHide(question);
                }}
              >
                {question.isHidden ? (
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
          </div>
        </div>
      </div>

      {/* 2. 중앙: 참여자 아바타 스택 (서양식 포럼 스타일) */}
      <div className="forum-topic-participants" title="토픽 참여자">
        <div className="forum-avatar-stack">
          {participants.map((p, idx) => (
            <span
              key={idx}
              className={`forum-mini-avatar role-${p.role}`}
              title={p.title}
            >
              {p.label}
            </span>
          ))}
        </div>
      </div>

      {/* 3. 우측 통계: Replies / Posts */}
      <div className="forum-topic-stats">
        <div className={`forum-stat-replies ${answerCount > 0 ? 'has-replies' : ''}`}>
          <span className="stat-num">{answerCount}</span>
          <span className="stat-label">Replies</span>
        </div>
        <div className="forum-stat-posts">
          <span className="stat-num">{answerCount + 1}</span>
          <span className="stat-label">Posts</span>
        </div>
      </div>

      {/* 4. 최근 활동 시각 */}
      <div className="forum-topic-activity">
        <span className={`forum-status-chip ${isAnswered ? 'answered' : 'pending'}`}>
          {isAnswered
            ? hasPrimaryAnswer
              ? '담당교수 완료'
              : '공동답변 등록'
            : '답변 대기'}
        </span>
        <span className="forum-activity-time">
          <Clock size={12} /> {formattedActivity}
        </span>
      </div>
    </article>
  );
};

export default QuestionCard;
