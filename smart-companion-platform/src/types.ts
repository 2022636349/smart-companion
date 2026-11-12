// Public API 2.10.0 report contract.
export interface Summary {
  id: string;
  courseId: string;
  fileName: string;
  sha256: string;
  sourceLabel: string;
  status: string;
  createdAt: string;
  confirmedAt: string | null;
  analyzedAt: string | null;
  studentCount: number;
  materialCount: number;
  rawRecordCount: number;
  nodeCount: number;
}
export interface Student {
  id: string;
  classId: string;
  grade: string;
}
export interface Node {
  id: string;
  date: string;
  scope: string;
  description: string;
  availableMaterialIds: string[];
}
export interface Quality {
  rawRecordCount: number;
  effectiveRecordCount: number;
  scoredCount: number;
  pendingCount: number;
  unansweredCount: number;
  supersededCount: number;
  duplicateCount: number;
  invalidCount: number;
  conflictCount: number;
  rows: {
    rowNumber: number;
    recordId: string;
    eventId: string;
    revision: number | null;
    disposition: string;
    reasons: string[];
  }[];
}
export interface Material {
  id: string;
  topicId: string | null;
  type: string;
  questionType: string;
  body: string;
  options: string;
  answer: string;
  explanation: string;
  maxScore: number | null;
  scoringPoints: string;
  indicatorIds: string[];
  source: string;
}
export interface RecordEvidence {
  id: string;
  studentId: string;
  materialId: string;
  topicId: string;
  nodeId: string;
  eventId: string;
  revision: number;
  submittedAt: string;
  status: string;
  score: number | null;
  maxScore: number;
  answer: string;
  process: string;
  pointScores: string;
}
export interface Observation {
  studentId: string;
  nodeId: string;
  indicatorId: string;
  availableQuestionCount: number;
  scoredCount: number;
  scorePercent: number | null;
  status: string;
  evidenceIds: string[];
}
export interface Indicator {
  id: string;
  topicId: string;
  topicName: string;
  topicGoal: string;
  label: string;
  standardReference: string;
  standardPages: string;
  textbookPages: string;
}
export interface Report {
  version: string;
  importId: string;
  courseId: string;
  sourceLabel: string;
  generatedAt: string;
  method: string[];
  limitations: string[];
  quality: Quality;
  students: Student[];
  nodes: Node[];
  indicators: Indicator[];
  materials: Material[];
  records: RecordEvidence[];
  studentSummaries: {
    studentId: string;
    nodeId: string;
    scoredCount: number;
    pendingCount: number;
    unansweredCount: number;
    availableQuestionCount: number;
    coveragePercent: number | null;
    earnedScore: number;
    maxScore: number;
    scorePercent: number | null;
    meanRecordPercent: number | null;
  }[];
  groupSummaries: {
    classId: string;
    nodeId: string;
    totalStudentCount: number;
    scoredStudentCount: number;
    scoredCount: number;
    pendingCount: number;
    unansweredCount: number;
    earnedScore: number;
    maxScore: number;
    scorePercent: number | null;
    studentMeanPercent: number | null;
  }[];
  observations: Observation[];
  recommendations: {
    studentId: string;
    nodeId: string;
    indicatorId: string;
    purpose: string;
    reason: string;
    materialIds: string[];
    evidenceIds: string[];
    draft: true;
  }[];
}
export interface ImportDetail {
  summary: Summary;
  preview: {
    canConfirm: boolean;
    quality: Quality;
    issues: {
      severity: string;
      table: string;
      rowNumber: number | null;
      recordId: string | null;
      code: string;
      message: string;
    }[];
    notes: string[];
    students: Student[];
    nodes: Node[];
  };
  report: Report | null;
}
