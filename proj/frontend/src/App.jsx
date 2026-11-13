// src/App.jsx
// 광운대학교 학업 Q&A 포럼 메인 애플리케이션 (R3 Overhaul: 서양식 포럼 / 3단계 반응형 / 블러 인증 게이트 / 관리 모드)

import React, { useState, useEffect, useCallback } from 'react';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { NotificationProvider } from './context/NotificationContext';
import ErrorBoundary from './components/common/ErrorBoundary';
import Header from './components/common/Header';
import BottomNav from './components/common/BottomNav';
import AuthGateOverlay from './components/common/AuthGateOverlay';
import QuestionList from './components/questions/QuestionList';
import QuestionDetail from './components/questions/QuestionDetail';
import QuestionForm from './components/student/QuestionForm';
import ProfessorDashboard from './components/professor/ProfessorDashboard';
import SettingsModal from './components/common/SettingsModal';
import NotificationSettingsModal from './components/professor/NotificationSettingsModal';
import AdminModal from './components/admin/AdminModal';
import { api } from './services/api';
import './App.css';

function getDeviceTier(width) {
  if (width <= 640) return 'smartphone';
  if (width <= 1024) return 'tablet'; // 태블릿 + 스마트폰 가로모드 + Galaxy Z Fold 펼침 화면
  return 'desktop';
}

