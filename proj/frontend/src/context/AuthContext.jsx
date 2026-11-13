// src/context/AuthContext.jsx
import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { api } from '../services/api';

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [currentUser, setCurrentUser] = useState(() => api.getCurrentSessionUser());
  const [departments, setDepartments] = useState([]);
  const [allCourses, setAllCourses] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [authModalMode, setAuthModalMode] = useState('prompt'); // 'prompt' | 'login' | 'register'

  const refreshCatalog = useCallback(async () => {
    try {
      const [deptList, courseList] = await Promise.all([
        api.getDepartments(),
        api.getAllCourses()
      ]);
      if (Array.isArray(deptList)) setDepartments(deptList);
      if (Array.isArray(courseList)) setAllCourses(courseList);
    } catch (err) {
      console.error('Failed to load catalog:', err);
    }
  }, []);

  const refreshCurrentUser = useCallback(async () => {
    const sessionUser = api.getCurrentSessionUser();
    if (!sessionUser?.id) {
      setCurrentUser(null);
      return null;
    }
    try {
      const updated = await api.getUser(sessionUser.id);
      if (updated) {
        if (updated.isWithdrawn) {
          api.logout({ skipRemote: true });
          setCurrentUser(null);
          return null;
        }
        setCurrentUser(updated);
        return updated;
      }
    } catch (err) {
      if (err?.status === 401 || err?.status === 403 || err?.message === 'UNAUTHORIZED') {
        api.logout({ skipRemote: true });
        setCurrentUser(null);
        return null;
      }
      console.warn('Session refresh warning:', err);
    }
    return sessionUser;
  }, []);

  useEffect(() => {
    let mounted = true;
    (async () => {
      await refreshCatalog();
      const sessionUser = api.getCurrentSessionUser();
      if (sessionUser?.id && mounted) {
        await refreshCurrentUser();
      }
      if (mounted) setIsLoading(false);
    })();
    return () => {
      mounted = false;
    };
  }, [refreshCatalog, refreshCurrentUser]);

  const login = async (username, password) => {
    const result = await api.login(username, password);
    setCurrentUser(result.user);
    await refreshCatalog();
    return result.user;
  };

  const register = async (payload) => {
    const result = await api.register(payload);
    setCurrentUser(result.user);
    await refreshCatalog();
    return result.user;
  };

  const updateUserCourses = useCallback(async (courseIds) => {
    const updatedUser = await api.updateUserCourses(courseIds);
    if (updatedUser && updatedUser.id) {
      setCurrentUser((prev) => {
        const merged = { ...(prev || {}), ...updatedUser };
        api.saveSession(merged);
        return merged;
      });
    }
    return updatedUser;
  }, []);

  const logout = () => {
    void api.logout();
    setCurrentUser(null);
    setAuthModalMode('prompt');
  };

  const isAuthenticated = Boolean(currentUser && currentUser.id && !currentUser.isWithdrawn);
  const isStudent = isAuthenticated && currentUser?.role === 'student';
  const isProfessor = isAuthenticated && currentUser?.role === 'professor';
  const isAdmin = isAuthenticated && Boolean(currentUser?.isAdmin);

  return (
    <AuthContext.Provider
      value={{
        currentUser,
        isAuthenticated,
        isStudent,
        isProfessor,
        isAdmin,
        isLoading,
        departments,
        allCourses,
        authModalMode,
        setAuthModalMode,
        login,
        register,
        logout,
        refreshCatalog,
        refreshCurrentUser,
        updateUserCourses
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
