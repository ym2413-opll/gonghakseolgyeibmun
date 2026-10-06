// src/context/ThemeContext.jsx
import React, { createContext, useContext, useState, useEffect } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';

const ThemeContext = createContext();

export function ThemeProvider({ children }) {
  const { currentUser } = useAuth();
  const [theme, setTheme] = useState('light');
  const [fontSize, setFontSize] = useState('md'); // 'sm' (14px), 'md' (16px), 'lg' (18px), 'xl' (20px)
  const [isLoadingSettings, setIsLoadingSettings] = useState(true);

  // 사용자 변경 및 최초 마운트 시 백엔드에서 설정값 요청
  useEffect(() => {
    let isMounted = true;
    async function loadUserSettings() {
      setIsLoadingSettings(true);
      try {
        const settings = await api.getSettings(currentUser.id);
        if (isMounted && settings) {
          if (settings.theme) setTheme(settings.theme);
          if (settings.fontSize) setFontSize(settings.fontSize);
        }
      } catch (err) {
        console.error('Failed to load theme settings from backend', err);
      } finally {
        if (isMounted) setIsLoadingSettings(false);
      }
    }

    loadUserSettings();
    return () => {
      isMounted = false;
    };
  }, [currentUser.id]);

  // DOM에 테마 및 폰트 크기 속성 반영
  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-font-size', fontSize);
  }, [theme, fontSize]);

  const toggleTheme = async () => {
    const newTheme = theme === 'light' ? 'dark' : 'light';
    setTheme(newTheme);
    await api.updateSettings(currentUser.id, { theme: newTheme });
  };

  const changeFontSize = async (newSize) => {
    setFontSize(newSize);
    await api.updateSettings(currentUser.id, { fontSize: newSize });
  };

  return (
    <ThemeContext.Provider
      value={{
        theme,
        fontSize,
        toggleTheme,
        changeFontSize,
        isLoadingSettings
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
}
