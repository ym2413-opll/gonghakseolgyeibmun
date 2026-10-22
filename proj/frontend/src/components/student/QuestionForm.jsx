// src/components/student/QuestionForm.jsx
import React, { useState, useEffect, useRef } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useNotifications } from '../../context/NotificationContext';
import { CATEGORY_DEFINITIONS, CATEGORY_LIST } from '../../services/mockData';
import { api } from '../../services/api';
import { validateSecurity, formatFileSize, MAX_ATTACHMENTS_COUNT } from '../../utils/fileSecurity';
import { compressImageClient } from '../../utils/imageCompression';
import { generateSummaryTitle } from '../../utils/textSummary';
import AiRefineModal from './AiRefineModal';
import SimilarQuestionsModal from './SimilarQuestionsModal';
import {
  Send,
  Sparkles,
  Search,
  EyeOff,
  AlertCircle,
  CheckCircle2,
  BookOpen,
  Paperclip,
  UploadCloud,
  FileText,
  Image as ImageIcon,
  Trash2,
  ShieldCheck,
  Loader2,
  FileCode,
  FileArchive
} from 'lucide-react';

export default function QuestionForm({ onQuestionCreated, onCancel }) {
  const { currentUser } = useAuth();
  const { profSettings } = useNotifications();
  const fileInputRef = useRef(null);

  // 학생의 수강 과목 목록
  const studentCourses = currentUser?.courses || [];
  const [selectedCourseId, setSelectedCourseId] = useState(studentCourses[0]?.id || '');
  const [selectedCategory, setSelectedCategory] = useState('lecture');
  const [content, setContent] = useState('');
  const [originalContent, setOriginalContent] = useState('');
  const [isAnonymous, setIsAnonymous] = useState(false);
  const [hasAiRefined, setHasAiRefined] = useState(false);

  // 파일 첨부 관련 상태
  const [attachments, setAttachments] = useState([]);
  const [isProcessingFiles, setIsProcessingFiles] = useState(false);
  const [fileProcessStatus, setFileProcessStatus] = useState('');
  const [dragActive, setDragActive] = useState(false);

  // 모달 및 제출 상태
  const [isAiModalOpen, setIsAiModalOpen] = useState(false);
  const [isSimilarModalOpen, setIsSimilarModalOpen] = useState(false);
  const [similarList, setSimilarList] = useState([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [securitySuccessMsg, setSecuritySuccessMsg] = useState('');

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
  }, [content, selectedCategory]);

  const handleApplyAiRefined = (refinedContent, originContent) => {
    if (refinedContent) setContent(refinedContent);
    if (originContent) setOriginalContent(originContent);
    setHasAiRefined(true);
  };

  const handleResolveWithSimilar = (similarItem) => {
    alert(`[${similarItem.title}] 질답을 확인하셨습니다. 질문 작성을 종료합니다.`);
    if (onCancel) onCancel();
  };

  // 파일 선택 및 드래그 앤 드롭 처리 (엄격 보안 검증 + 이미지 압축)
  const processFiles = async (files) => {
    if (!files || files.length === 0) return;

    if (attachments.length + files.length > MAX_ATTACHMENTS_COUNT) {
      setErrorMsg(`파일은 최대 ${MAX_ATTACHMENTS_COUNT}개까지만 첨부할 수 있습니다.`);
      return;
    }

    setIsProcessingFiles(true);
    setErrorMsg('');
    setSecuritySuccessMsg('');

    const newAttachments = [];

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      setFileProcessStatus(`'${file.name}' 보안 검사 및 무결성 분석 중...`);

      // 1. 엄격한 사전 보안 검사 (확장자, 다중확장자, 경로문자, 매직바이트)
      const secResult = await validateSecurity(file);
      if (!secResult.valid) {
        setErrorMsg(`[보안 차단: ${file.name}] ${secResult.error}`);
        setIsProcessingFiles(false);
        setFileProcessStatus('');
        return;
      }

      let fileToUpload = file;
      let compressionMeta = {
        isImage: secResult.isImage,
        originalSize: file.size,
        compressedSize: file.size,
        isCompressed: false,
        compressionRatio: 0,
        previewUrl: ''
      };

      // 2. 이미지 파일인 경우 클라이언트단 압축 및 리사이징 적용
      if (secResult.isImage) {
        setFileProcessStatus(`'${file.name}' 고화질 이미지 최적화 및 압축 중...`);
        try {
          const compResult = await compressImageClient(file);
          fileToUpload = compResult.compressedFile;
          compressionMeta = {
            isImage: true,
            originalSize: compResult.originalSize,
            compressedSize: compResult.compressedSize,
            isCompressed: compResult.isCompressed,
            compressionRatio: compResult.compressionRatio,
            previewUrl: compResult.previewUrl
          };
        } catch (compErr) {
          console.warn('Image compression fallback to original', compErr);
        }
      }

      // 3. 백엔드 또는 로컬 스토리지에 파일 업로드
      setFileProcessStatus(`'${file.name}' 서버 안전 업로드 중...`);
      try {
        const uploadRes = await api.uploadAttachment(fileToUpload, compressionMeta);
        if (uploadRes && uploadRes.success) {
          newAttachments.push(uploadRes.attachment);
        }
      } catch (uploadErr) {
        setErrorMsg(`파일 업로드 실패: ${uploadErr.message}`);
        setIsProcessingFiles(false);
        setFileProcessStatus('');
        return;
      }
    }

    setAttachments((prev) => [...prev, ...newAttachments]);
    setSecuritySuccessMsg(`${newAttachments.length}개 파일의 보안 검사 및 최적화가 완료되었습니다.`);
    setIsProcessingFiles(false);
    setFileProcessStatus('');

    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleFileInputChange = (e) => {
    const files = Array.from(e.target.files || []);
    processFiles(files);
  };

  const handleDrag = (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setDragActive(true);
    } else if (e.type === 'dragleave') {
      setDragActive(false);
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setDragActive(false);
    if (e.dataTransfer?.files && e.dataTransfer.files.length > 0) {
      processFiles(Array.from(e.dataTransfer.files));
    }
  };

  const handleRemoveAttachment = async (indexToRemove) => {
    const item = attachments[indexToRemove];
    if (item && item.id) {
      await api.deleteAttachment(item.id);
    }
    setAttachments((prev) => prev.filter((_, idx) => idx !== indexToRemove));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
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
      // 제목은 별도 입력 없이 본문에서 자동으로 요약 생성
      const summaryTitle = generateSummaryTitle(content);

      const payload = {
        courseId: currentCourse.id,
        courseName: currentCourse.name,
        courseGroup: currentCourse.group || '',
        profId: currentCourse.profId,
        profName: currentCourse.profName,
        category: selectedCategory,
        title: summaryTitle,
        content: content.trim(),
        originalContent: originalContent || content.trim(),
        hasAiRefined,
        studentId: currentUser.id,
        studentName: currentUser.name,
        studentTag: currentUser.displayTag,
        department: currentUser.department,
        isAnonymous: selectedCategory === 'grade' ? false : isAnonymous,
        // 첨부파일 목록 및 ID 연동
        attachmentIds: attachments.map((a) => a.id),
        attachments: attachments
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

  // 첨부 파일 아이콘 렌더링 헬퍼
  const renderAttachmentIcon = (att) => {
    if (att.isImage) {
      return <ImageIcon size={20} className="file-icon-img" />;
    }
    const ext = att.originalName?.split('.').pop()?.toLowerCase();
    if (ext === 'pdf') return <FileText size={20} className="file-icon-pdf" />;
    if (['zip', 'rar', '7z'].includes(ext)) return <FileArchive size={20} className="file-icon-zip" />;
    if (['c', 'cpp', 'py', 'java', 'js', 'html'].includes(ext)) return <FileCode size={20} className="file-icon-code" />;
    return <FileText size={20} className="file-icon-doc" />;
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
            작성하신 질문은 담당 교수님께 안전하게 전달되며, 별도의 제목 입력 없이 본문만 작성하시면 됩니다.
          </p>
        </div>

        {errorMsg && (
          <div className="form-error-banner">
            <AlertCircle size={16} />
            <span>{errorMsg}</span>
          </div>
        )}

        {securitySuccessMsg && (
          <div className="form-success-banner">
            <ShieldCheck size={16} className="text-emerald-500" />
            <span>{securitySuccessMsg}</span>
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

          {/* 3. 본문 입력 & AI 질문 도우미 툴바 */}
          <div className="form-group">
            <div className="content-label-row">
              <div className="content-label-group">
                <label className="form-label" htmlFor="q-content">
                  질문 본문 <span className="req">*</span>
                </label>
                <span className="title-omitted-hint">
                  (제목을 별도로 쓰실 필요 없이 본문만 작성하시면 됩니다)
                </span>
              </div>

              <div className="content-toolbar-actions">
                <button
                  type="button"
                  className={`btn-ai-assist ${hasAiRefined ? 'refined-active' : ''}`}
                  onClick={() => setIsAiModalOpen(true)}
                  title="학생이 작성한 본문을 정중하고 명확한 학술/질문 양식으로 교정 및 윤문합니다"
                >
                  <Sparkles size={14} />
                  <span>{hasAiRefined ? '✨ AI 교정 완료 (다시 다듬기)' : '✨ AI 질문 도우미 (본문 정중하게 윤색)'}</span>
                </button>

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
              placeholder="궁금한 사항을 상세히 작성해 주세요. 어조가 걱정되신다면 편하게 작성 후 상단의 [✨ AI 질문 도우미]를 클릭해보세요! (목록에 표기될 제목은 본문 내용에서 자동으로 생성됩니다)"
              value={content}
              onChange={(e) => {
                setContent(e.target.value);
                if (hasAiRefined) setHasAiRefined(false);
              }}
            />

            {hasAiRefined && (
              <div className="refined-status-hint">
                <CheckCircle2 size={14} />
                <span>AI가 질문 본문을 공손하고 명확한 학술 양식으로 윤색하였습니다. 원문은 안전하게 보관됩니다.</span>
              </div>
            )}
          </div>

          {/* 4. 파일 첨부 영역 (엄격 보안 검증 및 이미지 자동 압축) */}
          <div className="form-group attachment-section">
            <div className="attachment-header-row">
              <div className="attachment-label-wrap">
                <label className="form-label">
                  <Paperclip size={15} />
                  파일 첨부
                  <span className="attachment-count-tag">
                    ({attachments.length}/{MAX_ATTACHMENTS_COUNT})
                  </span>
                </label>
                <span className="attachment-hint-text">
                  과제 소스코드, 회로도, 실습 캡처 등을 첨부할 수 있습니다.
                </span>
              </div>
              <div className="security-badges-wrap">
                <span className="badge-security-verified" title="악성 실행 파일 및 확장자 위조 자동 차단">
                  <ShieldCheck size={13} />
                  엄격 보안 검사
                </span>
                <span className="badge-compression-applied" title="고화질 이미지는 최적 압축되어 업로드 속도가 향상됩니다">
                  ⚡ 이미지 자동 압축
                </span>
              </div>
            </div>

            {/* 드래그 앤 드롭 업로드 박스 */}
            <div
              className={`file-dropzone ${dragActive ? 'drag-over' : ''} ${attachments.length >= MAX_ATTACHMENTS_COUNT ? 'dropzone-disabled' : ''}`}
              onDragEnter={handleDrag}
              onDragLeave={handleDrag}
              onDragOver={handleDrag}
              onDrop={handleDrop}
              onClick={() => {
                if (attachments.length < MAX_ATTACHMENTS_COUNT && !isProcessingFiles) {
                  fileInputRef.current?.click();
                }
              }}
            >
              <input
                ref={fileInputRef}
                type="file"
                multiple
                className="hidden-file-input"
                onChange={handleFileInputChange}
                accept=".png,.jpg,.jpeg,.gif,.webp,.pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.hwp,.hwpx,.txt,.zip"
                disabled={attachments.length >= MAX_ATTACHMENTS_COUNT || isProcessingFiles}
              />

              {isProcessingFiles ? (
                <div className="dropzone-processing">
                  <Loader2 size={24} className="spin-icon text-indigo-600" />
                  <span className="processing-text">{fileProcessStatus}</span>
                  <span className="processing-subtext">보안 무결성 및 이미지 최적화를 진행 중입니다.</span>
                </div>
              ) : (
                <div className="dropzone-content">
                  <UploadCloud size={28} className="upload-cloud-icon" />
                  <div className="dropzone-text-group">
                    <span className="dropzone-primary-text">
                      클릭하여 파일을 선택하거나, 마우스로 파일을 여기에 끌어다 놓으세요
                    </span>
                    <span className="dropzone-secondary-text">
                      허용: PDF, Office, HWP, TXT, ZIP, 이미지 (PNG, JPG, WEBP, GIF) | 개당 최대 15MB
                      <strong className="block text-rose-500 font-medium mt-0.5">※ 실행 파일(EXE/BAT/SH 등) 및 위조 확장자는 즉시 차단됩니다.</strong>
                    </span>
                  </div>
                </div>
              )}
            </div>

            {/* 첨부된 파일 목록 */}
            {attachments.length > 0 && (
              <div className="attached-files-list">
                {attachments.map((att, idx) => (
                  <div key={att.id || idx} className="attached-file-card">
                    {/* 이미지 썸네일 미리보기 */}
                    {att.isImage && (att.previewUrl || att.url) ? (
                      <div className="attached-file-thumb">
                        <img src={att.previewUrl || att.url} alt={att.originalName} />
                      </div>
                    ) : (
                      <div className="attached-file-icon-box">{renderAttachmentIcon(att)}</div>
                    )}

                    <div className="attached-file-info">
                      <div className="file-name-row">
                        <span className="file-name" title={att.originalName}>
                          {att.originalName}
                        </span>
                        <span className="badge-safe-verified">
                          <ShieldCheck size={11} /> 안전 검증됨
                        </span>
                      </div>

                      <div className="file-meta-row">
                        {att.isCompressed ? (
                          <div className="compression-info-wrap">
                            <span className="file-size-badge compressed">
                              ⚡ {formatFileSize(att.originalSize)} → {formatFileSize(att.fileSize)}
                            </span>
                            <span className="compression-ratio-pill">
                              {att.compressionRatio}% 압축 완료
                            </span>
                          </div>
                        ) : (
                          <span className="file-size-badge normal">
                            {formatFileSize(att.fileSize)}
                          </span>
                        )}
                      </div>
                    </div>

                    <button
                      type="button"
                      className="btn-remove-attachment"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleRemoveAttachment(idx);
                      }}
                      title="첨부 파일 삭제"
                    >
                      <Trash2 size={15} />
                    </button>
                  </div>
                ))}
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
              <button type="button" className="btn btn-secondary" onClick={onCancel} disabled={isSubmitting}>
                취소
              </button>
            )}
            <button
              type="submit"
              className="btn btn-primary submit-btn"
              disabled={isSubmitting || isProcessingFiles}
            >
              {isSubmitting ? (
                <>
                  <Loader2 size={16} className="spin-icon" />
                  <span>등록 중...</span>
                </>
              ) : (
                <>
                  <Send size={16} />
                  <span>질문 등록하기</span>
                </>
              )}
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
        onApplyRefined={handleApplyAiRefined}
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
