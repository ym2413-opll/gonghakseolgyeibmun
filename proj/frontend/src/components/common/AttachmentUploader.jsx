// src/components/common/AttachmentUploader.jsx
import React, { useRef, useState } from 'react';
import { api } from '../../services/api';
import {
  validateSecurity,
  formatFileSize,
  isSafeAttachmentUrl,
  MAX_ATTACHMENTS_COUNT
} from '../../utils/fileSecurity';
import { compressImageClient } from '../../utils/imageCompression';
import HelpBubble from './HelpBubble';
import {
  Paperclip,
  UploadCloud,
  FileText,
  Image as ImageIcon,
  Trash2,
  Loader2,
  FileCode,
  FileArchive
} from 'lucide-react';

function renderAttachmentIcon(att) {
  if (att.isImage) {
    return <ImageIcon size={20} className="file-icon-img" />;
  }
  const ext = att.originalName?.split('.').pop()?.toLowerCase();
  if (ext === 'pdf') return <FileText size={20} className="file-icon-pdf" />;
  if (['zip', 'rar', '7z'].includes(ext)) return <FileArchive size={20} className="file-icon-zip" />;
  if (['c', 'cpp', 'py', 'java', 'js', 'html'].includes(ext)) {
    return <FileCode size={20} className="file-icon-code" />;
  }
  return <FileText size={20} className="file-icon-doc" />;
}

export function insertSnippetAtCursor(textareaRef, currentValue, setValue, snippet, onAfterInsert) {
  const textarea = textareaRef?.current;
  if (!textarea || typeof textarea.selectionStart !== 'number') {
    setValue((prev) => (prev ? `${prev}\n${snippet}` : snippet));
    if (onAfterInsert) onAfterInsert();
    return;
  }
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd ?? start;
  const nextValue = `${currentValue.slice(0, start)}${snippet}${currentValue.slice(end)}`;
  setValue(nextValue);
  if (onAfterInsert) onAfterInsert();
  requestAnimationFrame(() => {
    textarea.focus();
    const nextPos = start + snippet.length;
    textarea.setSelectionRange(nextPos, nextPos);
  });
}

