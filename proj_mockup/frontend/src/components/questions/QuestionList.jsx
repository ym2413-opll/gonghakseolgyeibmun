// src/components/questions/QuestionList.jsx
import React, { useState, useMemo } from 'react';
import { useAuth } from '../../context/AuthContext';
import { CATEGORY_LIST, isQuestionSolved } from '../../services/mockData';
import { formatSemesterLabel } from '../../utils/semester';
import {
  collectLinkedCourseIds,
  extractCourseScopes,
  extractCourseSemesters,
  filterCoursesList,
  sortCoursesList
} from '../../utils/courseLineage';
import QuestionCard from './QuestionCard';
import HelpBubble from '../common/HelpBubble';
import {
  Search,
  Plus,
  Filter,
  MessageSquarePlus,
  BookOpen,
  MessagesSquare
} from 'lucide-react';

const QuestionList = ({
  questions,
  selectedQuestionId,
  onSelectQuestion,
  onOpenNewQuestion,
  onToggleHideQuestion,
  onDeleteQuestion
}) => {
  const { currentUser, allCourses, departments, colleges, currentSemester } = useAuth();

  const [selectedCourseId, setSelectedCourseId] = useState('ALL');
  const [selectedCategory, setSelectedCategory] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL'); // ALL, UNANSWERED, ANSWERED
  const [onlyMine, setOnlyMine] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [courseTabQuery, setCourseTabQuery] = useState('');
  const [courseTabSemester, setCourseTabSemester] = useState('ALL');
  const [courseTabDept, setCourseTabDept] = useState('ALL');
  const [courseTabSort, setCourseTabSort] = useState('semester_desc');

  const courses = useMemo(() => {
    const raw =
      Array.isArray(currentUser?.courses) && currentUser.courses.length > 0
        ? currentUser.courses
        : allCourses;
    const map = new Map();
    (raw || []).forEach((c) => {
      if (c?.id && !map.has(c.id)) map.set(c.id, c);
    });
    return Array.from(map.values());
  }, [currentUser?.courses, allCourses]);

  const courseSemesterOptions = useMemo(() => extractCourseSemesters(courses), [courses]);
  const courseScopeOptions = useMemo(() => extractCourseScopes(courses), [courses]);

  const visibleTabCourses = useMemo(() => {
    const filtered = filterCoursesList(courses, {
      query: courseTabQuery,
      semester: courseTabSemester,
      department: courseTabDept,
      departments,
      colleges,
      currentSemester,
      keepSelectedIds: selectedCourseId !== 'ALL' ? [selectedCourseId] : null
    });
    return sortCoursesList(filtered, courseTabSort, selectedCourseId !== 'ALL' ? [selectedCourseId] : []);
  }, [
    courses,
    courseTabQuery,
    courseTabSemester,
    courseTabDept,
    departments,
    colleges,
    currentSemester,
    selectedCourseId,
    courseTabSort
  ]);

  const selectedLineageIds = useMemo(() => {
    if (selectedCourseId === 'ALL') return null;
    return collectLinkedCourseIds(selectedCourseId, allCourses);
  }, [selectedCourseId, allCourses]);

  const selectedCourseObj = useMemo(
    () => (selectedCourseId === 'ALL' ? null : courses.find((c) => c.id === selectedCourseId) || null),
    [courses, selectedCourseId]
  );

  const filteredQuestions = useMemo(() => {
    return questions.filter((q) => {
      if (selectedCourseId !== 'ALL') {
        const matchesLineage = selectedLineageIds && q.courseId && selectedLineageIds.has(q.courseId);
        const matchesName = selectedCourseObj && q.courseName === selectedCourseObj.name;
        if (!matchesLineage && !matchesName && q.courseId !== selectedCourseId) {
          return false;
        }
      }
      if (selectedCategory !== 'ALL' && q.category?.toLowerCase() !== selectedCategory.toLowerCase()) {
        return false;
      }
      const solved = isQuestionSolved(q);
      if (statusFilter === 'UNANSWERED' && solved) {
        return false;
      }
      if (statusFilter === 'ANSWERED' && !solved) {
        return false;
      }
      if (onlyMine && q.studentId !== currentUser?.id) {
        return false;
      }
      if (searchQuery.trim()) {
        const kw = searchQuery.toLowerCase();
        const inTitle = (q.title || '').toLowerCase().includes(kw);
        const inContent = (q.content || q.refinedContent || '').toLowerCase().includes(kw);
        const inCourse = (q.courseName || '').toLowerCase().includes(kw);
        const inProf = (q.profName || q.assignedProfessorName || '').toLowerCase().includes(kw);
        return inTitle || inContent || inCourse || inProf;
      }
      return true;
    });
  }, [questions, selectedCourseId, selectedCategory, statusFilter, onlyMine, searchQuery, currentUser?.id]);

  return (
    <div className="forum-board-container">
      <div className="forum-board-hero">
        <div className="forum-hero-left">
          <div className="forum-hero-icon">
            <MessagesSquare size={20} />
          </div>
          <div>
            <h2 className="forum-board-title">질문 게시판</h2>
          </div>
        </div>
        <HelpBubble
          tier="SOME"
          mode="floating"
          placement="bottom-end"
          text="새 질문을 작성해요."
        >
          <button type="button" className="btn btn-primary btn-new-topic" onClick={onOpenNewQuestion}>
            <Plus size={18} />
            <span>새 질문 작성</span>
          </button>
        </HelpBubble>
      </div>

      <HelpBubble
        tier="SOME"
        mode="inline"
        text="수강 중인 과목별 질문을 모아봐요."
        className="mb-help-bubble"
      />

      {/* 과목 검색·필터·정렬 바 */}
      <div className="course-tabs-toolbar">
        <div className="course-search-input-wrap" style={{ flex: '1 1 180px', marginBottom: 0 }}>
          <Search size={13} className="search-icon" />
          <input
            type="text"
            className="form-input course-search-input"
            placeholder="과목 검색 (과목명·학수번호·교수명)..."
            value={courseTabQuery}
            onChange={(e) => setCourseTabQuery(e.target.value)}
            aria-label="과목 탭 검색"
          />
        </div>
        {courseSemesterOptions.length > 1 && (
          <select
            className="form-select course-picker-ctrl-select"
            value={courseTabSemester}
            onChange={(e) => setCourseTabSemester(e.target.value)}
            aria-label="과목 학기 필터"
          >
            <option value="ALL">전체 학기</option>
            {courseSemesterOptions.map((sem) => (
              <option key={sem} value={sem}>
                {formatSemesterLabel(sem)}
              </option>
            ))}
          </select>
        )}
        {courseScopeOptions.length > 1 && (
          <select
            className="form-select course-picker-ctrl-select"
            value={courseTabDept}
            onChange={(e) => setCourseTabDept(e.target.value)}
            aria-label="과목 학과 필터"
          >
            <option value="ALL">전체 학과</option>
            {courseScopeOptions.map((sc) => (
              <option key={sc} value={sc}>
                {sc}
              </option>
            ))}
          </select>
        )}
        <select
          className="form-select course-picker-ctrl-select"
          value={courseTabSort}
          onChange={(e) => setCourseTabSort(e.target.value)}
          aria-label="과목 정렬"
        >
          <option value="semester_desc">최신 학기순</option>
          <option value="name_asc">과목명순</option>
          <option value="code_asc">학수번호순</option>
        </select>
      </div>

      {/* 과목 탭 필터 */}
      <div className="course-tabs-bar">
        <button
          type="button"
          className={`course-tab ${selectedCourseId === 'ALL' ? 'active' : ''}`}
          onClick={() => setSelectedCourseId('ALL')}
        >
          <BookOpen size={15} />
          <span>전체 강좌 ({questions.length})</span>
        </button>
        {(courseTabQuery.trim() || courseTabSemester !== 'ALL' || courseTabDept !== 'ALL'
          ? visibleTabCourses.slice(0, 24)
          : visibleTabCourses.slice(0, 16)
        ).map((course) => {
          const lineage = collectLinkedCourseIds(course.id, allCourses);
          const count = questions.filter(
            (q) => (q.courseId && lineage.has(q.courseId)) || q.courseName === course.name
          ).length;
          return (
            <button
              type="button"
              key={course.id}
              className={`course-tab ${
                selectedCourseId === course.id ? 'active' : ''
              }`}
              onClick={() => setSelectedCourseId(course.id)}
            >
              <span>{course.name}</span>
              <span className="tab-count">{count}</span>
            </button>
          );
        })}
      </div>

      {/* 검색 및 필터 컨트롤 바 */}
      <HelpBubble
        tier="ALL"
        mode="inline"
        text="검색어, 카테고리, 상태, '내 질문만' 필터로 원하는 질문을 찾아요."
        className="mb-help-bubble"
      />
      <div className="filter-bar">
        <div className="search-input-wrap">
          <Search size={16} className="search-icon" />
          <input
            type="text"
            className="search-input"
            placeholder="질문 제목, 본문, 과목명, 담당교수 검색..."
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

          <div className="secondary-filters">
            <select
              className="status-select"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="ALL">모든 상태</option>
              <option value="UNANSWERED">진행 중 (미답변)</option>
              <option value="ANSWERED">답변 완료</option>
            </select>

            <label className="mine-checkbox-label">
              <input
                type="checkbox"
                checked={onlyMine}
                onChange={(e) => setOnlyMine(e.target.checked)}
              />
              <span>내 질문만</span>
            </label>
          </div>
        </div>
      </div>

      {/* 질문 테이블 헤더 */}
      <HelpBubble
        tier="ALL"
        mode="inline"
        text="질문을 누르면 답변과 추가 질문을 보고, 본인 질문은 수정·삭제하거나 AI 초안을 확인할 수 있어요."
        className="mb-help-bubble"
      />
      <div className="forum-table-header">
        <div className="forum-col-topic">질문</div>
        <div className="forum-col-users">참여자</div>
        <div className="forum-col-replies">답변 / 글</div>
        <div className="forum-col-activity">최근 활동</div>
      </div>

      {/* 토픽 스트림 리스트 */}
      <div className="forum-topic-list">
        {filteredQuestions.length === 0 ? (
          <div className="empty-state-box">
            <MessageSquarePlus size={32} className="text-muted" />
            <h4>표시할 질문이 없습니다</h4>
            <button
              type="button"
              className="btn btn-outline btn-sm"
              onClick={() => {
                setSelectedCourseId('ALL');
                setSelectedCategory('ALL');
                setStatusFilter('ALL');
                setOnlyMine(false);
                setSearchQuery('');
              }}
            >
              <Filter size={14} /> 필터 초기화
            </button>
          </div>
        ) : (
          filteredQuestions.map((q) => (
            <QuestionCard
              key={q.id}
              question={q}
              isSelected={q.id === selectedQuestionId}
              onClick={() => onSelectQuestion(q.id)}
              onToggleHide={onToggleHideQuestion}
              onDelete={onDeleteQuestion}
            />
          ))
        )}
      </div>
    </div>
  );
};

export default QuestionList;
