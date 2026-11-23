// src/context/ThemeContext.jsx
import React, { createContext, useContext, useState, useEffect } from 'react';
import { api } from '../services/api';
import { useAuth } from './AuthContext';

const ThemeContext = createContext(null);

const VALID_THEMES = ['light', 'dark', 'green', 'green-dark', 'blue-white'];
const VALID_FONT_SIZES = ['sm', 'md', 'lg', 'xl'];
const VALID_HELP_LEVELS = ['NONE', 'SOME', 'ALL'];
const BLUE_WHITE_UNLOCK_STORAGE_KEY = 'prof_qa_unlock_blue_white';

function safeStorageGet(key) {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return null;
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeStorageSet(key, value) {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return;
    window.localStorage.setItem(key, value);
  } catch {
    // Ignore storage quota or privacy-mode SecurityError
  }
}

function normalizeFontSize(value) {
  const normalized = String(value || 'md').toLowerCase();
  return VALID_FONT_SIZES.includes(normalized) ? normalized : 'md';
}

export const ThemeProvider = ({ children }) => {
  const { currentUser } = useAuth();
  const [theme, setTheme] = useState(() => {
    const saved = safeStorageGet('prof_qa_theme_mode');
    return VALID_THEMES.includes(saved) ? saved : 'light';
  });
  const [isBlueWhiteUnlocked, setIsBlueWhiteUnlocked] = useState(() => {
    return (
      safeStorageGet(BLUE_WHITE_UNLOCK_STORAGE_KEY) === 'true' ||
      safeStorageGet('prof_qa_theme_mode') === 'blue-white'
    );
  });
  const [showBlueWhiteToast, setShowBlueWhiteToast] = useState(false);
  const [fontSize, setFontSize] = useState(() => {
    return normalizeFontSize(safeStorageGet('prof_qa_font_size'));
  });
  const [helpLevel, setHelpLevel] = useState(() => {
    const saved = (safeStorageGet('prof_qa_help_level') || 'SOME').toUpperCase();
    return VALID_HELP_LEVELS.includes(saved) ? saved : 'SOME';
  });
  const [exhibitionMode, setExhibitionMode] = useState(() => {
    return safeStorageGet('prof_qa_exhibition_mode') === 'true';
  });
  const [settings, setSettings] = useState(null);

  const isDark = theme === 'dark' || theme === 'green-dark';
  const isGreen = theme === 'green' || theme === 'green-dark';
  const isBlueWhite = theme === 'blue-white';
  const brightness = isDark ? 'dark' : 'light';
  const colorScheme = isBlueWhite ? 'blue-white' : isGreen ? 'green' : 'mono';

  useEffect(() => {
    let mounted = true;
    async function loadUserSettings() {
      if (!currentUser?.id) {
        setSettings(null);
        return;
      }
      try {
        const userSettings = await api.getSettings(currentUser.id);
        if (mounted && userSettings) {
          setSettings(userSettings);
          if (userSettings.theme && VALID_THEMES.includes(userSettings.theme)) {
            setTheme(userSettings.theme);
            safeStorageSet('prof_qa_theme_mode', userSettings.theme);
            if (userSettings.theme === 'blue-white') {
              setIsBlueWhiteUnlocked(true);
              safeStorageSet(BLUE_WHITE_UNLOCK_STORAGE_KEY, 'true');
            }
          }
          if (userSettings.fontSize) {
            const safeSize = normalizeFontSize(userSettings.fontSize);
            setFontSize(safeSize);
            safeStorageSet('prof_qa_font_size', safeSize);
          }
          if (userSettings.helpLevel) {
            const upperHelp = String(userSettings.helpLevel).toUpperCase();
            if (VALID_HELP_LEVELS.includes(upperHelp)) {
              setHelpLevel(upperHelp);
              safeStorageSet('prof_qa_help_level', upperHelp);
            }
          }
          if (typeof userSettings.exhibitionMode === 'boolean') {
            const hasLocalPref = safeStorageGet('prof_qa_exhibition_mode') !== null;
            if (userSettings.exhibitionMode) {
              setExhibitionMode(true);
              safeStorageSet('prof_qa_exhibition_mode', 'true');
            } else if (!hasLocalPref) {
              setExhibitionMode(false);
              safeStorageSet('prof_qa_exhibition_mode', 'false');
            }
          }
        }
      } catch (err) {
        console.warn('Failed to load user settings:', err);
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

  const unlockBlueWhiteTheme = () => {
    if (isBlueWhiteUnlocked) return false;
    setIsBlueWhiteUnlocked(true);
    safeStorageSet(BLUE_WHITE_UNLOCK_STORAGE_KEY, 'true');
    setShowBlueWhiteToast(true);
    return true;
  };

  const checkBlueWhiteUnlock = (textValue) => {
    if (!isBlueWhiteUnlocked && typeof textValue === 'string' && textValue.includes('BlueWhite')) {
      unlockBlueWhiteTheme();
    }
  };

  useEffect(() => {
    if (!showBlueWhiteToast) return;
    const timer = setTimeout(() => setShowBlueWhiteToast(false), 7000);
    return () => clearTimeout(timer);
  }, [showBlueWhiteToast]);

  const updateTheme = async (newTheme) => {
    const normalized = VALID_THEMES.includes(newTheme) ? newTheme : 'light';
    if (normalized === 'blue-white') {
      setIsBlueWhiteUnlocked(true);
      safeStorageSet(BLUE_WHITE_UNLOCK_STORAGE_KEY, 'true');
    }
    setTheme(normalized);
    safeStorageSet('prof_qa_theme_mode', normalized);
    if (currentUser?.id) {
      try {
        const updated = await api.updateSettings(currentUser.id, { theme: normalized });
        setSettings(updated);
      } catch (err) {
        console.warn('Failed to persist theme setting:', err);
      }
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
    const normalized = normalizeFontSize(newSize);
    setFontSize(normalized);
    safeStorageSet('prof_qa_font_size', normalized);
    if (currentUser?.id) {
      try {
        const updated = await api.updateSettings(currentUser.id, { fontSize: normalized });
        setSettings(updated);
      } catch (err) {
        console.warn('Failed to persist fontSize setting:', err);
      }
    }
  };

  const updateHelpLevel = async (newLevel) => {
    const upper = String(newLevel || 'SOME').toUpperCase();
    const normalized = VALID_HELP_LEVELS.includes(upper) ? upper : 'SOME';
    setHelpLevel(normalized);
    safeStorageSet('prof_qa_help_level', normalized);
    if (currentUser?.id) {
      try {
        const updated = await api.updateSettings(currentUser.id, { helpLevel: normalized });
        setSettings(updated);
      } catch (err) {
        console.warn('Failed to persist helpLevel setting:', err);
      }
    }
  };

  const updateExhibitionMode = async (enabled) => {
    const isAdminUser = Boolean(currentUser?.isAdmin || currentUser?.role === 'admin');
    if (!isAdminUser) {
      throw new Error('전시 모드 설정은 관리자 모드에서만 변경할 수 있습니다.');
    }
    const normalized = Boolean(enabled);
    const updated = await api.updateSettings(currentUser.id, { exhibitionMode: normalized });
    setSettings(updated);
    setExhibitionMode(normalized);
    safeStorageSet('prof_qa_exhibition_mode', String(normalized));
  };

  const updateAllSettings = async (partialSettings) => {
    const payload = {};
    if (partialSettings.theme && VALID_THEMES.includes(partialSettings.theme)) {
      payload.theme = partialSettings.theme;
      setTheme(partialSettings.theme);
      safeStorageSet('prof_qa_theme_mode', partialSettings.theme);
      if (partialSettings.theme === 'blue-white') {
        setIsBlueWhiteUnlocked(true);
        safeStorageSet(BLUE_WHITE_UNLOCK_STORAGE_KEY, 'true');
      }
    }
    if (partialSettings.fontSize) {
      const safeSize = normalizeFontSize(partialSettings.fontSize);
      payload.fontSize = safeSize;
      setFontSize(safeSize);
      safeStorageSet('prof_qa_font_size', safeSize);
    }
    if (partialSettings.helpLevel) {
      const upper = String(partialSettings.helpLevel).toUpperCase();
      if (VALID_HELP_LEVELS.includes(upper)) {
        payload.helpLevel = upper;
        setHelpLevel(upper);
        safeStorageSet('prof_qa_help_level', upper);
      }
    }
    const isAdminUser = Boolean(currentUser?.isAdmin || currentUser?.role === 'admin');
    if (typeof partialSettings.exhibitionMode === 'boolean' && isAdminUser) {
      payload.exhibitionMode = partialSettings.exhibitionMode;
      setExhibitionMode(partialSettings.exhibitionMode);
      safeStorageSet('prof_qa_exhibition_mode', String(partialSettings.exhibitionMode));
    }
    if (currentUser?.id && Object.keys(payload).length > 0) {
      try {
        const updated = await api.updateSettings(currentUser.id, payload);
        setSettings(updated);
        return updated;
      } catch (err) {
        console.warn('Failed to persist settings:', err);
      }
    }
    return payload;
  };

  return (
    <ThemeContext.Provider
      value={{
        theme,
        brightness,
        colorScheme,
        isDark,
        isGreen,
        isBlueWhite,
        isBlueWhiteUnlocked,
        fontSize,
        helpLevel,
        exhibitionMode,
        settings,
        unlockBlueWhiteTheme,
        checkBlueWhiteUnlock,
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
      {showBlueWhiteToast && (
        <div className="hidden-theme-unlock-toast" role="status" aria-live="polite">
          <span>
            숨겨진 테마 <strong>파랑/하양</strong> 배색이 해금되었습니다!
          </span>
          <button
            type="button"
            className="unlock-toast-apply"
            onClick={() => {
              updateTheme('blue-white');
              setShowBlueWhiteToast(false);
            }}
          >
            바로 적용
          </button>
          <button
            type="button"
            className="unlock-toast-close"
            aria-label="닫기"
            onClick={() => setShowBlueWhiteToast(false)}
          >
            ×
          </button>
        </div>
      )}
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
