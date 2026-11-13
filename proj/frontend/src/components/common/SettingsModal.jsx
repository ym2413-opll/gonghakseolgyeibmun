// src/components/common/SettingsModal.jsx
import React, { useEffect, useState } from 'react';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { api } from '../../services/api';
import CoursePicker from './CoursePicker';
import { Sun, Moon, Leaf, Type, X, Check, BookOpen, Save } from 'lucide-react';

const FONT_SIZE_OPTIONS = [
  { id: 'sm', label: '작게', sample: '가나다' },
  { id: 'md', label: '보통', sample: '가나다' },
  { id: 'lg', label: '크게', sample: '가나다' },
  { id: 'xl', label: '아주 크게', sample: '가나다' }
];

const SettingsModal = ({ isOpen, onClose, onCoursesUpdated }) => {
  const { theme, fontSize, updateTheme, updateFontSize } = useTheme();
  const { currentUser, isStudent, isProfessor, allCourses, refreshCurrentUser } = useAuth();

  const [selectedCourseIds, setSelectedCourseIds] = useState([]);
  const [courseSaving, setCourseSaving] = useState(false);
  const [courseStatus, setCourseStatus] = useState({ type: '', text: '' });

  useEffect(() => {
    if (!isOpen) return;
    const initialIds = Array.isArray(currentUser?.courses)
      ? currentUser.courses.map((c) => c.id).filter(Boolean)
      : [];
    setSelectedCourseIds(initialIds);
    setCourseStatus({ type: '', text: '' });
  }, [isOpen, currentUser?.courses]);

  if (!isOpen) return null;

  const canManageCourses = isStudent || isProfessor;

  const handleSaveCourses = async () => {
    if (selectedCourseIds.length === 0) {
      setCourseStatus({
        type: 'error',
        text: isProfessor
          ? '담당 교과목은 최소 1개 이상 선택해야 합니다.'
          : '수강 과목은 최소 1개 이상 선택해야 합니다.'
      });
      return;
    }
    setCourseSaving(true);
    setCourseStatus({ type: '', text: '' });
    try {
      await api.updateUserCourses(selectedCourseIds);
      await refreshCurrentUser();
      if (onCoursesUpdated) await onCoursesUpdated();
      setCourseStatus({
        type: 'success',
        text: isProfessor ? '담당 교과목이 저장되었습니다.' : '수강 과목이 저장되었습니다.'
      });
    } catch (err) {
      setCourseStatus({
        type: 'error',
        text: err.message || '과목 설정 저장 중 오류가 발생했습니다.'
      });
    } finally {
      setCourseSaving(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-container modal-md" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">
            <Type size={19} className="text-primary" />
            <span>화면 설정</span>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <div className="settings-section">
            <h4 className="settings-section-title">테마</h4>

            <div className="theme-selector-grid theme-selector-grid-3">
              <button
                type="button"
                className={`theme-option-card ${theme === 'light' ? 'active' : ''}`}
                onClick={() => updateTheme('light')}
              >
                <div className="theme-preview light-preview">
                  <Sun size={20} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">라이트</span>
                </div>
                {theme === 'light' && <Check size={16} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'green' ? 'active' : ''}`}
                onClick={() => updateTheme('green')}
              >
                <div className="theme-preview green-preview">
                  <Leaf size={20} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">그린</span>
                </div>
                {theme === 'green' && <Check size={16} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'dark' ? 'active' : ''}`}
                onClick={() => updateTheme('dark')}
              >
                <div className="theme-preview dark-preview">
                  <Moon size={20} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">다크</span>
                </div>
                {theme === 'dark' && <Check size={16} className="text-primary" />}
              </button>
            </div>
          </div>

          <div className="settings-divider" />

          <div className="settings-section" style={{ marginBottom: 0 }}>
            <h4 className="settings-section-title">글자 크기</h4>

            <div className="fontsize-option-list">
              {FONT_SIZE_OPTIONS.map((option) => (
                <button
                  type="button"
                  key={option.id}
                  className={`fontsize-option-item ${fontSize === option.id ? 'active' : ''}`}
                  onClick={() => updateFontSize(option.id)}
                >
                  <div>
                    <div className="fontsize-label">{option.label}</div>
                    <div className={`fontsize-sample sample-${option.id}`}>{option.sample}</div>
                  </div>
                  {fontSize === option.id && <Check size={16} className="text-primary" />}
                </button>
              ))}
            </div>
          </div>

          {canManageCourses && (
            <>
              <div className="settings-divider" />
              <div className="settings-section">
                <div className="settings-title-group" style={{ marginBottom: 10 }}>
                  <BookOpen size={17} className="text-primary" />
                  <div>
                    <h4 className="settings-section-title">
                      {isProfessor ? '담당 교과목 설정' : '수강 과목 설정'}
                    </h4>
                    <p className="settings-section-desc">
                      {isProfessor
                        ? '질문을 받고 관리할 담당 교과목을 선택합니다. (최소 1개)'
                        : '포럼에서 조회하고 질문할 수강 과목을 선택합니다. (최소 1개)'}
                    </p>
                  </div>
                </div>

                <CoursePicker
                  courses={allCourses}
                  selectedIds={selectedCourseIds}
                  onChange={setSelectedCourseIds}
                  label={isProfessor ? '담당 교과목' : '수강 과목'}
                  helperText="최소 1개 이상"
                />

                {courseStatus.text && (
                  <div
                    className={`settings-inline-status ${
                      courseStatus.type === 'error' ? 'is-error' : 'is-success'
                    }`}
                  >
                    {courseStatus.text}
                  </div>
                )}

                <div className="course-settings-actions">
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    onClick={handleSaveCourses}
                    disabled={courseSaving}
                  >
                    <Save size={15} />
                    {courseSaving ? '저장 중...' : '과목 설정 저장'}
                  </button>
                </div>
              </div>
            </>
          )}
        </div>

        <div className="modal-footer">
          <button type="button" className="btn btn-primary btn-sm" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
};

export default SettingsModal;