export default function AttachmentUploader({
  attachments = [],
  onChangeAttachments,
  isProcessing = false,
  onProcessingChange,
  onInsertPhotoTag,
  onError,
  compact = false,
  showHelpBubble = false
}) {
  const fileInputRef = useRef(null);
  const [fileProcessStatus, setFileProcessStatus] = useState('');
  const [dragActive, setDragActive] = useState(false);

  const setProcessing = (val) => {
    if (onProcessingChange) onProcessingChange(val);
  };

  const processFiles = async (files) => {
    if (!files || files.length === 0) return;

    if (attachments.length + files.length > MAX_ATTACHMENTS_COUNT) {
      if (onError) onError(`파일은 최대 ${MAX_ATTACHMENTS_COUNT}개까지만 첨부할 수 있습니다.`);
      return;
    }

    setProcessing(true);
    if (onError) onError('');

    const newAttachments = [];

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      setFileProcessStatus(`'${file.name}' 보안 검사 및 무결성 분석 중...`);

      const secResult = await validateSecurity(file);
      if (!secResult.valid) {
        if (onError) onError(`[보안 차단: ${file.name}] ${secResult.error}`);
        setProcessing(false);
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

      setFileProcessStatus(`'${file.name}' 서버 안전 업로드 중...`);
      try {
        const uploadRes = await api.uploadAttachment(fileToUpload, compressionMeta);
        if (uploadRes && uploadRes.success) {
          newAttachments.push(uploadRes.attachment);
        }
      } catch (uploadErr) {
        if (onError) onError(`파일 업로드 실패: ${uploadErr.message}`);
        setProcessing(false);
        setFileProcessStatus('');
        return;
      }
    }

    onChangeAttachments((prev) => [...prev, ...newAttachments]);
    setProcessing(false);
    setFileProcessStatus('');

    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handleFileInputChange = (e) => {
    processFiles(Array.from(e.target.files || []));
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
    if (item?.previewUrl && String(item.previewUrl).startsWith('blob:')) {
      URL.revokeObjectURL(item.previewUrl);
    }
    if (item && item.id) {
      try {
        await api.deleteAttachment(item.id);
      } catch (err) {
        console.warn('Failed to delete remote attachment:', err);
      }
    }
    onChangeAttachments((prev) => prev.filter((_, idx) => idx !== indexToRemove));
  };

  let photoCounter = 0;

  return (
    <div className={`form-group attachment-section ${compact ? 'is-compact' : ''}`}>
      <div className="attachment-header-row">
        <div className="attachment-label-wrap">
          <label className="form-label">
            <Paperclip size={15} />
            첨부 파일 ({attachments.length}/{MAX_ATTACHMENTS_COUNT})
          </label>
        </div>
      </div>

      {showHelpBubble && (
        <HelpBubble
          tier="ALL"
          mode="inline"
          text="파일을 첨부하고 [사진 1] 태그로 본문에 넣어요."
          className="mb-help-bubble"
        />
      )}

      <div
        className={`file-dropzone ${compact ? 'is-compact' : ''} ${dragActive ? 'drag-over' : ''} ${
          attachments.length >= MAX_ATTACHMENTS_COUNT ? 'dropzone-disabled' : ''
        }`}
        onDragEnter={handleDrag}
        onDragLeave={handleDrag}
        onDragOver={handleDrag}
        onDrop={handleDrop}
        onClick={() => {
          if (attachments.length < MAX_ATTACHMENTS_COUNT && !isProcessing) {
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
          disabled={attachments.length >= MAX_ATTACHMENTS_COUNT || isProcessing}
        />

        {isProcessing ? (
          <div className="dropzone-processing">
            <Loader2 size={20} className="spin-icon" />
            <span className="processing-text">{fileProcessStatus}</span>
          </div>
        ) : (
          <div className="dropzone-content">
            <UploadCloud size={compact ? 18 : 24} className="upload-cloud-icon" />
            <div className="dropzone-text-group">
              <span className="dropzone-primary-text">파일 선택 또는 드래그 앤 드롭</span>
              <span className="dropzone-secondary-text">
                PDF, Office, HWP, TXT, ZIP, 이미지 (최대 15MB)
              </span>
            </div>
          </div>
        )}
      </div>

      {attachments.length > 0 && (
        <div className="attached-files-list">
          {attachments.map((att, idx) => {
            const photoNum = att.isImage ? ++photoCounter : null;
            return (
              <div key={att.id || idx} className="attached-file-card">
                {att.isImage && isSafeAttachmentUrl(att.previewUrl || att.url) ? (
                  <div className="attached-file-thumb">
                    <img src={att.previewUrl || att.url} alt={att.originalName} />
                  </div>
                ) : (
                  <div className="attached-file-icon-box">{renderAttachmentIcon(att)}</div>
                )}

                <div className="attached-file-info">
                  <div className="file-name-row">
                    {photoNum && <span className="photo-seq-tag">[사진 {photoNum}]</span>}
                    <span className="file-name" title={att.originalName}>
                      {att.originalName}
                    </span>
                  </div>

                  <div className="file-meta-row">
                    <span className="file-size-badge normal">{formatFileSize(att.fileSize)}</span>
                    {photoNum && onInsertPhotoTag && (
                      <button
                        type="button"
                        className="photo-insert-btn"
                        onClick={(e) => {
                          e.stopPropagation();
                          onInsertPhotoTag(photoNum);
                        }}
                        title={`[사진 ${photoNum}] 삽입`}
                      >
                        [사진 {photoNum}] 삽입
                      </button>
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
                  title="삭제"
                  aria-label={`${att.originalName} 첨부 삭제`}
                >
                  <Trash2 size={15} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
