// src/context/AuthContext.jsx
// 광운대학교 학업 Q&A 센터 포털 사용자 인증 및 계정 관리 컨텍스트

import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { PRESET_USERS } from '../services/mockData';
import { api } from '../services/api';

const AuthContext = createContext();

export function AuthProvider({ children }) {
  const [presetUsers, setPresetUsers] = useState(PRESET_USERS);

  const [currentUser, setCurrentUser] = useState(() => {
    const savedId = localStorage.getItem('prof_qa_current_user_id');
    const matched = PRESET_USERS.find(u => u.id === savedId);
    return matched || PRESET_USERS[0];
  });

  // 백엔드 사용자 목록 및 최신 강좌 목록 비동기 동기화
  const syncRemoteCourses = useCallback(async (user) => {
    if (!user) return;
    try {
      const courses = await api.getCourses(user.id);
      if (Array.isArray(courses) && courses.length > 0) {
        setCurrentUser(prev => {
          if (prev.id === user.id) {
            return { ...prev, courses };
          }
          return prev;
        });
      }
    } catch (err) {
      console.warn('Failed to sync remote courses', err);
    }
  }, []);

  useEffect(() => {
    async function loadServerUsers() {
      try {
        const users = await api.getPresetUsers();
        if (Array.isArray(users) && users.length > 0) {
          setPresetUsers(users);
          const savedId = localStorage.getItem('prof_qa_current_user_id');
          const matched = users.find(u => u.id === savedId) || users[0];
          setCurrentUser(matched);
          syncRemoteCourses(matched);
        }
      } catch (err) {
        console.warn('Failed to load server users', err);
      }
    }
    loadServerUsers();
  }, [syncRemoteCourses]);

  const switchUser = (userId) => {
    const target = presetUsers.find(u => u.id === userId);
    if (target) {
      setCurrentUser(target);
      localStorage.setItem('prof_qa_current_user_id', target.id);
      syncRemoteCourses(target);
    }
  };

  const isProfessor = currentUser.role === 'PROFESSOR' || currentUser.role === 'professor';
  const isStudent = currentUser.role === 'STUDENT' || currentUser.role === 'student';

  return (
    <AuthContext.Provider
      value={{
        currentUser,
        presetUsers,
        switchUser,
        isProfessor,
        isStudent,
        syncRemoteCourses
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
