// src/App.jsx
// 광운대학교 학업 Q&A 센터 메인 애플리케이션 (반응형 자동 감지)

import React, { useState, useEffect, useCallback } from 'react';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { NotificationProvider, useNotifications } from './context/NotificationContext';
import ErrorBoundary from './components/common/ErrorBoundary';
import Header from './components/common/Header';
import BottomNav from './components/common/BottomNav';
import QuestionList from './components/student/QuestionList';
import QuestionForm from './components/student/QuestionForm';
import QuestionDetail from './components/student/QuestionDetail';
import ProfessorDashboard from './components/professor/ProfessorDashboard';
import SettingsModal from './components/shared/SettingsModal';
import { api } from './services/api';
import './App.css';

function MainApp() {
  const { currentUser, isProfessor } = useAuth();
  const { loadNotifications } = useNotifications();

  const [questions, setQuestions] = useState([]);
  const [selectedQuestionId, setSelectedQuestionId] = useState(null);
  const [currentView, setCurrentView] = useState('list'); // 'list', 'detail', 'new'
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [toastMessage, setToastMessage] = useState('');

  // 화면 너비 기준 PC / 모바일 환경 자동 감지 (768px 이하 모바일 레이아웃)
  const [isMobile, setIsMobile] = useState(() => typeof window !== 'undefined' && window.innerWidth <= 768);

  useEffect(() => {
    const handleResize = () => {
      setIsMobile(window.innerWidth <= 768);
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // 질문 목록 로드 (현재 사용자 ID 전달)
  const fetchQuestions = useCallback(async () => {
    try {
      const data = await api.getQuestions(currentUser?.id);
      setQuestions(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load questions', err);
      setQuestions([]);
    }
  }, [currentUser?.id]);

  useEffect(() => {
    fetchQuestions();
  }, [fetchQuestions]);

  // 사용자 전환 시 목록 뷰로 리셋
  useEffect(() => {
    setCurrentView('list');
    setSelectedQuestionId(null);
  }, [currentUser?.id]);

  const showToast = (msg) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage('');
    }, 3200);
  };

  const handleSelectQuestion = (id) => {
    setSelectedQuestionId(id);
    setCurrentView('detail');
  };

  const handleQuestionCreated = (newQ) => {
    if (!newQ) return;
    setQuestions((prev) => [newQ, ...(Array.isArray(prev) ? prev : [])]);
    setSelectedQuestionId(newQ.id);
    setCurrentView('detail');
    loadNotifications(true);
    showToast('질문이 등록되었습니다.');
  };

  const handleUpdateQuestion = (updatedQ, toastMsg) => {
    if (!updatedQ) return;
    setQuestions((prev) =>
      (Array.isArray(prev) ? prev : []).map((q) => (q.id === updatedQ.id ? updatedQ : q))
    );
    loadNotifications(true);
    showToast(toastMsg || '수정되었습니다.');
  };

  const safeQuestions = Array.isArray(questions) ? questions : [];
  const selectedQuestion = safeQuestions.find((q) => q.id === selectedQuestionId);

  return (
    <div className={`app-root ${isMobile ? 'is-mobile' : 'is-desktop'}`}>
      {/* 알림 토스트 */}
      {toastMessage && (
        <div className="toast-notification">
          <span>{toastMessage}</span>
        </div>
      )}

      <div className="app-shell">
        <Header
          onGoHome={() => setCurrentView('list')}
          onOpenNewQuestion={() => setCurrentView('new')}
          onSelectQuestion={handleSelectQuestion}
          currentView={currentView}
          isMobile={isMobile}
          onResetData={fetchQuestions}
        />

        <main className={`main-content-area ${isMobile ? 'mobile-main-content' : ''}`}>
          {currentView === 'detail' && selectedQuestion ? (
            <QuestionDetail
              question={selectedQuestion}
              onBack={() => setCurrentView('list')}
              onUpdateQuestion={handleUpdateQuestion}
            />
          ) : currentView === 'new' ? (
            <QuestionForm
              onQuestionCreated={handleQuestionCreated}
              onCancel={() => setCurrentView('list')}
            />
          ) : isProfessor ? (
            <ProfessorDashboard
              questions={safeQuestions}
              onSelectQuestion={handleSelectQuestion}
              onRefreshQuestions={fetchQuestions}
            />
          ) : (
            <QuestionList
              questions={safeQuestions}
              onSelectQuestion={handleSelectQuestion}
              onOpenNewQuestion={() => setCurrentView('new')}
            />
          )}
        </main>

        {/* 모바일 환경 전용 네이티브 하단 탭바 */}
        {isMobile && (
          <BottomNav
            currentView={currentView}
            onGoHome={() => setCurrentView('list')}
            onOpenNewQuestion={() => setCurrentView('new')}
            onOpenSettings={() => setIsSettingsOpen(true)}
          />
        )}
      </div>

      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
      />
    </div>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <AuthProvider>
        <ThemeProvider>
          <NotificationProvider>
            <MainApp />
          </NotificationProvider>
        </ThemeProvider>
      </AuthProvider>
    </ErrorBoundary>
  );
}