function MainApp() {
  const { currentUser, isAuthenticated, isStudent, isProfessor, isAdmin } = useAuth();

  const [questions, setQuestions] = useState([]);
  const [selectedQuestionId, setSelectedQuestionId] = useState(null);
  const [selectedQuestionDetail, setSelectedQuestionDetail] = useState(null);
  const [currentView, setCurrentView] = useState('list'); // 'list' | 'detail' | 'new'

  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isProfSettingsOpen, setIsProfSettingsOpen] = useState(false);
  const [isAdminModalOpen, setIsAdminModalOpen] = useState(false);
  const [toastMessage, setToastMessage] = useState('');
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  // 3단계 반응형 디바이스 티어 감지 (Smartphone / Tablet+ZFold / Desktop)
  const [deviceTier, setDeviceTier] = useState(() =>
    typeof window !== 'undefined' ? getDeviceTier(window.innerWidth) : 'desktop'
  );

  useEffect(() => {
    const handleResize = () => {
      setDeviceTier(getDeviceTier(window.innerWidth));
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const showToast = useCallback((msg) => {
    setToastMessage(msg);
    setTimeout(() => {
      setToastMessage('');
    }, 3200);
  }, []);

  const fetchQuestions = useCallback(async () => {
    try {
      const data = await api.getQuestions(currentUser?.id);
      setQuestions(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load questions:', err);
      setQuestions([]);
    }
  }, [currentUser?.id]);

  const fetchSelectedDetail = useCallback(
    async (qid) => {
      if (!qid) {
        setSelectedQuestionDetail(null);
        return;
      }
      try {
        const detail = await api.getQuestion(qid, currentUser?.id);
        setSelectedQuestionDetail(detail);
      } catch (err) {
        console.warn('Failed to load question detail:', err);
      }
    },
    [currentUser?.id]
  );

  useEffect(() => {
    fetchQuestions();
  }, [fetchQuestions, isAuthenticated, isAdmin]);

  useEffect(() => {
    if (selectedQuestionId) {
      fetchSelectedDetail(selectedQuestionId);
    } else {
      setSelectedQuestionDetail(null);
    }
  }, [selectedQuestionId, fetchSelectedDetail, isAdmin]);

  // 계정 전환 또는 로그아웃 시 작성 중이던 화면·상세·모달 상태를 초기화하여 권한 혼선 방지
  useEffect(() => {
    setCurrentView('list');
    setSelectedQuestionId(null);
    setSelectedQuestionDetail(null);
    setIsSettingsOpen(false);
    setIsProfSettingsOpen(false);
    setIsAdminModalOpen(false);
  }, [currentUser?.id, currentUser?.role, isAuthenticated]);

  const handleSelectQuestion = (id) => {
    if (!id) {
      setSelectedQuestionId(null);
      setSelectedQuestionDetail(null);
      setCurrentView('list');
      return;
    }
    setSelectedQuestionId(id);
    setCurrentView('detail');
  };

  const handleQuestionCreated = async (newQ) => {
    if (!newQ) return;
    await fetchQuestions();
    setSelectedQuestionId(newQ.id);
    setCurrentView('detail');
    setRefreshTrigger((prev) => prev + 1);
    showToast('새 포럼 질문 토픽이 등록되었습니다. (담당 교수님 이메일 알림 발송)');
  };

  const handleUpdateQuestion = async (questionId, updatedFields) => {
    const updated = await api.updateQuestion(questionId, updatedFields, currentUser?.id);
    await fetchQuestions();
    await fetchSelectedDetail(questionId);
    showToast('토픽 내용이 수정되었습니다.');
    return updated;
  };

  const handleAddAnswer = async (questionId, answerPayload) => {
    await api.createAnswer(questionId, answerPayload);
    await fetchQuestions();
    await fetchSelectedDetail(questionId);
    setRefreshTrigger((prev) => prev + 1);
    showToast('스레드 답변 포스트가 등록되었습니다.');
  };

  const handleAddFollowUp = async (questionId, followUpPayload) => {
    await api.createFollowUp(questionId, followUpPayload);
    await fetchQuestions();
    await fetchSelectedDetail(questionId);
    setRefreshTrigger((prev) => prev + 1);
    showToast('추가 질문(꼬리질문)이 등록되었습니다.');
  };

  const handleToggleHideQuestion = async (q) => {
    await api.toggleHideQuestion(q.id, !q.isHidden);
    await fetchQuestions();
    if (selectedQuestionId === q.id) {
      await fetchSelectedDetail(q.id);
    }
    showToast(
      !q.isHidden
        ? '해당 질문 토픽이 가리기(비표시) 처리되었습니다.'
        : '질문 토픽 가리기가 해제되었습니다.'
    );
  };

  const handleToggleHideAnswer = async (ans, questionId) => {
    await api.toggleHideAnswer(ans.id, !ans.isHidden, questionId);
    await fetchQuestions();
    if (questionId) {
      await fetchSelectedDetail(questionId);
    }
    showToast(
      !ans.isHidden
        ? '해당 답변이 가리기(비표시) 처리되었습니다.'
        : '답변 가리기가 해제되었습니다.'
    );
  };

  const handleDeleteQuestion = async (q) => {
    if (!q?.id) return;
    if (!window.confirm('이 질문 게시물을 영구 삭제하시겠습니까?')) return;
    try {
      await api.deleteQuestion(q.id);
      if (selectedQuestionId === q.id) {
        setSelectedQuestionId(null);
        setSelectedQuestionDetail(null);
        setCurrentView('list');
      }
      await fetchQuestions();
      showToast('질문이 영구 삭제되었습니다.');
    } catch (err) {
      showToast(err.message || '질문 삭제에 실패했습니다.');
    }
  };

  const handleDeleteAnswer = async (ans, questionId) => {
    if (!ans?.id) return;
    if (!window.confirm('이 답변을 영구 삭제하시겠습니까?')) return;
    try {
      await api.deleteAnswer(ans.id, questionId);
      await fetchQuestions();
      if (questionId) {
        await fetchSelectedDetail(questionId);
      }
      showToast('답변이 영구 삭제되었습니다.');
    } catch (err) {
      showToast(err.message || '답변 삭제에 실패했습니다.');
    }
  };

  const safeQuestions = Array.isArray(questions) ? questions : [];
  const activeQuestion =
    selectedQuestionDetail || safeQuestions.find((q) => q.id === selectedQuestionId) || null;

  const isDesktopSplit = deviceTier === 'desktop' && currentView !== 'new';

  return (
    <div
      className={`app-root tier-${deviceTier} ${
        deviceTier === 'smartphone' ? 'is-mobile' : 'is-desktop'
      }`}
    >
      {toastMessage && (
        <div className="toast-notification">
          <span>{toastMessage}</span>
        </div>
      )}

      <div className="app-shell">
        <Header
          onOpenSettings={() => setIsSettingsOpen(true)}
          onOpenProfSettings={() => setIsProfSettingsOpen(true)}
          onOpenAdminModal={() => setIsAdminModalOpen(true)}
          onSelectQuestion={handleSelectQuestion}
          refreshTrigger={refreshTrigger}
        />

        {/* 헤더 아래 포럼 영역: 비로그인 시 블러 처리 + 로그인/회원가입 오버레이 표시 */}
        <div className="forum-stage-wrapper">
          <main
            className={`main-content-area forum-main-stage ${
              !isAuthenticated ? 'is-blurred-locked' : ''
            }`}
            aria-hidden={!isAuthenticated}
          >
            {currentView === 'new' && isAuthenticated && isStudent ? (
              <QuestionForm
                onQuestionCreated={handleQuestionCreated}
                onCancel={() => setCurrentView('list')}
              />
            ) : isDesktopSplit ? (
              <div className="forum-desktop-split">
                <div className="forum-split-left">
                  {isProfessor ? (
                    <ProfessorDashboard
                      questions={safeQuestions}
                      selectedQuestionId={selectedQuestionId}
                      onSelectQuestion={handleSelectQuestion}
                      onOpenProfSettings={() => setIsProfSettingsOpen(true)}
                      onToggleHideQuestion={handleToggleHideQuestion}
                      onDeleteQuestion={handleDeleteQuestion}
                    />
                  ) : (
                    <QuestionList
                      questions={safeQuestions}
                      selectedQuestionId={selectedQuestionId}
                      onSelectQuestion={handleSelectQuestion}
                      onOpenNewQuestion={() => setCurrentView('new')}
                      onToggleHideQuestion={handleToggleHideQuestion}
                      onDeleteQuestion={handleDeleteQuestion}
                    />
                  )}
                </div>
                <div className="forum-split-right">
                  <QuestionDetail
                    question={activeQuestion}
                    onAddAnswer={handleAddAnswer}
                    onAddFollowUp={handleAddFollowUp}
                    onUpdateQuestion={handleUpdateQuestion}
                    onToggleHideQuestion={handleToggleHideQuestion}
                    onToggleHideAnswer={handleToggleHideAnswer}
                    onDeleteQuestion={handleDeleteQuestion}
                    onDeleteAnswer={handleDeleteAnswer}
                  />
                </div>
              </div>
            ) : currentView === 'detail' && activeQuestion ? (
              <QuestionDetail
                question={activeQuestion}
                onAddAnswer={handleAddAnswer}
                onAddFollowUp={handleAddFollowUp}
                onUpdateQuestion={handleUpdateQuestion}
                onToggleHideQuestion={handleToggleHideQuestion}
                onToggleHideAnswer={handleToggleHideAnswer}
                onDeleteQuestion={handleDeleteQuestion}
                onDeleteAnswer={handleDeleteAnswer}
                onBack={() => setCurrentView('list')}
              />
            ) : isProfessor ? (
              <ProfessorDashboard
                questions={safeQuestions}
                selectedQuestionId={selectedQuestionId}
                onSelectQuestion={handleSelectQuestion}
                onOpenProfSettings={() => setIsProfSettingsOpen(true)}
                onToggleHideQuestion={handleToggleHideQuestion}
                onDeleteQuestion={handleDeleteQuestion}
              />
            ) : (
              <QuestionList
                questions={safeQuestions}
                selectedQuestionId={selectedQuestionId}
                onSelectQuestion={handleSelectQuestion}
                onOpenNewQuestion={() => setCurrentView('new')}
                onToggleHideQuestion={handleToggleHideQuestion}
                onDeleteQuestion={handleDeleteQuestion}
              />
            )}
          </main>

          {/* 비로그인 상태일 때 블러 처리된 영역 위에 표시되는 인증 유도 오버레이 */}
          {!isAuthenticated && <AuthGateOverlay />}
        </div>

        {/* 스마트폰 환경 전용 하단 내비게이션 바 */}
        {deviceTier === 'smartphone' && isAuthenticated && (
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
        onCoursesUpdated={fetchQuestions}
      />

      <NotificationSettingsModal
        isOpen={isProfSettingsOpen}
        onClose={() => setIsProfSettingsOpen(false)}
        onCoursesUpdated={fetchQuestions}
      />

      <AdminModal
        isOpen={isAdminModalOpen}
        onClose={() => setIsAdminModalOpen(false)}
        questions={safeQuestions}
        onDataChanged={fetchQuestions}
        onDeleteQuestion={handleDeleteQuestion}
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
