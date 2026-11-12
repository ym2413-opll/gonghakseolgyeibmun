// src/components/common/AuthGateOverlay.jsx
import React, { useState, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import {
  Lock,
  LogIn,
  UserPlus,
  GraduationCap,
  BookOpen,
  ShieldCheck,
  Mail,
  KeyRound,
  AlertCircle,
  CheckCircle2,
  Terminal
} from 'lucide-react';

const AuthGateOverlay = () => {
  const {
    login,
    register,
    departments,
    allCourses,
    authModalMode,
    setAuthModalMode
  } = useAuth();

  const [mode, setMode] = useState(authModalMode || 'login'); // 'login' | 'register'
  const [role, setRole] = useState('STUDENT'); // 'STUDENT' | 'PROFESSOR'

  // Login form state
  const [loginUsername, setLoginUsername] = useState('');
  const [loginPassword, setLoginPassword] = useState('');

  // Register form state
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

  useEffect(() => {
    if (authModalMode) {
      setMode(authModalMode);
      setErrorMsg('');
    }
  }, [authModalMode]);

  useEffect(() => {
    if (!department && departments.length > 0) {
      setDepartment(departments[0].name);
    }
  }, [departments, department]);

  useEffect(() => {
    if (selectedCourseIds.length === 0 && allCourses.length > 0) {
      setSelectedCourseIds([allCourses[0].id]);
    }
  }, [allCourses, selectedCourseIds.length]);

  const switchMode = (nextMode) => {
    setMode(nextMode);
    setAuthModalMode(nextMode);
    setErrorMsg('');
  };

  const handleCourseToggle = (courseId) => {
    setSelectedCourseIds((prev) =>
      prev.includes(courseId)
        ? prev.filter((id) => id !== courseId)
        : [...prev, courseId]
    );
  };

  const handleLoginSubmit = async (e) => {
    e.preventDefault();
    setErrorMsg('');
    setIsSubmitting(true);
    try {
      await login(loginUsername, loginPassword);
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

    if (role === 'PROFESSOR' && selectedCourseIds.length === 0) {
      setErrorMsg('담당 과목을 최소 1개 이상 선택해주세요.');
      return;
    }

    setIsSubmitting(true);
    try {
      const selectedCourses = allCourses.filter((c) =>
        selectedCourseIds.includes(c.id)
      );
      await register({
        username,
        password,
        role,
        name,
        department,
        identifier: role === 'STUDENT' ? studentId : null,
        email: role === 'PROFESSOR' ? email : null,
        courseIds: role === 'PROFESSOR' ? selectedCourseIds : [],
        courseNames: role === 'PROFESSOR' ? selectedCourses.map((c) => c.name) : []
      });
    } catch (err) {
      setErrorMsg(err.message || '회원가입 처리 중 오류가 발생했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // 중복 과목명을 병합하여 깔끔하게 표시하되 대표 ID 사용
  const uniqueCourses = React.useMemo(() => {
    const map = new Map();
    allCourses.forEach((c) => {
      if (!map.has(c.name)) {
        map.set(c.name, c);
      }
    });
    return Array.from(map.values());
  }, [allCourses]);

  return (
    <div className="auth-gate-overlay" role="dialog" aria-modal="true" aria-labelledby="auth-gate-title">
      <div className="auth-gate-card">
        <div className="auth-gate-banner">
          <div className="auth-gate-lock-icon">
            <Lock size={22} />
          </div>
          <div>
            <span className="auth-gate-kicker">MEMBERS ONLY ACADEMIC FORUM</span>
            <h2 id="auth-gate-title" className="auth-gate-heading">
              포럼 열람 및 질의응답을 위해 로그인해 주세요
            </h2>
            <p className="auth-gate-desc">
              비로그인 상태에서는 학업 질문 및 교수님 답변 내용이 보호(블러 처리)됩니다.
              계정이 없으시다면 <strong>전시용 회원가입</strong>으로 즉시 참여하실 수 있습니다.
            </p>
          </div>
        </div>

        <div className="auth-gate-tabs">
          <button
            type="button"
            className={`auth-gate-tab ${mode === 'login' ? 'active' : ''}`}
            onClick={() => switchMode('login')}
          >
            <LogIn size={16} />
            로그인
          </button>
          <button
            type="button"
            className={`auth-gate-tab ${mode === 'register' ? 'active' : ''}`}
            onClick={() => switchMode('register')}
          >
            <UserPlus size={16} />
            전시용 회원가입
          </button>
        </div>

        {errorMsg && (
          <div className="auth-gate-alert error" role="alert">
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        {mode === 'login' ? (
          <form className="auth-gate-form" onSubmit={handleLoginSubmit}>
            <div className="form-group">
              <label className="form-label" htmlFor="login-username">
                아이디 (ID)
              </label>
              <input
                id="login-username"
                type="text"
                className="form-input"
                placeholder="가입하신 아이디를 입력하세요"
                value={loginUsername}
                onChange={(e) => setLoginUsername(e.target.value)}
                autoComplete="username"
                required
              />
            </div>

            <div className="form-group">
              <label className="form-label" htmlFor="login-password">
                비밀번호 (Password)
              </label>
              <input
                id="login-password"
                type="password"
                className="form-input"
                placeholder="비밀번호를 입력하세요"
                value={loginPassword}
                onChange={(e) => setLoginPassword(e.target.value)}
                autoComplete="current-password"
                required
              />
            </div>

            <button
              type="submit"
              className="btn btn-primary auth-submit-btn"
              disabled={isSubmitting}
            >
              <LogIn size={17} />
              {isSubmitting ? '인증 확인 중...' : '포럼 로그인'}
            </button>

            <div className="auth-gate-switch-prompt">
              아직 계정이 없으신가요?{' '}
              <button
                type="button"
                className="auth-link-btn"
                onClick={() => switchMode('register')}
              >
                전시용 새 계정 만들기 →
              </button>
            </div>
          </form>
        ) : (
          <form className="auth-gate-form" onSubmit={handleRegisterSubmit}>
            <div className="role-selector-grid">
              <button
                type="button"
                className={`role-select-card ${role === 'STUDENT' ? 'active' : ''}`}
                onClick={() => {
                  setRole('STUDENT');
                  setErrorMsg('');
                }}
              >
                <GraduationCap size={20} />
                <div>
                  <div className="role-select-title">학생 계정 가입</div>
                  <div className="role-select-sub">학번 · 학과 · 이름 · ID · 비밀번호</div>
                </div>
              </button>
              <button
                type="button"
                className={`role-select-card ${role === 'PROFESSOR' ? 'active' : ''}`}
                onClick={() => {
                  setRole('PROFESSOR');
                  setErrorMsg('');
                }}
              >
                <BookOpen size={20} />
                <div>
                  <div className="role-select-title">교수 계정 가입</div>
                  <div className="role-select-sub">학과 · 담당 과목 · 이메일 · ID · 비밀번호</div>
                </div>
              </button>
            </div>

            <div className="auth-form-grid-2">
              <div className="form-group">
                <label className="form-label">아이디 (ID) *</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder="영문/숫자 3~30자 (예: kw_student1)"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoComplete="username"
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label">이름 (실명) *</label>
                <input
                  type="text"
                  className="form-input"
                  placeholder={role === 'STUDENT' ? '예: 홍길동' : '예: 김광운'}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="auth-form-grid-2">
              <div className="form-group">
                <label className="form-label">비밀번호 (6자 이상) *</label>
                <input
                  type="password"
                  className="form-input"
                  placeholder="비밀번호 입력"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="new-password"
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label">비밀번호 확인 *</label>
                <input
                  type="password"
                  className="form-input"
                  placeholder="비밀번호 재입력"
                  value={passwordConfirm}
                  onChange={(e) => setPasswordConfirm(e.target.value)}
                  autoComplete="new-password"
                  required
                />
              </div>
            </div>

            <div className="auth-form-grid-2">
              <div className="form-group">
                <label className="form-label">소속 학과 *</label>
                <select
                  className="form-select"
                  value={department}
                  onChange={(e) => setDepartment(e.target.value)}
                  required
                >
                  {departments.map((d) => (
                    <option key={d.id || d.name} value={d.name}>
                      {d.name}
                    </option>
                  ))}
                </select>
              </div>

              {role === 'STUDENT' ? (
                <div className="form-group">
                  <label className="form-label">학번 (숫자 6~12자리, 중복 불가) *</label>
                  <input
                    type="text"
                    className="form-input"
                    placeholder="예: 2026202099"
                    value={studentId}
                    onChange={(e) => setStudentId(e.target.value)}
                    required
                  />
                </div>
              ) : (
                <div className="form-group">
                  <label className="form-label">
                    <Mail size={13} style={{ display: 'inline', marginRight: 4 }} />
                    이메일 (질문 알림 발송 연동, 중복 불가) *
                  </label>
                  <input
                    type="email"
                    className="form-input"
                    placeholder="예: professor@kw.ac.kr"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                  />
                </div>
              )}
            </div>

            {role === 'PROFESSOR' && (
              <div className="form-group">
                <label className="form-label">
                  담당 과목 선택 (복수 선택 가능) *
                </label>
                <div className="course-checkbox-grid">
                  {uniqueCourses.slice(0, 18).map((course) => {
                    const checked = selectedCourseIds.includes(course.id);
                    return (
                      <label
                        key={course.id}
                        className={`course-checkbox-pill ${checked ? 'checked' : ''}`}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => handleCourseToggle(course.id)}
                        />
                        <span>{course.name}</span>
                      </label>
                    );
                  })}
                </div>
              </div>
            )}

            <div className="auth-security-note">
              <ShieldCheck size={16} />
              <span>
                비밀번호는 솔트(Salt) 기반 <strong>PBKDF2-HMAC-SHA256 (65,536회 반복)</strong> 단방향 암호화 해시로만 저장되며, 학번·이메일·ID 중복 가입이 엄격히 차단됩니다.
              </span>
            </div>

            <button
              type="submit"
              className="btn btn-primary auth-submit-btn"
              disabled={isSubmitting}
            >
              <CheckCircle2 size={17} />
              {isSubmitting ? '계정 생성 중...' : `${role === 'STUDENT' ? '학생' : '교수'} 계정 생성 및 로그인`}
            </button>

            <div className="auth-gate-switch-prompt">
              이미 계정을 생성하셨나요?{' '}
              <button
                type="button"
                className="auth-link-btn"
                onClick={() => switchMode('login')}
              >
                로그인 화면으로 이동 →
              </button>
            </div>
          </form>
        )}

        <div className="auth-console-hint">
          <Terminal size={14} />
          <span>
            <strong>관리자(Admin) 권한 보안 정책:</strong> 보안상 브라우저에서는 관리자 권한을 부여할 수 없으며, 백엔드 서버 터미널 콘솔에서 <code>admin &lt;아이디&gt;</code> 명령을 통해서만 부여됩니다.
          </span>
        </div>
      </div>
    </div>
  );
};

export default AuthGateOverlay;
