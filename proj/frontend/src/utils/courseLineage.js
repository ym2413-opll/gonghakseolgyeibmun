// src/utils/courseLineage.js
import { getCollegeSearchNames, getDepartmentSearchNames } from '../services/api';
import { compareSemesters, parseSemesterOrNull } from './semester';

/**
 * Traverses a course's doubly-linked list (prevCourseId <-> nextCourseId)
 * continuously to return the ordered lineage chain from oldest/head to newest/tail.
 */
export function buildCourseLineageChain(courseId, allCourses = []) {
  if (!courseId || !Array.isArray(allCourses) || allCourses.length === 0) return [];
  const byId = new Map(allCourses.filter((c) => c?.id).map((c) => [c.id, c]));
  const start = byId.get(courseId);
  if (!start) return [];

  const visited = new Set([start.id]);
  const prevNodes = [];
  let cursor = start.prevCourseId;
  let guard = 0;
  while (cursor && guard++ < 200 && !visited.has(cursor)) {
    const node = byId.get(cursor);
    if (!node) break;
    visited.add(node.id);
    prevNodes.push(node);
    cursor = node.prevCourseId;
  }

  const nextNodes = [];
  cursor = start.nextCourseId;
  guard = 0;
  while (cursor && guard++ < 200 && !visited.has(cursor)) {
    const node = byId.get(cursor);
    if (!node) break;
    visited.add(node.id);
    nextNodes.push(node);
    cursor = node.nextCourseId;
  }

  return [...prevNodes.reverse(), start, ...nextNodes];
}

/**
 * Returns a Set of all course IDs belonging to the continuous linked-list lineage of startCourseId.
 */
export function collectLinkedCourseIds(startCourseId, allCourses = []) {
  const chain = buildCourseLineageChain(startCourseId, allCourses);
  if (chain.length === 0 && startCourseId) return new Set([startCourseId]);
  return new Set(chain.map((c) => c.id));
}

/**
 * Returns a Set of startCourseId and all of its forward (nextCourseId) descendants
 * to prevent cycle creation when choosing a prevCourseId.
 */
export function collectSelfAndDescendantIds(startCourseId, allCourses = []) {
  const result = new Set();
  if (!startCourseId) return result;
  result.add(startCourseId);
  const byId = new Map((allCourses || []).filter((c) => c?.id).map((c) => [c.id, c]));
  let cursor = byId.get(startCourseId)?.nextCourseId;
  let guard = 0;
  while (cursor && guard++ < 200 && !result.has(cursor)) {
    result.add(cursor);
    cursor = byId.get(cursor)?.nextCourseId;
  }
  return result;
}

/**
 * Extracts sorted unique semester values from a course list (newest first).
 */
export function extractCourseSemesters(courses = []) {
  const set = new Set();
  (courses || []).forEach((c) => {
    const sem = parseSemesterOrNull(c?.semester);
    if (sem) set.add(sem);
  });
  return Array.from(set).sort((a, b) => compareSemesters(b, a));
}

/**
 * Extracts sorted unique department / college labels from a course list.
 */
export function extractCourseScopes(courses = []) {
  const set = new Set();
  (courses || []).forEach((c) => {
    const label = String(c?.department || c?.college || '').trim();
    if (label) set.add(label);
  });
  return Array.from(set).sort((a, b) => a.localeCompare(b, 'ko'));
}

/**
 * Shared search + semester + department/college filter for course lists.
 */
export function filterCoursesList(
  courses = [],
  {
    query = '',
    semester = 'ALL', // 'ALL' | 'CURRENT' | 'YYYY-term'
    department = 'ALL',
    departments = [],
    colleges = [],
    currentSemester = '',
    keepSelectedIds = null
  } = {}
) {
  const kw = String(query || '').trim().toLowerCase();
  const normCurSem = parseSemesterOrNull(currentSemester);
  const normFilterSem =
    semester === 'CURRENT'
      ? normCurSem
      : semester && semester !== 'ALL'
        ? parseSemesterOrNull(semester)
        : null;
  const keepSet = keepSelectedIds ? new Set(keepSelectedIds) : null;

  return (courses || []).filter((c) => {
    if (!c?.id) return false;
    const isPinned = keepSet ? keepSet.has(c.id) : false;

    if (normFilterSem && !isPinned) {
      const cSem = parseSemesterOrNull(c.semester);
      if (cSem && cSem !== normFilterSem) return false;
    }

    if (department && department !== 'ALL' && !isPinned) {
      const cScope = String(c.department || c.college || '').trim();
      if (cScope !== department && String(c.college || '').trim() !== department) {
        return false;
      }
    }

    if (!kw) return true;
    const n = String(c.name || '').toLowerCase();
    const code = String(c.code || '').toLowerCase();
    const sem = String(c.semester || '').toLowerCase();
    const dept = String(c.department || '').toLowerCase();
    const deptAliases = getDepartmentSearchNames(departments, c.department).some((alias) =>
      alias.toLowerCase().includes(kw)
    );
    const college = String(c.college || '').toLowerCase();
    const collegeAliases = getCollegeSearchNames(colleges, c.college).some((alias) =>
      alias.toLowerCase().includes(kw)
    );
    const prof = String(c.profName || c.assignedProfessorName || '').toLowerCase();
    const prevName = String(c.prevCourseName || '').toLowerCase();
    const nextName = String(c.nextCourseName || '').toLowerCase();
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
      prevName.includes(kw) ||
      nextName.includes(kw) ||
      sectionMatch
    );
  });
}

/**
 * Shared sort helper for course lists.
 */
export function sortCoursesList(courses = [], sortBy = 'semester_desc', selectedIds = []) {
  const selectedSet = new Set(selectedIds || []);
  return [...(courses || [])].sort((a, b) => {
    if (sortBy === 'selected') {
      const aSel = selectedSet.has(a.id) ? 0 : 1;
      const bSel = selectedSet.has(b.id) ? 0 : 1;
      if (aSel !== bSel) return aSel - bSel;
      return (
        compareSemesters(b.semester, a.semester) ||
        String(a.name || '').localeCompare(String(b.name || ''), 'ko')
      );
    }
    if (sortBy === 'linked_first') {
      const aLinked = a.prevCourseId || a.nextCourseId ? 0 : 1;
      const bLinked = b.prevCourseId || b.nextCourseId ? 0 : 1;
      if (aLinked !== bLinked) return aLinked - bLinked;
      return (
        compareSemesters(b.semester, a.semester) ||
        String(a.name || '').localeCompare(String(b.name || ''), 'ko')
      );
    }
    if (sortBy === 'name_asc') {
      return (
        String(a.name || '').localeCompare(String(b.name || ''), 'ko') ||
        compareSemesters(b.semester, a.semester)
      );
    }
    if (sortBy === 'code_asc') {
      return (
        String(a.code || '').localeCompare(String(b.code || ''), 'ko') ||
        compareSemesters(b.semester, a.semester)
      );
    }
    if (sortBy === 'semester_asc') {
      return (
        compareSemesters(a.semester, b.semester) ||
        String(a.name || '').localeCompare(String(b.name || ''), 'ko')
      );
    }
    // Default: semester_desc
    return (
      compareSemesters(b.semester, a.semester) ||
      String(a.name || '').localeCompare(String(b.name || ''), 'ko')
    );
  });
}
