// src/components/professor/ProfessorDashboard.jsx
import React, { useState, useMemo } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { CATEGORY_LIST, isQuestionSolved } from '../../services/mockData';
import QuestionCard from '../questions/QuestionCard';
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

  const profCourseNames = useMemo(
    () => profCourses.map((c) => c.name),
    [profCourses]
  );

  const categorizedLists = useMemo(() => {
    const myAssigned = questions.filter(
      (q) =>
        q.profId === currentUser?.id ||
        q.assignedProfessorId === currentUser?.id ||
        q.profName === currentUser?.name ||
        q.assignedProfessorName === currentUser?.name
    );

    const coProfLecture = questions.filter((q) => {
      const isMyCourseGroup = profCourseNames.includes(q.courseName);
      const isOtherProf =
        q.profId !== currentUser?.id &&
        q.assignedProfessorId !== currentUser?.id;
      const isLectureCat = (q.category || '').toLowerCase() === 'lecture';
      const hasPrimaryAnswer =
        q.isAnsweredByAssignedProfessor ||
        (q.answers || []).some((a) => a.isPrimaryProf || a.isAssignedProfessor);
      return isMyCourseGroup && isOtherProf && isLectureCat && !hasPrimaryAnswer;
    });

    const allMyCourseQuestions = questions.filter(
      (q) =>
        profCourseNames.includes(q.courseName) ||
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
      if (statusFilter === 'UNANSWERED' && solved) return false;
      if (statusFilter === 'ANSWERED' && !solved) return false;
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
    (q) => !isQuestionSolved(q)
  ).length;
  const coProfAvailableCount = categorizedLists.coProfLecture.length;

  return (
    <div className="forum-board-container prof-dashboard-container">
      {/* 교수 대시보드 요약 카드 */}
      <div className="prof-summary-banner">
        <div className="prof-summary-stats">
          <div
            className={`prof-stat-card ${activeTab === 'MY_ASSIGNED' ? 'active' : ''}`}
            onClick={() => setActiveTab('MY_ASSIGNED')}
          >
            <div className="stat-icon-box primary">
              <Inbox size={20} />
            </div>
            <div>
              <span className="stat-label">내 담당 질문 토픽</span>
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
              <span className="stat-label">공동 답변 가능 (타 분반 강의)</span>
              <div className="stat-value-row">
                <strong className="stat-value">{coProfAvailableCount}</strong>
                <span className="stat-sub">동일 과목 미답변 강의 토픽</span>
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
              <span className="stat-label">담당 교과목 전체 토픽</span>
              <div className="stat-value-row">
                <strong className="stat-value">
                  {categorizedLists.allMyCourseQuestions.length}
                </strong>
                <span className="stat-sub">전체 아카이브</span>
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
            <option value="UNANSWERED">미답변 토픽만</option>
            <option value="ANSWERED">답변완료 토픽만</option>
          </select>
        </div>
      </div>

      {/* 서양식 포럼 토픽 테이블 헤더 */}
      <div className="forum-table-header">
        <div className="forum-col-topic">Topic (질문 스레드)</div>
        <div className="forum-col-users">Participants</div>
        <div className="forum-col-replies">Replies / Posts</div>
        <div className="forum-col-activity">Activity</div>
      </div>

      {/* 토픽 목록 */}
      <div className="forum-topic-list">
        {currentList.length === 0 ? (
          <div className="empty-state-box">
            <Clock size={36} className="text-muted" />
            <h4>표시할 토픽이 없습니다</h4>
            <p>현재 탭 또는 검색 조건에 해당하는 학생 질문 스레드가 없습니다.</p>
          </div>
        ) : (
          currentList.map((q) => {
            const isCoProfAnswerable =
              q.profId !== currentUser?.id &&
              q.assignedProfessorId !== currentUser?.id &&
              (q.category || '').toLowerCase() === 'lecture' &&
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
