// src/services/mockData.js
// 광운대학교 학업 Q&A 센터 포털 사용자, 교과목, 질문 및 알림 데이터 규격

export const PRESET_USERS = [
  {
    id: 'user-stu-001',
    role: 'student',
    name: '서영민',
    displayTag: '26_소프트_서영민',
    studentId: '20261101',
    department: '소프트웨어학과',
    avatar: '서',
    courses: [
      { id: 'course-001', name: '공학설계입문', group: 'ENG_DESIGN', section: '01분반', profId: 'user-prof-001', profName: '김교수' },
      { id: 'course-002', name: '자료구조', group: 'DATA_STRUCT', section: '01분반', profId: 'user-prof-003', profName: '박교수' }
    ]
  },
  {
    id: 'user-stu-002',
    role: 'student',
    name: '김민지',
    displayTag: '25_소프트_김민지',
    studentId: '20251102',
    department: '소프트웨어학과',
    avatar: '김',
    courses: [
      { id: 'course-001', name: '공학설계입문', group: 'ENG_DESIGN', section: '02분반', profId: 'user-prof-002', profName: '이교수' },
      { id: 'course-002', name: '자료구조', group: 'DATA_STRUCT', section: '01분반', profId: 'user-prof-003', profName: '박교수' }
    ]
  },
  {
    id: 'user-stu-003',
    role: 'student',
    name: '박준형',
    displayTag: '24_컴공_박준형',
    studentId: '20241103',
    department: '컴퓨터공학과',
    avatar: '박',
    courses: [
      { id: 'course-002', name: '자료구조', group: 'DATA_STRUCT', section: '01분반', profId: 'user-prof-003', profName: '박교수' },
      { id: 'course-001', name: '공학설계입문', group: 'ENG_DESIGN', section: '01분반', profId: 'user-prof-001', profName: '김교수' }
    ]
  },
  {
    id: 'user-prof-001',
    role: 'professor',
    name: '김교수 (김진명)',
    displayTag: '소프트웨어학과 김교수',
    title: '교수',
    department: '소프트웨어학과',
    avatar: '김',
    courses: [
      { id: 'course-001', name: '공학설계입문', group: 'ENG_DESIGN', section: '01분반' }
    ],
    settings: {
      dndEnabled: true,
      dndStartTime: '22:00',
      dndEndTime: '08:00',
      coProfNotification: true,
      activeCategories: {
        grade: true,
        assignment: true,
        lecture: true
      }
    }
  },
  {
    id: 'user-prof-002',
    role: 'professor',
    name: '이교수 (이동건)',
    displayTag: '컴퓨터공학과 이교수',
    title: '교수',
    department: '컴퓨터공학과',
    avatar: '이',
    courses: [
      { id: 'course-001', name: '공학설계입문', group: 'ENG_DESIGN', section: '02분반' }
    ],
    settings: {
      dndEnabled: false,
      dndStartTime: '23:00',
      dndEndTime: '07:00',
      coProfNotification: true,
      activeCategories: {
        grade: true,
        assignment: true,
        lecture: true
      }
    }
  },
  {
    id: 'user-prof-003',
    role: 'professor',
    name: '박교수 (박진우)',
    displayTag: '컴퓨터공학과 박교수',
    title: '교수',
    department: '컴퓨터공학과',
    avatar: '박',
    courses: [
      { id: 'course-002', name: '자료구조', group: 'DATA_STRUCT', section: '01분반' }
    ],
    settings: {
      dndEnabled: true,
      dndStartTime: '21:00',
      dndEndTime: '09:00',
      coProfNotification: false,
      activeCategories: {
        grade: true,
        assignment: true,
        lecture: true
      }
    }
  }
];

