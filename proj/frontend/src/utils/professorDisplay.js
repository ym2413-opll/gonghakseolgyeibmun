// src/utils/professorDisplay.js
// 담당교수 표기 유틸리티 (미가입 시간표 교수 회색 구분 및 동명이인 분반/학과 표기)

function buildProfessorLabel(prof, allProfsOnCourse = []) {
  if (!prof || !prof.name) return '미배정';
  const baseName = String(prof.name).trim();
  if (!baseName || baseName === '미배정') return '미배정';

  const sameNameCount = Array.isArray(allProfsOnCourse)
    ? allProfsOnCourse.filter((p) => p && String(p.name || '').trim() === baseName).length
    : 0;
  const disambiguator =
    sameNameCount > 1 ? String(prof.section || prof.department || '').trim() : '';
  return disambiguator ? `${baseName} (${disambiguator})` : baseName;
}

export function getCourseProfessorItems(course) {
  if (!course) return [{ id: 'unassigned', label: '미배정', isRegistered: false }];
  const profs = Array.isArray(course.professors) ? course.professors.filter((p) => p && p.name) : [];
  if (profs.length > 0) {
    return profs.map((p, idx) => ({
      id: p.id || `prof-${idx}`,
      label: buildProfessorLabel(p, profs),
      isRegistered: p.isRegistered !== false
    }));
  }
  const fallbackName = String(course.assignedProfessorName || course.profName || '').trim();
  if (!fallbackName || fallbackName === '미배정') {
    return [{ id: 'unassigned', label: '미배정', isRegistered: false }];
  }
  return [
    {
      id: course.assignedProfessorId || course.profId || 'primary',
      label: fallbackName,
      isRegistered: course.assignedProfessorRegistered !== false
    }
  ];
}

export function getPrimaryProfessorItem(course) {
  if (!course) return { id: 'unassigned', label: '미배정', isRegistered: false };
  const profs = Array.isArray(course.professors) ? course.professors.filter((p) => p && p.name) : [];
  const primaryId = course.assignedProfessorId || course.profId;
  const matched =
    (primaryId && profs.find((p) => p.id === primaryId)) ||
    profs.find((p) => p.isRegistered !== false) ||
    profs[0] ||
    null;
  if (matched) {
    return {
      id: matched.id || 'primary',
      label: buildProfessorLabel(matched, profs),
      isRegistered: matched.isRegistered !== false
    };
  }
  const fallbackName = String(course.assignedProfessorName || course.profName || '').trim();
  if (!fallbackName || fallbackName === '미배정') {
    return { id: 'unassigned', label: '미배정', isRegistered: false };
  }
  return {
    id: primaryId || 'primary',
    label: fallbackName,
    isRegistered: course.assignedProfessorRegistered !== false
  };
}

export function formatCourseProfessorsSummary(course) {
  return getCourseProfessorItems(course)
    .map((item) => item.label)
    .join(', ');
}
