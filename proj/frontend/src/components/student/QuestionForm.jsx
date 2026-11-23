// src/components/student/QuestionForm.jsx
import React, { useState, useEffect, useRef } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useTheme } from '../../context/ThemeContext';
import { useNotifications } from '../../context/NotificationContext';
import { CATEGORY_DEFINITIONS, CATEGORY_LIST } from '../../services/mockData';
import { api } from '../../services/api';
import { generateSummaryTitle } from '../../utils/textSummary';
import { sanitizeAnonymousStudentText } from '../../utils/anonymize';
import AttachmentUploader, { insertSnippetAtCursor } from '../common/AttachmentUploader';
import MarkdownContent, { MarkdownModeTabs } from '../common/MarkdownContent';
import ProfessorNameList from '../common/ProfessorNameList';
import AiRefineModal from './AiRefineModal';
import AiFormulaModal from '../common/AiFormulaModal';
import SimilarQuestionsModal from './SimilarQuestionsModal';
import HelpBubble from '../common/HelpBubble';
import {
  Send,
  PenTool,
  Search,
  AlertCircle,
  CheckCircle2,
  Sigma
} from 'lucide-react';

export default function QuestionForm({ onQuestionCreated, onCancel }) {
  const { currentUser, isStudent, allCourses, refreshCurrentUser, refreshCatalog } = useAuth();
  const { checkBlueWhiteUnlock } = useTheme();
  const { profSettings } = useNotifications();
  const contentTextareaRef = useRef(null);

  useEffect(() => {
    refreshCurrentUser();
    refreshCatalog();
  }, [refreshCurrentUser, refreshCatalog]);

  // 학생의 수강 과목 목록
  const studentCourses = currentUser?.courses?.length
    ? currentUser.courses
    : (allCourses || []).slice(0, 12);
  const [selectedCourseId, setSelectedCourseId] = useState(studentCourses[0]?.id || '');
  const [selectedCategory, setSelectedCategory] = useState('lecture');
  const [content, setContent] = useState('');
  const [originalContent, setOriginalContent] = useState('');
  const [refinedTitle, setRefinedTitle] = useState('');
  const [isAnonymous, setIsAnonymous] = useState(false);
  const [hasAiRefined, setHasAiRefined] = useState(false);

  // 파일 첨부 관련 상태
  const [attachments, setAttachments] = useState([]);
  const [isProcessingFiles, setIsProcessingFiles] = useState(false);
  const [isPreviewMode, setIsPreviewMode] = useState(false);

  // 모달 및 제출 상태
  const [isAiModalOpen, setIsAiModalOpen] = useState(false);
  const [isFormulaModalOpen, setIsFormulaModalOpen] = useState(false);
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

  // 본문 내용 변경 시 실시간 유사 질문 감지
  useEffect(() => {
    let cancel = false;
    const searchText = content.trim();

    if (selectedCategory !== 'grade' && searchText.length >= 6) {
      const timer = setTimeout(async () => {
        const results = await api.getSimilarQuestions(searchText, selectedCategory, currentCourse.id);
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
  }, [content, selectedCategory, currentCourse.id]);

  const scrubForAnonymous = (text) =>
    sanitizeAnonymousStudentText(text, currentUser, {
      professorNames: [currentCourse?.profName, currentCourse?.assignedProfessorName]
    });

  const handleAnonymousToggle = (checked) => {
    setIsAnonymous(checked);
    if (checked && selectedCategory !== 'grade') {
      setContent((prev) => scrubForAnonymous(prev));
      setOriginalContent((prev) => (prev ? scrubForAnonymous(prev) : prev));
      setRefinedTitle((prev) => (prev ? scrubForAnonymous(prev) : prev));
    }
  };

  const handleApplyAiRefined = (refinedContent, originContent, aiTitle) => {
    const anonActive = selectedCategory !== 'grade' && isAnonymous;
    if (refinedContent) setContent(anonActive ? scrubForAnonymous(refinedContent) : refinedContent);
    if (originContent) setOriginalContent(anonActive ? scrubForAnonymous(originContent) : originContent);
    if (aiTitle) setRefinedTitle(anonActive ? scrubForAnonymous(aiTitle) : aiTitle);
    setHasAiRefined(true);
  };

  const handleResolveWithSimilar = (similarItem) => {
    alert(`[${similarItem.title}] 질답을 확인하셨습니다. 질문 작성을 종료합니다.`);
    if (onCancel) onCancel();
  };

  const handleInsertPhotoTag = (photoNum) => {
    insertSnippetAtCursor(contentTextareaRef, content, setContent, `[사진 ${photoNum}]`, () => {
      if (hasAiRefined) setHasAiRefined(false);
    });
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isSubmitting) return;
    if (!content.trim()) {
      setErrorMsg('질문 내용을 입력해 주세요.');
      return;
    }

    if (isProcessingFiles) {
      setErrorMsg('첨부 파일 보안 검사 및 압축이 진행 중입니다. 잠시만 기다려 주세요.');
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
      const latestUser = await refreshCurrentUser();
      if (!latestUser || latestUser.role !== 'student' || latestUser.id !== currentUser?.id) {
        setErrorMsg(
          latestUser?.role === 'professor'
            ? '교수 계정으로 변경되어 질문을 작성할 수 없습니다.'
            : '로그인 상태가 변경되었습니다. 계정을 확인한 뒤 다시 시도해 주세요.'
        );
        return;
      }

      const effectiveAnonymous = selectedCategory === 'grade' ? false : isAnonymous;
      const finalContent = effectiveAnonymous ? scrubForAnonymous(content.trim()) : content.trim();
      const finalOriginal = effectiveAnonymous
        ? scrubForAnonymous(originalContent || content.trim())
        : (originalContent || content.trim());

      // AI 정제 제목이 있으면 우선 사용하고, 없으면 본문에서 자동으로 요약 생성
      const rawSummaryTitle = (hasAiRefined && refinedTitle?.trim())
        ? refinedTitle.trim()
        : generateSummaryTitle(finalContent);
      const summaryTitle = effectiveAnonymous ? scrubForAnonymous(rawSummaryTitle) : rawSummaryTitle;
      const safeAttachments = effectiveAnonymous
        ? attachments.map((a) => ({
            ...a,
            originalName: a.originalName ? scrubForAnonymous(a.originalName) : a.originalName,
            fileName: a.fileName ? scrubForAnonymous(a.fileName) : a.fileName
          }))
        : attachments;

      const payload = {
        courseId: currentCourse.id,
        courseName: currentCourse.name,
        courseGroup: currentCourse.group || '',
        profId: currentCourse.profId,
        profName: currentCourse.profName,
        category: selectedCategory,
        title: summaryTitle,
        content: finalContent,
        originalContent: finalOriginal,
        hasAiRefined,
        studentId: currentUser.id,
        studentName: effectiveAnonymous ? '익명 수강생' : currentUser.name,
        studentTag: effectiveAnonymous ? `${currentCourse.name || '과목'} 수강생` : currentUser.displayTag,
        department: effectiveAnonymous ? '' : currentUser.department,
        isAnonymous: effectiveAnonymous,
        // 첨부파일 목록 및 ID 연동
        attachmentIds: safeAttachments.map((a) => a.id),
        attachments: safeAttachments
      };

      const created = await api.createQuestion(payload);
      if (onQuestionCreated) onQuestionCreated(created);
    } catch (err) {
      console.error('Failed to submit question', err);
      setErrorMsg(err.message || '질문 등록 중 오류가 발생했습니다.');
    } finally {
      setIsSubmitting(false);
    }
  };

  if (!isStudent) {
    return null;
  }

  return (
    <div className="question-form-container">
      <div className="form-card">
        <div className="form-header">
          <div className="form-header-title">
            <h3>질문 작성</h3>
          </div>
        </div>

        {errorMsg && (
          <div className="form-error-banner">
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="qa-form">
          <HelpBubble
            tier="SOME"
            mode="inline"
            text="질문할 과목과 카테고리(강의·과제·성적)를 선택해요."
            className="mb-help-bubble"
          />
          {/* 1. 과목 선택 */}
          <div className="form-group">
            <label className="form-label">
              과목 <span className="req">*</span>
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
                    <ProfessorNameList course={c} mode="primary" /> ({c.section})
                  </span>
                </button>
              ))}
            </div>
          </div>

          {/* 2. 카테고리 선택 */}
          <div className="form-group">
            <label className="form-label">
              카테고리 <span className="req">*</span>
            </label>

            <div className="category-select-grid">
              {CATEGORY_LIST.map((cat) => {
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
                        <span className="constraint-warn">실명 필수 · 유사 질답 미제공</span>
                      )}
                      {cat.id === 'assignment' && (
                        <span className="constraint-info">익명 가능 · 담당 교수만 답변</span>
                      )}
                      {cat.id === 'lecture' && (
                        <span className="constraint-ok">익명 가능 · 동일 과목 교수 답변 허용</span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* 유사 질답 감지 배너 (성적 카테고리 제외) */}
          {selectedCategory !== 'grade' && similarList.length > 0 && (
            <div className="similar-detect-banner">
              <div className="detect-text">
                <Search size={16} />
                <span>
                  유사한 기존 질문 <strong>{similarList.length}건</strong>이 있습니다. (최고 유사도{' '}
                  {similarList[0].similarityScore}%)
                </span>
              </div>
              <button
                type="button"
                className="btn-view-similar"
                onClick={() => setIsSimilarModalOpen(true)}
              >
                확인하기
              </button>
            </div>
          )}

          {/* 3. 본문 입력 & 문장 다듬기 툴바 */}
          <div className="form-group">
            <div className="content-label-row">
              <div className="content-label-group">
                <label className="form-label" htmlFor="q-content">
                  본문 <span className="req">*</span>
                </label>
              </div>

              <div className="content-toolbar-actions">
                <MarkdownModeTabs isPreview={isPreviewMode} onTogglePreview={setIsPreviewMode} />
                <HelpBubble
                  tier="ALL"
                  mode="floating"
                  placement="bottom-end"
                  text="원하는 수식을 문장으로 적으면 LaTeX 수식으로 변환해 본문에 넣어줘요."
                >
                  <button
                    type="button"
                    className="btn-ai-formula"
                    onClick={() => setIsFormulaModalOpen(true)}
                  >
                    <Sigma size={14} />
                    <span>수식 입력</span>
                  </button>
                </HelpBubble>
                <HelpBubble
                  tier="ALL"
                  mode="floating"
                  placement="bottom-end"
                  text="작성한 질문을 정중하게 다듬고 본문의 [[지시사항]]을 AI 결과로 바꿔줘요."
                >
                  <button
                    type="button"
                    className={`btn-ai-assist ${hasAiRefined ? 'refined-active' : ''}`}
                    onClick={() => setIsAiModalOpen(true)}
                  >
                    <PenTool size={14} />
                    <span>{hasAiRefined ? '문장 다듬기 다시 실행' : '문장 다듬기'}</span>
                  </button>
                </HelpBubble>

                {selectedCategory !== 'grade' && (
                  <HelpBubble
                    tier="ALL"
                    mode="floating"
                    placement="bottom-end"
                    text="유사한 기존 질문과 답변을 확인해요."
                  >
                    <button
                      type="button"
                      className="btn-similar-search"
                      onClick={() => setIsSimilarModalOpen(true)}
                      disabled={similarList.length === 0}
                    >
                      <Search size={14} />
                      <span>유사 질답 ({similarList.length})</span>
                    </button>
                  </HelpBubble>
                )}
              </div>
            </div>

            <HelpBubble
              tier="SOME"
              mode="inline"
              text="마크다운 서식, 수식($...$, $$...$$), 이미지 링크를 지원해요. 편집/미리보기 탭으로 결과를 바로 확인해요."
              className="mb-help-bubble"
            />

            {isPreviewMode ? (
              <div className="md-preview-box">
                {content.trim() ? (
                  <MarkdownContent
                    content={
                      isAnonymous && selectedCategory !== 'grade'
                        ? scrubForAnonymous(content)
                        : content
                    }
                    currentSeq={1}
                    imagesBySeq={
                      new Map([
                        [
                          1,
                          attachments.filter(
                            (a) =>
                              a &&
                              (a.isImage ||
                                (a.mimeType && a.mimeType.startsWith('image/')) ||
                                /\.(png|jpe?g|gif|webp)$/i.test(a.originalName || a.fileName || ''))
                          )
                        ]
                      ])
                    }
                  />
                ) : (
                  <span className="md-preview-empty">미리볼 내용이 없습니다.</span>
                )}
              </div>
            ) : (
              <textarea
                ref={contentTextareaRef}
                id="q-content"
                rows={8}
                className={`textarea-content ${hasAiRefined ? 'has-refined-border' : ''}`}
                placeholder="질문 내용을 입력하세요."
                value={content}
                onChange={(e) => {
                  const nextVal = e.target.value;
                  setContent(nextVal);
                  checkBlueWhiteUnlock(nextVal);
                  if (hasAiRefined) setHasAiRefined(false);
                }}
              />
            )}

            {hasAiRefined && (
              <div className="refined-status-hint">
                <CheckCircle2 size={14} />
                <span>정제된 문장이 적용되었습니다.</span>
              </div>
            )}
          </div>

          {/* 4. 파일 첨부 영역 */}
          <AttachmentUploader
            attachments={attachments}
            onChangeAttachments={setAttachments}
            isProcessing={isProcessingFiles}
            onProcessingChange={setIsProcessingFiles}
            onInsertPhotoTag={handleInsertPhotoTag}
            onError={setErrorMsg}
            showHelpBubble
          />

          {/* 5. 익명 질문 체크박스 */}
          <div className="form-group anonymous-group">
            <HelpBubble
              tier="ALL"
              mode="inline"
              text="익명으로 올리면 이름·학번 등 신상 정보가 본문에서 자동 가림 처리돼요. (성적 카테고리는 실명 필수)"
              className="mb-help-bubble"
            />
            <div className="anon-check-wrapper">
              <label className={`checkbox-label ${selectedCategory === 'grade' ? 'disabled-label' : ''}`}>
                <input
                  type="checkbox"
                  checked={isAnonymous}
                  disabled={selectedCategory === 'grade'}
                  onChange={(e) => handleAnonymousToggle(e.target.checked)}
                />
                <span className="checkbox-text">
                  익명으로 질문하기
                </span>
              </label>

              {selectedCategory === 'grade' ? (
                <span className="anon-disabled-reason">
                  성적 카테고리는 실명으로만 작성할 수 있습니다.
                </span>
              ) : (
                <span className="anon-rule-desc">
                  목록에 '{currentCourse?.name || '과목'} 수강생'으로 표시됩니다.
                </span>
              )}
            </div>
          </div>

          {/* 제출 버튼 */}
          <div className="form-submit-row">
            {onCancel && (
              <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={isSubmitting}>
                취소
              </button>
            )}
            <button
              type="submit"
              className="btn btn-primary submit-btn"
              disabled={isSubmitting || isProcessingFiles}
            >
              {isSubmitting ? '등록 중...' : '등록'}
            </button>
          </div>
        </form>
      </div>

      {/* AI 질문 도우미 모달 */}
      <AiRefineModal
        isOpen={isAiModalOpen}
        onClose={() => setIsAiModalOpen(false)}
        initialText={content}
        category={selectedCategory}
        courseName={currentCourse?.name || ''}
        professorName={currentCourse?.profName || currentCourse?.assignedProfessorName || ''}
        isAnonymous={isAnonymous}
        onApplyRefined={handleApplyAiRefined}
      />

      {/* 수식 입력 모달 */}
      <AiFormulaModal
        isOpen={isFormulaModalOpen}
        onClose={() => setIsFormulaModalOpen(false)}
        onInsertFormula={(wrapped) =>
          insertSnippetAtCursor(contentTextareaRef, content, setContent, wrapped, () => {
            if (hasAiRefined) setHasAiRefined(false);
          })
        }
      />

      {/* 유사 질답 검색 모달 */}
      <SimilarQuestionsModal
        isOpen={isSimilarModalOpen}
        onClose={() => setIsSimilarModalOpen(false)}
        similarList={similarList}
        queryText={content}
        onResolveWithSimilar={handleResolveWithSimilar}
      />
    </div>
  );
}
