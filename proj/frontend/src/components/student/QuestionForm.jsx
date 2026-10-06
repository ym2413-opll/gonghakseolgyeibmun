// src/components/student/QuestionForm.jsx
import React, { useState, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useNotifications } from '../../context/NotificationContext';
import { CATEGORY_DEFINITIONS } from '../../services/mockData';
import { api } from '../../services/api';
import AiRefineModal from './AiRefineModal';
import SimilarQuestionsModal from './SimilarQuestionsModal';
import {
  Send,
  Sparkles,
  Search,
  EyeOff,
  AlertCircle,
  CheckCircle2,
  BookOpen
} from 'lucide-react';

export default function QuestionForm({ onQuestionCreated, onCancel }) {
  const { currentUser } = useAuth();
  const { profSettings } = useNotifications();

  // 학생의 수강 과목 목록
  const studentCourses = currentUser?.courses || [];
  const [selectedCourseId, setSelectedCourseId] = useState(studentCourses[0]?.id || '');
  const [selectedCategory, setSelectedCategory] = useState('lecture');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [originalContent, setOriginalContent] = useState('');
  const [isAnonymous, setIsAnonymous] = useState(false);
  const [hasAiRefined, setHasAiRefined] = useState(false);

  // 모달 상태
  const [isAiModalOpen, setIsAiModalOpen] = useState(false);
  const [isSimilarModalOpen, setIsSimilarModalOpen] = useState(false);
  const [similarList, setSimilarList] = useState([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  const currentCourse = studentCourses.find((c) => c.id === selectedCourseId) || studentCourses[0] || {
    id: 'course-001',
    name: '공학설계입문',
    section: '01분반',
    profId: 'user-prof-001',
    profName: '이교수'
  };
  const catDef = CATEGORY_DEFINITIONS[selectedCategory] || CATEGORY_DEFINITIONS.lecture;

  // 카테고리 변경 시 규칙 적용
  const handleCategoryChange = (catKey) => {
    setSelectedCategory(catKey);
    // 성적 카테고리는 익명 불가능 명세
    if (catKey === 'grade') {
      setIsAnonymous(false);
    }
  };

  // 내용 변경 시 실시간 유사 질문 감지
  useEffect(() => {
    let cancel = false;
    const searchText = `${title} ${content}`.trim();

    if (selectedCategory !== 'grade' && searchText.length >= 6) {
      const timer = setTimeout(async () => {
        const results = await api.getSimilarQuestions(searchText, selectedCategory);
        if (!cancel) {
          setSimilarList(results);
        }
      }, 400);
      return () => {
        cancel = true;
        clearTimeout(timer);
      };
    } else {
      setSimilarList([]);
    }
  }, [title, content, selectedCategory]);

  const handleApplyAiRefined = (refined, origin) => {
    setContent(refined);
    setOriginalContent(origin);
    setHasAiRefined(true);
  };

  const handleResolveWithSimilar = (similarItem) => {
    alert(`[${similarItem.title}] 질답을 확인하셨습니다. 질문 작성을 종료합니다.`);
    if (onCancel) onCancel();
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!title.trim()) {
      setErrorMsg('질문 제목을 입력해 주세요.');
      return;
    }
    if (!content.trim()) {
      setErrorMsg('질문 내용을 입력해 주세요.');
      return;
    }

    // 교수가 해당 카테고리를 비활성화했는지 검사
    const isCatActive = profSettings.activeCategories?.[selectedCategory] ?? true;
    if (!isCatActive) {
      setErrorMsg(`현재 담당 교수님께서 [${catDef.name}] 카테고리의 질문 접수를 일시 중단하셨습니다.`);
      return;
    }

    setIsSubmitting(true);
    setErrorMsg('');

    try {
      const payload = {
        courseId: currentCourse.id,
        courseName: currentCourse.name,
        courseGroup: currentCourse.group || '',
        profId: currentCourse.profId,
        profName: currentCourse.profName,
        category: selectedCategory,
        title: title.trim(),
        content: content.trim(),
        originalContent: originalContent || content.trim(),
        hasAiRefined,
        // 명세: 질문자 정보는 백엔드로 보낼 것. 익명 질문이더라도 익명 처리되지 않은 원본 정보를 보낼 것.
        studentId: currentUser.id,
        studentName: currentUser.name,
        studentTag: currentUser.displayTag,
        department: currentUser.department,
        isAnonymous: selectedCategory === 'grade' ? false : isAnonymous
      };

      const created = await api.createQuestion(payload);
      if (onQuestionCreated) onQuestionCreated(created);
    } catch (err) {
      console.error('Failed to submit question', err);
      setErrorMsg('질문 등록 중 오류가 발생했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="question-form-container">
      <div className="form-card">
        <div className="form-header">
          <div className="form-header-title">
            <BookOpen size={20} className="primary-icon" />
            <h3>교수님께 새 질문 작성하기</h3>
          </div>
          <p className="form-header-subtitle">
            작성하신 질문은 담당 교수님께 안전하게 전달되며, AI 교정 도우미를 통해 공손한 서식으로 다듬을 수 있습니다.
          </p>
        </div>

        {errorMsg && (
          <div className="form-error-banner">
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="qa-form">
          {/* 1. 과목 선택 */}
          <div className="form-group">
            <label className="form-label">
              질문할 과목 선택 <span className="req">*</span>
            </label>
            <div className="course-select-grid">
              {studentCourses.map((c) => (
                <button
                  type="button"
                  key={c.id}
                  className={`course-chip ${selectedCourseId === c.id ? 'active' : ''}`}
                  onClick={() => setSelectedCourseId(c.id)}
                >
                  <span className="course-name">{c.name}</span>
                  <span className="course-prof">
                    {c.profName} ({c.section})
                  </span>
                </button>
              ))}
            </div>
          </div>

          {/* 2. 카테고리 선택 (필수 1개) */}
          <div className="form-group">
            <div className="label-with-rule">
              <label className="form-label">
                카테고리 선택 (필수 1개) <span className="req">*</span>
              </label>
              <span className="cat-rule-summary">
                카테고리별로 익명 가능 여부 및 답변 권한이 다릅니다.
              </span>
            </div>

            <div className="category-select-grid">
              {Object.values(CATEGORY_DEFINITIONS).map((cat) => {
                const isSelected = selectedCategory === cat.id;
                const isProfActive = profSettings.activeCategories?.[cat.id] ?? true;

                return (
                  <div
                    key={cat.id}
                    className={`category-select-card ${isSelected ? 'selected' : ''} ${!isProfActive ? 'disabled-cat' : ''}`}
                    onClick={() => isProfActive && handleCategoryChange(cat.id)}
                  >
                    <div className="cat-select-top">
                      <span className={`cat-badge ${cat.badgeClass}`}>{cat.name}</span>
                      {!isProfActive ? (
                        <span className="cat-inactive-tag">접수 중단</span>
                      ) : (
                        isSelected && <CheckCircle2 size={16} className="selected-check" />
                      )}
                    </div>
                    <p className="cat-desc-text">{cat.description}</p>
                    <div className="cat-constraints-info">
                      {cat.id === 'grade' && (
                        <span className="constraint-warn">⚠️ 실명 필수 / 유사 질답 불가</span>
                      )}
                      {cat.id === 'assignment' && (
                        <span className="constraint-info">ℹ️ 익명 가능 / 타 분반 교수 답변 불가</span>
                      )}
                      {cat.id === 'lecture' && (
                        <span className="constraint-ok">✅ 익명 가능 / 같은 과목 다른 교수 답변 허용</span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* 3. 제목 입력 */}
          <div className="form-group">
            <label className="form-label" htmlFor="q-title">
              질문 제목 <span className="req">*</span>
            </label>
            <input
              id="q-title"
              type="text"
              className="input-text"
              placeholder="질문할 핵심 내용을 요약하여 입력하세요 (예: 3주차 실습 회로 전압 강하 질문)"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={120}
            />
          </div>

          {/* 유사 질답 감지 배너 (성적 카테고리 제외) */}
          {selectedCategory !== 'grade' && similarList.length > 0 && (
            <div className="similar-detect-banner">
              <div className="detect-text">
                <Search size={16} />
                <span>
                  작성하신 내용과 유사한 기존 질문 <strong>{similarList.length}건</strong>이 있습니다! (최고 유사도{' '}
                  {similarList[0].similarityScore}%)
                </span>
              </div>
              <button
                type="button"
                className="btn-view-similar"
                onClick={() => setIsSimilarModalOpen(true)}
              >
                유사 질답 바로 확인하기
              </button>
            </div>
          )}

          {/* 4. 본문 입력 & AI 질문 도우미 툴바 */}
          <div className="form-group">
            <div className="content-label-row">
              <label className="form-label" htmlFor="q-content">
                질문 본문 <span className="req">*</span>
              </label>

              <div className="content-toolbar-actions">
                {/* AI 질문 도우미 버튼 */}
                <button
                  type="button"
                  className={`btn-ai-assist ${hasAiRefined ? 'refined-active' : ''}`}
                  onClick={() => setIsAiModalOpen(true)}
                  title="학생이 작성한 내용을 정중한 학술/질문 양식으로 교정 및 윤문합니다"
                >
                  <Sparkles size={14} />
                  <span>{hasAiRefined ? '✨ AI 교정 완료 (다시 다듬기)' : '✨ AI 질문 도우미'}</span>
                </button>

                {/* 유사 질답 보기 버튼 */}
                {selectedCategory !== 'grade' && (
                  <button
                    type="button"
                    className="btn-similar-search"
                    onClick={() => setIsSimilarModalOpen(true)}
                    disabled={similarList.length === 0}
                    title="기존에 답변된 유사 질답 보기"
                  >
                    <Search size={14} />
                    <span>유사 질답 ({similarList.length})</span>
                  </button>
                )}
              </div>
            </div>

            <textarea
              id="q-content"
              rows={8}
              className={`textarea-content ${hasAiRefined ? 'has-refined-border' : ''}`}
              placeholder="궁금한 사항을 상세히 작성해 주세요. 어조가 걱정되신다면 편하게 작성 후 상단의 [✨ AI 질문 도우미]를 클릭해보세요!"
              value={content}
              onChange={(e) => {
                setContent(e.target.value);
                if (hasAiRefined) setHasAiRefined(false);
              }}
            />

            {hasAiRefined && (
              <div className="refined-status-hint">
                <CheckCircle2 size={14} />
                <span>AI가 공손한 어조로 교정한 내용이 적용되었습니다. 원문도 안전하게 보관됩니다.</span>
              </div>
            )}
          </div>

          {/* 5. 익명 질문 체크박스 (성적 카테고리에서는 비활성화) */}
          <div className="form-group anonymous-group">
            <div className="anon-check-wrapper">
              <label className={`checkbox-label ${selectedCategory === 'grade' ? 'disabled-label' : ''}`}>
                <input
                  type="checkbox"
                  checked={isAnonymous}
                  disabled={selectedCategory === 'grade'}
                  onChange={(e) => setIsAnonymous(e.target.checked)}
                />
                <span className="checkbox-text">
                  <EyeOff size={16} />
                  익명으로 질문하기
                </span>
              </label>

              {selectedCategory === 'grade' ? (
                <span className="anon-disabled-reason">
                  ※ '성적' 카테고리는 공정한 성적 조회 및 이의제기를 위해 익명으로 등록할 수 없습니다.
                </span>
              ) : (
                <span className="anon-rule-desc">
                  타 학생 및 질문 목록에는 <strong>'{currentCourse?.name || '과목'} 수강생'</strong>으로 표시됩니다.
                  (부당한 질문 방지를 위해 백엔드에는 본인 정보가 안전히 전달됩니다)
                </span>
              )}
            </div>
          </div>

          {/* 제출 버튼 */}
          <div className="form-submit-row">
            {onCancel && (
              <button type="button" className="btn btn-secondary" onClick={onCancel}>
                취소
              </button>
            )}
            <button type="submit" className="btn btn-primary submit-btn" disabled={isSubmitting}>
              <Send size={16} />
              <span>{isSubmitting ? '등록 중...' : '질문 등록하기'}</span>
            </button>
          </div>
        </form>
      </div>

      {/* AI 질문 도우미 모달 */}
      <AiRefineModal
        isOpen={isAiModalOpen}
        onClose={() => setIsAiModalOpen(false)}
        initialText={content || title}
        category={selectedCategory}
        onApplyRefined={handleApplyAiRefined}
      />

      {/* 유사 질답 검색 모달 */}
      <SimilarQuestionsModal
        isOpen={isSimilarModalOpen}
        onClose={() => setIsSimilarModalOpen(false)}
        similarList={similarList}
        queryText={`${title} ${content}`}
        onResolveWithSimilar={handleResolveWithSimilar}
      />
    </div>
  );
}
