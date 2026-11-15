// src/context/ThemeContext.jsx
import React, { createContext, useContext, useState, useEffect } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';

const ThemeContext = createContext(null);

const VALID_THEMES = ['light', 'dark', 'green', 'green-dark'];
const VALID_HELP_LEVELS = ['NONE', 'SOME', 'ALL'];

export const ThemeProvider = ({ children }) => {
  const { currentUser } = useAuth();
  const [theme, setTheme] = useState(() => {
    const saved = localStorage.getItem('prof_qa_theme_mode');
    return VALID_THEMES.includes(saved) ? saved : 'light';
  });
  const [fontSize, setFontSize] = useState(() => {
    return localStorage.getItem('prof_qa_font_size') || 'md';
  });
  const [helpLevel, setHelpLevel] = useState(() => {
    const saved = (localStorage.getItem('prof_qa_help_level') || 'SOME').toUpperCase();
    return VALID_HELP_LEVELS.includes(saved) ? saved : 'SOME';
  });
  const [exhibitionMode, setExhibitionMode] = useState(() => {
    return localStorage.getItem('prof_qa_exhibition_mode') === 'true';
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
        if (userSettings.helpLevel) {
          const upperHelp = String(userSettings.helpLevel).toUpperCase();
          if (VALID_HELP_LEVELS.includes(upperHelp)) {
            setHelpLevel(upperHelp);
            localStorage.setItem('prof_qa_help_level', upperHelp);
          }
        }
        if (typeof userSettings.exhibitionMode === 'boolean') {
          const hasLocalPref = localStorage.getItem('prof_qa_exhibition_mode') !== null;
          if (userSettings.exhibitionMode) {
            setExhibitionMode(true);
            localStorage.setItem('prof_qa_exhibition_mode', 'true');
          } else if (!hasLocalPref) {
            setExhibitionMode(false);
            localStorage.setItem('prof_qa_exhibition_mode', 'false');
          }
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

  const updateHelpLevel = async (newLevel) => {
    const upper = String(newLevel || 'SOME').toUpperCase();
    const normalized = VALID_HELP_LEVELS.includes(upper) ? upper : 'SOME';
    setHelpLevel(normalized);
    localStorage.setItem('prof_qa_help_level', normalized);
    if (currentUser?.id) {
      const updated = await api.updateSettings(currentUser.id, { helpLevel: normalized });
      setSettings(updated);
    }
  };

  const updateExhibitionMode = async (enabled) => {
    const normalized = Boolean(enabled);
    setExhibitionMode(normalized);
    localStorage.setItem('prof_qa_exhibition_mode', String(normalized));
    if (currentUser?.id) {
      const updated = await api.updateSettings(currentUser.id, { exhibitionMode: normalized });
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
    if (partialSettings.helpLevel) {
      const upper = String(partialSettings.helpLevel).toUpperCase();
      if (VALID_HELP_LEVELS.includes(upper)) {
        setHelpLevel(upper);
        localStorage.setItem('prof_qa_help_level', upper);
      }
    }
    if (typeof partialSettings.exhibitionMode === 'boolean') {
      setExhibitionMode(partialSettings.exhibitionMode);
      localStorage.setItem('prof_qa_exhibition_mode', String(partialSettings.exhibitionMode));
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
        helpLevel,
        exhibitionMode,
        settings,
        toggleColorScheme,
        toggleBrightness,
        toggleTheme,
        updateTheme,
        updateFontSize,
        updateHelpLevel,
        updateExhibitionMode,
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
