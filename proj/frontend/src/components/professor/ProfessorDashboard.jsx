// src/components/professor/ProfessorDashboard.jsx
import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useNotifications } from '../../context/NotificationContext';
import QuestionCard from '../shared/QuestionCard';
import NotificationSettingsModal from './NotificationSettingsModal';
import CategorySettingsModal from './CategorySettingsModal';
import {
  Clock,
  SlidersHorizontal,
  AlertCircle,
  Users,
  BookOpen,
  Filter,
  Inbox
} from 'lucide-react';

export default function ProfessorDashboard({
  questions,
  onSelectQuestion,
  _onRefreshQuestions
}) {
  const { currentUser } = useAuth();
  const { isCurrentlyInDnd } = useNotifications();

  const [activeTab, setActiveTab] = useState('all'); // 'all', 'primary', 'co-course', 'unanswered'
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [isNotifModalOpen, setIsNotifModalOpen] = useState(false);
  const [isCatModalOpen, setIsCatModalOpen] = useState(false);

  // 현재 교수의 강의 과목명 및 그룹 목록
  const myCourses = currentUser?.courses || [];
  const myCourseNames = myCourses.map((c) => c.name);
  const myCourseGroups = myCourses.map((c) => c.group).filter(Boolean);
  const safeQuestions = Array.isArray(questions) ? questions : [];

  // 강의명이 같거나 다른 강의이지만 그룹이 같은지 판별
  const isRelatedCourseOrGroup = (q) => {
    if (myCourseNames.includes(q.courseName)) return true;
    if (q.courseGroup && myCourseGroups.includes(q.courseGroup)) return true;
    return false;
  };

  // 1. 직접 배정된 질문
  const myAssignedQuestions = safeQuestions.filter((q) => q.profId === currentUser?.id);

  // 2. 같은 과목 또는 동일 교과목군 다른 분반의 미답변 질문 (답변 지원 대상)
  const sameGroupQuestions = safeQuestions.filter(
    (q) =>
      q.profId !== currentUser?.id &&
      isRelatedCourseOrGroup(q) &&
      q.category === 'lecture'
  );

  // 3. 미답변 질문
  const unansweredQuestions = safeQuestions.filter(
    (q) =>
      (q.profId === currentUser?.id ||
        (isRelatedCourseOrGroup(q) && q.category === 'lecture')) &&
      (!q.answers || q.answers.length === 0)
  );

  // 탭별 필터링
  let filtered = safeQuestions.filter((q) => {
    return isRelatedCourseOrGroup(q) || q.profId === currentUser?.id;
  });

  if (activeTab === 'primary') {
    filtered = myAssignedQuestions;
  } else if (activeTab === 'co-course') {
    filtered = sameGroupQuestions;
  } else if (activeTab === 'unanswered') {
    filtered = unansweredQuestions;
  }

  if (selectedCategory !== 'all') {
    filtered = filtered.filter((q) => q.category === selectedCategory);
  }

  const inDnd = isCurrentlyInDnd();

  return (
    <div className="professor-dashboard">
      {/* 대시보드 상단 프로필 및 환영 카드 */}
      <div className="prof-welcome-card">
        <div className="prof-welcome-left">
          <div className="prof-avatar-box">{currentUser.avatar}</div>
          <div className="prof-info-box">
            <div className="prof-name-badge">
              <span className="prof-name">{currentUser.name}</span>
              <span className="prof-badge">{currentUser.title}</span>
              <span className="prof-dept">{currentUser.department}</span>
            </div>
            <p className="prof-courses-list">
              담당 강좌:{' '}
              {currentUser.courses?.map((c) => `${c.name} (${c.section})`).join(', ')}
            </p>
          </div>
        </div>

        {/* 교수 전용 빠른 제어 버튼 */}
        <div className="prof-quick-controls">
          <button
            className={`prof-control-btn ${inDnd ? 'dnd-on' : ''}`}
            onClick={() => setIsNotifModalOpen(true)}
            title="방해 금지 시간대 및 알림 설정"
          >
            <Clock size={16} />
            <span>
              {inDnd ? '방해금지 켜짐' : '알림 시간대 설정'}
            </span>
          </button>

          <button
            className="prof-control-btn"
            onClick={() => setIsCatModalOpen(true)}
            title="카테고리별 질문 접수 활성화/비활성화"
          >
            <SlidersHorizontal size={16} />
            <span>카테고리 접수 관리</span>
          </button>
        </div>
      </div>

      {/* 요약 통계 카드 */}
      <div className="prof-stats-grid">
        <div
          className={`stat-card ${activeTab === 'unanswered' ? 'highlight' : ''}`}
          onClick={() => setActiveTab('unanswered')}
        >
          <div className="stat-icon warn">
            <AlertCircle size={20} />
          </div>
          <div className="stat-content">
            <span className="stat-label">답변 대기 질문</span>
            <span className="stat-value">{unansweredQuestions.length}건</span>
          </div>
        </div>

        <div
          className={`stat-card ${activeTab === 'primary' ? 'highlight' : ''}`}
          onClick={() => setActiveTab('primary')}
        >
          <div className="stat-icon primary">
            <BookOpen size={20} />
          </div>
          <div className="stat-content">
            <span className="stat-label">내 배정 질문</span>
            <span className="stat-value">{myAssignedQuestions.length}건</span>
          </div>
        </div>

        <div
          className={`stat-card ${activeTab === 'co-course' ? 'highlight' : ''}`}
          onClick={() => setActiveTab('co-course')}
        >
          <div className="stat-icon info">
            <Users size={20} />
          </div>
          <div className="stat-content">
            <span className="stat-label">동일 과목/교과목군</span>
            <span className="stat-value">{sameGroupQuestions.length}건</span>
          </div>
        </div>

        <div
          className={`stat-card ${activeTab === 'all' ? 'highlight' : ''}`}
          onClick={() => setActiveTab('all')}
        >
          <div className="stat-icon normal">
            <Inbox size={20} />
          </div>
          <div className="stat-content">
            <span className="stat-label">전체 관련 질문</span>
            <span className="stat-value">{filtered.length}건</span>
          </div>
        </div>
      </div>

      {/* 탭 네비게이션 및 카테고리 필터 */}
      <div className="dashboard-filters-bar">
        <div className="filter-tabs">
          <button
            className={`filter-tab ${activeTab === 'all' ? 'active' : ''}`}
            onClick={() => setActiveTab('all')}
          >
            전체 질문 ({questions.length})
          </button>
          <button
            className={`filter-tab ${activeTab === 'primary' ? 'active' : ''}`}
            onClick={() => setActiveTab('primary')}
          >
            내 배정 질문 ({myAssignedQuestions.length})
          </button>
          <button
            className={`filter-tab ${activeTab === 'co-course' ? 'active' : ''}`}
            onClick={() => setActiveTab('co-course')}
          >
            동일 과목/교과목군 ({sameGroupQuestions.length})
          </button>
          <button
            className={`filter-tab ${activeTab === 'unanswered' ? 'active' : ''}`}
            onClick={() => setActiveTab('unanswered')}
          >
            답변 대기 ({unansweredQuestions.length})
          </button>
        </div>

        <div className="category-filter-select">
          <Filter size={14} />
          <select
            value={selectedCategory}
            onChange={(e) => setSelectedCategory(e.target.value)}
            className="cat-select"
          >
            <option value="all">모든 카테고리</option>
            <option value="grade">성적</option>
            <option value="assignment">과제</option>
            <option value="lecture">강의</option>
          </select>
        </div>
      </div>

      {/* 질문 목록 그리드 */}
      <div className="dashboard-questions-container">
        {filtered.length === 0 ? (
          <div className="empty-state-box">
            <Inbox size={36} />
            <p>해당 조건에 일치하는 질문이 없습니다.</p>
          </div>
        ) : (
          <div className="questions-grid">
            {filtered.map((q) => (
              <QuestionCard
                key={q.id}
                question={q}
                onClick={() => onSelectQuestion(q.id)}
              />
            ))}
          </div>
        )}
      </div>

      {/* 모달 창들 */}
      <NotificationSettingsModal
        isOpen={isNotifModalOpen}
        onClose={() => setIsNotifModalOpen(false)}
      />

      <CategorySettingsModal
        isOpen={isCatModalOpen}
        onClose={() => setIsCatModalOpen(false)}
      />
    </div>
  );
}
