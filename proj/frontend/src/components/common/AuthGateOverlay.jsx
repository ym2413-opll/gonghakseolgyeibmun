// src/components/common/AuthGateOverlay.jsx
import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import CoursePicker from './CoursePicker';
import {
  Lock,
  LogIn,
  UserPlus,
  GraduationCap,
  BookOpen,
  AlertCircle,
  ArrowLeft
} from 'lucide-react';

const AuthGateOverlay = () => {
  const {
    departments,
    allCourses,
    authModalMode,
    setAuthModalMode,
    login,
    register
  } = useAuth();

  const viewMode = authModalMode === 'login' || authModalMode === 'register' ? authModalMode : 'prompt';

  // Login State
  const [loginId, setLoginId] = useState('');
  const [loginPassword, setLoginPassword] = useState('');

  // Register State
  const [role, setRole] = useState('STUDENT');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [name, setName] = useState('');
  const [department, setDepartment] = useState('');
  const [studentId, setStudentId] = useState('');
  const [email, setEmail] = useState('');
  const [selectedCourseIds, setSelectedCourseIds] = useState([]);

  const [errorMsg, setErrorMsg] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const effectiveDepartment = department || departments[0]?.name || '';

  const switchAuthMode = (nextMode) => {
    setErrorMsg('');
    setAuthModalMode(nextMode);
  };

  const handleLoginSubmit = async (e) => {
    e.preventDefault();
    setErrorMsg('');
    setIsSubmitting(true);
    try {
      await login(loginId, loginPassword);
    } catch (err) {
      setErrorMsg(err.message || '로그인에 실패했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRegisterSubmit = async (e) => {
    e.preventDefault();
    setErrorMsg('');

    if (password !== passwordConfirm) {
      setErrorMsg('비밀번호와 비밀번호 확인이 일치하지 않습니다.');
      return;
    }

    const availableCourses = (allCourses || []).filter((c) => Boolean(c?.id));
    if (availableCourses.length > 0 && selectedCourseIds.length === 0) {
      setErrorMsg(
        role === 'PROFESSOR'
          ? '담당 과목을 최소 1개 이상 선택해주세요.'
          : '수강 과목을 최소 1개 이상 선택해주세요.'
      );
      return;
    }

    setIsSubmitting(true);
    try {
      const selectedCourses = availableCourses.filter((c) => selectedCourseIds.includes(c.id));
      const cleanSid = studentId.trim();
      await register({
        username: role === 'STUDENT' ? cleanSid : username.trim(),
        password,
        role,
        name,
        department: effectiveDepartment,
        identifier: role === 'STUDENT' ? cleanSid : null,
        email: role === 'PROFESSOR' ? email : null,
        courseIds: selectedCourseIds,
        courseNames: selectedCourses.map((c) => c.name)
      });
    } catch (err) {
      setErrorMsg(err.message || '회원가입 중 오류가 발생했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  if (viewMode === 'prompt') {
    return (
      <div className="auth-gate-overlay" role="dialog" aria-modal="true" aria-labelledby="auth-gate-title">
        <div className="auth-gate-card auth-prompt-card">
          <div className="auth-prompt-header">
            <div className="auth-gate-lock-icon">
              <Lock size={20} />
            </div>
            <h2 id="auth-gate-title" className="auth-gate-heading">
              교수질문센터
            </h2>
            <p className="auth-gate-desc">로그인 후 이용할 수 있습니다.</p>
          </div>

          <div className="auth-prompt-actions">
            <button
              type="button"
              className="btn btn-primary auth-submit-btn"
              onClick={() => switchAuthMode('login')}
            >
              <LogIn size={17} />
              <span>로그인</span>
            </button>
            <button
              type="button"
              className="btn btn-secondary auth-submit-btn"
              onClick={() => switchAuthMode('register')}
            >
              <UserPlus size={17} />
              <span>회원가입</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-gate-overlay" role="dialog" aria-modal="true" aria-labelledby="auth-gate-title">
      <div className="auth-gate-card">
        <div className="auth-page-header">
          <button
            type="button"
            className="btn btn-outline btn-xs"
            onClick={() => switchAuthMode('prompt')}
          >
            <ArrowLeft size={14} />
            <span>이전</span>
          </button>
          <h2 id="auth-gate-title" className="auth-gate-heading">
            {viewMode === 'login' ? '로그인' : '회원가입'}
          </h2>
          <div style={{ width: 52 }} />
        </div>

        {errorMsg && (
          <div className="auth-gate-alert error" role="alert">
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        {viewMode === 'login' ? (
          <form className="auth-gate-form" onSubmit={handleLoginSubmit}>
            <div className="form-group">
              <label className="form-label" htmlFor="login-username">
                학번 또는 아이디
              </label>
              <input
                id="login-username"
                type="text"
                className="form-input"
                placeholder="학번 또는 아이디"
                value={loginId}
                onChange={(e) => setLoginId(e.target.value)}
                required
                autoComplete="username"
              />
            </div>

            <div className="form-group">
              <label className="form-label" htmlFor="login-password">
                비밀번호
              </label>
              <input
                id="login-password"
                type="password"
                className="form-input"
                placeholder="비밀번호"
                value={loginPassword}
                onChange={(e) => setLoginPassword(e.target.value)}
                required
                autoComplete="current-password"
              />
            </div>

            <button
              type="submit"
              className="btn btn-primary auth-submit-btn"
              disabled={isSubmitting}
            >
              <LogIn size={17} />
              <span>{isSubmitting ? '로그인 중...' : '로그인'}</span>
            </button>

            <div className="auth-gate-switch-prompt">
              계정이 없으신가요?{' '}
              <button
                type="button"
                className="auth-link-btn"
                onClick={() => switchAuthMode('register')}
              >
                회원가입
              </button>
            </div>
          </form>
        ) : (
          <form className="auth-gate-form" onSubmit={handleRegisterSubmit}>
            <div className="role-selector-grid">
              <button
                type="button"
                className={`role-select-card ${role === 'STUDENT' ? 'active' : ''}`}
                onClick={() => setRole('STUDENT')}
              >
                <GraduationCap size={18} />
                <span className="role-select-title">학생</span>
              </button>

              <button
                type="button"
                className={`role-select-card ${role === 'PROFESSOR' ? 'active' : ''}`}
                onClick={() => setRole('PROFESSOR')}
              >
                <BookOpen size={18} />
                <span className="role-select-title">교수</span>
              </button>
            </div>

            <div className="auth-form-grid-2">
              {role === 'STUDENT' ? (
                <div className="form-group">
                  <label className="form-label" htmlFor="reg-student-id">
                    학번 *
                  </label>
                  <input
                    id="reg-student-id"
                    type="text"
                    className="form-input"
                    placeholder="예: 2026202001"
                    value={studentId}
                    onChange={(e) => setStudentId(e.target.value)}
                    required
                  />
                </div>
              ) : (
                <div className="form-group">
                  <label className="form-label" htmlFor="reg-username">
                    아이디 *
                  </label>
                  <input
                    id="reg-username"
                    type="text"
                    className="form-input"
                    placeholder="영문·숫자 3~30자"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    required
                  />
                </div>
              )}

              <div className="form-group">
                <label className="form-label" htmlFor="reg-name">
                  이름 *
                </label>
                <input
                  id="reg-name"
                  type="text"
                  className="form-input"
                  placeholder="실명 입력"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="auth-form-grid-2">
              <div className="form-group">
                <label className="form-label" htmlFor="reg-password">
                  비밀번호 *
                </label>
                <input
                  id="reg-password"
                  type="password"
                  className="form-input"
                  placeholder="6자 이상"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  minLength={6}
                />
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="reg-password-confirm">
                  비밀번호 확인 *
                </label>
                <input
                  id="reg-password-confirm"
                  type="password"
                  className="form-input"
                  placeholder="비밀번호 재입력"
                  value={passwordConfirm}
                  onChange={(e) => setPasswordConfirm(e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="auth-form-grid-2">
              <div className="form-group">
                <label className="form-label" htmlFor="reg-dept">
                  소속 학과 *
                </label>
                <select
                  id="reg-dept"
                  className="form-select"
                  value={effectiveDepartment}
                  onChange={(e) => setDepartment(e.target.value)}
                  required
                >
                  {departments.map((dept) => (
                    <option key={dept.id || dept.name} value={dept.name}>
                      {dept.name}
                    </option>
                  ))}
                </select>
              </div>

              {role === 'PROFESSOR' && (
                <div className="form-group">
                  <label className="form-label" htmlFor="reg-email">
                    이메일 *
                  </label>
                  <input
                    id="reg-email"
                    type="email"
                    className="form-input"
                    placeholder="professor@kw.ac.kr"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                  />
                </div>
              )}
            </div>

            <CoursePicker
              courses={allCourses}
              selectedIds={selectedCourseIds}
              onChange={setSelectedCourseIds}
              label={role === 'PROFESSOR' ? '담당 교과목' : '수강 과목'}
              required={(allCourses || []).some((c) => Boolean(c?.id))}
              helperText="최소 1개 이상"
            />

            <button
              type="submit"
              className="btn btn-primary auth-submit-btn"
              disabled={isSubmitting}
            >
              <UserPlus size={17} />
              <span>{isSubmitting ? '가입 처리 중...' : '회원가입'}</span>
            </button>

            <div className="auth-gate-switch-prompt">
              이미 계정이 있으신가요?{' '}
              <button
                type="button"
                className="auth-link-btn"
                onClick={() => switchAuthMode('login')}
              >
                로그인
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};

export default AuthGateOverlay;