export const CATEGORY_DEFINITIONS = {
  grade: {
    id: 'grade',
    name: '성적',
    description: '채점 기준이나 이의제기 등',
    color: '#EF4444',
    bgColor: '#FEF2F2',
    badgeClass: 'badge-grade',
    allowAnonymous: false,
    allowSimilarQuestions: false,
    allowCoProfessorAnswer: false,
    rules: [
      '공정한 학사 관리 및 이의 확인을 위해 익명 질문이 불가능합니다.',
      '개인 평가 정보 보호를 위해 유사 질답 보기 기능이 제공되지 않습니다.',
      '평가 권한 보호를 위해 담당 교수님만 답변할 수 있습니다.'
    ]
  },
  assignment: {
    id: 'assignment',
    name: '과제',
    description: '과제 채점 기준이나 제출 형식 등',
    color: '#F59E0B',
    bgColor: '#FFFBEB',
    badgeClass: 'badge-assignment',
    allowAnonymous: true,
    allowSimilarQuestions: true,
    allowCoProfessorAnswer: false,
    rules: [
      '익명으로 질문이 가능합니다 (수강생으로 표기).',
      '유사 질문 검색을 통해 기존 과제 Q&A를 확인할 수 있습니다.',
      '분반별 과제 기준 상이를 방지하기 위해 담당 교수님만 답변할 수 있습니다.'
    ]
  },
  lecture: {
    id: 'lecture',
    name: '강의',
    description: '강의 내용 및 이론/실습에 대한 질문',
    color: '#3B82F6',
    bgColor: '#EFF6FF',
    badgeClass: 'badge-lecture',
    allowAnonymous: true,
    allowSimilarQuestions: true,
    allowCoProfessorAnswer: true,
    rules: [
      '익명으로 질문이 가능합니다 (수강생으로 표기).',
      '유사 질답 보기를 통해 동일 개념에 대한 질답을 바로 볼 수 있습니다.',
      '빠른 피드백을 위해 같은 과목을 강의하시는 다른 교수님도 답변할 수 있습니다.'
    ]
  }
};

// 대소문자 무관 참조 지원
CATEGORY_DEFINITIONS.GRADE = CATEGORY_DEFINITIONS.grade;
CATEGORY_DEFINITIONS.ASSIGNMENT = CATEGORY_DEFINITIONS.assignment;
CATEGORY_DEFINITIONS.LECTURE = CATEGORY_DEFINITIONS.lecture;

export const CATEGORY_LIST = [
  CATEGORY_DEFINITIONS.grade,
  CATEGORY_DEFINITIONS.assignment,
  CATEGORY_DEFINITIONS.lecture
];

