// src/utils/professorDisplay.js
// 담당교수 표기 유틸리티 (미가입 시간표 교수 구분 및 동명이인 분반/학과 표기)

export function formatProfessorLabel(prof, allProfsOnCourse = []) {
  if (!prof || !prof.name) return '미배정';
  const baseName = String(prof.name).trim();
  if (!baseName || baseName === '미배정') return '미배정';

  const sameNameCount = Array.isArray(allProfsOnCourse)
    ? allProfsOnCourse.filter((p) => p && String(p.name || '').trim() === baseName).length
    : 0;
  const disambiguator =
    sameNameCount > 1 ? String(prof.section || prof.department || '').trim() : '';
  const nameWithContext = disambiguator ? `${baseName} (${disambiguator})` : baseName;

  return prof.isRegistered === false ? `${nameWithContext} (미가입)` : nameWithContext;
}

export function formatCourseProfessorsSummary(course) {
  if (!course) return '미배정';
  const profs = Array.isArray(course.professors) ? course.professors.filter((p) => p && p.name) : [];
  if (profs.length > 0) {
    return profs.map((p) => formatProfessorLabel(p, profs)).join(', ');
  }
  const fallbackName = String(course.assignedProfessorName || course.profName || '').trim();
  if (!fallbackName || fallbackName === '미배정') return '미배정';
  return course.assignedProfessorRegistered === false ? `${fallbackName} (미가입)` : fallbackName;
}

export function formatPrimaryProfessorLabel(course) {
  if (!course) return '미배정';
  const profs = Array.isArray(course.professors) ? course.professors.filter((p) => p && p.name) : [];
  const primaryId = course.assignedProfessorId || course.profId;
  const matched =
    (primaryId && profs.find((p) => p.id === primaryId)) ||
    profs.find((p) => p.isRegistered !== false) ||
    profs[0] ||
    null;
  if (matched) {
    return formatProfessorLabel(matched, profs);
  }
  const fallbackName = String(course.assignedProfessorName || course.profName || '').trim();
  if (!fallbackName || fallbackName === '미배정') return '미배정';
  return course.assignedProfessorRegistered === false ? `${fallbackName} (미가입)` : fallbackName;
}
