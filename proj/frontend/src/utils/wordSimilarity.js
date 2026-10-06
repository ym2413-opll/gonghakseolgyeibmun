// src/utils/wordSimilarity.js
// Word2Vec / Cosine Similarity 시뮬레이션 알고리즘
// 질문 검색 및 유사 질답 보기 기능용

/**
 * 텍스트에서 단어 토큰 및 2-gram 추출
 */
function tokenize(text) {
  if (!text) return [];
  // 한글, 영문, 숫자 단어 추출
  const cleaned = text.toLowerCase().replace(/[^a-z0-9가-힣\s]/g, ' ');
  const words = cleaned.split(/\s+/).filter(w => w.length > 1);
  
  // 문자 단위 바이그램 추가 (한국어 어미 및 복합어 유사도 보정)
  const ngrams = [];
  for (let i = 0; i < cleaned.length - 1; i++) {
    const pair = cleaned.slice(i, i + 2).trim();
    if (pair.length === 2) {
      ngrams.push(pair);
    }
  }
  return [...words, ...ngrams];
}

/**
 * 단어 벡터 생성 (빈도 기반 Term-Frequency)
 */
function getTermVector(tokens, vocabulary) {
  const vector = new Array(vocabulary.length).fill(0);
  const tokenCount = {};
  tokens.forEach(t => {
    tokenCount[t] = (tokenCount[t] || 0) + 1;
  });
  
  vocabulary.forEach((word, index) => {
    if (tokenCount[word]) {
      vector[index] = tokenCount[word];
    }
  });
  return vector;
}

/**
 * 두 벡터 간 코사인 유사도(Cosine Similarity) 계산
 */
function cosineSimilarity(vecA, vecB) {
  let dotProduct = 0;
  let normA = 0;
  let normB = 0;

  for (let i = 0; i < vecA.length; i++) {
    dotProduct += vecA[i] * vecB[i];
    normA += vecA[i] * vecA[i];
    normB += vecB[i] * vecB[i];
  }

  if (normA === 0 || normB === 0) return 0;
  return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
}

/**
 * 질문 목록과 비교하여 코사인 유사도 상위 n개 반환
 * @param {string} queryText - 사용자가 입력 중인 질문 (제목 + 본문)
 * @param {Array} questions - 기존 등록된 질문 목록
 * @param {number} topN - 반환할 최대 개수 (기본 3개)
 */
export function calculateSimilarQuestions(queryText, questions, topN = 3) {
  if (!queryText || !queryText.trim() || !questions || questions.length === 0) {
    return [];
  }

  const queryTokens = tokenize(queryText);
  if (queryTokens.length === 0) return [];

  // 전체 어휘집(Vocabulary) 생성
  const vocabSet = new Set(queryTokens);
  questions.forEach(q => {
    const targetText = `${q.title} ${q.content} ${q.originalContent || ''}`;
    const targetTokens = tokenize(targetText);
    targetTokens.forEach(t => vocabSet.add(t));
  });

  const vocabulary = Array.from(vocabSet);
  const queryVector = getTermVector(queryTokens, vocabulary);

  const scoredQuestions = questions
    .filter(q => q.category !== 'grade') // 명세: '성적' 카테고리는 유사 질답 보기 사용 불가
    .map(q => {
      const targetText = `${q.title} ${q.content} ${q.originalContent || ''}`;
      const targetTokens = tokenize(targetText);
      const targetVector = getTermVector(targetTokens, vocabulary);
      const similarity = cosineSimilarity(queryVector, targetVector);

      return {
        ...q,
        similarityScore: Math.round(similarity * 100) // 백분율 점수 (0 ~ 100)
      };
    })
    .filter(item => item.similarityScore >= 15) // 최소 유사도 15% 이상
    .sort((a, b) => b.similarityScore - a.similarityScore)
    .slice(0, topN);

  return scoredQuestions;
}