export const INITIAL_QUESTIONS = [
  {
    id: 'q-001',
    courseId: 'course-001',
    courseName: '공학설계입문',
    courseGroup: 'ENG_DESIGN',
    profId: 'user-prof-001',
    profName: '김교수',
    assignedProfessorId: 'user-prof-001',
    assignedProfessorName: '김교수',
    category: 'lecture',
    title: '아두이노 PWM 제어 시 3번 핀 주파수 설정 관련 질문입니다.',
    content: '교수님, 3주차 실습에서 서보모터 제어 시 3번 핀의 PWM 펄스 폭 제어가 일부 흔들리는 현상이 발생합니다. 기본 490Hz 주파수 타이머와 간섭이 있는 것인지, 별도 레지스터 설정이 필요한지 문의드립니다.',
    originalContent: '아두이노 3번핀 모터 돌릴 때 자꾸 떨리는데 왜이러나요? 타이머 문제인가요',
    studentId: 'user-stu-001',
    studentTag: '26_소프트_서영민',
    studentName: '서영민',
    isAnonymous: false,
    createdAt: '2026-10-04T10:15:00',
    answers: [
      {
        id: 'ans-001',
        authorId: 'user-prof-001',
        authorName: '김교수 (김진명)',
        authorTitle: '담당교수',
        isPrimaryProf: true,
        content: '좋은 질문입니다. 아두이노 Uno의 경우 3번 핀은 Timer 2(기본 약 490Hz)에 연결되어 있습니다. 만약 Servo 라이브러리를 함께 사용하는 경우 Timer 1을 선점하므로 다른 PWM 핀들과 간섭이 일어날 수 있습니다. 9번/10번 핀 대신 하드웨어 타이머 분주비를 확인해보거나 Timer 2 설정을 직접 조정해보세요. 다음 수업 실습 시간에 직접 회로를 함께 점검해봅시다.',
        createdAt: '2026-10-04T14:30:00'
      }
    ]
  },
  {
    id: 'q-002',
    courseId: 'course-001',
    courseName: '공학설계입문',
    courseGroup: 'ENG_DESIGN',
    profId: 'user-prof-001',
    profName: '김교수',
    assignedProfessorId: 'user-prof-001',
    assignedProfessorName: '김교수',
    category: 'lecture',
    title: '브레드보드 전원 공급 시 5V 레귤레이터 발열 및 전압 강하 문의',
    content: '공학설계입문 프로젝트 회로 구성 중 9V 배터리에서 레귤레이터를 통해 5V를 강하시킬 때 발열이 심하고 4.3V까지 전압이 떨어집니다. 방열판 장착이나 DC-DC 벅 컨버터로 교체하는 것이 권장되는지 조언 부탁드립니다.',
    originalContent: '회로 켜면 레귤레이터 뜨거워지고 전압 뚝 떨어지는데 컨버터 써야하나요?',
    studentId: 'user-stu-001',
    studentTag: '26_소프트_서영민',
    studentName: '서영민',
    isAnonymous: true,
    createdAt: '2026-10-05T09:20:00',
    answers: [] // 미답변 -> 같은 과목(공학설계입문) 다른 분반의 이교수(user-prof-002)도 답변 가능
  },
  {
    id: 'q-003',
    courseId: 'course-001',
    courseName: '공학설계입문',
    courseGroup: 'ENG_DESIGN',
    profId: 'user-prof-001',
    profName: '김교수',
    assignedProfessorId: 'user-prof-001',
    assignedProfessorName: '김교수',
    category: 'assignment',
    title: '중간 설계 제안서 제출 양식 및 분량 제한 질문',
    content: '공학설계입문 1차 설계 제안서 제출 시 PDF 형식 변환 기준과 부록(도면 및 부품 리스트) 포함 페이지 제한이 있는지 여쭙고자 합니다.',
    originalContent: '과제 제출할 때 PDF로 내면 되나요? 부록 페이지도 분량 제한에 들어가나요',
    studentId: 'user-stu-001',
    studentTag: '26_소프트_서영민',
    studentName: '서영민',
    isAnonymous: true,
    createdAt: '2026-10-05T11:45:00',
    answers: [] // 과제 카테고리이므로 배정된 담당교수(김교수)만 답변 가능
  },
  {
    id: 'q-004',
    courseId: 'course-001',
    courseName: '공학설계입문',
    courseGroup: 'ENG_DESIGN',
    profId: 'user-prof-001',
    profName: '김교수',
    assignedProfessorId: 'user-prof-001',
    assignedProfessorName: '김교수',
    category: 'grade',
    title: '중간고사 3번 실습 설계 배점 기준에 대해 조심스럽게 여쭙습니다.',
    content: '안녕하십니까 김교수님. 공학설계입문 수강생 서영민입니다. 중간고사 실습 3번 문항에서 상태 전이도 설계 점수 채점 기준과 관련하여 피드백을 부탁드리고자 합니다. 혹시 연구실 면담이나 확인이 가능한 시간대가 있으신지 여쭙습니다.',
    originalContent: '중간고사 채점 기준표 관련하여 면담 요청드립니다.',
    studentId: 'user-stu-001',
    studentTag: '26_소프트_서영민',
    studentName: '서영민',
    isAnonymous: false, // 성적 카테고리는 무조건 실명
    createdAt: '2026-10-05T16:00:00',
    answers: [
      {
        id: 'ans-002',
        authorId: 'user-prof-001',
        authorName: '김교수 (김진명)',
        authorTitle: '담당교수',
        isPrimaryProf: true,
        content: '서영민 학생, 3번 문항 채점 기준표를 확인해보면 예외 조건에 대한 페일세이프(Fail-Safe) 상태 루프가 누락되어 부분점수 5점이 감점되었습니다. 목요일 오후 2시 연구실(새빛관 402호)로 방문하면 답안지와 상세 루브릭을 확인시켜 줄 수 있습니다.',
        createdAt: '2026-10-05T18:10:00'
      }
    ]
  },
  {
    id: 'q-005',
    courseId: 'course-002',
    courseName: '자료구조',
    courseGroup: 'DATA_STRUCT',
    profId: 'user-prof-003',
    profName: '박교수',
    assignedProfessorId: 'user-prof-003',
    assignedProfessorName: '박교수',
    category: 'lecture',
    title: 'AVL 트리 회전 연산 시 균형 인수(Balance Factor) 갱신 시점',
    content: '박교수님 안녕하십니까. AVL 트리에서 Double Rotation(LR, RL 회전) 수행 시 자식 노드들의 균형 인수가 재계산되는 과정에 대해 추가 질문이 있습니다.',
    originalContent: 'AVL 트리 더블 로테이션 돌릴 때 밸런스 팩터 언제 바뀌나요 헷갈려요',
    studentId: 'user-stu-003',
    studentTag: '24_컴공_박준형',
    studentName: '박준형',
    isAnonymous: false,
    createdAt: '2026-10-06T09:00:00',
    answers: []
  }
];

export const INITIAL_NOTIFICATIONS = [
  {
    id: 'notif-001',
    userId: 'user-stu-001',
    type: 'new_answer',
    title: '새 답변이 등록되었습니다',
    message: '김교수님이 질문 [아두이노 PWM 제어 시 3번 핀 주파수 설정...]에 답변을 등록하셨습니다.',
    questionId: 'q-001',
    isRead: false,
    createdAt: '2026-10-04T14:30:00'
  },
  {
    id: 'notif-002',
    userId: 'user-prof-001',
    type: 'new_question',
    title: '새 질문이 접수되었습니다',
    message: '[공학설계입문] 서영민 학생이 새로운 강의 질문을 남겼습니다.',
    questionId: 'q-002',
    isRead: false,
    createdAt: '2026-10-05T09:20:00'
  },
  {
    id: 'notif-003',
    userId: 'user-prof-002',
    type: 'co_prof_question',
    title: '같은 과목 질문 알림 (공동 답변 가능)',
    message: '[공학설계입문] 강의 카테고리에 미답변 질문이 등록되었습니다 (이교수님 답변 가능).',
    questionId: 'q-002',
    isRead: false,
    createdAt: '2026-10-05T09:21:00'
  }
];

