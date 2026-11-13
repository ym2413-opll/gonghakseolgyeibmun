// src/components/professor/AnswerForm.jsx
import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { api } from '../../services/api';
import { CATEGORY_DEFINITIONS } from '../../services/mockData';
import { Send, CheckCircle2, Lock, ShieldAlert, Users } from 'lucide-react';

export default function AnswerForm({ question, onAnswerAdded }) {
  const { currentUser, isProfessor, refreshCurrentUser } = useAuth();
  const [content, setContent] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  if (!isProfessor) return null;

  // 1. 질문의 과목/그룹과 일치하는 교수의 강좌 찾기
  // (강의명이 같거나, 다른 강의이지만 교과목 그룹이 같은 경우)
  const matchingCourse = currentUser.courses?.find(
    (c) =>
      c.name === question.courseName ||
      (c.group && (c.group === question.courseGroup || c.group === question.group))
  );

  const isDesignatedProf = currentUser.id === question.profId || currentUser.id === question.assignedProfessorId;
  const isSameCourseOrGroupProf = !isDesignatedProf && Boolean(matchingCourse);

  // 2. 지정 담당 교수의 답변이 이미 존재하는지 여부
  const hasDesignatedProfAnswered = question.isAnsweredByAssignedProfessor || question.answers?.some(
    (a) => a.authorId === question.profId || a.authorId === question.assignedProfessorId || a.isAssignedProfessor
  );

  // 3. 권한 검사 (주/부교수 개념 배제 및 동등한 교수 권한 적용)
  let canAnswer = false;
  let statusMessage = null;
  let statusType = 'info';

  if (isDesignatedProf) {
    canAnswer = true;
    statusMessage = '담당 교수 권한으로 답변을 작성합니다.';
    statusType = 'primary';
  } else if (isSameCourseOrGroupProf) {
    if ((question.category || '').toLowerCase() !== 'lecture') {
      canAnswer = false;
      const catName = (CATEGORY_DEFINITIONS[question.category?.toLowerCase()] || CATEGORY_DEFINITIONS[question.category])?.name || question.category;
      statusMessage = `[${catName}] 카테고리 질문은 분반/평가 기준 보호를 위해 배정된 담당 교수님만 답변할 수 있습니다.`;
      statusType = 'locked';
    } else if (hasDesignatedProfAnswered) {
      canAnswer = false;
      statusMessage = `배정된 담당 교수(${question.profName || question.assignedProfessorName || '담당 교수'})님이 이미 답변을 완료하여, 추가 답변이 제한됩니다.`;
      statusType = 'locked';
    } else {
      canAnswer = true;
      statusMessage = `[동일 과목/교과목군] 학생의 원활한 학습 지원을 위해 담당 교수 자격으로 답변을 작성할 수 있습니다.`;
      statusType = 'co-prof-active';
    }
  } else {
    canAnswer = false;
    statusMessage = '해당 강좌 또는 동일 교과목군을 담당하는 교수가 아니므로 답변 권한이 없습니다.';
    statusType = 'unauthorized';
  }

  // 백엔드 명시적 권한 플래그가 있는 경우 동기화
  if (question.canAnswer !== undefined) {
    canAnswer = question.canAnswer;
  }

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!content.trim()) {
      setErrorMsg('답변 내용을 입력해 주세요.');
      return;
    }
    if (!canAnswer) return;

    setIsSubmitting(true);
    setErrorMsg('');

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

      // 주교수/부교수 구분 없이 모든 교수의 표시 직함은 동일하게 '담당교수'로 통일
      const answerPayload = {
        authorId: currentUser.id,
        authorName: currentUser.name,
        authorTitle: '담당교수',
        isPrimaryProf: true,
        content: content.trim()
      };

      const newAns = await api.createAnswer(question.id, answerPayload);
      setContent('');
      if (onAnswerAdded) onAnswerAdded(newAns);
    } catch (err) {
      console.error('Answer creation error', err);
      setErrorMsg('답변 등록에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="answer-form-box">
      {/* 권한 상태 알림 바 */}
      <div className={`prof-permission-banner ${statusType}`}>
        {statusType === 'primary' && <CheckCircle2 size={16} />}
        {statusType === 'co-prof-active' && <Users size={16} />}
        {statusType === 'locked' && <Lock size={16} />}
        {statusType === 'unauthorized' && <ShieldAlert size={16} />}
        <span>{statusMessage}</span>
      </div>

      {canAnswer ? (
        <form onSubmit={handleSubmit} className="answer-form">
          <div className="ans-input-header">
            <span className="ans-author-preview">
              작성자: <strong>{currentUser.name}</strong> (담당교수)
            </span>
          </div>

          {errorMsg && <p className="ans-err-text">{errorMsg}</p>}

          <textarea
            rows={5}
            className="ans-textarea"
            placeholder="답변 내용을 입력하세요."
            value={content}
            onChange={(e) => setContent(e.target.value)}
          />

          <div className="ans-submit-row">
            <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
              <span>{isSubmitting ? '등록 중...' : '답변 등록'}</span>
            </button>
          </div>
        </form>
      ) : (
        <div className="ans-disabled-notice">
          <Lock size={20} />
          <p>현재 상태에서는 답변을 작성할 수 없습니다.</p>
        </div>
      )}
    </div>
  );
}
