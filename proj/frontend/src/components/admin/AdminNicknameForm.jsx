// src/components/admin/AdminNicknameForm.jsx
import React, { useEffect, useState } from 'react';
import { Save, RotateCcw } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

export default function AdminNicknameForm({ onSaved, compact = false }) {
  const { currentUser, isAdmin, updateAdminNickname } = useAuth();
  const [nickname, setNickname] = useState(currentUser?.nickname || '');
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState({ type: '', text: '' });

  useEffect(() => {
    setNickname(currentUser?.nickname || '');
  }, [currentUser?.nickname]);

  if (!isAdmin) return null;

  const submitNickname = async (nextValue) => {
    setSaving(true);
    setStatus({ type: '', text: '' });
    try {
      const updated = await updateAdminNickname(nextValue);
      const savedNick = updated?.nickname || '';
      setNickname(savedNick);
      const msg = savedNick
        ? `관리자 닉네임이 '${savedNick}'(으)로 저장되었습니다.`
        : '관리자 닉네임이 기본값으로 초기화되었습니다.';
      setStatus({ type: 'success', text: msg });
      if (onSaved) await onSaved(msg);
    } catch (err) {
      setStatus({
        type: 'error',
        text: err.message || '관리자 닉네임 저장 중 오류가 발생했습니다.'
      });
    } finally {
      setSaving(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    await submitNickname(nickname);
  };

  return (
    <form onSubmit={handleSubmit} className={compact ? 'admin-compact-form' : 'admin-nickname-form'}>
      <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
        <input
          type="text"
          className="form-input"
          style={{ flex: '1 1 220px' }}
          placeholder={`표시될 관리자 닉네임 (예: 학사운영팀, 미입력 시 ${currentUser?.displayTag || currentUser?.name || '기본값'})`}
          value={nickname}
          maxLength={30}
          onChange={(e) => setNickname(e.target.value)}
        />
        <button type="submit" className="btn btn-primary btn-sm" disabled={saving}>
          <Save size={15} />
          {saving ? '저장 중...' : '닉네임 저장'}
        </button>
        {currentUser?.nickname && (
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            disabled={saving}
            onClick={() => submitNickname('')}
            title="기본 이름/표시명으로 되돌리기"
          >
            <RotateCcw size={14} />
            초기화
          </button>
        )}
      </div>
      {status.text && (
        <div
          className={`settings-inline-status ${
            status.type === 'error' ? 'is-error' : 'is-success'
          }`}
          style={{ marginTop: 8 }}
        >
          {status.text}
        </div>
      )}
    </form>
  );
}
