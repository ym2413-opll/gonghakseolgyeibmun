// src/components/shared/QuestionCard.jsx
import React from 'react';
import { CATEGORY_DEFINITIONS } from '../../services/mockData';
import { useAuth } from '../../context/AuthContext';
import { Sparkles, CheckCircle2, Clock, User, EyeOff, Users } from 'lucide-react';

export default function QuestionCard({ question, onClick }) {
  const { currentUser, isProfessor } = useAuth();

  if (!question) return null;

  const catDef = CATEGORY_DEFINITIONS[question.category] || CATEGORY_DEFINITIONS.lecture;
  const hasAnswers = Array.isArray(question.answers) && question.answers.length > 0;
  const isMine = currentUser?.id === question.studentId;

  // 익명 표기 로직 (명세: ex. 26_소프트_서영민 -> 공학설계입문 수강생)
  let authorDisplay = question.isAnonymous
    ? `${question.courseName || '수강'} 수강생`
    : question.studentTag || question.studentName || '수강생';

  if (question.isAnonymous && isMine) {
    authorDisplay = `${authorDisplay} (나 - 익명 처리됨)`;
  }

  // '강의명이 같거나 동일 그룹인 경우' 답변 가능한 상태인지 여부 표시
  const teachesSameCourseOrGroup =
    isProfessor &&
    currentUser?.courses?.some(
      (c) =>
        c.name === question.courseName ||
        (c.group && (c.group === question.courseGroup || c.group === question.group))
    );
  const isCoProfTarget =
    isProfessor &&
    teachesSameCourseOrGroup &&
    currentUser?.id !== question.profId &&
    question.category === 'lecture' &&
    !hasAnswers;

  const formattedDate = (() => {
    try {
      if (!question.createdAt) return '';
      const d = new Date(question.createdAt);
      return isNaN(d.getTime()) ? '' : d.toLocaleDateString([], { month: 'short', day: 'numeric' });
    } catch {
      return '';
    }
  })();

  return (
    <div className={`question-card ${hasAnswers ? 'status-answered' : 'status-waiting'}`} onClick={onClick}>
      <div className="card-top-row">
        <div className="card-tags-left">
          <span className={`cat-pill ${catDef.badgeClass || ''}`}>{catDef.name || question.category}</span>
          <span className="course-tag">{question.courseName}</span>
          {question.hasAiRefined && (
            <span className="ai-applied-tag" title="AI 말투 교정이 적용된 질문입니다">
              <Sparkles size={11} /> AI 윤문
            </span>
          )}
        </div>

        <div className="card-status-right">
          {isCoProfTarget && (
            <span className="co-prof-action-badge">
              <Users size={12} /> 답변 가능 (동일 과목/그룹)
            </span>
          )}
          {hasAnswers ? (
            <span className="status-badge answered">
              <CheckCircle2 size={13} /> 답변 완료 ({question.answers.length})
            </span>
          ) : (
            <span className="status-badge waiting">
              <Clock size={13} /> 답변 대기중
            </span>
          )}
        </div>
      </div>

      <h4 className="card-title">{question.title || '제목 없음'}</h4>

      <p className="card-snippet">
        {(question.content || '').length > 120
          ? `${question.content.slice(0, 120)}...`
          : (question.content || '')}
      </p>

      <div className="card-bottom-row">
        <div className="card-author">
          {question.isAnonymous ? (
            <span className="author-anon">
              <EyeOff size={13} /> {authorDisplay}
            </span>
          ) : (
            <span className="author-real">
              <User size={13} /> {authorDisplay}
            </span>
          )}
          <span className="target-prof">· 담당: {question.profName || '담당교수'}</span>
        </div>

        <div className="card-date">
          {formattedDate}
        </div>
      </div>
    </div>
  );
}
