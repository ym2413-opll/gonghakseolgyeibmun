// src/components/common/CoursePicker.jsx
import React, { useMemo, useState } from 'react';
import { Search } from 'lucide-react';

/**
 * Reusable searchable multi-course checkbox picker shared across
 * registration, user settings, and professor settings modals.
 */
const CoursePicker = ({
  courses = [],
  selectedIds = [],
  onChange,
  label = '과목 선택',
  required = true,
  searchPlaceholder = '과목명, 학수번호, 학과·단과대 검색...',
  emptyText = '검색 결과가 없습니다.',
  helperText = ''
}) => {
  const [searchQuery, setSearchQuery] = useState('');

  const uniqueCourses = useMemo(() => {
    const map = new Map();
    (courses || []).forEach((c) => {
      if (c?.id && !map.has(c.id)) {
        map.set(c.id, c);
      }
    });
    return Array.from(map.values());
  }, [courses]);

  const filteredCourses = useMemo(() => {
    const kw = searchQuery.trim().toLowerCase();
    if (!kw) return uniqueCourses;
    return uniqueCourses.filter((c) => {
      const n = (c.name || '').toLowerCase();
      const code = (c.code || '').toLowerCase();
      const dept = (c.department || '').toLowerCase();
      const college = (c.college || '').toLowerCase();
      const prof = (c.profName || c.assignedProfessorName || '').toLowerCase();
      return (
        n.includes(kw) ||
        code.includes(kw) ||
        dept.includes(kw) ||
        college.includes(kw) ||
        prof.includes(kw)
      );
    });
  }, [uniqueCourses, searchQuery]);

  const handleToggle = (courseId) => {
    const next = selectedIds.includes(courseId)
      ? selectedIds.filter((id) => id !== courseId)
      : [...selectedIds, courseId];
    onChange(next);
  };

  return (
    <div className="form-group">
      <div className="course-picker-header">
        <label className="form-label" style={{ marginBottom: 0 }}>
          {label} {required ? '*' : ''} ({selectedIds.length}개 선택)
        </label>
        {helperText && <span className="course-picker-hint">{helperText}</span>}
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
            const profNames = Array.isArray(course.professors)
              ? course.professors.map((p) => p?.name).filter(Boolean)
              : [];
            const profLabel =
              profNames.length > 0
                ? profNames.join(', ')
                : course.profName && course.profName !== '미배정'
                  ? course.profName
                  : '미배정';
            return (
              <label
                key={course.id}
                className={`course-checkbox-pill ${checked ? 'checked' : ''}`}
                title={`${course.name}${course.code ? ` (${course.code})` : ''}${scopeLabel ? ` · ${scopeLabel}` : ''} · 담당: ${profLabel}`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => handleToggle(course.id)}
                />
                <span className="course-pill-text">
                  {course.name}
                  {course.code ? ` (${course.code})` : ''}
                  {scopeLabel ? ` · ${scopeLabel}` : ''}
                  {` · 담당: ${profLabel}`}
                </span>
              </label>
            );
          })
        )}
      </div>
    </div>
  );
};

export default CoursePicker;
