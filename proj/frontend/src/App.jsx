// src/App.jsx
import React, { useState, useEffect } from 'react';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { NotificationProvider, useNotifications } from './context/NotificationContext';
import ErrorBoundary from './components/common/ErrorBoundary';
import Header from './components/common/Header';
import DemoBar from './components/common/DemoBar';
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
  const [viewMode, setViewMode] = useState('desktop'); // 'desktop', 'mobile'
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [toastMessage, setToastMessage] = useState('');

  // 질문 목록 로드
  const fetchQuestions = async () => {
    try {
      const data = await api.getQuestions();
      setQuestions(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load questions', err);
      setQuestions([]);
    }
  };

  useEffect(() => {
    fetchQuestions();
  }, []);

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
    showToast('질문이 성공적으로 등록되었습니다!');
  };

  const handleUpdateQuestion = (updatedQ, toastMsg) => {
    if (!updatedQ) return;
    setQuestions((prev) =>
      (Array.isArray(prev) ? prev : []).map((q) => (q.id === updatedQ.id ? updatedQ : q))
    );
    loadNotifications(true);
    showToast(toastMsg || '질문 정보가 성공적으로 반영되었습니다!');
  };

  const safeQuestions = Array.isArray(questions) ? questions : [];
  const selectedQuestion = safeQuestions.find((q) => q.id === selectedQuestionId);

  return (
    <div className={`app-root ${viewMode === 'mobile' ? 'mobile-simulator-active' : ''}`}>
      {/* 1. 최상단 전시용 제어 바 (계정 전환 / 모바일 뷰 전환 / 테마) */}
      <DemoBar
        viewMode={viewMode}
        setViewMode={setViewMode}
        onResetData={fetchQuestions}
      />

      {/* 토스트 알림 */}
      {toastMessage && (
        <div className="toast-notification">
          <span>{toastMessage}</span>
        </div>
      )}

      {/* 2. 기기 뷰 래퍼 (데스크톱 vs 모바일 프레임) */}
      <div className={`device-viewport-wrapper ${viewMode}`}>
        {viewMode === 'mobile' && (
          <div className="mobile-phone-notch">
            <div className="notch-speaker"></div>
            <div className="notch-camera"></div>
          </div>
        )}

        <div className="app-shell">
          <Header
            onGoHome={() => setCurrentView('list')}
            onOpenNewQuestion={() => setCurrentView('new')}
            onSelectQuestion={handleSelectQuestion}
            currentView={currentView}
          />

          <main className="main-content-area">
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

          {/* 모바일 뷰 전용 하단 탭바 */}
          {viewMode === 'mobile' && (
            <BottomNav
              currentView={currentView}
              onGoHome={() => setCurrentView('list')}
              onOpenNewQuestion={() => setCurrentView('new')}
              onOpenSettings={() => setIsSettingsOpen(true)}
            />
          )}
        </div>
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