export const AI_INQUIRY_TEMPLATES = [
  {
    title: '성적 이의 및 확인 문의',
    category: 'grade',
    rawTitle: '성적이 이게 뭐냐',
    refinedTitle: '[성적 문의] 2026학년도 2학기 최종 평가 결과 피드백 및 재수강 기준 문의의 건',
    rawText: '아니 교수야 성적이 이게 말이 되냐 차라리 재수강하게 C로 내려줘라',
    refinedText: '안녕하십니까, 교수님. 이번 학기 강의를 성실히 수강한 소프트웨어학과 20261101 서영민입니다. 한 학기 동안 열정적인 강의와 가르침을 주셔서 진심으로 감사드립니다.\n\n다름이 아니라 이번 학기 최종 성적을 확인하였으나, 제가 예상했던 결과보다 다소 아쉬운 성적을 받게 되어 조심스럽게 문의를 드립니다. 혹시 제가 어떤 평가 항목에서 부족했는지 피드백을 여쭈어보아도 괜찮을지 여쭙고자 합니다.\n\n아울러 현재 부여된 성적으로는 본교 규정상 추후 재수강을 통한 보완이 어려운 상황입니다. 만약 성적 상향이 어렵다면, 부족한 부분을 다시 성실히 공부하여 채울 수 있도록 재수강 요건에 맞추어 성적을 C0으로 조정해 주시는 것이 학칙상 가능한지 결례를 무릅쓰고 조심스레 여쭙습니다.\n\n학기 말 바쁘신 일정 중에 번거로운 문의를 드려 송구합니다. 날씨가 쌀쌀한데 건강 유의하시기 바랍니다. 감사합니다.'
  },
  {
    title: '과제 마감 기한 연장 문의',
    category: 'assignment',
    rawTitle: '과제 마감 미뤄주세요',
    refinedTitle: '[과제 문의] 과제 2번 프로젝트 제출 기한 유예 가능 여부 문의의 건',
    rawText: '이번 과제 너무 어려운데 마감 내일모레까지 하루만 더 미뤄주시면 안되나요? 다 못했습니다',
    refinedText: '안녕하십니까 교수님. 공학설계입문을 수강하고 있는 학생입니다. 평소 유익하고 깊이 있는 강의를 준비해 주셔서 감사한 마음으로 학습하고 있습니다.\n\n현재 진행 중인 과제 2번 프로젝트를 수행하는 과정에서 핵심 알고리즘 구현 및 회로 디버깅에 예상보다 많은 기술적 난관이 발생하여 부득이하게 문의를 드리게 되었습니다.\n\n완성도 높은 결과물과 충분한 분석 보고서를 제출하기 위해 혹시 제출 기한을 하루 정도 유예해 주시는 것이 강의 운영 일정상 가능한지 조심스럽게 여쭙고자 합니다. 학업 일정에 차질을 드려 송구스러우며, 교수님의 소중한 지도에 감사드립니다.'
  },
  {
    title: '강의 내용 개념 이해 질문',
    category: 'lecture',
    rawTitle: 'PWM 질문이요',
    refinedTitle: '[강의 문의] 4장 마이크로컨트롤러 PWM 듀티비 개념 관련 질의의 건',
    rawText: '수업 때 들은 PWM 듀티비 개념 이해가 하나도 안 가는데 다시 쉽게 설명해주세요',
    refinedText: '안녕하십니까 교수님. 수업 시간에 다루어 주신 마이크로컨트롤러 PWM 제어 기법에 대해 복습하던 중, 듀티비(Duty Cycle)에 따른 평균 전압 계산과 실제 모터 출력과의 상관관계 부분에서 개념 정리가 다소 부족하여 질문을 올립니다.\n\n강의 자료 4장을 참고하여 실습 코드를 분석해보았으나 명확한 이해가 어려워, 혹시 해당 개념을 이해하는 데 도움이 될 만한 핵심 직관이나 참고 자료를 추천해 주실 수 있으신지 여쭙고 싶습니다. 바쁘신 와중에 시간 내어 주셔서 감사드립니다.'
  }
];

export const AI_DEMO_TEMPLATES = AI_INQUIRY_TEMPLATES;
