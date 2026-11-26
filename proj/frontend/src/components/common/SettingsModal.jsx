// src/components/common/SettingsModal.jsx
import React, { useEffect, useState } from 'react';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { useNotifications } from '../../context/NotificationContext';
import CoursePicker, { buildCourseSelectionsPayload } from './CoursePicker';
import AdminNicknameForm from '../admin/AdminNicknameForm';
import HelpBubble from './HelpBubble';
import { Sun, Moon, Leaf, Type, X, Check, BookOpen, Save, Bell, ShieldAlert, Sparkles } from 'lucide-react';

const FONT_SIZE_OPTIONS = [
  { id: 'sm', label: '작게', sample: '가나다' },
  { id: 'md', label: '보통', sample: '가나다' },
  { id: 'lg', label: '크게', sample: '가나다' },
  { id: 'xl', label: '아주 크게', sample: '가나다' }
];

const HELP_LEVEL_OPTIONS = [
  { id: 'NONE', label: '필요 없음', desc: '표시 안 함' },
  { id: 'SOME', label: '조금', desc: '핵심 안내만 표시' },
  { id: 'ALL', label: '모두', desc: '모든 기능·모달 안내 전체' }
];

const SettingsModal = ({ isOpen, onClose, onCoursesUpdated }) => {
  const {
    theme,
    isBlueWhiteUnlocked,
    fontSize,
    helpLevel,
    updateTheme,
    updateFontSize,
    updateHelpLevel
  } = useTheme();
  const { currentUser, isStudent, isProfessor, isAdmin, allCourses, updateUserCourses } = useAuth();
  const { browserPermission, requestPermission, sendTestNotification } = useNotifications();

  const [selectedCourseIds, setSelectedCourseIds] = useState([]);
  const [sectionSelections, setSectionSelections] = useState({});
  const [courseSaving, setCourseSaving] = useState(false);
  const [courseStatus, setCourseStatus] = useState({ type: '', text: '' });
  const [notifTestStatus, setNotifTestStatus] = useState({ type: '', text: '' });

  useEffect(() => {
    if (!isOpen) return;
    const userCourses = Array.isArray(currentUser?.courses) ? currentUser.courses : [];
    const initialIds = userCourses.map((c) => c.id).filter(Boolean);
    const initSecMap = {};
    userCourses.forEach((c) => {
      if (!c?.id) return;
      if (isProfessor) {
        if (Array.isArray(c.myCourseProfessorIds) && c.myCourseProfessorIds.length > 0) {
          initSecMap[c.id] = c.myCourseProfessorIds;
        } else if (c.assignedCourseProfessorId) {
          initSecMap[c.id] = [c.assignedCourseProfessorId];
        }
      } else if (c.assignedCourseProfessorId) {
        initSecMap[c.id] = c.assignedCourseProfessorId;
      }
    });
    setSelectedCourseIds(initialIds);
    setSectionSelections(initSecMap);
  }, [isOpen, currentUser?.courses, isProfessor]);

  useEffect(() => {
    if (isOpen) {
      setCourseStatus({ type: '', text: '' });
      setNotifTestStatus({ type: '', text: '' });
    }
  }, [isOpen]);

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
      const selectionsPayload = buildCourseSelectionsPayload(
        selectedCourseIds,
        sectionSelections,
        allCourses,
        isProfessor
      );
      await updateUserCourses(selectedCourseIds, selectionsPayload);
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
            <span>화면 · 알림 설정</span>
          </div>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="닫기">
            <X size={18} />
          </button>
        </div>

        <div className="modal-body">
          <HelpBubble
            tier="ALL"
            mode="inline"
            text="테마·글자 크기·도움말 표시 수준은 바꾸는 즉시 화면에 반영되고 계정에 저장돼요."
            className="mb-help-bubble"
          />

          <div className="settings-section">
            <h4 className="settings-section-title">테마</h4>

            <div className="theme-selector-grid theme-selector-grid-4">
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
                  <span className="theme-option-sub">무채색</span>
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
                  <span className="theme-option-sub">초록·화이트</span>
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
                  <span className="theme-option-sub">무채색</span>
                </div>
                {theme === 'dark' && <Check size={16} className="text-primary" />}
              </button>

              <button
                type="button"
                className={`theme-option-card ${theme === 'green-dark' ? 'active' : ''}`}
                onClick={() => updateTheme('green-dark')}
              >
                <div className="theme-preview green-dark-preview">
                  <Leaf size={18} />
                </div>
                <div className="theme-option-info">
                  <span className="theme-option-label">다크 그린</span>
                  <span className="theme-option-sub">초록·검정</span>
                </div>
                {theme === 'green-dark' && <Check size={16} className="text-primary" />}
              </button>

              {isBlueWhiteUnlocked && (
                <button
                  type="button"
                  className={`theme-option-card ${theme === 'blue-white' ? 'active' : ''}`}
                  onClick={() => updateTheme('blue-white')}
                >
                  <div className="theme-preview blue-white-preview">
                    <Sparkles size={18} />
                  </div>
                  <div className="theme-option-info">
                    <span className="theme-option-label">♬ BlueWhite</span>
                    <span className="theme-option-sub">by HAYAKO</span>
                  </div>
                  {theme === 'blue-white' && <Check size={16} className="text-primary" />}
                </button>
              )}
            </div>
          </div>

          <div className="settings-divider" />

          <div className="settings-section">
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

          <div className="settings-divider" />

          <div className="settings-section">
            <h4 className="settings-section-title">도움말</h4>

            <div className="help-level-option-list">
              {HELP_LEVEL_OPTIONS.map((option) => (
                <button
                  type="button"
                  key={option.id}
                  className={`fontsize-option-item ${helpLevel === option.id ? 'active' : ''}`}
                  onClick={() => updateHelpLevel(option.id)}
                >
                  <div>
                    <div className="fontsize-label">{option.label}</div>
                    <div className="help-level-option-desc">{option.desc}</div>
                  </div>
                  {helpLevel === option.id && <Check size={16} className="text-primary" />}
                </button>
              ))}
            </div>
          </div>

          <div className="settings-divider" />

          <div className="settings-section" style={canManageCourses ? undefined : { marginBottom: 0 }}>
            <HelpBubble
              tier="ALL"
              mode="inline"
              text="브라우저 알림을 허용하면 창을 보지 않아도 새 질문·답변을 바로 받을 수 있어요. (HTTPS 필요)"
              className="mb-help-bubble"
            />
            <div className="settings-title-group" style={{ marginBottom: 8 }}>
              <Bell size={17} className="text-primary" />
              <div>
                <h4 className="settings-section-title">브라우저 알림</h4>
                <p className="settings-section-desc">
                  새 질문·추가 질문·답변 도착 시 운영체제(OS) 알림 팝업을 표시합니다.
                </p>
              </div>
            </div>

            <div className="browser-notif-setting-box">
              <div className="notif-status-info">
                <span className="status-label">권한 상태:</span>
                {browserPermission === 'granted' ? (
                  <span className="badge-notif-granted">허용됨</span>
                ) : browserPermission === 'denied' ? (
                  <span className="badge-notif-denied">차단됨</span>
                ) : browserPermission === 'insecure' ? (
                  <span className="badge-notif-denied">HTTPS 필요 (현재 HTTP)</span>
                ) : browserPermission === 'unsupported' ? (
                  <span className="badge-notif-denied">브라우저 미지원</span>
                ) : (
                  <span className="badge-notif-default">미설정</span>
                )}
              </div>

              {browserPermission === 'default' && (
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={async () => {
                    const perm = await requestPermission();
                    if (perm === 'granted') {
                      const sent = await sendTestNotification();
                      setNotifTestStatus(
                        sent
                          ? { type: 'success', text: '알림 권한이 허용되었고 테스트 알림을 전송했습니다.' }
                          : { type: 'error', text: '알림 권한은 허용되었지만 브라우저 알림 생성에 실패했습니다.' }
                      );
                    } else if (perm === 'denied') {
                      setNotifTestStatus({
                        type: 'error',
                        text: '권한 요청이 차단되었습니다. 브라우저 주소창 권한 설정을 확인해 주세요.'
                      });
                    }
                  }}
                >
                  권한 허용
                </button>
              )}

              {browserPermission === 'granted' && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={async () => {
                    const sent = await sendTestNotification();
                    setNotifTestStatus(
                      sent
                        ? {
                            type: 'success',
                            text: '테스트 브라우저 알림을 전송했습니다. (OS 방해금지 모드가 켜져 있으면 숨겨질 수 있습니다.)'
                          }
                        : { type: 'error', text: '브라우저 알림 생성에 실패했습니다.' }
                    );
                  }}
                >
                  테스트 알림 보내기
                </button>
              )}
            </div>

            {browserPermission === 'denied' && (
              <p className="settings-section-desc" style={{ marginTop: 6 }}>
                브라우저 주소창 왼쪽 자물쇠/사이트 설정 아이콘에서 알림을 &quot;허용&quot;으로 변경한 뒤 다시 시도해 주세요.
              </p>
            )}
            {browserPermission === 'insecure' && (
              <p className="settings-section-desc" style={{ marginTop: 6 }}>
                Chrome 등 브라우저는 보안상 HTTPS(또는 localhost) 주소에서만 Web Notification 알림을 허용합니다.
              </p>
            )}
            {notifTestStatus?.text && (
              <div
                className={`settings-inline-status ${
                  notifTestStatus.type === 'error' ? 'is-error' : 'is-success'
                }`}
                style={{ marginTop: 8 }}
              >
                {notifTestStatus.text}
              </div>
            )}
          </div>

          {isAdmin && (
            <>
              <div className="settings-divider" />
              <div className="settings-section">
                <div className="settings-title-group" style={{ marginBottom: 10 }}>
                  <ShieldAlert size={17} className="text-danger" />
                  <div>
                    <h4 className="settings-section-title">관리자 닉네임 설정</h4>
                    <p className="settings-section-desc">
                      게시글·답변 및 상단 프로필에 표시될 관리자 닉네임을 설정합니다. (최대 30자)
                    </p>
                  </div>
                </div>
                <AdminNicknameForm onSaved={onCoursesUpdated} />
              </div>
            </>
          )}

          {canManageCourses && (
            <>
              <div className="settings-divider" />
              <div className="settings-section">
                <HelpBubble
                  tier="ALL"
                  mode="inline"
                  text="과목명·학수번호·학과(구 명칭 포함)·교수명으로 검색하고, 여러 분반이 있는 과목은 본인 분반을 선택해 저장해요. '이전 학기 포함'으로 지난 학기 강의도 찾을 수 있어요."
                  className="mb-help-bubble"
                />
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
                  sectionMode={isProfessor ? 'multi' : 'single'}
                  sectionSelections={sectionSelections}
                  onSectionSelectionsChange={setSectionSelections}
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
