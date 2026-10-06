import React, { useState } from 'react';
import './App.css';

function App() {
  // 1. 상태(State) 선언
  // count: 현재 숫자를 저장하는 변수 (기본값 0)
  // setCount: count 값을 변경할 때 사용하는 전용 함수
  const [count, setCount] = useState(0);

  // 2. 숫자를 1 올리는 함수
  const handleIncrease = () => {
    setCount(count + 1);
  };

  // 3. 숫자를 1 내리는 함수
  const handleDecrease = () => {
    setCount(count - 1);
  };

  return (
    <div className="container">
      <div className="counter-card">
        <h2 className="title">간단 카운터</h2>
        
        {/* 중앙에 크게 표시되는 숫자 */}
        <div className="count-display">{count}</div>

        {/* 조작 버튼 그룹 */}
        <div className="button-group">
          <button className="btn decrease-btn" onClick={handleDecrease}>
            -
          </button>
          <button className="btn increase-btn" onClick={handleIncrease}>
            +
          </button>
        </div>
      </div>
    </div>
  );
}

export default App;