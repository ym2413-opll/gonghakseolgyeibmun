// src/components/common/CoursePicker.jsx
import React, { useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { getCollegeSearchNames, getDepartmentSearchNames } from '../../services/api';
import { formatCourseProfessorsSummary } from '../../utils/professorDisplay';
import { formatSemesterLabel, parseSemesterOrNull } from '../../utils/semester';
import ProfessorNameList from './ProfessorNameList';

/**
 * Reusable searchable multi-course checkbox picker shared across
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
  const [showAllSemesters, setShowAllSemesters] = useState(false);

  const uniqueCourses = useMemo(() => {
    const map = new Map();
    (courses || []).forEach((c) => {
      if (c?.id && !map.has(c.id)) {
        map.set(c.id, c);
      }
    });
    return Array.from(map.values());
  }, [courses]);

  const hasMultipleSemesters = useMemo(() => {
    const sems = new Set(
      uniqueCourses.map((c) => parseSemesterOrNull(c.semester)).filter(Boolean)
    );
    return sems.size > 1;
  }, [uniqueCourses]);

  const filteredCourses = useMemo(() => {
    const kw = searchQuery.trim().toLowerCase();
    const normCurSem = parseSemesterOrNull(currentSemester);

    return uniqueCourses.filter((c) => {
      const isSelected = selectedIds.includes(c.id);
      if (!showAllSemesters && hasMultipleSemesters && normCurSem && !isSelected) {
        const cSem = parseSemesterOrNull(c.semester);
        if (cSem && cSem !== normCurSem) return false;
      }

      if (!kw) return true;
      const n = (c.name || '').toLowerCase();
      const code = (c.code || '').toLowerCase();
      const sem = (c.semester || '').toLowerCase();
      const dept = (c.department || '').toLowerCase();
      const deptAliases = getDepartmentSearchNames(departments, c.department).some((alias) =>
        alias.toLowerCase().includes(kw)
      );
      const college = (c.college || '').toLowerCase();
      const collegeAliases = getCollegeSearchNames(colleges, c.college).some((alias) =>
        alias.toLowerCase().includes(kw)
      );
      const prof = (c.profName || c.assignedProfessorName || '').toLowerCase();
      const sectionMatch = Array.isArray(c.professors)
        ? c.professors.some(
            (p) =>
              String(p?.name || '').toLowerCase().includes(kw) ||
              String(p?.section || '').toLowerCase().includes(kw)
          )
        : false;

      return (
        n.includes(kw) ||
        code.includes(kw) ||
        sem.includes(kw) ||
        dept.includes(kw) ||
        deptAliases ||
        college.includes(kw) ||
        collegeAliases ||
        prof.includes(kw) ||
        sectionMatch
      );
    });
  }, [uniqueCourses, searchQuery, departments, colleges, showAllSemesters, hasMultipleSemesters, currentSemester, selectedIds]);

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
          {hasMultipleSemesters && (
            <label
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                fontSize: '0.76rem',
                color: 'var(--text-secondary)',
                cursor: 'pointer'
              }}
            >
              <input
                type="checkbox"
                checked={showAllSemesters}
                onChange={(e) => setShowAllSemesters(e.target.checked)}
              />
              <span>이전 학기 포함</span>
            </label>
          )}
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
                    {showSemTag && (
                      <span className="semester-pill" style={{ marginRight: 5 }}>
                        {formatSemesterLabel(course.semester)}
                      </span>
                    )}
                    {course.name}
                    {course.code ? ` (${course.code})` : ''}
                    {scopeLabel ? ` · ${scopeLabel}` : ''}
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
