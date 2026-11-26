// src/components/admin/AdminModal.jsx
import React, { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import ProfessorNameList from '../common/ProfessorNameList';
import { CourseSelectPicker } from '../common/CoursePicker';
import AdminNicknameForm from './AdminNicknameForm';
import HelpBubble from '../common/HelpBubble';
import {
  buildCourseLineageChain,
  collectSelfAndDescendantIds,
  extractCourseScopes,
  sortCoursesList
} from '../../utils/courseLineage';
import {
  buildSemesterOptions,
  compareSemesters,
  formatSemesterLabel,
  isNonCurrentSemesterThread,
  normalizeSemester,
  parseSemesterOrNull
} from '../../utils/semester';
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
  Trash2,
  Sparkles,
  Check,
  Pencil,
  Search,
  Upload,
  Link2,
  Calendar
} from 'lucide-react';

const CatalogChipList = ({
  items = [],
  entityKey,
  getSubLabel,
  editingAlias,
  onStartRenameAlias,
  onChangeEditingAliasValue,
  onSubmitRenameAlias,
  onCancelRenameAlias,
  onRemoveAlias,
  onStartEditItem
}) => (
  <div className="admin-chip-list">
    {items.map((item) => {
      const subLabel = getSubLabel(item);
      const aliases = Array.isArray(item.aliases) ? item.aliases : [];
      return (
        <span key={item.id || item.name} className="admin-dept-chip">
          {item.name}
          {subLabel ? <small> ({subLabel})</small> : null}
          <button
            type="button"
            title={`${item.name} 정보/명칭 수정`}
            aria-label={`${item.name} 정보 수정`}
            onClick={() => onStartEditItem(item)}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              padding: '0 3px',
              marginLeft: '4px',
              color: 'inherit',
              opacity: 0.75,
              verticalAlign: 'middle'
            }}
          >
            <Pencil size={11} />
          </button>
          {aliases.map((alias) => {
            const isRenamingThis =
              editingAlias &&
              editingAlias.entityKey === entityKey &&
              String(editingAlias.itemId) === String(item.id) &&
              editingAlias.oldAlias === alias;

            if (isRenamingThis) {
              return (
                <span
                  key={alias}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                    marginLeft: '6px'
                  }}
                >
                  <input
                    type="text"
                    className="form-input"
                    value={editingAlias.value}
                    onChange={(e) => onChangeEditingAliasValue(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        onSubmitRenameAlias();
                      } else if (e.key === 'Escape') {
                        onCancelRenameAlias();
                      }
                    }}
                    style={{ width: '115px', padding: '1px 6px', fontSize: '0.75em', height: '22px' }}
                    autoFocus
                  />
                  <button
                    type="button"
                    aria-label="별칭 저장"
                    onClick={onSubmitRenameAlias}
                    className="btn btn-primary btn-xs"
                    style={{ padding: '1px 6px', fontSize: '0.72em' }}
                  >
                    저장
                  </button>
                  <button
                    type="button"
                    aria-label="별칭 수정 취소"
                    onClick={onCancelRenameAlias}
                    className="btn btn-outline btn-xs"
                    style={{ padding: '1px 6px', fontSize: '0.72em' }}
                  >
                    취소
                  </button>
                </span>
              );
            }

            return (
              <small
                key={alias}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '4px',
                  marginLeft: '6px',
                  padding: '1px 6px',
                  borderRadius: '999px',
                  backgroundColor: 'rgba(139, 92, 246, 0.12)',
                  fontSize: '0.75em'
                }}
              >
                구: {alias}
                <button
                  type="button"
                  title={`${alias} 별칭 이름 변경`}
                  aria-label={`${alias} 별칭 변경`}
                  onClick={() => onStartRenameAlias(entityKey, item.id, alias)}
                  style={{
                    border: 'none',
                    background: 'transparent',
                    cursor: 'pointer',
                    padding: 0,
                    lineHeight: 1,
                    color: 'inherit',
                    opacity: 0.8
                  }}
                >
                  <Pencil size={10} />
                </button>
                <button
                  type="button"
                  title={`${alias} 별칭 삭제`}
                  aria-label={`${alias} 별칭 삭제`}
                  onClick={() => onRemoveAlias(item.id, alias)}
                  style={{
                    border: 'none',
                    background: 'transparent',
                    cursor: 'pointer',
                    padding: 0,
                    lineHeight: 1,
                    color: 'inherit',
                    fontWeight: 700
                  }}
                >
                  ×
                </button>
              </small>
            );
          })}
        </span>
      );
    })}
  </div>
);

const CourseScopeTargetField = ({
  scopeType,
  college,
  department,
  collegeOptions,
  departments,
  onChangeCollege,
  onChangeDepartment
}) => {
  const hasExtraCollege = Boolean(college && !collegeOptions.includes(college));
  const hasExtraDept = Boolean(department && !departments.some((d) => d.name === department));

  return (
    <div className="form-group">
      <label className="form-label">
        {scopeType === 'ALL'
          ? '적용 대상'
          : scopeType === 'COLLEGE'
            ? '주관 단과대'
            : '주관 학과'}
      </label>
      {scopeType === 'ALL' ? (
        <input
          type="text"
          className="form-input"
          value="전체 (모든 단과대·학과)"
          readOnly
          disabled
        />
      ) : scopeType === 'COLLEGE' ? (
        <select
          className="form-select"
          value={college}
          onChange={(e) => onChangeCollege(e.target.value)}
        >
          {hasExtraCollege && <option value={college}>{college} 전체</option>}
          {collegeOptions.map((col) => (
            <option key={col} value={col}>
              {col} 전체
            </option>
          ))}
        </select>
      ) : (
        <select
          className="form-select"
          value={department}
          onChange={(e) => onChangeDepartment(e.target.value)}
        >
          {hasExtraDept && <option value={department}>{department}</option>}
          {departments.map((d) => (
            <option key={d.id || d.name} value={d.name}>
              {d.name}{d.college ? ` (${d.college})` : ''}
            </option>
          ))}
        </select>
      )}
    </div>
  );
};

