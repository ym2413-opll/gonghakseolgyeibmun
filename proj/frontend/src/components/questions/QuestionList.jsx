// src/components/questions/QuestionList.jsx
import React, { useState, useMemo } from 'react';
import { useAuth } from '../../context/AuthContext';
import { CATEGORY_LIST } from '../../services/mockData';
import QuestionCard from './QuestionCard';
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
  const { currentUser, allCourses } = useAuth();

  const [selectedCourseId, setSelectedCourseId] = useState('ALL');
  const [selectedCategory, setSelectedCategory] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL'); // ALL, UNANSWERED, ANSWERED
  const [onlyMine, setOnlyMine] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  const courses = useMemo(() => {
    const raw =
      Array.isArray(currentUser?.courses) && currentUser.courses.length > 0
        ? currentUser.courses
        : allCourses;
    const map = new Map();
    (raw || []).forEach((c) => {
      if (!map.has(c.name)) map.set(c.name, c);
    });
    return Array.from(map.values());
  }, [currentUser?.courses, allCourses]);

  const filteredQuestions = useMemo(() => {
    return questions.filter((q) => {
      if (selectedCourseId !== 'ALL' && q.courseName !== selectedCourseId && q.courseId !== selectedCourseId) {
        return false;
      }
      if (selectedCategory !== 'ALL' && q.category?.toLowerCase() !== selectedCategory.toLowerCase()) {
        return false;
      }
      const ansCount = q.answerCount !== undefined ? q.answerCount : (q.answers?.length || 0);
      if (statusFilter === 'UNANSWERED' && ansCount > 0) {
        return false;
      }
      if (statusFilter === 'ANSWERED' && ansCount === 0) {
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
        <button type="button" className="btn btn-primary btn-new-topic" onClick={onOpenNewQuestion}>
          <Plus size={18} />
          <span>새 질문 작성</span>
        </button>
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
        {courses.slice(0, 12).map((course) => {
          const count = questions.filter(
            (q) => q.courseName === course.name || q.courseId === course.id
          ).length;
          return (
            <button
              type="button"
              key={course.id}
              className={`course-tab ${
                selectedCourseId === course.name ? 'active' : ''
              }`}
              onClick={() => setSelectedCourseId(course.name)}
            >
              <span>{course.name}</span>
              <span className="tab-count">{count}</span>
            </button>
          );
        })}
      </div>

      {/* 포럼 검색 및 필터 컨트롤 바 */}
      <div className="filter-bar">
        <div className="search-input-wrap">
          <Search size={16} className="search-icon" />
          <input
            type="text"
            className="search-input"
            placeholder="토픽 제목, 본문 키워드, 과목명, 담당교수 검색..."
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
              <option value="ALL">모든 토픽 상태</option>
              <option value="UNANSWERED">진행 중 (미답변)</option>
              <option value="ANSWERED">해결됨 (답변완료)</option>
            </select>

            <label className="mine-checkbox-label">
              <input
                type="checkbox"
                checked={onlyMine}
                onChange={(e) => setOnlyMine(e.target.checked)}
              />
              <span>내 토픽만</span>
            </label>
          </div>
        </div>
      </div>

      {/* 서양식 포럼 토픽 테이블 헤더 */}
      <div className="forum-table-header">
        <div className="forum-col-topic">Topic (질문 스레드)</div>
        <div className="forum-col-users">Participants</div>
        <div className="forum-col-replies">Replies / Posts</div>
        <div className="forum-col-activity">Activity</div>
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
