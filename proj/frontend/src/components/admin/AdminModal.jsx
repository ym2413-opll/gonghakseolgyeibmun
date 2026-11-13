// src/components/admin/AdminModal.jsx
import React, { useState, useEffect, useCallback } from 'react';
import { api } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import {
  ShieldAlert,
  X,
  Building2,
  BookPlus,
  Users,
  EyeOff,
  Eye,
  Ban,
  UserX,
  UserCheck,
  Mail,
  Terminal,
  PlusCircle,
  CheckCircle2,
  AlertTriangle,
  RefreshCw,
  Trash2
} from 'lucide-react';

const AdminModal = ({ isOpen, onClose, questions = [], onDataChanged, onDeleteQuestion }) => {
  const { departments, allCourses, refreshCatalog, refreshCurrentUser } = useAuth();
  const [activeTab, setActiveTab] = useState('catalog'); // 'catalog' | 'users' | 'posts' | 'emails'
  const [users, setUsers] = useState([]);
  const [emailLogs, setEmailLogs] = useState([]);
  const [statusMessage, setStatusMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  // Catalog form state
  const [deptName, setDeptName] = useState('');
  const [deptCollege, setDeptCollege] = useState('');

  const [courseCode, setCourseCode] = useState('');
  const [courseName, setCourseName] = useState('');
  const [courseSemester, setCourseSemester] = useState('2026-1');
  const [courseScopeType, setCourseScopeType] = useState('DEPARTMENT');
  const [courseCollege, setCourseCollege] = useState('');
  const [courseDept, setCourseDept] = useState('');

  const availableColleges = Array.from(
    new Set(
      [
        ...departments.map((d) => (d.college || '').trim()),
        ...allCourses.map((c) => (c.college || '').trim())
      ].filter((col) => col && col !== '전체' && col !== '공통' && col !== '광운대학교')
    )
  );
  const collegeOptions = availableColleges.length > 0
    ? availableColleges
    : ['전자정보공과대학', '인공지능융합대학', '공과대학', '자연과학대학', '인문사회과학대학', '정책법학대학', '경영대학', '참빛인재대학'];

  // User sanction duration state
  const [banDurationHours, setBanDurationHours] = useState(24);

  const loadAdminData = useCallback(async () => {
    setIsLoading(true);
    try {
      const [userList, emails] = await Promise.all([
        api.getAdminUsers(),
        api.getEmailLogs()
      ]);
      setUsers(Array.isArray(userList) ? userList : []);
      setEmailLogs(Array.isArray(emails) ? emails : []);
    } catch (err) {
      console.warn('Admin data load warning:', err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    loadAdminData();
    refreshCatalog();
  }, [isOpen, loadAdminData, refreshCatalog]);

  useEffect(() => {
    if (!isOpen) return;
    if (!courseDept && departments.length > 0) {
      setCourseDept(departments[0].name);
    }
    if (!courseCollege) {
      const cols = Array.from(
        new Set(
          [
            ...departments.map((d) => (d.college || '').trim()),
            ...allCourses.map((c) => (c.college || '').trim())
          ].filter((col) => col && col !== '전체' && col !== '공통' && col !== '광운대학교')
        )
      );
      setCourseCollege(cols[0] || '전자정보공과대학');
    }
  }, [isOpen, departments, allCourses, courseDept, courseCollege]);

  if (!isOpen) return null;

  const notifySuccess = (msg) => {
    setErrorMessage('');
    setStatusMessage(msg);
    setTimeout(() => setStatusMessage(''), 4000);
  };

  const notifyError = (msg) => {
    setStatusMessage('');
    setErrorMessage(msg);
  };

  const handleAddDepartment = async (e) => {
    e.preventDefault();
    if (!deptName.trim()) return;
    const addedDeptName = deptName.trim();
    try {
      await api.createDepartment({
        name: addedDeptName,
        college: deptCollege.trim() || '광운대학교'
      });
      setDeptName('');
      setDeptCollege('');
      await refreshCatalog();
      notifySuccess(`학과 '${addedDeptName}'이(가) 동적으로 추가되었습니다.`);
    } catch (err) {
      notifyError(err.message || '학과 추가 중 오류가 발생했습니다.');
    }
  };

  const handleAddCourse = async (e) => {
    e.preventDefault();
    if (!courseCode.trim() || !courseName.trim()) return;
    if (courseScopeType === 'DEPARTMENT' && !courseDept) {
      notifyError('주관 학과를 선택해주세요.');
      return;
    }
    if (courseScopeType === 'COLLEGE' && !courseCollege) {
      notifyError('주관 단과대를 선택해주세요.');
      return;
    }

    const matchedDept = departments.find((d) => d.name === courseDept);
    const resolvedCollege =
      courseScopeType === 'ALL'
        ? '전체'
        : courseScopeType === 'COLLEGE'
          ? courseCollege
          : (matchedDept?.college || courseCollege || '');
    const resolvedDepartment =
      courseScopeType === 'ALL'
        ? '전체'
        : courseScopeType === 'COLLEGE'
          ? `${courseCollege} 전체`
          : courseDept;

    const addedName = courseName.trim();
    const addedCode = courseCode.trim();
    try {
      await api.createCourse({
        code: addedCode,
        name: addedName,
        semester: courseSemester.trim() || '2026-1',
        scopeType: courseScopeType,
        college: resolvedCollege,
        department: resolvedDepartment
      });
      setCourseCode('');
      setCourseName('');
      await refreshCatalog();
      if (onDataChanged) await onDataChanged();
      notifySuccess(`과목 '${addedName} (${addedCode})'이(가) 동적으로 추가되었습니다.`);
    } catch (err) {
      notifyError(err.message || '과목 추가 중 오류가 발생했습니다.');
    }
  };

  const handleSanction = async (targetUser, action) => {
    try {
      await api.sanctionUser(targetUser.id, {
        action,
        durationHours: Number(banDurationHours) || 24,
        durationDays: 0
      });
      await loadAdminData();
      await refreshCurrentUser();
      notifySuccess(`'${targetUser.name} (${targetUser.username || targetUser.identifier})' 계정에 [${action}] 조치가 적용되었습니다.`);
    } catch (err) {
      notifyError(err.message || '제재 적용에 실패했습니다.');
    }
  };

  const handleToggleQuestionHide = async (q) => {
    try {
      await api.toggleHideQuestion(q.id, !q.isHidden);
      if (onDataChanged) await onDataChanged();
      notifySuccess(`질문 ${!q.isHidden ? '가리기' : '가리기 해제'} 처리 완료`);
    } catch (err) {
      notifyError(err.message || '게시물 상태 변경 실패');
    }
  };

  const handleDeletePost = async (q) => {
    if (!q?.id) return;
    if (onDeleteQuestion) {
      await onDeleteQuestion(q);
      return;
    }
    if (!window.confirm('이 질문 게시물을 영구 삭제하시겠습니까?')) return;
    try {
      await api.deleteQuestion(q.id);
      if (onDataChanged) await onDataChanged();
      notifySuccess('질문이 영구 삭제되었습니다.');
    } catch (err) {
      notifyError(err.message || '게시물 삭제 실패');
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal-container modal-xl admin-modal-container"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header admin-modal-header">
          <div className="modal-title">
            <ShieldAlert size={20} className="text-danger" />
            <span>관리자 모드</span>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="admin-tabs-bar">
          <button
            type="button"
            className={`admin-tab-btn ${activeTab === 'catalog' ? 'active' : ''}`}
            onClick={() => setActiveTab('catalog')}
          >
            <Building2 size={16} />
            <span>학과 · 과목</span>
          </button>
          <button
            type="button"
            className={`admin-tab-btn ${activeTab === 'users' ? 'active' : ''}`}
            onClick={() => setActiveTab('users')}
          >
            <Users size={16} />
            <span>계정 ({users.length})</span>
          </button>
          <button
            type="button"
            className={`admin-tab-btn ${activeTab === 'posts' ? 'active' : ''}`}
            onClick={() => setActiveTab('posts')}
          >
            <EyeOff size={16} />
            <span>게시물 ({questions.length})</span>
          </button>
          <button
            type="button"
            className={`admin-tab-btn ${activeTab === 'emails' ? 'active' : ''}`}
            onClick={() => setActiveTab('emails')}
          >
            <Mail size={16} />
            <span>이메일 ({emailLogs.length})</span>
          </button>
        </div>

        <div className="modal-body admin-modal-body">
          {statusMessage && (
            <div className="auth-gate-alert success" style={{ marginBottom: 14 }}>
              <CheckCircle2 size={16} />
              <span>{statusMessage}</span>
            </div>
          )}
          {errorMessage && (
            <div className="auth-gate-alert error" style={{ marginBottom: 14 }}>
              <AlertTriangle size={16} />
              <span>{errorMessage}</span>
            </div>
          )}

          {activeTab === 'catalog' && (
            <div className="admin-catalog-grid">
              <div className="admin-panel-card">
                <h3 className="admin-panel-title">
                  <Building2 size={18} />
                  학과(Department) 동적 추가
                </h3>
                <form onSubmit={handleAddDepartment} className="admin-compact-form">
                  <div className="form-group">
                    <label className="form-label">학과 정식 명칭 *</label>
                    <input
                      type="text"
                      className="form-input"
                      placeholder="예: 인공지능융합학과"
                      value={deptName}
                      onChange={(e) => setDeptName(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label className="form-label">소속 단과대학</label>
                    <input
                      type="text"
                      className="form-input"
                      list="admin-college-options"
                      placeholder="예: 인공지능융합대학 (미입력 시 광운대학교)"
                      value={deptCollege}
                      onChange={(e) => setDeptCollege(e.target.value)}
                    />
                    <datalist id="admin-college-options">
                      {collegeOptions.map((col) => (
                        <option key={col} value={col} />
                      ))}
                    </datalist>
                  </div>
                  <button type="submit" className="btn btn-primary btn-sm">
                    <PlusCircle size={15} />
                    학과 즉시 추가
                  </button>
                </form>

                <div className="admin-chip-list-wrap">
                  <div className="admin-chip-header">현재 등록된 학과 ({departments.length}개)</div>
                  <div className="admin-chip-list">
                    {departments.map((d) => {
                      const subLabel = (d.college || d.shortName || '').trim();
                      return (
                        <span key={d.id || d.name} className="admin-dept-chip">
                          {d.name}
                          {subLabel ? <small> ({subLabel})</small> : null}
                        </span>
                      );
                    })}
                  </div>
                </div>
              </div>

              <div className="admin-panel-card">
                <h3 className="admin-panel-title">
                  <BookPlus size={18} />
                  교과목(Course) 동적 추가
                </h3>
                <form onSubmit={handleAddCourse} className="admin-compact-form">
                  <div className="auth-form-grid-2">
                    <div className="form-group">
                      <label className="form-label">학수번호(코드) *</label>
                      <input
                        type="text"
                        className="form-input"
                        placeholder="예: SW401-01"
                        value={courseCode}
                        onChange={(e) => setCourseCode(e.target.value)}
                        required
                      />
                    </div>
                    <div className="form-group">
                      <label className="form-label">개설 학기</label>
                      <input
                        type="text"
                        className="form-input"
                        placeholder="예: 2026-1"
                        value={courseSemester}
                        onChange={(e) => setCourseSemester(e.target.value)}
                      />
                    </div>
                  </div>

                  <div className="form-group">
                    <label className="form-label">교과목명 *</label>
                    <input
                      type="text"
                      className="form-input"
                      placeholder="예: 클라우드네이티브아키텍처"
                      value={courseName}
                      onChange={(e) => setCourseName(e.target.value)}
                      required
                    />
                  </div>

                  <div className="auth-form-grid-2">
                    <div className="form-group">
                      <label className="form-label">주관 단위</label>
                      <select
                        className="form-select"
                        value={courseScopeType}
                        onChange={(e) => setCourseScopeType(e.target.value)}
                      >
                        <option value="DEPARTMENT">학과 단위</option>
                        <option value="COLLEGE">단과대 단위</option>
                        <option value="ALL">전체 (전교 공통)</option>
                      </select>
                    </div>
                    <div className="form-group">
                      <label className="form-label">
                        {courseScopeType === 'ALL'
                          ? '적용 대상'
                          : courseScopeType === 'COLLEGE'
                            ? '주관 단과대'
                            : '주관 학과'}
                      </label>
                      {courseScopeType === 'ALL' ? (
                        <input
                          type="text"
                          className="form-input"
                          value="전체 (모든 단과대·학과)"
                          readOnly
                          disabled
                        />
                      ) : courseScopeType === 'COLLEGE' ? (
                        <select
                          className="form-select"
                          value={courseCollege}
                          onChange={(e) => setCourseCollege(e.target.value)}
                        >
                          {collegeOptions.map((col) => (
                            <option key={col} value={col}>
                              {col} 전체
                            </option>
                          ))}
                        </select>
                      ) : (
                        <select
                          className="form-select"
                          value={courseDept}
                          onChange={(e) => setCourseDept(e.target.value)}
                        >
                          {departments.map((d) => (
                            <option key={d.id || d.name} value={d.name}>
                              {d.name}{d.college ? ` (${d.college})` : ''}
                            </option>
                          ))}
                        </select>
                      )}
                    </div>
                  </div>

                  <button type="submit" className="btn btn-primary btn-sm">
                    <PlusCircle size={15} />
                    교과목 즉시 추가
                  </button>
                </form>

                <div className="admin-chip-list-wrap">
                  <div className="admin-chip-header">등록된 교과목 ({allCourses.length}개)</div>
                  <div className="admin-chip-list">
                    {allCourses.slice(0, 24).map((c) => {
                      const profNames = Array.isArray(c.professors)
                        ? c.professors.map((p) => p?.name).filter(Boolean)
                        : [];
                      const profLabel =
                        profNames.length > 0
                          ? profNames.join(', ')
                          : c.profName && c.profName !== '미배정'
                            ? c.profName
                            : '미배정';
                      return (
                        <span key={c.id} className="admin-dept-chip">
                          {c.name}{' '}
                          <small>
                            ({c.code}
                            {c.department ? ` · ${c.department}` : ''}
                            {` · 담당: ${profLabel}`})
                          </small>
                        </span>
                      );
                    })}
                  </div>
                </div>
              </div>
            </div>
          )}

          {activeTab === 'users' && (
            <div>
              <div className="admin-sanction-toolbar">
                <div>
                  <strong>질문/답변 제한 기간 설정:</strong> 제재 버튼 클릭 시 아래 선택한 기간만큼 작성 권한이 제한됩니다.
                </div>
                <div className="admin-duration-select-wrap">
                  <label>제한 기간:</label>
                  <select
                    className="form-select form-select-sm"
                    value={banDurationHours}
                    onChange={(e) => setBanDurationHours(Number(e.target.value))}
                  >
                    <option value={1}>1시간</option>
                    <option value={6}>6시간</option>
                    <option value={24}>1일 (24시간)</option>
                    <option value={72}>3일 (72시간)</option>
                    <option value={168}>7일 (1주일)</option>
                    <option value={720}>30일 (1개월)</option>
                  </select>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={loadAdminData}
                  >
                    <RefreshCw size={14} /> 새로고침
                  </button>
                </div>
              </div>

              {users.length === 0 ? (
                <div className="empty-state-box">
                  가입된 일반 계정이 아직 없습니다. 회원가입으로 새 계정을 생성해보세요.
                </div>
              ) : (
                <div className="admin-table-wrap">
                  <table className="admin-data-table">
                    <thead>
                      <tr>
                        <th>ID / 이름</th>
                        <th>역할 / 학과</th>
                        <th>학번 / 이메일</th>
                        <th>상태 (탈퇴 · 제한 만료일)</th>
                        <th>관리 조치 (탈퇴 / 질문·답변 제한)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {users.map((u) => {
                        const qBanned =
                          u.questionBanUntil && new Date(u.questionBanUntil) > new Date();
                        const aBanned =
                          u.answerBanUntil && new Date(u.answerBanUntil) > new Date();
                        return (
                          <tr key={u.id} className={u.isWithdrawn ? 'row-withdrawn' : ''}>
                            <td>
                              <div className="font-semibold">
                                {u.name}{' '}
                                {u.isAdmin && (
                                  <span className="admin-inline-badge">ADMIN</span>
                                )}
                              </div>
                              <div className="text-xs text-muted">@{u.username || u.id.slice(0, 8)}</div>
                            </td>
                            <td>
                              <span className="badge badge-role">
                                {String(u.role).toUpperCase() === 'PROFESSOR' ? '교수' : '학생'}
                              </span>
                              <div className="text-xs text-muted">{u.department}</div>
                            </td>
                            <td>
                              <div className="text-xs">{u.identifier || '-'}</div>
                              {u.email && <div className="text-xs text-muted">{u.email}</div>}
                            </td>
                            <td>
                              {u.isWithdrawn ? (
                                <span className="status-pill danger">탈퇴(차단됨)</span>
                              ) : (
                                <span className="status-pill success">정상 활동</span>
                              )}
                              {qBanned && (
                                <div className="text-xs text-danger">
                                  질문제한: ~{new Date(u.questionBanUntil).toLocaleString('ko-KR')}
                                </div>
                              )}
                              {aBanned && (
                                <div className="text-xs text-danger">
                                  답변제한: ~{new Date(u.answerBanUntil).toLocaleString('ko-KR')}
                                </div>
                              )}
                            </td>
                            <td>
                              <div className="admin-action-btns">
                                {u.isWithdrawn ? (
                                  <button
                                    type="button"
                                    className="btn btn-secondary btn-xs"
                                    onClick={() => handleSanction(u, 'RESTORE')}
                                  >
                                    <UserCheck size={13} /> 탈퇴 복구
                                  </button>
                                ) : (
                                  <button
                                    type="button"
                                    className="btn btn-danger btn-xs"
                                    onClick={() => handleSanction(u, 'WITHDRAW')}
                                  >
                                    <UserX size={13} /> 강제 탈퇴
                                  </button>
                                )}

                                {qBanned ? (
                                  <button
                                    type="button"
                                    className="btn btn-secondary btn-xs"
                                    onClick={() => handleSanction(u, 'UNBAN_QUESTION')}
                                  >
                                    질문 제한 해제
                                  </button>
                                ) : (
                                  <button
                                    type="button"
                                    className="btn btn-outline btn-xs"
                                    onClick={() => handleSanction(u, 'BAN_QUESTION')}
                                  >
                                    <Ban size={12} /> 질문 제한 ({banDurationHours}h)
                                  </button>
                                )}

                                {aBanned ? (
                                  <button
                                    type="button"
                                    className="btn btn-secondary btn-xs"
                                    onClick={() => handleSanction(u, 'UNBAN_ANSWER')}
                                  >
                                    답변 제한 해제
                                  </button>
                                ) : (
                                  <button
                                    type="button"
                                    className="btn btn-outline btn-xs"
                                    onClick={() => handleSanction(u, 'BAN_ANSWER')}
                                  >
                                    <Ban size={12} /> 답변 제한 ({banDurationHours}h)
                                  </button>
                                )}
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {activeTab === 'posts' && (
            <div>
              <div className="admin-table-wrap">
                <table className="admin-data-table">
                  <thead>
                    <tr>
                      <th>과목 / 카테고리</th>
                      <th>질문 제목</th>
                      <th>표시 작성자</th>
                      <th>실제 질문자</th>
                      <th>상태</th>
                      <th>관리</th>
                    </tr>
                  </thead>
                  <tbody>
                    {questions.map((q) => (
                      <tr key={q.id} className={q.isHidden ? 'row-withdrawn' : ''}>
                        <td>
                          <div className="font-semibold">{q.courseName}</div>
                          <div className="text-xs text-muted">{String(q.category).toUpperCase()}</div>
                        </td>
                        <td>
                          <div className="font-semibold">{q.title}</div>
                          <div className="text-xs text-muted">
                            답변 {(q.answers || []).length}개 · {q.createdAt?.slice(0, 16).replace('T', ' ')}
                          </div>
                        </td>
                        <td>
                          {q.isAnonymous ? (
                            <span className="status-pill warning">익명</span>
                          ) : (
                            <span>{q.studentTag || q.studentName}</span>
                          )}
                        </td>
                        <td>
                          <span className="admin-real-author-badge">
                            {q.realStudentTag || q.realStudentName || q.studentTag || q.studentName}
                            {q.realStudentIdentifier ? ` (${q.realStudentIdentifier})` : ''}
                          </span>
                        </td>
                        <td>
                          {q.isHidden ? (
                            <span className="status-pill danger">숨김</span>
                          ) : (
                            <span className="status-pill success">공개</span>
                          )}
                        </td>
                        <td>
                          <div className="admin-action-btns">
                            <button
                              type="button"
                              className={`btn btn-xs ${q.isHidden ? 'btn-secondary' : 'btn-outline'}`}
                              onClick={() => handleToggleQuestionHide(q)}
                            >
                              {q.isHidden ? (
                                <>
                                  <Eye size={13} /> 해제
                                </>
                              ) : (
                                <>
                                  <EyeOff size={13} /> 가리기
                                </>
                              )}
                            </button>
                            <button
                              type="button"
                              className="btn btn-danger btn-xs"
                              onClick={() => handleDeletePost(q)}
                            >
                              <Trash2 size={13} /> 삭제
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {activeTab === 'emails' && (
            <div className="admin-panel-card">
              <h3 className="admin-panel-title">
                <Mail size={18} />
                교수 질문 알림 이메일 발송 내역 ({emailLogs.length}건)
              </h3>
              <p className="text-xs text-muted" style={{ marginBottom: 8 }}>
                <Terminal size={13} style={{ display: 'inline', marginRight: 4 }} />
                보안 정책상 관리자 권한 부여/회수는 백엔드 서버 터미널 콘솔(<code>admin &lt;id&gt;</code>)에서만 수행할 수 있습니다.
              </p>
              {emailLogs.length === 0 ? (
                <div className="empty-state-box">
                  아직 발송된 이메일 알림 내역이 없습니다. 학생 계정으로 질문을 등록하면 연동된 교수님의 이메일로 즉시 발송 및 기록됩니다.
                </div>
              ) : (
                <div className="admin-email-log-list">
                  {emailLogs.map((log) => {
                    const statusBadge =
                      log.status === 'SENT_SMTP'
                        ? { cls: 'success', label: 'SMTP 발송 완료' }
                        : log.status === 'SENT_LOGGED'
                          ? { cls: 'danger', label: 'SMTP 발송 실패 (로그 기록)' }
                          : log.status === 'QUEUED'
                            ? { cls: 'warning', label: '발송 대기' }
                            : { cls: 'warning', label: 'SMTP 미설정 (DB 로그만 기록)' };
                    return (
                      <div key={log.id} className="admin-email-log-item">
                        <div className="admin-email-log-top">
                          <strong>To: {log.recipientEmail}</strong>
                          <span className={`status-pill ${statusBadge.cls}`} title={log.status}>
                            {statusBadge.label}
                          </span>
                        </div>
                        <div className="admin-email-log-subject">{log.subject}</div>
                        <div className="text-xs text-muted">
                          {log.createdAt?.slice(0, 19).replace('T', ' ')}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
};

export default AdminModal;
