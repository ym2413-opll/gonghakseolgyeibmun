// src/components/common/BottomNav.jsx
import { useAuth } from '../../context/AuthContext';
import { Home, PlusCircle, Inbox, Settings } from 'lucide-react';

export default function BottomNav({
  currentView,
  onGoHome,
  onOpenNewQuestion,
  onOpenSettings
}) {
  const { isProfessor } = useAuth();

  return (
    <nav className="bottom-nav-bar">
      <button
        className={`bottom-nav-item ${currentView === 'list' ? 'active' : ''}`}
        onClick={onGoHome}
      >
        <Home size={20} />
        <span>홈</span>
      </button>

      {!isProfessor ? (
        <button
          className={`bottom-nav-item ${currentView === 'new' ? 'active' : ''}`}
          onClick={onOpenNewQuestion}
        >
          <PlusCircle size={22} className="accent-icon" />
          <span>질문하기</span>
        </button>
      ) : (
        <button
          className={`bottom-nav-item ${currentView === 'list' ? 'active' : ''}`}
          onClick={onGoHome}
        >
          <Inbox size={20} />
          <span>대시보드</span>
        </button>
      )}

      <button
        className="bottom-nav-item"
        onClick={onOpenSettings}
      >
        <Settings size={20} />
        <span>설정</span>
      </button>
    </nav>
  );
}
