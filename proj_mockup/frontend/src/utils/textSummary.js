// src/utils/textSummary.js
// 질문 본문에서 핵심 첫 문장 또는 요약 제목을 추출하는 유틸리티
import { containsMathSyntax, stripMarkdownForPreview } from './markdown.js';

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

  const hasMath = containsMathSyntax(clean);
  // 다중 줄 수식·코드 블록을 먼저 제거·평문화하되 줄바꿈은 유지해 첫 의미 줄을 추출
  const plainMultiline = stripMarkdownForPreview(clean, {
    mathReplacement: '',
    preserveNewlines: true
  });
  const lines = plainMultiline.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const firstLine = lines[0] || '';
  const stripped = firstLine
    .replace(/[[\]]/g, '')
    .replace(/^(?:은|는|가|을|를|의|에서|와|과|으로|로)\s+/, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!stripped) return hasMath ? '수식 관련 질문' : '학업 질문';

  // 첫 문장 마침표나 물음표가 있으면 거기까지 우선 자름
  const sentenceMatch = stripped.match(/^(.+?[.!?])(\s|$)/);
  if (sentenceMatch && sentenceMatch[1] && sentenceMatch[1].length >= 10 && sentenceMatch[1].length <= maxLength) {
    return sentenceMatch[1];
  }

  if (stripped.length <= maxLength) {
    return stripped;
  }

  return stripped.slice(0, maxLength) + '...';
}
