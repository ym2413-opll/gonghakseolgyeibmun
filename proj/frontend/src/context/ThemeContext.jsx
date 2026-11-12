// src/context/ThemeContext.jsx
import React, { createContext, useContext, useState, useEffect } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';

const ThemeContext = createContext(null);

const VALID_THEMES = ['light', 'dark', 'green'];

export const ThemeProvider = ({ children }) => {
  const { currentUser } = useAuth();
  const [theme, setTheme] = useState(() => {
    const saved = localStorage.getItem('prof_qa_theme_mode');
    return VALID_THEMES.includes(saved) ? saved : 'light';
  });
  const [fontSize, setFontSize] = useState(() => {
    return localStorage.getItem('prof_qa_font_size') || 'md';
  });
  const [settings, setSettings] = useState(null);

  useEffect(() => {
    let mounted = true;
    async function loadUserSettings() {
      if (!currentUser?.id) return;
      const userSettings = await api.getSettings(currentUser.id);
      if (mounted && userSettings) {
        setSettings(userSettings);
        if (userSettings.theme && VALID_THEMES.includes(userSettings.theme)) {
          setTheme(userSettings.theme);
          localStorage.setItem('prof_qa_theme_mode', userSettings.theme);
        }
        if (userSettings.fontSize) {
          setFontSize(userSettings.fontSize);
          localStorage.setItem('prof_qa_font_size', userSettings.fontSize);
        }
      }
    }
    loadUserSettings();
    return () => {
      mounted = false;
    };
  }, [currentUser?.id]);

  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-font-size', fontSize);
  }, [theme, fontSize]);

  const updateTheme = async (newTheme) => {
    const normalized = VALID_THEMES.includes(newTheme) ? newTheme : 'light';
    setTheme(normalized);
    localStorage.setItem('prof_qa_theme_mode', normalized);
    if (currentUser?.id) {
      const updated = await api.updateSettings(currentUser.id, { theme: normalized });
      setSettings(updated);
    }
  };

  const toggleTheme = async () => {
    const nextMap = { light: 'green', green: 'dark', dark: 'light' };
    await updateTheme(nextMap[theme] || 'light');
  };

  const updateFontSize = async (newSize) => {
    setFontSize(newSize);
    localStorage.setItem('prof_qa_font_size', newSize);
    if (currentUser?.id) {
      const updated = await api.updateSettings(currentUser.id, { fontSize: newSize });
      setSettings(updated);
    }
  };

  const updateAllSettings = async (partialSettings) => {
    if (partialSettings.theme && VALID_THEMES.includes(partialSettings.theme)) {
      setTheme(partialSettings.theme);
      localStorage.setItem('prof_qa_theme_mode', partialSettings.theme);
    }
    if (partialSettings.fontSize) {
      setFontSize(partialSettings.fontSize);
      localStorage.setItem('prof_qa_font_size', partialSettings.fontSize);
    }
    if (currentUser?.id) {
      const updated = await api.updateSettings(currentUser.id, partialSettings);
      setSettings(updated);
      return updated;
    }
    return partialSettings;
  };

  return (
    <ThemeContext.Provider
      value={{
        theme,
        fontSize,
        settings,
        toggleTheme,
        updateTheme,
        updateFontSize,
        updateAllSettings
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = () => {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within a ThemeProvider');
  }
  return context;
};
