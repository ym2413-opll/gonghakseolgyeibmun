// src/context/ThemeContext.jsx
import React, { createContext, useContext, useState, useEffect } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';

const ThemeContext = createContext(null);

const VALID_THEMES = ['light', 'dark', 'green', 'green-dark'];

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

  const isDark = theme === 'dark' || theme === 'green-dark';
  const isGreen = theme === 'green' || theme === 'green-dark';
  const brightness = isDark ? 'dark' : 'light';
  const colorScheme = isGreen ? 'green' : 'mono';

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
    root.setAttribute('data-brightness', brightness);
    root.setAttribute('data-color-scheme', colorScheme);
    root.setAttribute('data-font-size', fontSize);
  }, [theme, brightness, colorScheme, fontSize]);

  const updateTheme = async (newTheme) => {
    const normalized = VALID_THEMES.includes(newTheme) ? newTheme : 'light';
    setTheme(normalized);
    localStorage.setItem('prof_qa_theme_mode', normalized);
    if (currentUser?.id) {
      const updated = await api.updateSettings(currentUser.id, { theme: normalized });
      setSettings(updated);
    }
  };

  const resolveTheme = (nextColorScheme, nextBrightness) => {
    if (nextColorScheme === 'green') {
      return nextBrightness === 'dark' ? 'green-dark' : 'green';
    }
    return nextBrightness === 'dark' ? 'dark' : 'light';
  };

  const toggleColorScheme = async () => {
    await updateTheme(resolveTheme(isGreen ? 'mono' : 'green', brightness));
  };

  const toggleBrightness = async () => {
    await updateTheme(resolveTheme(colorScheme, isDark ? 'light' : 'dark'));
  };

  const toggleTheme = async () => {
    await toggleBrightness();
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
        brightness,
        colorScheme,
        isDark,
        isGreen,
        fontSize,
        settings,
        toggleColorScheme,
        toggleBrightness,
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
