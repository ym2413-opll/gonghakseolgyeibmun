// src/components/professor/ProfessorDashboard.jsx
import React, { useState, useMemo } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { CATEGORY_LIST, isQuestionSolved } from '../../services/mockData';
import QuestionCard from '../questions/QuestionCard';
import HelpBubble from '../common/HelpBubble';
import {
  Inbox,
  Users,
  CheckCircle2,
  Clock,
  Sliders,
  BellOff,
  Search,
  Mail
} from 'lucide-react';

const ProfessorDashboard = ({
  questions,
  selectedQuestionId,
  onSelectQuestion,
  onOpenProfSettings,
  onToggleHideQuestion,
  onDeleteQuestion
}) => {
  const { currentUser, allCourses } = useAuth();
  const { settings } = useTheme();

  const [activeTab, setActiveTab] = useState('MY_ASSIGNED'); // MY_ASSIGNED, CO_PROF_LECTURE, ALL_COURSES
  const [selectedCategory, setSelectedCategory] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL'); // ALL, UNANSWERED, ANSWERED
  const [searchQuery, setSearchQuery] = useState('');

  const profCourses = useMemo(() => {
    const raw =
      Array.isArray(currentUser?.courses) && currentUser.courses.length > 0
        ? currentUser.courses
        : allCourses;
    return raw || [];
  }, [currentUser?.courses, allCourses]);

  const profCourseIds = useMemo(
    () => profCourses.map((c) => c.id).filter(Boolean),
    [profCourses]
  );

  const profCourseNames = useMemo(
    () => profCourses.map((c) => c.name),
    [profCourses]
  );

  const categorizedLists = useMemo(() => {
    const matchesMyCourse = (q) =>
      q.courseId
        ? profCourseIds.includes(q.courseId)
        : profCourseNames.includes(q.courseName);

    const myAssigned = questions.filter(
      (q) =>
        q.profId === currentUser?.id ||
        q.assignedProfessorId === currentUser?.id
    );

    const coProfLecture = questions.filter((q) => {
      const isMyCourseGroup = matchesMyCourse(q);
      const isOtherProf =
        q.profId !== currentUser?.id &&
        q.assignedProfessorId !== currentUser?.id;
      const isOpenThread = Boolean(q.isThreadOpen);
      const hasPrimaryAnswer =
        q.isAnsweredByAssignedProfessor ||
        (q.answers || []).some((a) => a.isPrimaryProf || a.isAssignedProfessor);
      return isMyCourseGroup && isOtherProf && isOpenThread && !hasPrimaryAnswer && !q.isDeleted;
    });

    const allMyCourseQuestions = questions.filter(
      (q) =>
        matchesMyCourse(q) ||
        q.profId === currentUser?.id ||
        q.assignedProfessorId === currentUser?.id
    );

    return {
      myAssigned,
      coProfLecture,
      allMyCourseQuestions
    };
  }, [questions, currentUser, profCourseNames]);

  const currentList = useMemo(() => {
    let base = [];
    if (activeTab === 'MY_ASSIGNED') base = categorizedLists.myAssigned;
    else if (activeTab === 'CO_PROF_LECTURE') base = categorizedLists.coProfLecture;
    else base = categorizedLists.allMyCourseQuestions;

    return base.filter((q) => {
      if (
        selectedCategory !== 'ALL' &&
        (q.category || '').toLowerCase() !== selectedCategory.toLowerCase()
      ) {
        return false;
      }
      const solved = isQuestionSolved(q);
      if (statusFilter === 'UNANSWERED' && (solved || q.isDeleted)) return false;
      if (statusFilter === 'ANSWERED' && (!solved || q.isDeleted)) return false;
      if (searchQuery.trim()) {
        const kw = searchQuery.toLowerCase();
        return (
          (q.title || '').toLowerCase().includes(kw) ||
          (q.content || q.refinedContent || '').toLowerCase().includes(kw) ||
          (q.studentTag || '').toLowerCase().includes(kw)
        );
      }
      return true;
    });
  }, [activeTab, categorizedLists, selectedCategory, statusFilter, searchQuery]);

  const unansweredAssignedCount = categorizedLists.myAssigned.filter(
    (q) => !isQuestionSolved(q) && !q.isDeleted
  ).length;
  const coProfAvailableCount = categorizedLists.coProfLecture.length;

  return (
    <div className="forum-board-container prof-dashboard-container">
      {/* 교수 대시보드 요약 카드 */}
      <div className="prof-summary-banner">
        <HelpBubble
          tier="SOME"
          mode="inline"
          text="담당 질문, 공동 답변 가능 질문, 전체 질문을 분류해 봐요."
          className="mb-help-bubble"
        />
        <div className="prof-summary-stats">
          <div
            className={`prof-stat-card ${activeTab === 'MY_ASSIGNED' ? 'active' : ''}`}
            onClick={() => setActiveTab('MY_ASSIGNED')}
          >
            <div className="stat-icon-box primary">
              <Inbox size={20} />
            </div>
            <div>
              <span className="stat-label">내 담당 질문</span>
              <div className="stat-value-row">
                <strong className="stat-value">{categorizedLists.myAssigned.length}</strong>
                {unansweredAssignedCount > 0 && (
                  <span className="stat-badge-urgent">미답변 {unansweredAssignedCount}건</span>
                )}
              </div>
            </div>
          </div>

          <div
            className={`prof-stat-card ${activeTab === 'CO_PROF_LECTURE' ? 'active' : ''}`}
            onClick={() => setActiveTab('CO_PROF_LECTURE')}
          >
            <div className="stat-icon-box blue">
              <Users size={20} />
            </div>
            <div>
              <span className="stat-label">공동 답변 가능 (열림)</span>
              <div className="stat-value-row">
                <strong className="stat-value">{coProfAvailableCount}</strong>
                <span className="stat-sub">열린 미답변 질문</span>
              </div>
            </div>
          </div>

          <div
            className={`prof-stat-card ${activeTab === 'ALL_COURSES' ? 'active' : ''}`}
            onClick={() => setActiveTab('ALL_COURSES')}
          >
            <div className="stat-icon-box green">
              <CheckCircle2 size={20} />
            </div>
            <div>
              <span className="stat-label">담당 교과목 전체 질문</span>
              <div className="stat-value-row">
                <strong className="stat-value">
                  {categorizedLists.allMyCourseQuestions.length}
                </strong>
                <span className="stat-sub">전체 보관함</span>
              </div>
            </div>
          </div>
        </div>

        {/* 교수 설정 상태 요약 바 (이메일 연동 + DND 표시) */}
        <div className="prof-status-strip">
          <div className="prof-status-items">
            <span className="status-pill-item">
              <Mail size={14} />
              이메일 알림:{' '}
              <strong>
                {settings?.emailEnabled !== false
                  ? settings?.notificationEmail || currentUser?.email || '연동됨'
                  : '꺼짐'}
              </strong>
            </span>
            <span className="status-pill-item">
              <BellOff size={14} />
              방해금지:{' '}
              <strong>
                {settings?.dndEnabled
                  ? `${settings.dndStartTime} ~ ${settings.dndEndTime} (일괄 전달)`
                  : '사용 안 함'}
              </strong>
            </span>
          </div>
          <button className="btn-text-xs" onClick={onOpenProfSettings}>
            <Sliders size={13} /> 이메일·알림 설정 변경
          </button>
        </div>
      </div>

      {/* 필터 바 */}
      <HelpBubble
        tier="ALL"
        mode="inline"
        text="검색·카테고리·미답변 필터로 우선 답변할 질문을 좁히고, 상태 바에서 이메일·방해금지 설정을 확인해요."
        className="mb-help-bubble"
      />
      <div className="filter-bar">
        <div className="search-input-wrap">
          <Search size={16} className="search-icon" />
          <input
            type="text"
            className="search-input"
            placeholder="질문 제목, 내용, 학생 태그(학번_학과_이름) 검색..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>

        <div className="filter-chips-row">
          <div className="category-filter-group">
            <button
              type="button"
              className={`filter-chip ${selectedCategory === 'ALL' ? 'active' : ''}`}
              onClick={() => setSelectedCategory('ALL')}
            >
              전체 카테고리
            </button>
            {CATEGORY_LIST.map((cat) => (
              <button
                type="button"
                key={cat.id}
                className={`filter-chip ${selectedCategory === cat.id ? 'active' : ''}`}
                onClick={() => setSelectedCategory(cat.id)}
              >
                {cat.name}
              </button>
            ))}
          </div>

          <select
            className="status-select"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="ALL">전체 상태</option>
            <option value="UNANSWERED">미답변 질문만</option>
            <option value="ANSWERED">답변완료 질문만</option>
          </select>
        </div>
      </div>

      {/* 질문 테이블 헤더 */}
      <div className="forum-table-header">
        <div className="forum-col-topic">질문</div>
        <div className="forum-col-users">참여자</div>
        <div className="forum-col-replies">답변 / 글</div>
        <div className="forum-col-activity">최근 활동</div>
      </div>

      {/* 질문 목록 */}
      <div className="forum-topic-list">
        {currentList.length === 0 ? (
          <div className="empty-state-box">
            <Clock size={36} className="text-muted" />
            <h4>표시할 질문이 없습니다</h4>
            <p>조건에 해당하는 학생 질문이 없습니다.</p>
          </div>
        ) : (
          currentList.map((q) => {
            const isCoProfAnswerable =
              q.profId !== currentUser?.id &&
              q.assignedProfessorId !== currentUser?.id &&
              Boolean(q.isThreadOpen) &&
              !q.isAnsweredByAssignedProfessor &&
              !(q.answers || []).some((a) => a.isPrimaryProf || a.isAssignedProfessor);

            return (
              <QuestionCard
                key={q.id}
                question={q}
                isSelected={q.id === selectedQuestionId}
                onClick={() => onSelectQuestion(q.id)}
                canCoProfAnswer={isCoProfAnswerable}
                onToggleHide={onToggleHideQuestion}
                onDelete={onDeleteQuestion}
              />
            );
          })
        )}
      </div>
    </div>
  );
};

export default ProfessorDashboard;
