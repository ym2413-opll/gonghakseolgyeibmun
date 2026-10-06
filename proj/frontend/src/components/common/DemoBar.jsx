// src/components/common/DemoBar.jsx
import React from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { Smartphone, Monitor, Sun, Moon, RotateCcw, UserCheck, Sparkles } from 'lucide-react';
import { api } from '../../services/api';

export default function DemoBar({ viewMode, setViewMode, onResetData }) {
  const { currentUser, presetUsers, switchUser } = useAuth();
  const { theme, toggleTheme } = useTheme();

  const handleReset = async () => {
    if (window.confirm('데모 데이터를 초기 상태로 리셋하시겠습니까?')) {
      await api.resetToDefaults();
      if (onResetData) onResetData();
      window.location.reload();
    }
  };

  return (
    <aside aria-label="시연 모드 툴바" className="demo-bar">
      <div className="demo-bar-inner">
        <div className="demo-badge">
          <Sparkles size={14} className="sparkle-icon" />
          <span>전시 시연 모드</span>
        </div>

        <div className="demo-user-selector">
          <label htmlFor="demo-user-select" className="demo-label">
            <UserCheck size={14} /> 계정 전환:
          </label>
          <select
            id="demo-user-select"
            value={currentUser?.id || ''}
            onChange={(e) => switchUser(e.target.value)}
            className="demo-select"
          >
            <optgroup label="학생 계정">
              {presetUsers
                .filter((u) => u.role === 'student')
                .map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.avatar} {u.displayTag} ({u.department})
                  </option>
                ))}
            </optgroup>
            <optgroup label="교수 계정">
              {presetUsers
                .filter((u) => u.role === 'professor')
                .map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.avatar} {u.name} [{u.displayTag}]
                  </option>
                ))}
            </optgroup>
          </select>
        </div>

        <div className="demo-actions">
          {/* 플랫폼 뷰 모드 토글 (Desktop vs Mobile App) */}
          <div className="view-mode-toggle" title="플랫폼 뷰 전환 (데스크톱 웹 / 모바일 앱)">
            <button
              className={`view-btn ${viewMode === 'desktop' ? 'active' : ''}`}
              onClick={() => setViewMode('desktop')}
            >
              <Monitor size={14} />
              <span>데스크톱</span>
            </button>
            <button
              className={`view-btn ${viewMode === 'mobile' ? 'active' : ''}`}
              onClick={() => setViewMode('mobile')}
            >
              <Smartphone size={14} />
              <span>모바일 앱</span>
            </button>
          </div>

          {/* 테마 간편 토글 */}
          <button
            className="demo-icon-btn"
            onClick={toggleTheme}
            title={`테마 전환 (현재: ${theme === 'dark' ? '다크 모드' : '라이트 모드'})`}
          >
            {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
          </button>

          {/* 데이터 초기화 */}
          <button
            className="demo-icon-btn reset-btn"
            onClick={handleReset}
            title="데이터 초기화"
          >
            <RotateCcw size={14} />
            <span className="hide-on-mobile">초기화</span>
          </button>
        </div>
      </div>
    </aside>
  );
}
