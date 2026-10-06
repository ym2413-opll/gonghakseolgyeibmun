// src/context/AuthContext.jsx
import React, { createContext, useContext, useState } from 'react';
import { PRESET_USERS } from '../services/mockData';

const AuthContext = createContext();

export function AuthProvider({ children }) {
  // 전시용 기본 사용자: 학생 서영민
  const [currentUser, setCurrentUser] = useState(() => {
    const savedId = localStorage.getItem('prof_qa_current_user_id');
    const matched = PRESET_USERS.find(u => u.id === savedId);
    return matched || PRESET_USERS[0];
  });

  const [presetUsers] = useState(PRESET_USERS);

  const switchUser = (userId) => {
    const target = presetUsers.find(u => u.id === userId);
    if (target) {
      setCurrentUser(target);
      localStorage.setItem('prof_qa_current_user_id', target.id);
    }
  };

  const isProfessor = currentUser.role === 'professor';
  const isStudent = currentUser.role === 'student';

  return (
    <AuthContext.Provider
      value={{
        currentUser,
        presetUsers,
        switchUser,
        isProfessor,
        isStudent
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
