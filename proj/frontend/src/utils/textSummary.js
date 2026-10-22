// src/utils/textSummary.js
// 질문 본문에서 핵심 첫 문장 또는 요약 제목을 추출하는 유틸리티

/**
 * 본문 내용을 바탕으로 표시용 요약 제목 생성
 * @param {string} content 질문 본문
 * @param {number} maxLength 최대 길이 (기본 45자)
 * @returns {string} 요약된 제목
 */
export function generateSummaryTitle(content, maxLength = 45) {
  if (!content || typeof content !== 'string') {
    return '학업 질문';
  }

  const clean = content.trim();
  if (!clean) return '학업 질문';

  // 첫 번째 줄 추출 (엔터 기준)
  const lines = clean.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  const firstLine = lines[0] || clean;

  // 마크다운 기호(#, -, *, 숫자 목차 등) 제거
  let stripped = firstLine
    .replace(/^[#*\-•\d.]+\s*/, '')
    .replace(/[\[\\]]/g, '')
    .trim();

  // 첫 문장 마침표나 물음표가 있으면 거기까지 우선 자름
  const sentenceMatch = stripped.match(/^(.+?[.!?])(\s|$)/);
  if (sentenceMatch && sentenceMatch[1] && sentenceMatch[1].length >= 10 && sentenceMatch[1].length <= maxLength) {
    return sentenceMatch[1];
  }

  if (stripped.length <= maxLength) {
    return stripped || clean.slice(0, maxLength);
  }

  return stripped.slice(0, maxLength) + '...';
}
