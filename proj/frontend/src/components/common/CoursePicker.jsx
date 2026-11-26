// src/components/common/CoursePicker.jsx
import React, { useMemo, useState } from 'react';
import { Search, ChevronDown, ChevronUp, Link2, Check, X } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { formatCourseProfessorsSummary } from '../../utils/professorDisplay';
import { formatSemesterLabel, parseSemesterOrNull } from '../../utils/semester';
import {
  extractCourseScopes,
  extractCourseSemesters,
  filterCoursesList,
  sortCoursesList
} from '../../utils/courseLineage';
import ProfessorNameList from './ProfessorNameList';

/**
 * Reusable searchable, filterable, sortable multi-course checkbox picker shared across
 * registration, user settings, and professor settings modals.
 * Supports section-level selection (`courseProfessorId`) when `sectionMode` is `'single'` or `'multi'`.
 */
const CoursePicker = ({
  courses = [],
  selectedIds = [],
  onChange,
  sectionMode = 'none', // 'none' | 'single' | 'multi'
  sectionSelections = {}, // { [courseId]: string | string[] }
  onSectionSelectionsChange = null,
  departments: propDepartments = null,
  colleges: propColleges = null,
  label = '과목 선택',
  required = true,
  searchPlaceholder = '과목명, 학수번호, 분반, 학과·단과대, 교수명 검색...',
  emptyText = '검색 결과가 없습니다.',
  helperText = ''
}) => {
  const auth = useAuth();
  const departments = propDepartments ?? auth?.departments ?? [];
  const colleges = propColleges ?? auth?.colleges ?? [];
  const currentSemester = auth?.currentSemester || '2026-2';
  const [searchQuery, setSearchQuery] = useState('');
  const [semesterFilter, setSemesterFilter] = useState('CURRENT');
  const [deptFilter, setDeptFilter] = useState('ALL');
  const [sortBy, setSortBy] = useState('selected');

  const uniqueCourses = useMemo(() => {
    const map = new Map();
    (courses || []).forEach((c) => {
      if (c?.id && !map.has(c.id)) {
        map.set(c.id, c);
      }
    });
    return Array.from(map.values());
  }, [courses]);

  const semesterOptions = useMemo(() => extractCourseSemesters(uniqueCourses), [uniqueCourses]);
  const scopeOptions = useMemo(() => extractCourseScopes(uniqueCourses), [uniqueCourses]);
  const hasMultipleSemesters = semesterOptions.length > 1;

  const effectiveSemesterFilter = hasMultipleSemesters ? semesterFilter : 'ALL';

  const filteredCourses = useMemo(() => {
    const filtered = filterCoursesList(uniqueCourses, {
      query: searchQuery,
      semester: effectiveSemesterFilter,
      department: deptFilter,
      departments,
      colleges,
      currentSemester,
      keepSelectedIds: selectedIds
    });
    return sortCoursesList(filtered, sortBy, selectedIds);
  }, [
    uniqueCourses,
    searchQuery,
    effectiveSemesterFilter,
    deptFilter,
    departments,
    colleges,
    currentSemester,
    selectedIds,
    sortBy
  ]);

  const handleToggle = (course) => {
    const courseId = course.id;
    const isRemoving = selectedIds.includes(courseId);
    const next = isRemoving
      ? selectedIds.filter((id) => id !== courseId)
      : [...selectedIds, courseId];
    onChange(next);

    if (onSectionSelectionsChange && sectionMode !== 'none') {
      const nextMap = { ...(sectionSelections || {}) };
      if (isRemoving) {
        delete nextMap[courseId];
      } else {
        const secs = (course.professors || []).filter((p) => p?.courseProfessorId);
        if (secs.length > 0 && !nextMap[courseId]) {
          const defaultCp = course.assignedCourseProfessorId || secs[0].courseProfessorId;
          nextMap[courseId] = sectionMode === 'multi' ? [defaultCp] : defaultCp;
        }
      }
      onSectionSelectionsChange(nextMap);
    }
  };

  const handleSectionSelect = (courseId, cpId) => {
    if (!onSectionSelectionsChange || !cpId) return;
    const nextMap = { ...(sectionSelections || {}) };
    if (sectionMode === 'multi') {
      const curArr = Array.isArray(nextMap[courseId])
        ? nextMap[courseId]
        : nextMap[courseId]
          ? [nextMap[courseId]]
          : [];
      if (curArr.includes(cpId)) {
        const filtered = curArr.filter((id) => id !== cpId);
        nextMap[courseId] = filtered.length > 0 ? filtered : [cpId];
      } else {
        nextMap[courseId] = [...curArr, cpId];
      }
    } else {
      nextMap[courseId] = cpId;
    }
    onSectionSelectionsChange(nextMap);
  };

  return (
    <div className="form-group">
      <div className="course-picker-header" style={{ flexWrap: 'wrap', gap: 6 }}>
        <label className="form-label" style={{ marginBottom: 0 }}>
          {label} {required ? '*' : ''} ({selectedIds.length}개 선택)
        </label>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {helperText && <span className="course-picker-hint">{helperText}</span>}
        </div>
      </div>

      <div className="course-search-input-wrap">
        <Search size={14} className="search-icon" />
        <input
          type="text"
          className="form-input course-search-input"
          placeholder={searchPlaceholder}
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
      </div>

      <div className="course-picker-controls">
        {hasMultipleSemesters && (
          <select
            className="form-select course-picker-ctrl-select"
            value={semesterFilter}
            onChange={(e) => setSemesterFilter(e.target.value)}
            aria-label="학기 필터"
          >
            <option value="CURRENT">현재 학기 ({formatSemesterLabel(currentSemester)})</option>
            <option value="ALL">전체 학기</option>
            {semesterOptions.map((sem) => (
              <option key={sem} value={sem}>
                {formatSemesterLabel(sem)}
              </option>
            ))}
          </select>
        )}
        {scopeOptions.length > 1 && (
          <select
            className="form-select course-picker-ctrl-select"
            value={deptFilter}
            onChange={(e) => setDeptFilter(e.target.value)}
            aria-label="학과·단과대 필터"
          >
            <option value="ALL">전체 학과·단과대</option>
            {scopeOptions.map((sc) => (
              <option key={sc} value={sc}>
                {sc}
              </option>
            ))}
          </select>
        )}
        <select
          className="form-select course-picker-ctrl-select"
          value={sortBy}
          onChange={(e) => setSortBy(e.target.value)}
          aria-label="교과목 정렬"
        >
          <option value="selected">선택 우선</option>
          <option value="name_asc">과목명순</option>
          <option value="semester_desc">최신 학기순</option>
          <option value="code_asc">학수번호순</option>
        </select>
      </div>

      <div className="course-checkbox-grid">
        {filteredCourses.length === 0 ? (
          <div className="course-search-empty">{emptyText}</div>
        ) : (
          filteredCourses.map((course) => {
            const checked = selectedIds.includes(course.id);
            const scopeLabel = course.department || course.college || '';
            const profLabel = formatCourseProfessorsSummary(course);
            const cSem = parseSemesterOrNull(course.semester);
            const curSem = parseSemesterOrNull(currentSemester);
            const showSemTag = Boolean(cSem && curSem && cSem !== curSem);
            const sectionOptions = Array.isArray(course.professors)
              ? course.professors.filter((p) => p && p.courseProfessorId)
              : [];
            const canPickSection =
              checked &&
              sectionMode !== 'none' &&
              Boolean(onSectionSelectionsChange) &&
              sectionOptions.length > 1;
            const currentSecVal = sectionSelections?.[course.id];

            return (
              <div key={course.id} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <label
                  className={`course-checkbox-pill ${checked ? 'checked' : ''}`}
                  title={`${course.name}${course.code ? ` (${course.code})` : ''}${scopeLabel ? ` · ${scopeLabel}` : ''} · 담당: ${profLabel}`}
                >
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => handleToggle(course)}
                  />
                  <span className="course-pill-text">
                    {course.isCustomDraft && (
                      <span className="admin-linked-chain-badge" style={{ marginLeft: 0, marginRight: 5 }}>
                        NEW
                      </span>
                    )}
                    {showSemTag && (
                      <span className="semester-pill" style={{ marginRight: 5 }}>
                        {formatSemesterLabel(course.semester)}
                      </span>
                    )}
                    {course.name}
                    {course.code ? ` (${course.code})` : ''}
                    {scopeLabel ? ` · ${scopeLabel}` : ''}
                    {course.isCustomDraft && course.professors?.[0]?.section
                      ? ` · ${course.professors[0].section}`
                      : ''}
                    {' · 담당: '}
                    <ProfessorNameList course={course} mode="all" />
                  </span>
                </label>

                {canPickSection && (
                  <div className="course-section-subpicker">
                    <span className="course-section-subpicker-label">
                      분반 선택
                    </span>
                    <div className="course-section-chip-list">
                      {sectionOptions.map((sec) => {
                        const isSecActive =
                          sectionMode === 'multi'
                            ? Array.isArray(currentSecVal)
                              ? currentSecVal.includes(sec.courseProfessorId)
                              : currentSecVal === sec.courseProfessorId
                            : (currentSecVal || sectionOptions[0]?.courseProfessorId) === sec.courseProfessorId;
                        return (
                          <button
                            type="button"
                            key={sec.courseProfessorId}
                            className={`section-select-chip ${isSecActive ? 'active' : ''}`}
                            onClick={() => handleSectionSelect(course.id, sec.courseProfessorId)}
                            title={sec.schedule || ''}
                          >
                            <strong>{sec.section}</strong>
                            <span>· {sec.name}</span>
                            {sec.department && sec.department !== '공통' ? (
                              <span className="text-muted">({sec.department})</span>
                            ) : null}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};

/**
 * Reusable searchable, filterable, sortable SINGLE-course selector
 * (e.g., for AdminModal's '이전 학기 연동' across ANY semester & ANY course).
 */
export const CourseSelectPicker = ({
  courses = [],
  value = '',
  onChange,
  specialOptions = [], // [{ value: 'AUTO', label: '자동 연동 (전학기 동일 과목명)' }, { value: 'NONE', label: '연동 없음' }]
  excludeIds = null, // Set<string> | string[]
  departments: propDepartments = null,
  colleges: propColleges = null,
  placeholder = '연동할 강의를 선택하세요',
  searchPlaceholder = '과목명, 학수번호, 학기, 학과, 교수명 검색...',
  maxVisible = 60
}) => {
  const auth = useAuth();
  const departments = propDepartments ?? auth?.departments ?? [];
  const colleges = propColleges ?? auth?.colleges ?? [];
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [semesterFilter, setSemesterFilter] = useState('ALL');
  const [deptFilter, setDeptFilter] = useState('ALL');
  const [sortBy, setSortBy] = useState('semester_desc');

  const excludeSet = useMemo(() => {
    if (!excludeIds) return new Set();
    return excludeIds instanceof Set ? excludeIds : new Set(excludeIds);
  }, [excludeIds]);

  const candidateCourses = useMemo(
    () => (courses || []).filter((c) => c?.id && !excludeSet.has(c.id)),
    [courses, excludeSet]
  );

  const semesterOptions = useMemo(() => extractCourseSemesters(candidateCourses), [candidateCourses]);
  const scopeOptions = useMemo(() => extractCourseScopes(candidateCourses), [candidateCourses]);

  const filteredCandidates = useMemo(() => {
    const filtered = filterCoursesList(candidateCourses, {
      query: searchQuery,
      semester: semesterFilter,
      department: deptFilter,
      departments,
      colleges
    });
    return sortCoursesList(filtered, sortBy);
  }, [candidateCourses, searchQuery, semesterFilter, deptFilter, departments, colleges, sortBy]);

  const selectedSpecial = specialOptions.find((opt) => opt.value === value);
  const selectedCourse = useMemo(
    () => (courses || []).find((c) => c?.id === value) || null,
    [courses, value]
  );

  const displayLabel = selectedSpecial
    ? selectedSpecial.label
    : selectedCourse
      ? `[${formatSemesterLabel(selectedCourse.semester)}] ${selectedCourse.name} (${selectedCourse.code})`
      : placeholder;

  const handleSelect = (nextVal) => {
    onChange(nextVal);
    setIsOpen(false);
  };

  return (
    <div className="course-select-picker">
      <button
        type="button"
        className={`course-select-trigger ${isOpen ? 'open' : ''}`}
        onClick={() => setIsOpen((prev) => !prev)}
        aria-expanded={isOpen}
      >
        <span className="course-select-trigger-text">{displayLabel}</span>
        {isOpen ? <ChevronUp size={15} /> : <ChevronDown size={15} />}
      </button>

      {isOpen && (
        <div className="course-select-panel">
          {specialOptions.length > 0 && (
            <div className="course-select-special-row">
              {specialOptions.map((opt) => {
                const active = value === opt.value;
                return (
                  <button
                    type="button"
                    key={opt.value}
                    className={`course-select-special-btn ${active ? 'active' : ''}`}
                    onClick={() => handleSelect(opt.value)}
                  >
                    {active && <Check size={12} />}
                    <span>{opt.label}</span>
                  </button>
                );
              })}
            </div>
          )}

          <div className="course-search-input-wrap" style={{ marginBottom: 6 }}>
            <Search size={13} className="search-icon" />
            <input
              type="text"
              className="form-input course-search-input"
              placeholder={searchPlaceholder}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              autoFocus
            />
            {searchQuery && (
              <button
                type="button"
                className="course-search-clear-btn"
                onClick={() => setSearchQuery('')}
                aria-label="검색어 지우기"
              >
                <X size={12} />
              </button>
            )}
          </div>

          <div className="course-picker-controls" style={{ marginBottom: 6 }}>
            <select
              className="form-select course-picker-ctrl-select"
              value={semesterFilter}
              onChange={(e) => setSemesterFilter(e.target.value)}
              aria-label="학기 필터"
            >
              <option value="ALL">전체 학기 ({candidateCourses.length})</option>
              {semesterOptions.map((sem) => (
                <option key={sem} value={sem}>
                  {formatSemesterLabel(sem)}
                </option>
              ))}
            </select>
            {scopeOptions.length > 1 && (
              <select
                className="form-select course-picker-ctrl-select"
                value={deptFilter}
                onChange={(e) => setDeptFilter(e.target.value)}
                aria-label="학과·단과대 필터"
              >
                <option value="ALL">전체 학과</option>
                {scopeOptions.map((sc) => (
                  <option key={sc} value={sc}>
                    {sc}
                  </option>
                ))}
              </select>
            )}
            <select
              className="form-select course-picker-ctrl-select"
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value)}
              aria-label="정렬 기준"
            >
              <option value="semester_desc">최신 학기순</option>
              <option value="semester_asc">오래된 학기순</option>
              <option value="name_asc">과목명순</option>
              <option value="code_asc">학수번호순</option>
              <option value="linked_first">연동 과목 우선</option>
            </select>
          </div>

          <div className="course-select-option-list">
            {filteredCandidates.length === 0 ? (
              <div className="course-search-empty">조건에 맞는 강의가 없습니다.</div>
            ) : (
              filteredCandidates.slice(0, maxVisible).map((cand) => {
                const isSelected = cand.id === value;
                const scopeLabel = cand.department || cand.college || '';
                return (
                  <button
                    type="button"
                    key={cand.id}
                    className={`course-select-option ${isSelected ? 'active' : ''}`}
                    onClick={() => handleSelect(cand.id)}
                  >
                    <div className="course-select-option-main">
                      <span className="semester-pill">{formatSemesterLabel(cand.semester)}</span>
                      <span className="course-select-option-name">{cand.name}</span>
                      <span className="course-select-option-meta">
                        ({cand.code}{scopeLabel ? ` · ${scopeLabel}` : ''})
                      </span>
                    </div>
                    <div className="course-select-option-sub">
                      <span>담당: {formatCourseProfessorsSummary(cand)}</span>
                      {(cand.prevCourseId || cand.nextCourseId) && (
                        <span className="admin-linked-chain-badge">
                          <Link2 size={10} />
                          연동됨
                        </span>
                      )}
                    </div>
                  </button>
                );
              })
            )}
            {filteredCandidates.length > maxVisible && (
              <div className="course-search-empty" style={{ padding: '6px 8px' }}>
                외 {filteredCandidates.length - maxVisible}개 — 검색어나 필터로 범위를 좁혀 주세요.
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export function buildCourseSelectionsPayload(selectedIds = [], sectionSelections = {}, allCourses = [], isMulti = false) {
  const courseMap = new Map((allCourses || []).map((c) => [c.id, c]));
  const result = [];
  for (const cId of selectedIds) {
    const rawVal = sectionSelections?.[cId];
    if (isMulti && Array.isArray(rawVal) && rawVal.length > 0) {
      rawVal.forEach((cpId) => {
        if (cpId) result.push({ courseId: cId, courseProfessorId: cpId });
      });
    } else if (typeof rawVal === 'string' && rawVal) {
      result.push({ courseId: cId, courseProfessorId: rawVal });
    } else {
      const course = courseMap.get(cId);
      const fallbackCp =
        course?.assignedCourseProfessorId ||
        course?.professors?.find((p) => p?.courseProfessorId)?.courseProfessorId ||
        null;
      result.push({ courseId: cId, courseProfessorId: fallbackCp });
    }
  }
  return result;
}

export default CoursePicker;
