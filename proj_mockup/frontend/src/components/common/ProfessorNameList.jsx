// src/components/common/ProfessorNameList.jsx
import React from 'react';
import {
  getCourseProfessorItems,
  getPrimaryProfessorItem
} from '../../utils/professorDisplay';

export default function ProfessorNameList({ course, mode = 'all' }) {
  const items =
    mode === 'primary'
      ? [getPrimaryProfessorItem(course)]
      : getCourseProfessorItems(course);

  return (
    <>
      {items.map((item, idx) => (
        <React.Fragment key={`${item.id}-${idx}`}>
          {idx > 0 ? ', ' : ''}
          <span
            className={
              item.isRegistered ? 'prof-name-registered' : 'prof-name-unregistered'
            }
            title={item.isRegistered || item.label === '미배정' ? undefined : '미가입 교수 (시간표 정보)'}
          >
            {item.label}
          </span>
        </React.Fragment>
      ))}
    </>
  );
}
