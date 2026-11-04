// src/components/student/QuestionList.jsx
import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import QuestionCard from '../shared/QuestionCard';
import { Plus, Search, Filter, Inbox } from 'lucide-react';

export default function QuestionList({
  questions,
  onSelectQuestion,
  onOpenNewQuestion
}) {
  const { currentUser } = useAuth();
  const [activeTab, setActiveTab] = useState('all'); // 'all', 'mine', 'answered'
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');

  const safeQuestions = Array.isArray(questions) ? questions : [];

  // 1. 내 질문
  const myQuestions = safeQuestions.filter((q) => q.isMyQuestion || q.studentId === currentUser?.id);

  // 2. 답변 완료된 질문
  const answeredQuestions = safeQuestions.filter((q) => q.hasAnswers || (Array.isArray(q.answers) && q.answers.length > 0));

  // 탭 필터링
  let filtered = safeQuestions;
  if (activeTab === 'mine') {
    filtered = myQuestions;
  } else if (activeTab === 'answered') {
    filtered = answeredQuestions;
  }

  // 카테고리 필터링
  if (selectedCategory !== 'all') {
    filtered = filtered.filter((q) => (q.category || '').toLowerCase() === selectedCategory.toLowerCase());
  }

  // 검색어 필터링
  if (searchQuery.trim()) {
    const q = searchQuery.toLowerCase().trim();
    filtered = filtered.filter(
      (item) =>
        item.title.toLowerCase().includes(q) ||
        item.content.toLowerCase().includes(q) ||
        item.courseName.toLowerCase().includes(q)
    );
  }

  return (
    <div className="student-question-list">
      {/* 학생 상단 정보 & 질문 작성 */}
      <div className="student-banner-card">
        <div className="student-banner-left">
          <div className="student-avatar-box">{currentUser.avatar}</div>
          <div className="student-info-meta">
            <h3>{currentUser.name}</h3>
            <p className="student-sub">
              {currentUser.department} · 수강 과목: {currentUser.courses?.map((c) => c.name).join(', ')}
            </p>
          </div>
        </div>

        <button className="btn btn-primary new-q-btn" onClick={onOpenNewQuestion}>
          <Plus size={16} />
          <span>질문 작성</span>
        </button>
      </div>

      {/* 검색 & 필터 헤더 */}
      <div className="list-controls-bar">
        <div className="search-input-wrap">
          <Search size={16} className="search-icon" />
          <input
            type="text"
            className="search-input"
            placeholder="과목명, 제목, 내용 검색"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>

        <div className="filter-group-right">
          <div className="student-tabs">
            <button
              className={`student-tab ${activeTab === 'all' ? 'active' : ''}`}
              onClick={() => setActiveTab('all')}
            >
              전체 질문 ({questions.length})
            </button>
            <button
              className={`student-tab ${activeTab === 'mine' ? 'active' : ''}`}
              onClick={() => setActiveTab('mine')}
            >
              내 질문 ({myQuestions.length})
            </button>
            <button
              className={`student-tab ${activeTab === 'answered' ? 'active' : ''}`}
              onClick={() => setActiveTab('answered')}
            >
              답변 완료 ({answeredQuestions.length})
            </button>
          </div>

          <div className="category-select-wrap">
            <Filter size={14} />
            <select
              value={selectedCategory}
              onChange={(e) => setSelectedCategory(e.target.value)}
              className="cat-select"
            >
              <option value="all">전체 카테고리</option>
              <option value="grade">성적</option>
              <option value="assignment">과제</option>
              <option value="lecture">강의</option>
            </select>
          </div>
        </div>
      </div>

      {/* 질문 카드 목록 */}
      <div className="questions-container">
        {filtered.length === 0 ? (
          <div className="empty-state-box">
            <Inbox size={36} />
            <p>검색 조건에 일치하는 질문이 없습니다.</p>
            {activeTab === 'mine' && (
              <button className="btn btn-outline" onClick={onOpenNewQuestion}>
                첫 질문 작성하러 가기
              </button>
            )}
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
    </div>
  );
}