const AdminModal = ({ isOpen, onClose, questions = [], onDataChanged, onDeleteQuestion }) => {
  const {
    colleges = [],
    departments,
    allCourses,
    currentSemester = '2026-2',
    availableSemesters = [],
    refreshCatalog,
    refreshCurrentUser
  } = useAuth();
  const { exhibitionMode, updateExhibitionMode } = useTheme();
  const [activeTab, setActiveTab] = useState('catalog'); // 'catalog' | 'users' | 'posts' | 'emails'
  const [users, setUsers] = useState([]);
  const [emailLogs, setEmailLogs] = useState([]);
  const [statusMessage, setStatusMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const statusTimerRef = useRef(null);

  // Catalog form state (Departments)
  const [deptName, setDeptName] = useState('');
  const [deptCollege, setDeptCollege] = useState('');
  const [deptAliasesInput, setDeptAliasesInput] = useState('');
  const [aliasTargetDeptId, setAliasTargetDeptId] = useState('');
  const [aliasNewName, setAliasNewName] = useState('');
  const [editingDept, setEditingDept] = useState(null); // { id, name, college, keepOldNameAsAlias }

  // Catalog form state (Colleges)
  const [newCollegeName, setNewCollegeName] = useState('');
  const [newCollegeAliasesInput, setNewCollegeAliasesInput] = useState('');
  const [aliasTargetCollegeId, setAliasTargetCollegeId] = useState('');
  const [collegeAliasNewName, setCollegeAliasNewName] = useState('');
  const [editingCollege, setEditingCollege] = useState(null); // { id, name, keepOldNameAsAlias }

  // Inline alias rename state shared for departments & colleges
  const [editingAlias, setEditingAlias] = useState(null); // { entityKey: 'dept'|'college', itemId, oldAlias, value }

  const [courseCode, setCourseCode] = useState('');
  const [courseName, setCourseName] = useState('');
  const [courseSemester, setCourseSemester] = useState('');
  const [courseScopeType, setCourseScopeType] = useState('DEPARTMENT');
  const [courseCollege, setCourseCollege] = useState('');
  const [courseDept, setCourseDept] = useState('');
  const [coursePrevId, setCoursePrevId] = useState('AUTO'); // 'AUTO' | 'NONE' | '<uuid>'
  const [editingCourse, setEditingCourse] = useState(null); // { id, name, code, semester, scopeType, college, department, prevCourseId }
  const [courseSearchQuery, setCourseSearchQuery] = useState('');
  const [courseSemesterFilter, setCourseSemesterFilter] = useState('ALL');
  const [courseDeptFilter, setCourseDeptFilter] = useState('ALL');
  const [courseSortBy, setCourseSortBy] = useState('semester_desc');
  const [courseListLimit, setCourseListLimit] = useState(24);

  // Semester rollover & Excel bulk import state
  const [rolloverTargetSem, setRolloverTargetSem] = useState('');
  const [rolloverAutoLink, setRolloverAutoLink] = useState(true);
  const [isRollingOver, setIsRollingOver] = useState(false);

  const [excelFile, setExcelFile] = useState(null);
  const [excelTargetSem, setExcelTargetSem] = useState('');
  const [excelAutoLink, setExcelAutoLink] = useState(true);
  const [excelSetCurrent, setExcelSetCurrent] = useState(false);
  const [isImportingExcel, setIsImportingExcel] = useState(false);
  const excelInputRef = useRef(null);

  const semesterOptions = buildSemesterOptions(currentSemester, availableSemesters);
  const effectiveCourseSemester = courseSemester || currentSemester || '2026-2';
  const effectiveRolloverSem = rolloverTargetSem || currentSemester || '2026-2';

  const availableColleges = Array.from(
    new Set(
      [
        ...colleges.map((c) => (c.name || '').trim()),
        ...departments.map((d) => (d.college || '').trim()),
        ...allCourses.map((c) => (c.college || '').trim())
      ].filter((col) => col && col !== '전체' && col !== '공통' && col !== '광운대학교')
    )
  );
  const collegeOptions = availableColleges.length > 0
    ? availableColleges
    : ['전자정보공과대학', '인공지능융합대학', '공과대학', '자연과학대학', '인문사회과학대학', '정책법학대학', '경영대학', '참빛인재대학'];

  const effectiveCourseDept = departments.some((d) => d.name === courseDept)
    ? courseDept
    : (departments[0]?.name || '');
  const effectiveCourseCollege = collegeOptions.includes(courseCollege)
    ? courseCollege
    : (collegeOptions[0] || '');
  const effectiveAliasTargetDeptId = departments.some((d) => String(d.id) === String(aliasTargetDeptId))
    ? aliasTargetDeptId
    : (departments[0]?.id || '');
  const effectiveAliasTargetCollegeId = colleges.some((c) => String(c.id) === String(aliasTargetCollegeId))
    ? aliasTargetCollegeId
    : (colleges[0]?.id || '');

  // User sanction duration state
  const [banDurationHours, setBanDurationHours] = useState(24);

  const loadAdminData = useCallback(async () => {
    try {
      const [userList, emails] = await Promise.all([
        api.getAdminUsers(),
        api.getEmailLogs()
      ]);
      setUsers(Array.isArray(userList) ? userList : []);
      setEmailLogs(Array.isArray(emails) ? emails : []);
    } catch (err) {
      console.warn('Admin data load warning:', err);
    }
  }, []);

  useEffect(() => {
    if (!isOpen) {
      setEditingDept(null);
      setEditingCollege(null);
      setEditingAlias(null);
      setEditingCourse(null);
      setCourseSearchQuery('');
      setCourseListLimit(24);
      return;
    }
    loadAdminData();
    refreshCatalog();
  }, [isOpen, loadAdminData, refreshCatalog]);

  useEffect(() => () => {
    if (statusTimerRef.current) clearTimeout(statusTimerRef.current);
  }, []);

  if (!isOpen) return null;

  const notifySuccess = (msg) => {
    setErrorMessage('');
    setStatusMessage(msg);
    if (statusTimerRef.current) clearTimeout(statusTimerRef.current);
    statusTimerRef.current = setTimeout(() => setStatusMessage(''), 4000);
  };

  const notifyError = (msg) => {
    setStatusMessage('');
    setErrorMessage(msg);
  };

  const handleAddDepartment = async (e) => {
    e.preventDefault();
    if (!deptName.trim()) return;
    const addedDeptName = deptName.trim();
    const initialAliases = deptAliasesInput
      .split(',')
      .map((a) => a.trim())
      .filter(Boolean);
    try {
      await api.createDepartment({
        name: addedDeptName,
        college: deptCollege.trim() || '광운대학교',
        aliases: initialAliases
      });
      setDeptName('');
      setDeptCollege('');
      setDeptAliasesInput('');
      await refreshCatalog();
      notifySuccess(`학과 '${addedDeptName}'이(가) 동적으로 추가되었습니다.`);
    } catch (err) {
      notifyError(err.message || '학과 추가 중 오류가 발생했습니다.');
    }
  };

  const handleAddAlias = async (e) => {
    e.preventDefault();
    const targetId = effectiveAliasTargetDeptId;
    const cleanAlias = aliasNewName.trim();
    if (!targetId || !cleanAlias) return;
    try {
      const updated = await api.addDepartmentAlias(targetId, cleanAlias);
      setAliasNewName('');
      await refreshCatalog();
      notifySuccess(`'${updated?.name || '학과'}'에 기존 명칭(별칭) '${cleanAlias}'이(가) 연결되었습니다.`);
    } catch (err) {
      notifyError(err.message || '학과 별칭 추가 중 오류가 발생했습니다.');
    }
  };

  const handleRemoveAlias = async (deptId, aliasName) => {
    try {
      await api.removeDepartmentAlias(deptId, aliasName);
      await refreshCatalog();
      notifySuccess(`학과 별칭 '${aliasName}'이(가) 제거되었습니다.`);
    } catch (err) {
      notifyError(err.message || '학과 별칭 삭제 중 오류가 발생했습니다.');
    }
  };

  const handleSaveEditedDept = async (e) => {
    e.preventDefault();
    if (!editingDept || !editingDept.name.trim()) return;
    try {
      const updated = await api.updateDepartment(editingDept.id, {
        name: editingDept.name.trim(),
        college: editingDept.college.trim() || undefined,
        keepOldNameAsAlias: editingDept.keepOldNameAsAlias
      });
      setEditingDept(null);
      await Promise.all([refreshCatalog(), refreshCurrentUser()]);
      if (onDataChanged) await onDataChanged();
      notifySuccess(`학과 '${updated?.name || editingDept.name}' 정보가 수정되었습니다.`);
    } catch (err) {
      notifyError(err.message || '학과 수정 중 오류가 발생했습니다.');
    }
  };

  const handleAddCollege = async (e) => {
    e.preventDefault();
    if (!newCollegeName.trim()) return;
    const addedName = newCollegeName.trim();
    const initialAliases = newCollegeAliasesInput
      .split(',')
      .map((a) => a.trim())
      .filter(Boolean);
    try {
      await api.createCollege({
        name: addedName,
        aliases: initialAliases
      });
      setNewCollegeName('');
      setNewCollegeAliasesInput('');
      await refreshCatalog();
      notifySuccess(`단과대학 '${addedName}'이(가) 추가되었습니다.`);
    } catch (err) {
      notifyError(err.message || '단과대학 추가 중 오류가 발생했습니다.');
    }
  };

  const handleAddCollegeAlias = async (e) => {
    e.preventDefault();
    const targetId = effectiveAliasTargetCollegeId;
    const cleanAlias = collegeAliasNewName.trim();
    if (!targetId || !cleanAlias) return;
    try {
      const updated = await api.addCollegeAlias(targetId, cleanAlias);
      setCollegeAliasNewName('');
      await refreshCatalog();
      notifySuccess(`'${updated?.name || '단과대학'}'에 기존 명칭(별칭) '${cleanAlias}'이(가) 연결되었습니다.`);
    } catch (err) {
      notifyError(err.message || '단과대학 별칭 추가 중 오류가 발생했습니다.');
    }
  };

  const handleRemoveCollegeAlias = async (collegeId, aliasName) => {
    try {
      await api.removeCollegeAlias(collegeId, aliasName);
      await refreshCatalog();
      notifySuccess(`단과대학 별칭 '${aliasName}'이(가) 제거되었습니다.`);
    } catch (err) {
      notifyError(err.message || '단과대학 별칭 삭제 중 오류가 발생했습니다.');
    }
  };

  const handleSaveEditedCollege = async (e) => {
    e.preventDefault();
    if (!editingCollege || !editingCollege.name.trim()) return;
    try {
      const updated = await api.updateCollege(editingCollege.id, {
        name: editingCollege.name.trim(),
        keepOldNameAsAlias: editingCollege.keepOldNameAsAlias
      });
      setEditingCollege(null);
      await Promise.all([refreshCatalog(), refreshCurrentUser()]);
      if (onDataChanged) await onDataChanged();
      notifySuccess(`단과대학 '${updated?.name || editingCollege.name}' 정보가 수정되었습니다.`);
    } catch (err) {
      notifyError(err.message || '단과대학 수정 중 오류가 발생했습니다.');
    }
  };

  const handleSubmitRenameAlias = async () => {
    if (!editingAlias || !editingAlias.value.trim()) return;
    try {
      if (editingAlias.entityKey === 'dept') {
        await api.renameDepartmentAlias(editingAlias.itemId, editingAlias.oldAlias, editingAlias.value.trim());
      } else {
        await api.renameCollegeAlias(editingAlias.itemId, editingAlias.oldAlias, editingAlias.value.trim());
      }
      setEditingAlias(null);
      await refreshCatalog();
      notifySuccess('별칭이 성공적으로 변경되었습니다.');
    } catch (err) {
      notifyError(err.message || '별칭 변경 중 오류가 발생했습니다.');
    }
  };

  const resolveScopeOrganization = (scopeType, selectedCollege, selectedDept) => {
    if (scopeType === 'ALL') {
      return { college: '전체', department: '전체' };
    }
    if (scopeType === 'COLLEGE') {
      const targetCol = selectedCollege || effectiveCourseCollege || '';
      return { college: targetCol, department: targetCol ? `${targetCol} 전체` : '단과대 전체' };
    }
    const targetDept = selectedDept || effectiveCourseDept || '공통';
    const matchedDept = departments.find((d) => d.name === targetDept);
    return {
      college: matchedDept?.college || (targetDept === '공통' ? '광운대학교' : selectedCollege || effectiveCourseCollege || ''),
      department: targetDept
    };
  };

  const handleRolloverSemester = async (e) => {
    e.preventDefault();
    const cleanTarget = normalizeSemester(effectiveRolloverSem, currentSemester);
    setIsRollingOver(true);
    try {
      const res = await api.rolloverSemester({
        targetSemester: cleanTarget,
        autoLinkSameNameCourses: rolloverAutoLink
      });
      await Promise.all([refreshCatalog(), refreshCurrentUser()]);
      if (onDataChanged) await onDataChanged();
      notifySuccess(
        `현재 활성 학기가 '${formatSemesterLabel(res?.currentSemester || cleanTarget)}'로 설정되었습니다.` +
          (res?.autoLinkedCount > 0 ? ` (전학기 동일 과목명 ${res.autoLinkedCount}개 자동 연동됨)` : '')
      );
    } catch (err) {
      notifyError(err.message || '학기 전환 처리 중 오류가 발생했습니다.');
    } finally {
      setIsRollingOver(false);
    }
  };

  const handleImportExcel = async (e) => {
    e.preventDefault();
    if (!excelFile) {
      notifyError('업로드할 강의시간표 엑셀(.xlsx) 파일을 선택해 주세요.');
      return;
    }
    if (!excelFile.name.toLowerCase().endsWith('.xlsx')) {
      notifyError('.xlsx 형식의 엑셀 파일만 업로드할 수 있습니다.');
      return;
    }
    if (excelFile.size === 0 || excelFile.size > 15 * 1024 * 1024) {
      notifyError('파일 크기는 15MB 이하의 비어 있지 않은 .xlsx 파일이어야 합니다.');
      return;
    }
    setIsImportingExcel(true);
    try {
      const res = await api.importCoursesExcel(excelFile, {
        semester: excelTargetSem.trim(),
        autoLinkPrevSemester: excelAutoLink,
        setAsCurrentSemester: excelSetCurrent
      });
      setExcelFile(null);
      if (excelInputRef.current) {
        excelInputRef.current.value = '';
      }
      await Promise.all([refreshCatalog(), refreshCurrentUser()]);
      if (onDataChanged) await onDataChanged();
      notifySuccess(res?.message || '엑셀 강의 일괄 등록이 완료되었습니다.');
    } catch (err) {
      notifyError(err.message || '엑셀 업로드 중 오류가 발생했습니다.');
    } finally {
      setIsImportingExcel(false);
    }
  };

  const handleAddCourse = async (e) => {
    e.preventDefault();
    if (!courseCode.trim() || !courseName.trim()) return;
    if (courseScopeType === 'DEPARTMENT' && !effectiveCourseDept) {
      notifyError('주관 학과를 선택해주세요.');
      return;
    }
    if (courseScopeType === 'COLLEGE' && !effectiveCourseCollege) {
      notifyError('주관 단과대를 선택해주세요.');
      return;
    }

    const { college: resolvedCollege, department: resolvedDepartment } = resolveScopeOrganization(
      courseScopeType,
      effectiveCourseCollege,
      effectiveCourseDept
    );

    const addedName = courseName.trim();
    const addedCode = courseCode.trim();
    const cleanSem = normalizeSemester(effectiveCourseSemester, currentSemester);
    try {
      await api.createCourse({
        code: addedCode,
        name: addedName,
        semester: cleanSem,
        scopeType: courseScopeType,
        college: resolvedCollege,
        department: resolvedDepartment,
        prevCourseId: coursePrevId !== 'AUTO' && coursePrevId !== 'NONE' ? coursePrevId : null,
        autoLinkPrevSemester: coursePrevId === 'AUTO'
      });
      setCourseCode('');
      setCourseName('');
      setCoursePrevId('AUTO');
      await refreshCatalog();
      if (onDataChanged) await onDataChanged();
      notifySuccess(`과목 '${addedName} (${addedCode}, ${formatSemesterLabel(cleanSem)})'이(가) 추가되었습니다.`);
    } catch (err) {
      notifyError(err.message || '과목 추가 중 오류가 발생했습니다.');
    }
  };

  const handleStartEditCourse = (course) => {
    const rawDept = (course.department || '').trim();
    const rawCollege = (course.college || '').trim();
    const inferredScope =
      rawDept === '전체' || rawCollege === '전체'
        ? 'ALL'
        : rawDept.endsWith(' 전체')
          ? 'COLLEGE'
          : 'DEPARTMENT';
    const inferredCollege =
      inferredScope === 'COLLEGE'
        ? rawCollege || rawDept.replace(/ 전체$/, '').trim() || collegeOptions[0] || ''
        : rawCollege || collegeOptions[0] || '';
    const inferredDept =
      inferredScope === 'DEPARTMENT'
        ? rawDept || effectiveCourseDept || '공통'
        : effectiveCourseDept || departments[0]?.name || '공통';

    setEditingCourse({
      id: course.id,
      name: course.name || '',
      code: course.code || '',
      semester: course.semester || currentSemester || '2026-2',
      scopeType: inferredScope,
      college: inferredCollege,
      department: inferredDept,
      prevCourseId: course.prevCourseId || ''
    });
  };

  const handleSaveEditedCourse = async (e) => {
    e.preventDefault();
    if (!editingCourse) return;
    const cleanName = editingCourse.name.trim();
    const cleanCode = editingCourse.code.trim();
    if (!cleanName || !cleanCode) {
      notifyError('교과목명과 학수번호를 모두 입력해 주세요.');
      return;
    }
    const { college: resolvedCollege, department: resolvedDepartment } = resolveScopeOrganization(
      editingCourse.scopeType,
      editingCourse.college,
      editingCourse.department
    );
    const cleanSem = normalizeSemester(editingCourse.semester.trim(), currentSemester);
    const cleanPrev = (editingCourse.prevCourseId || '').trim();
    try {
      const updated = await api.updateCourse(editingCourse.id, {
        name: cleanName,
        code: cleanCode,
        semester: cleanSem,
        scopeType: editingCourse.scopeType,
        college: resolvedCollege,
        department: resolvedDepartment,
        prevCourseId: cleanPrev || null,
        unlinkPrevCourse: !cleanPrev
      });
      setEditingCourse(null);
      await Promise.all([refreshCatalog(), refreshCurrentUser()]);
      if (onDataChanged) await onDataChanged();
      notifySuccess(`과목 '${updated?.name || cleanName} (${updated?.code || cleanCode})' 정보가 수정되었습니다.`);
    } catch (err) {
      notifyError(err.message || '과목 수정 중 오류가 발생했습니다.');
    }
  };

  const handleDeleteCourse = async (course) => {
    if (!course?.id) return;
    if (
      !window.confirm(
        `'${course.name} (${course.code})' 과목을 삭제하시겠습니까?\n이 과목에 등록된 질문·답변 및 수강 내역도 함께 삭제됩니다.`
      )
    ) {
      return;
    }
    try {
      await api.deleteCourse(course.id);
      if (editingCourse && String(editingCourse.id) === String(course.id)) {
        setEditingCourse(null);
      }
      await Promise.all([refreshCatalog(), refreshCurrentUser()]);
      if (onDataChanged) await onDataChanged();
      notifySuccess(`과목 '${course.name} (${course.code})'이(가) 삭제되었습니다.`);
    } catch (err) {
      notifyError(err.message || '과목 삭제 중 오류가 발생했습니다.');
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
            <>
              <HelpBubble
                tier="ALL"
                mode="inline"
                text="엑셀(.xlsx) 시간표 일괄 등록, 정규·계절학기 전환 및 전학기 동일 강의 자동·수동 연결 리스트 관리, 학과·단과대·교과목 동적 관리를 수행해요."
                className="mb-help-bubble"
              />
              <datalist id="admin-semester-datalist">
                {semesterOptions.map((sem) => (
                  <option key={sem} value={sem}>
                    {formatSemesterLabel(sem)}
                  </option>
                ))}
              </datalist>
              <div className="admin-catalog-grid">
              {/* 학기 전환 · 강의 연동 & 엑셀 일괄 등록 */}
              <div className="admin-panel-card" style={{ gridColumn: '1 / -1' }}>
                <div className="admin-semester-excel-grid">
                  <div>
                    <h3 className="admin-panel-title">
                      <Calendar size={18} />
                      활성 학기 · 강의 연동
                    </h3>
                    <p className="text-xs text-muted" style={{ marginBottom: 10 }}>
                      현재 활성 학기: <strong>{formatSemesterLabel(currentSemester)}</strong> · 정규·계절학기를 지원하며 타 학기 스레드에는 작성 학기가 표시됩니다.
                    </p>
                    <HelpBubble
                      tier="ALL"
                      mode="inline"
                      text="새 학기로 전환하면 전학기와 이름이 같은 강의가 자동으로 연동돼요. 연동된 강의는 이전 학기 질문과 유사 질답 기록을 함께 이어볼 수 있어요."
                      className="mb-help-bubble"
                    />
                    <form onSubmit={handleRolloverSemester} className="admin-compact-form">
                      <div className="auth-form-grid-2">
                        <div className="form-group" style={{ marginBottom: 6 }}>
                          <label className="form-label">활성 학기</label>
                          <input
                            type="text"
                            list="admin-semester-datalist"
                            className="form-input"
                            placeholder="예: 2027-1, 2026-여름, 2026-겨울"
                            value={effectiveRolloverSem}
                            onChange={(e) => setRolloverTargetSem(e.target.value)}
                            required
                          />
                        </div>
                        <div className="form-group" style={{ marginBottom: 6, justifyContent: 'flex-end', display: 'flex', flexDirection: 'column' }}>
                          <label className="admin-check-inline">
                            <input
                              type="checkbox"
                              checked={rolloverAutoLink}
                              onChange={(e) => setRolloverAutoLink(e.target.checked)}
                            />
                            <span>전학기 동일 과목명 자동 연동</span>
                          </label>
                        </div>
                      </div>
                      <button type="submit" className="btn btn-primary btn-sm" disabled={isRollingOver}>
                        <RefreshCw size={14} />
                        {isRollingOver ? '적용 중...' : '활성 학기 적용'}
                      </button>
                    </form>
                  </div>

                  <div>
                    <h3 className="admin-panel-title">
                      <Upload size={18} />
                      엑셀 강의 일괄 등록
                    </h3>
                    <p className="text-xs text-muted" style={{ marginBottom: 10 }}>
                      강의시간표 엑셀(<code>.xlsx</code>)을 업로드하면 학정번호의 분반을 분리해 과목·분반·교수를 일괄 등록합니다.
                    </p>
                    <HelpBubble
                      tier="ALL"
                      mode="inline"
                      text="학교 개설강좌 엑셀(.xlsx)을 올리면 같은 과목의 여러 분반과 담당 교수가 자동으로 묶여 등록돼요. 적용 학기를 비우면 파일에서 학기를 자동 감지해요."
                      className="mb-help-bubble"
                    />
                    <form onSubmit={handleImportExcel} className="admin-compact-form">
                      <div className="auth-form-grid-2">
                        <div className="form-group" style={{ marginBottom: 6 }}>
                          <label className="form-label">엑셀 파일 (.xlsx) *</label>
                          <input
                            ref={excelInputRef}
                            type="file"
                            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            className="form-input"
                            onChange={(e) => setExcelFile(e.target.files?.[0] || null)}
                          />
                        </div>
                        <div className="form-group" style={{ marginBottom: 6 }}>
                          <label className="form-label">적용 학기 (비우면 자동 감지)</label>
                          <input
                            type="text"
                            list="admin-semester-datalist"
                            className="form-input"
                            placeholder="예: 2026-2, 2026-여름"
                            value={excelTargetSem}
                            onChange={(e) => setExcelTargetSem(e.target.value)}
                          />
                        </div>
                      </div>
                      <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', marginBottom: 6 }}>
                        <label className="admin-check-inline">
                          <input
                            type="checkbox"
                            checked={excelAutoLink}
                            onChange={(e) => setExcelAutoLink(e.target.checked)}
                          />
                          <span>전학기 동일 과목명 자동 연동</span>
                        </label>
                        <label className="admin-check-inline">
                          <input
                            type="checkbox"
                            checked={excelSetCurrent}
                            onChange={(e) => setExcelSetCurrent(e.target.checked)}
                          />
                          <span>등록 학기로 활성 학기 전환</span>
                        </label>
                      </div>
                      <button type="submit" className="btn btn-primary btn-sm" disabled={isImportingExcel}>
                        <Upload size={14} />
                        {isImportingExcel ? '등록 중...' : '엑셀 일괄 등록'}
                      </button>
                    </form>
                  </div>
                </div>
              </div>
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
                  <div className="form-group">
                    <label className="form-label">기존 학과명 별칭(선택 · 쉼표 구분)</label>
                    <input
                      type="text"
                      className="form-input"
                      placeholder="예: 소프트웨어학과, 구전산학과"
                      value={deptAliasesInput}
                      onChange={(e) => setDeptAliasesInput(e.target.value)}
                    />
                  </div>
                  <button type="submit" className="btn btn-primary btn-sm">
                    <PlusCircle size={15} />
                    학과 즉시 추가
                  </button>
                </form>

                <form onSubmit={handleAddAlias} className="admin-compact-form" style={{ marginTop: '14px', paddingTop: '12px', borderTop: '1px dashed var(--border-color)' }}>
                  <div className="auth-form-grid-2">
                    <div className="form-group">
                      <label className="form-label">별칭 연결 대상 학과</label>
                      <select
                        className="form-select"
                        value={effectiveAliasTargetDeptId}
                        onChange={(e) => setAliasTargetDeptId(e.target.value)}
                      >
                        {departments.map((d) => (
                          <option key={d.id || d.name} value={d.id}>
                            {d.name}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label className="form-label">기존 학과명(별칭) *</label>
                      <input
                        type="text"
                        className="form-input"
                        placeholder="예: 소프트웨어학과"
                        value={aliasNewName}
                        onChange={(e) => setAliasNewName(e.target.value)}
                        required
                      />
                    </div>
                  </div>
                  <button type="submit" className="btn btn-outline btn-sm">
                    <PlusCircle size={14} />
                    기존 학과명 별칭 연결
                  </button>
                </form>

                <div className="admin-chip-list-wrap">
                  <div className="admin-chip-header">현재 등록된 학과 ({departments.length}개) · 연필 아이콘으로 명칭/별칭 수정</div>
                  {editingDept && (
                    <form onSubmit={handleSaveEditedDept} className="admin-compact-form" style={{ marginBottom: '12px', padding: '10px', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                      <div className="auth-form-grid-2">
                        <div className="form-group">
                          <label className="form-label">수정할 정식 학과명 *</label>
                          <input
                            type="text"
                            className="form-input"
                            value={editingDept.name}
                            onChange={(e) => setEditingDept((prev) => ({ ...prev, name: e.target.value }))}
                            required
                          />
                        </div>
                        <div className="form-group">
                          <label className="form-label">소속 단과대학</label>
                          <input
                            type="text"
                            className="form-input"
                            list="admin-college-options"
                            value={editingDept.college}
                            onChange={(e) => setEditingDept((prev) => ({ ...prev, college: e.target.value }))}
                          />
                        </div>
                      </div>
                      <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '0.85em', marginBottom: '8px' }}>
                        <input
                          type="checkbox"
                          checked={editingDept.keepOldNameAsAlias}
                          onChange={(e) => setEditingDept((prev) => ({ ...prev, keepOldNameAsAlias: e.target.checked }))}
                        />
                        학과명 변경 시 기존 이름을 별칭으로 자동 보존
                      </label>
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button type="submit" className="btn btn-primary btn-xs">저장</button>
                        <button type="button" className="btn btn-outline btn-xs" onClick={() => setEditingDept(null)}>취소</button>
                      </div>
                    </form>
                  )}
                  <CatalogChipList
                    items={departments}
                    entityKey="dept"
                    getSubLabel={(d) => (d.college || d.shortName || '').trim()}
                    editingAlias={editingAlias}
                    onStartRenameAlias={(entityKey, itemId, oldAlias) => setEditingAlias({ entityKey, itemId, oldAlias, value: oldAlias })}
                    onChangeEditingAliasValue={(value) => setEditingAlias((prev) => (prev ? { ...prev, value } : null))}
                    onSubmitRenameAlias={handleSubmitRenameAlias}
                    onCancelRenameAlias={() => setEditingAlias(null)}
                    onRemoveAlias={handleRemoveAlias}
                    onStartEditItem={(d) => setEditingDept({ id: d.id, name: d.name, college: d.college || '', keepOldNameAsAlias: true })}
                  />
                </div>
              </div>

              <div className="admin-panel-card">
                <h3 className="admin-panel-title">
                  <Building2 size={18} />
                  단과대학(College) 및 별칭 관리
                </h3>
                <form onSubmit={handleAddCollege} className="admin-compact-form">
                  <div className="form-group">
                    <label className="form-label">단과대학 정식 명칭 *</label>
                    <input
                      type="text"
                      className="form-input"
                      placeholder="예: 인공지능융합대학"
                      value={newCollegeName}
                      onChange={(e) => setNewCollegeName(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <label className="form-label">기존 단과대학명 별칭(선택 · 쉼표 구분)</label>
                    <input
                      type="text"
                      className="form-input"
                      placeholder="예: 소프트웨어융합대학"
                      value={newCollegeAliasesInput}
                      onChange={(e) => setNewCollegeAliasesInput(e.target.value)}
                    />
                  </div>
                  <button type="submit" className="btn btn-primary btn-sm">
                    <PlusCircle size={15} />
                    단과대학 추가
                  </button>
                </form>

                <form onSubmit={handleAddCollegeAlias} className="admin-compact-form" style={{ marginTop: '14px', paddingTop: '12px', borderTop: '1px dashed var(--border-color)' }}>
                  <div className="auth-form-grid-2">
                    <div className="form-group">
                      <label className="form-label">별칭 연결 대상 단과대학</label>
                      <select
                        className="form-select"
                        value={effectiveAliasTargetCollegeId}
                        onChange={(e) => setAliasTargetCollegeId(e.target.value)}
                      >
                        {colleges.map((c) => (
                          <option key={c.id || c.name} value={c.id}>
                            {c.name}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label className="form-label">기존 단과대학명(별칭) *</label>
                      <input
                        type="text"
                        className="form-input"
                        placeholder="예: 소프트웨어융합대학"
                        value={collegeAliasNewName}
                        onChange={(e) => setCollegeAliasNewName(e.target.value)}
                        required
                      />
                    </div>
                  </div>
                  <button type="submit" className="btn btn-outline btn-sm">
                    <PlusCircle size={14} />
                    기존 단과대학명 별칭 연결
                  </button>
                </form>

                <div className="admin-chip-list-wrap">
                  <div className="admin-chip-header">현재 등록된 단과대학 ({colleges.length}개)</div>
                  {editingCollege && (
                    <form onSubmit={handleSaveEditedCollege} className="admin-compact-form" style={{ marginBottom: '12px', padding: '10px', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                      <div className="form-group">
                        <label className="form-label">수정할 정식 단과대학명 *</label>
                        <input
                          type="text"
                          className="form-input"
                          value={editingCollege.name}
                          onChange={(e) => setEditingCollege((prev) => ({ ...prev, name: e.target.value }))}
                          required
                        />
                      </div>
                      <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '0.85em', marginBottom: '8px' }}>
                        <input
                          type="checkbox"
                          checked={editingCollege.keepOldNameAsAlias}
                          onChange={(e) => setEditingCollege((prev) => ({ ...prev, keepOldNameAsAlias: e.target.checked }))}
                        />
                        단과대학명 변경 시 기존 이름을 별칭으로 자동 보존
                      </label>
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button type="submit" className="btn btn-primary btn-xs">저장</button>
                        <button type="button" className="btn btn-outline btn-xs" onClick={() => setEditingCollege(null)}>취소</button>
                      </div>
                    </form>
                  )}
                  <CatalogChipList
                    items={colleges}
                    entityKey="college"
                    getSubLabel={() => ''}
                    editingAlias={editingAlias}
                    onStartRenameAlias={(entityKey, itemId, oldAlias) => setEditingAlias({ entityKey, itemId, oldAlias, value: oldAlias })}
                    onChangeEditingAliasValue={(value) => setEditingAlias((prev) => (prev ? { ...prev, value } : null))}
                    onSubmitRenameAlias={handleSubmitRenameAlias}
                    onCancelRenameAlias={() => setEditingAlias(null)}
                    onRemoveAlias={handleRemoveCollegeAlias}
                    onStartEditItem={(c) => setEditingCollege({ id: c.id, name: c.name, keepOldNameAsAlias: true })}
                  />
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
                        list="admin-semester-datalist"
                        className="form-input"
                        placeholder="예: 2026-2, 2026-여름"
                        value={effectiveCourseSemester}
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

                  <div className="form-group">
                    <label className="form-label">이전 학기 연동</label>
                    <CourseSelectPicker
                      courses={allCourses}
                      value={coursePrevId}
                      onChange={setCoursePrevId}
                      departments={departments}
                      colleges={colleges}
                      specialOptions={[
                        { value: 'AUTO', label: '자동 연동 (전학기 동일 과목명)' },
                        { value: 'NONE', label: '연동 없음' }
                      ]}
                      placeholder="연동할 강의를 선택하세요"
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
                    <CourseScopeTargetField
                      scopeType={courseScopeType}
                      college={effectiveCourseCollege}
                      department={effectiveCourseDept}
                      collegeOptions={collegeOptions}
                      departments={departments}
                      onChangeCollege={setCourseCollege}
                      onChangeDepartment={setCourseDept}
                    />
                  </div>

                  <button type="submit" className="btn btn-primary btn-sm">
                    <PlusCircle size={15} />
                    교과목 즉시 추가
                  </button>
                </form>

                {(() => {
                  const trimmedQ = courseSearchQuery.trim().toLowerCase();
                  const aliasMatchedDepts = new Set(
                    trimmedQ
                      ? departments
                          .filter((d) =>
                            Array.isArray(d.aliases) &&
                            d.aliases.some((al) => String(al).toLowerCase().includes(trimmedQ))
                          )
                          .map((d) => d.name)
                      : []
                  );
                  const aliasMatchedColleges = new Set(
                    trimmedQ
                      ? colleges
                          .filter((col) =>
                            Array.isArray(col.aliases) &&
                            col.aliases.some((al) => String(al).toLowerCase().includes(trimmedQ))
                          )
                          .map((col) => col.name)
                      : []
                  );
                  const courseScopeOptions = extractCourseScopes(allCourses);
                  const semFiltered =
                    courseSemesterFilter === 'ALL'
                      ? allCourses
                      : allCourses.filter(
                          (c) => parseSemesterOrNull(c.semester) === parseSemesterOrNull(courseSemesterFilter)
                        );
                  const deptFiltered =
                    courseDeptFilter === 'ALL'
                      ? semFiltered
                      : semFiltered.filter(
                          (c) =>
                            String(c.department || c.college || '').trim() === courseDeptFilter ||
                            String(c.college || '').trim() === courseDeptFilter
                        );
                  const searchedCourses = trimmedQ
                    ? deptFiltered.filter((c) => {
                        const profNames = (c.professors || []).map((p) => p.name || '').join(' ');
                        const directMatch = [
                          c.name,
                          c.code,
                          c.semester,
                          c.department,
                          c.college,
                          c.profName,
                          profNames,
                          c.prevCourseName,
                          c.nextCourseName
                        ]
                          .filter(Boolean)
                          .some((field) => String(field).toLowerCase().includes(trimmedQ));
                        if (directMatch) return true;
                        if (c.department && aliasMatchedDepts.has(c.department)) return true;
                        if (c.college && aliasMatchedColleges.has(c.college)) return true;
                        return Boolean(
                          c.department &&
                            Array.from(aliasMatchedColleges).some((col) => c.department === `${col} 전체`)
                        );
                      })
                    : deptFiltered;
                  const filteredCourses = sortCoursesList(searchedCourses, courseSortBy);
                  const visibleCourses = filteredCourses.slice(0, courseListLimit);
                  const remainingCount = Math.max(0, filteredCourses.length - visibleCourses.length);
                  const editingChain = editingCourse
                    ? buildCourseLineageChain(editingCourse.id, allCourses)
                    : [];
                  const editingExcludeIds = editingCourse
                    ? collectSelfAndDescendantIds(editingCourse.id, allCourses)
                    : null;

                  return (
                    <div className="admin-chip-list-wrap">
                      <div className="admin-chip-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span>
                          등록된 교과목 ({allCourses.length}개
                          {trimmedQ || courseSemesterFilter !== 'ALL' || courseDeptFilter !== 'ALL' ? ` · 필터 결과 ${filteredCourses.length}개` : ''}) · 연필로 수정 및 연결 리스트 수동 설정
                        </span>
                        <div className="course-picker-controls" style={{ marginBottom: 0 }}>
                          <select
                            className="form-select course-picker-ctrl-select"
                            value={courseSemesterFilter}
                            onChange={(e) => {
                              setCourseSemesterFilter(e.target.value);
                              setCourseListLimit(24);
                            }}
                            aria-label="학기별 과목 필터"
                          >
                            <option value="ALL">전체 학기</option>
                            {semesterOptions.map((sem) => (
                              <option key={sem} value={sem}>
                                {formatSemesterLabel(sem)}
                              </option>
                            ))}
                          </select>
                          {courseScopeOptions.length > 1 && (
                            <select
                              className="form-select course-picker-ctrl-select"
                              value={courseDeptFilter}
                              onChange={(e) => {
                                setCourseDeptFilter(e.target.value);
                                setCourseListLimit(24);
                              }}
                              aria-label="학과·단과대 필터"
                            >
                              <option value="ALL">전체 학과</option>
                              {courseScopeOptions.map((sc) => (
                                <option key={sc} value={sc}>
                                  {sc}
                                </option>
                              ))}
                            </select>
                          )}
                          <select
                            className="form-select course-picker-ctrl-select"
                            value={courseSortBy}
                            onChange={(e) => {
                              setCourseSortBy(e.target.value);
                              setCourseListLimit(24);
                            }}
                            aria-label="교과목 정렬"
                          >
                            <option value="semester_desc">최신 학기순</option>
                            <option value="semester_asc">오래된 학기순</option>
                            <option value="name_asc">과목명순</option>
                            <option value="code_asc">학수번호순</option>
                            <option value="linked_first">연동 과목 우선</option>
                          </select>
                        </div>
                      </div>
                      <div className="admin-course-search-box">
                        <Search size={14} className="admin-course-search-icon" aria-hidden="true" />
                        <input
                          type="search"
                          className="form-input admin-course-search-input"
                          placeholder="과목명 · 학수번호 · 학과(별칭) · 교수명 검색..."
                          aria-label="등록된 교과목 검색"
                          value={courseSearchQuery}
                          onChange={(e) => {
                            setCourseSearchQuery(e.target.value);
                            setCourseListLimit(24);
                          }}
                        />
                        {courseSearchQuery && (
                          <button
                            type="button"
                            className="btn btn-outline btn-xs"
                            onClick={() => {
                              setCourseSearchQuery('');
                              setCourseListLimit(24);
                            }}
                            aria-label="교과목 검색어 지우기"
                          >
                            초기화
                          </button>
                        )}
                      </div>

                      {editingCourse && (
                        <form onSubmit={handleSaveEditedCourse} className="admin-compact-form admin-inline-edit-box">
                          <div className="auth-form-grid-2">
                            <div className="form-group">
                              <label className="form-label">수정할 교과목명 *</label>
                              <input
                                type="text"
                                className="form-input"
                                value={editingCourse.name}
                                onChange={(e) => setEditingCourse((prev) => ({ ...prev, name: e.target.value }))}
                                required
                              />
                            </div>
                            <div className="form-group">
                              <label className="form-label">학수번호(코드) *</label>
                              <input
                                type="text"
                                className="form-input"
                                value={editingCourse.code}
                                onChange={(e) => setEditingCourse((prev) => ({ ...prev, code: e.target.value }))}
                                required
                              />
                            </div>
                          </div>
                          <div className="auth-form-grid-2">
                            <div className="form-group">
                              <label className="form-label">개설 학기</label>
                              <input
                                type="text"
                                list="admin-semester-datalist"
                                className="form-input"
                                value={editingCourse.semester}
                                onChange={(e) => setEditingCourse((prev) => ({ ...prev, semester: e.target.value }))}
                              />
                            </div>
                            <div className="form-group">
                              <label className="form-label">이전 학기 연동</label>
                              <CourseSelectPicker
                                courses={allCourses}
                                value={editingCourse.prevCourseId || ''}
                                onChange={(val) => setEditingCourse((prev) => ({ ...prev, prevCourseId: val }))}
                                excludeIds={editingExcludeIds}
                                departments={departments}
                                colleges={colleges}
                                specialOptions={[{ value: '', label: '연동 없음' }]}
                                placeholder="연동 없음"
                              />
                            </div>
                          </div>
                          {editingChain.length > 1 && (
                            <div className="course-lineage-chain-box">
                              <div className="course-lineage-chain-title">
                                <Link2 size={12} />
                                <span>연결 리스트 체인 ({editingChain.length}개 강의 연속 연동 · 클릭하여 이어서 수정)</span>
                              </div>
                              <div className="course-lineage-chain-nodes">
                                {editingChain.map((node, idx) => {
                                  const isCurrent = node.id === editingCourse.id;
                                  return (
                                    <React.Fragment key={node.id}>
                                      {idx > 0 && <span className="course-lineage-arrow">→</span>}
                                      <button
                                        type="button"
                                        className={`course-lineage-node ${isCurrent ? 'current' : ''}`}
                                        onClick={() => !isCurrent && handleStartEditCourse(node)}
                                        disabled={isCurrent}
                                        title={isCurrent ? '현재 수정 중인 강의' : `${node.name} 수정으로 이동`}
                                      >
                                        <span className="semester-pill">{formatSemesterLabel(node.semester)}</span>
                                        <span>{node.name}</span>
                                      </button>
                                    </React.Fragment>
                                  );
                                })}
                              </div>
                            </div>
                          )}
                          <div className="auth-form-grid-2">
                            <div className="form-group">
                              <label className="form-label">주관 단위</label>
                              <select
                                className="form-select"
                                value={editingCourse.scopeType}
                                onChange={(e) =>
                                  setEditingCourse((prev) => ({
                                    ...prev,
                                    scopeType: e.target.value,
                                    college: prev.college || collegeOptions[0] || '',
                                    department: prev.department || effectiveCourseDept || '공통'
                                  }))
                                }
                              >
                                <option value="DEPARTMENT">학과 단위</option>
                                <option value="COLLEGE">단과대 단위</option>
                                <option value="ALL">전체 (전교 공통)</option>
                              </select>
                            </div>
                            <CourseScopeTargetField
                              scopeType={editingCourse.scopeType}
                              college={editingCourse.college}
                              department={editingCourse.department}
                              collegeOptions={collegeOptions}
                              departments={departments}
                              onChangeCollege={(val) => setEditingCourse((prev) => ({ ...prev, college: val }))}
                              onChangeDepartment={(val) => setEditingCourse((prev) => ({ ...prev, department: val }))}
                            />
                          </div>
                          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                            <button type="submit" className="btn btn-primary btn-xs">저장</button>
                            <button type="button" className="btn btn-outline btn-xs" onClick={() => setEditingCourse(null)}>취소</button>
                            <button
                              type="button"
                              className="btn btn-danger btn-xs"
                              style={{ marginLeft: 'auto' }}
                              onClick={() => handleDeleteCourse(editingCourse)}
                            >
                              <Trash2 size={12} /> 삭제
                            </button>
                          </div>
                        </form>
                      )}

                      {visibleCourses.length === 0 ? (
                        <div className="text-xs text-muted" style={{ padding: '8px 0' }}>
                          검색 조건과 일치하는 과목이 없습니다.
                        </div>
                      ) : (
                        <div className="admin-chip-list admin-course-chip-list">
                          {visibleCourses.map((c) => (
                            <span key={c.id} className="admin-dept-chip">
                              <span className="semester-pill" style={{ marginRight: 4 }}>
                                {formatSemesterLabel(c.semester)}
                              </span>
                              {c.name}{' '}
                              <small>
                                ({c.code}
                                {c.department ? ` · ${c.department}` : ''}
                                {' · 담당: '}
                                <ProfessorNameList course={c} mode="all" />)
                              </small>
                              {(c.prevCourseId || c.nextCourseId) && (
                                <span
                                  className="admin-linked-chain-badge"
                                  title={[
                                    c.prevCourseId
                                      ? `이전: [${formatSemesterLabel(c.prevCourseSemester)}] ${c.prevCourseName || ''}`
                                      : null,
                                    c.nextCourseId
                                      ? `다음: [${formatSemesterLabel(c.nextCourseSemester)}] ${c.nextCourseName || ''}`
                                      : null
                                  ]
                                    .filter(Boolean)
                                    .join(' / ')}
                                >
                                  <Link2 size={10} />
                                  {c.prevCourseSemester ? `${c.prevCourseSemester}←` : ''}
                                  연동
                                  {c.nextCourseSemester ? `→${c.nextCourseSemester}` : ''}
                                </span>
                              )}
                              <button
                                type="button"
                                className="admin-chip-action-btn"
                                title={`${c.name} 과목 수정`}
                                aria-label={`${c.name} 과목 수정`}
                                onClick={() => handleStartEditCourse(c)}
                              >
                                <Pencil size={11} />
                              </button>
                              <button
                                type="button"
                                className="admin-chip-action-btn is-danger"
                                title={`${c.name} 과목 삭제`}
                                aria-label={`${c.name} 과목 삭제`}
                                onClick={() => handleDeleteCourse(c)}
                              >
                                <Trash2 size={11} />
                              </button>
                            </span>
                          ))}
                        </div>
                      )}

                      {(remainingCount > 0 || courseListLimit > 24) && (
                        <div style={{ display: 'flex', gap: '8px', marginTop: '8px', flexWrap: 'wrap' }}>
                          {remainingCount > 0 && (
                            <button
                              type="button"
                              className="btn btn-outline btn-xs"
                              onClick={() => setCourseListLimit((prev) => prev + 40)}
                            >
                              더 보기 (+{Math.min(40, remainingCount)}개 / 남은 {remainingCount}개)
                            </button>
                          )}
                          {courseListLimit > 24 && (
                            <button
                              type="button"
                              className="btn btn-outline btn-xs"
                              onClick={() => setCourseListLimit(24)}
                            >
                              목록 접기
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })()}
              </div>

              <div className="admin-settings-pair-grid">
                <div className="admin-panel-card">
                  <h3 className="admin-panel-title">
                    <ShieldAlert size={18} />
                    관리자 닉네임 설정 (관리자 전용)
                  </h3>
                  <p className="text-xs text-muted" style={{ marginBottom: 12 }}>
                    상단 프로필과 게시글·답변 작성자 표시명에 본명 대신 노출될 관리자 닉네임(최대 30자)을 설정합니다. 비워 두고 저장하면 기본 표시명으로 돌아갑니다.
                  </p>
                  <AdminNicknameForm
                    compact
                    onSaved={async (msg) => {
                      notifySuccess(msg);
                      if (onDataChanged) await onDataChanged();
                    }}
                  />
                </div>

                <div className="admin-panel-card">
                  <h3 className="admin-panel-title">
                    <Sparkles size={18} />
                    전시 모드 (관리자 전용)
                  </h3>
                  <p className="text-xs text-muted" style={{ marginBottom: 12 }}>
                    시연·전시 환경에서 비로그인 회원가입 시 학번 무작위 생성 버튼을 노출합니다. 이 설정은 관리자 모드에서만 변경할 수 있습니다.
                  </p>
                  <div className="exhibition-option-list">
                    {[
                      { id: 'off', value: false, label: '꺼짐', desc: '직접 입력' },
                      { id: 'on', value: true, label: '켜짐', desc: '무작위 생성 표시' }
                    ].map((option) => (
                      <button
                        type="button"
                        key={option.id}
                        className={`fontsize-option-item ${exhibitionMode === option.value ? 'active' : ''}`}
                        onClick={async () => {
                          setErrorMessage('');
                          setStatusMessage('');
                          try {
                            await updateExhibitionMode(option.value);
                            setStatusMessage(
                              option.value
                                ? '전시 모드가 켜졌습니다. 회원가입 화면에 학번 무작위 생성 버튼이 노출됩니다.'
                                : '전시 모드가 꺼졌습니다.'
                            );
                          } catch (err) {
                            setErrorMessage(err.message || '전시 모드 설정 변경에 실패했습니다.');
                          }
                        }}
                      >
                        <div>
                          <div className="fontsize-label">{option.label}</div>
                          <div className="help-level-option-desc">{option.desc}</div>
                        </div>
                        {exhibitionMode === option.value && <Check size={16} className="text-primary" />}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </div>
            </>
          )}

          {activeTab === 'users' && (
            <div>
              <HelpBubble
                tier="ALL"
                mode="inline"
                text="제한 기간을 고른 뒤 계정별 질문·답변 제한이나 강제 탈퇴·복구를 적용해요."
                className="mb-help-bubble"
              />
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
              <HelpBubble
                tier="ALL"
                mode="inline"
                text="익명 글의 실제 작성자를 확인하고 게시물을 가리거나 영구 삭제해요. 본문 외부 링크 옆에는 실제 주소도 보여요."
                className="mb-help-bubble"
              />
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
                          <div className="font-semibold">
                            {q.courseName}
                            {q.section ? ` (${q.section})` : ''}
                          </div>
                          <div className="text-xs text-muted">
                            {String(q.category).toUpperCase()}
                            {isNonCurrentSemesterThread(q, currentSemester)
                              ? ` · ${formatSemesterLabel(q.semester)} 작성`
                              : ''}
                          </div>
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
              <HelpBubble
                tier="ALL"
                mode="inline"
                text="교수에게 발송된 질문 알림 이메일 기록과 발송 상태를 확인해요."
                className="mb-help-bubble"
              />
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
