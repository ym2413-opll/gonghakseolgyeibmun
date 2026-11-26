// src/components/common/AuthGateOverlay.jsx
import React, { useState, useEffect, useMemo } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { api } from '../../services/api';
import { buildSemesterOptions, formatSemesterLabel, normalizeSemester } from '../../utils/semester';
import CoursePicker, { buildCourseSelectionsPayload } from './CoursePicker';
import {
  Lock,
  LogIn,
  UserPlus,
  GraduationCap,
  BookOpen,
  AlertCircle,
  ArrowLeft,
  RefreshCw,
  Plus,
  Trash2
} from 'lucide-react';

const AuthGateOverlay = () => {
  const { exhibitionMode } = useTheme();
  const {
    departments,
    allCourses,
    currentSemester,
    availableSemesters,
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
  const [email, setEmail] = useState('');
  const [department, setDepartment] = useState('');
  const [entryYear, setEntryYear] = useState(2026);
  const [isForeign, setIsForeign] = useState(false);
  const [studentId, setStudentId] = useState('');
  const [professorInviteCode, setProfessorInviteCode] = useState('');
  const [professorInviteRequired, setProfessorInviteRequired] = useState(false);
  const [selectedCourseIds, setSelectedCourseIds] = useState([]);
  const [sectionSelections, setSectionSelections] = useState({});

  // Professor direct custom course creation during signup
  const [showCustomCourseBox, setShowCustomCourseBox] = useState(false);
  const [customCourses, setCustomCourses] = useState([]);
  const [draftCourseName, setDraftCourseName] = useState('');
  const [draftCourseCode, setDraftCourseCode] = useState('');
  const [draftCourseSection, setDraftCourseSection] = useState('01분반');
  const [draftCourseSemester, setDraftCourseSemester] = useState('');
  const [draftCourseCompType, setDraftCourseCompType] = useState('전선');
  const [draftCourseCredit, setDraftCourseCredit] = useState(3);
  const [draftCourseSchedule, setDraftCourseSchedule] = useState('');

  const [errorMsg, setErrorMsg] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const semesterOptions = useMemo(
    () => buildSemesterOptions(currentSemester || '2026-2', availableSemesters),
    [currentSemester, availableSemesters]
  );

  useEffect(() => {
    if (!draftCourseSemester && currentSemester) {
      setDraftCourseSemester(currentSemester);
    }
  }, [currentSemester, draftCourseSemester]);

  useEffect(() => {
    if (viewMode !== 'register') return;
    let active = true;
    api.getRegistrationPolicy().then((policy) => {
      if (active) {
        setProfessorInviteRequired(Boolean(policy?.professorInviteRequired));
      }
    });
    return () => {
      active = false;
    };
  }, [viewMode]);

  const effectiveDepartment = department || departments[0]?.name || '';

  const switchAuthMode = (nextMode) => {
    setErrorMsg('');
    setAuthModalMode(nextMode);
  };

  const handleAddCustomCourse = () => {
    setErrorMsg('');
    if (customCourses.length >= 10) {
      setErrorMsg('직접 추가할 수 있는 담당 강의는 최대 10개까지입니다.');
      return;
    }
    const cleanName = draftCourseName.trim().slice(0, 100);
    if (!cleanName) {
      setErrorMsg('직접 추가할 담당 강의의 과목명을 입력해 주세요.');
      return;
    }
    const cleanSem = normalizeSemester(draftCourseSemester || currentSemester || '2026-2');
    const cleanSec = (draftCourseSection.trim() || '01분반').slice(0, 20);
    const clampedCredit = Math.min(6, Math.max(1, Number(draftCourseCredit) || 3));
    setCustomCourses((prev) => [
      ...prev,
      {
        id: `custom-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        name: cleanName,
        code: draftCourseCode.trim().slice(0, 40),
        semester: cleanSem,
        section: cleanSec,
        department: effectiveDepartment,
        completionType: draftCourseCompType || '전선',
        credit: clampedCredit,
        schedule: draftCourseSchedule.trim().slice(0, 120)
      }
    ]);
    setDraftCourseName('');
    setDraftCourseCode('');
    setDraftCourseSection('01분반');
    setDraftCourseSchedule('');
  };

  const handleRemoveCustomCourse = (tempId) => {
    setCustomCourses((prev) => prev.filter((item) => item.id !== tempId));
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
    if (role === 'PROFESSOR') {
      if (availableCourses.length > 0 && selectedCourseIds.length === 0 && customCourses.length === 0) {
        setErrorMsg('담당 과목을 최소 1개 이상 선택하거나 직접 추가해 주세요.');
        return;
      }
    } else if (availableCourses.length > 0 && selectedCourseIds.length === 0) {
      setErrorMsg('수강 과목을 최소 1개 이상 선택해 주세요.');
      return;
    }

    setIsSubmitting(true);
    try {
      const selectedCourses = availableCourses.filter((c) => selectedCourseIds.includes(c.id));
      const cleanSid = studentId.trim();
      const courseSelections = buildCourseSelectionsPayload(
        selectedCourseIds,
        sectionSelections,
        availableCourses,
        role === 'PROFESSOR'
      );

      await register({
        username: role === 'STUDENT' ? cleanSid : username.trim(),
        password,
        role,
        name,
        department: effectiveDepartment,
        entryYear: role === 'STUDENT' ? Number(entryYear) || 2026 : null,
        isForeign: role === 'STUDENT' ? Boolean(isForeign) : false,
        identifier: role === 'STUDENT' ? cleanSid : null,
        email: role === 'PROFESSOR' && email.trim() ? email.trim() : null,
        professorInviteCode: role === 'PROFESSOR' ? professorInviteCode.trim() : null,
        courseIds: selectedCourseIds,
        courseNames: selectedCourses.map((c) => c.name),
        courseSelections,
        newCourses:
          role === 'PROFESSOR'
            ? customCourses.map(({ id: _unused, ...rest }) => rest)
            : []
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
                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                    <input
                      id="reg-student-id"
                      type="text"
                      className="form-input"
                      style={{ flex: '1 1 150px', minWidth: 0 }}
                      placeholder="예: 2026101001"
                      value={studentId}
                      onChange={(e) => setStudentId(e.target.value)}
                      required
                    />
                    {exhibitionMode && (
                      <button
                        type="button"
                        className="btn btn-outline btn-xs"
                        style={{ whiteSpace: 'nowrap' }}
                        onClick={() => {
                          api
                            .getNextStudentId({
                              year: entryYear,
                              isForeign,
                              random: true
                            })
                            .then((res) => {
                              if (res?.studentId) setStudentId(res.studentId);
                            })
                            .catch((err) => {
                              if (err?.message) setErrorMsg(err.message);
                            });
                        }}
                      >
                        <RefreshCw size={14} />
                        <span>무작위 생성</span>
                      </button>
                    )}
                  </div>
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

            {role === 'PROFESSOR' && professorInviteRequired && (
              <div className="form-group">
                <label className="form-label" htmlFor="reg-prof-invite">
                  교수 가입 인증 코드 *
                </label>
                <input
                  id="reg-prof-invite"
                  type="password"
                  className="form-input"
                  placeholder="관리자로부터 안내받은 교수 인증 코드를 입력하세요"
                  value={professorInviteCode}
                  onChange={(e) => setProfessorInviteCode(e.target.value)}
                  required
                />
              </div>
            )}

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
                  {departments.map((dept) => {
                    const aliasList = Array.isArray(dept.aliases) ? dept.aliases.filter(Boolean) : [];
                    const optionLabel =
                      aliasList.length > 0
                        ? `${dept.name} (기존: ${aliasList.join(', ')})`
                        : dept.name;
                    return (
                      <option key={dept.id || dept.name} value={dept.name}>
                        {optionLabel}
                      </option>
                    );
                  })}
                </select>
              </div>

              {role === 'STUDENT' ? (
                <div className="form-group">
                  <label className="form-label" htmlFor="reg-entry-year">
                    입학년도 · 구분
                  </label>
                  <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
                    <input
                      id="reg-entry-year"
                      type="number"
                      className="form-input"
                      style={{ width: '92px', maxWidth: '100%' }}
                      min={1990}
                      max={2099}
                      value={entryYear}
                      onChange={(e) => setEntryYear(e.target.value)}
                      required
                    />
                    <label
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '4px',
                        fontSize: '0.82rem',
                        whiteSpace: 'nowrap',
                        cursor: 'pointer'
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={isForeign}
                        onChange={(e) => setIsForeign(e.target.checked)}
                      />
                      <span>외국인 학생</span>
                    </label>
                  </div>
                </div>
              ) : (
                <div className="form-group">
                  <label className="form-label" htmlFor="reg-prof-email">
                    이메일 (선택)
                  </label>
                  <input
                    id="reg-prof-email"
                    type="email"
                    className="form-input"
                    placeholder="예: professor@kw.ac.kr"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </div>
              )}
            </div>

            <CoursePicker
              courses={allCourses}
              selectedIds={selectedCourseIds}
              onChange={setSelectedCourseIds}
              sectionMode={role === 'PROFESSOR' ? 'multi' : 'single'}
              sectionSelections={sectionSelections}
              onSectionSelectionsChange={setSectionSelections}
              label={role === 'PROFESSOR' ? '담당 교과목' : '수강 과목'}
              required={
                role === 'PROFESSOR'
                  ? (allCourses || []).some((c) => Boolean(c?.id)) && customCourses.length === 0
                  : (allCourses || []).some((c) => Boolean(c?.id))
              }
              helperText={role === 'PROFESSOR' ? '선택 또는 직접 추가' : '최소 1개 이상'}
            />

            {role === 'PROFESSOR' && (
              <div className="prof-signup-custom-course-wrap">
                <div className="prof-signup-custom-header">
                  <span className="text-xs text-muted">
                    목록에 담당 강의가 없다면 직접 추가할 수 있습니다.
                  </span>
                  <button
                    type="button"
                    className="btn btn-outline btn-xs"
                    onClick={() => setShowCustomCourseBox((prev) => !prev)}
                  >
                    <Plus size={13} />
                    <span>{showCustomCourseBox ? '접기' : '직접 추가'}</span>
                  </button>
                </div>

                {showCustomCourseBox && (
                  <div className="prof-signup-custom-form">
                    <div className="auth-form-grid-2">
                      <div className="form-group" style={{ marginBottom: 8 }}>
                        <label className="form-label">과목명 *</label>
                        <input
                          type="text"
                          className="form-input"
                          placeholder="예: 캡스톤설계"
                          value={draftCourseName}
                          onChange={(e) => setDraftCourseName(e.target.value)}
                        />
                      </div>
                      <div className="form-group" style={{ marginBottom: 8 }}>
                        <label className="form-label">학수번호 (선택)</label>
                        <input
                          type="text"
                          className="form-input"
                          placeholder="예: 7000-1-2957"
                          value={draftCourseCode}
                          onChange={(e) => setDraftCourseCode(e.target.value)}
                        />
                      </div>
                    </div>

                    <div className="auth-form-grid-2">
                      <div className="form-group" style={{ marginBottom: 8 }}>
                        <label className="form-label">개설 학기</label>
                        <select
                          className="form-select"
                          value={draftCourseSemester || currentSemester || '2026-2'}
                          onChange={(e) => setDraftCourseSemester(e.target.value)}
                        >
                          {semesterOptions.map((sem) => (
                            <option key={sem} value={sem}>
                              {formatSemesterLabel(sem)} ({sem})
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="form-group" style={{ marginBottom: 8 }}>
                        <label className="form-label">분반 · 이수구분</label>
                        <div style={{ display: 'flex', gap: 6 }}>
                          <input
                            type="text"
                            className="form-input"
                            style={{ flex: 1 }}
                            placeholder="01분반"
                            value={draftCourseSection}
                            onChange={(e) => setDraftCourseSection(e.target.value)}
                          />
                          <select
                            className="form-select"
                            style={{ width: 88 }}
                            value={draftCourseCompType}
                            onChange={(e) => setDraftCourseCompType(e.target.value)}
                          >
                            <option value="전선">전선</option>
                            <option value="전필">전필</option>
                            <option value="교선">교선</option>
                            <option value="교필">교필</option>
                            <option value="기필">기필</option>
                          </select>
                        </div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
                      <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
                        <label className="form-label">시간표 (선택)</label>
                        <input
                          type="text"
                          className="form-input"
                          placeholder="예: 화 1,2교시(비501)"
                          value={draftCourseSchedule}
                          onChange={(e) => setDraftCourseSchedule(e.target.value)}
                        />
                      </div>
                      <div className="form-group" style={{ width: 72, marginBottom: 0 }}>
                        <label className="form-label">학점</label>
                        <input
                          type="number"
                          min={1}
                          max={6}
                          className="form-input"
                          value={draftCourseCredit}
                          onChange={(e) => setDraftCourseCredit(e.target.value)}
                        />
                      </div>
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        style={{ height: 38, whiteSpace: 'nowrap' }}
                        onClick={handleAddCustomCourse}
                      >
                        <Plus size={14} /> 추가
                      </button>
                    </div>
                  </div>
                )}

                {customCourses.length > 0 && (
                  <div className="prof-signup-custom-list">
                    {customCourses.map((cc) => (
                      <span key={cc.id} className="prof-signup-custom-chip">
                        <strong>{cc.name}</strong>
                        {cc.code ? ` (${cc.code})` : ''} · {cc.section} · {formatSemesterLabel(cc.semester)}
                        <button
                          type="button"
                          className="chip-remove-btn"
                          onClick={() => handleRemoveCustomCourse(cc.id)}
                          aria-label="삭제"
                        >
                          <Trash2 size={12} />
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )}

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
