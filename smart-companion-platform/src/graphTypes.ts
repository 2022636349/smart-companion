export interface PageData<T> { items: T[]; page: number; size: number; total: number; totalPages: number }
export type GraphIngestionJobStatus = 'CREATED' | 'EXTRACTING' | 'REVIEWING' | 'VALIDATING' | 'WAITING_HUMAN_REVIEW' | 'READY_TO_PUBLISH' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED' | 'CANCELLED' | 'PARTIALLY_COMPLETED'
export type GraphCandidateType = 'NODE' | 'EDGE' | 'SOURCE'
export type GraphCandidateDecisionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type GraphReviewLane = 'FAST_TRACK' | 'STANDARD' | 'REJECT_RECOMMENDED'
export type GraphCopyrightStatus = 'CONFIRMED' | 'AUTHORIZED' | 'PUBLIC_DOMAIN' | 'UNKNOWN'
export interface GraphIngestionCounts { nodeCount: number; edgeCount: number; sourceCount: number; pendingCount: number; approvedCount: number; rejectedCount: number; errorCount: number; warningCount: number }
export interface GraphIngestionJob {
  id: string
  courseId: string
  batchId: string | null
  sourceFileId: string
  title: string
  sourceType: 'TEXTBOOK' | 'TEACHING_RESOURCE'
  copyrightStatus: GraphCopyrightStatus
  status: GraphIngestionJobStatus
  baseGraphVersion: string | null
  draftRevision: number | null
  attempt: number
  maxAttempts: number
  counts: GraphIngestionCounts
  failureCode: string | null
  failureMessage: string | null
  publishedGraphVersion: string | null
  previousGraphVersion: string | null
  releaseSha256: string | null
  createdBy: string
  version: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  updatedAt: string
}
export interface GraphIngestionJobCreateRequest { fileId: string; title: string; sourceType: GraphIngestionJob['sourceType']; copyrightStatus: GraphCopyrightStatus; baseGraphVersion?: string | null }
export interface GraphValidationIssue { code: string; severity: 'INFO' | 'WARNING' | 'ERROR'; message: string }
export interface GraphCandidateNode {
  candidateId: string
  id: string
  name: string
  level: number
  nodeType: 'course' | 'module' | 'chapter' | 'section' | 'knowledge_point'
  parentId: string | null
  description: string | null
  aliases: string[]
  keywords: string[]
  difficulty: 'BEGINNER' | 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | null
  importance: number | null
  estimatedMinutes: number | null
  learningObjectives: string[]
  commonMisconceptions: string[]
  exampleCode: string[]
  rightsStatus: 'PENDING' | 'ALLOWED' | 'RESTRICTED'
  evidence: string[]
  extractorConfidence: number
  reviewerConfidence: number
  reviewStatus: 'PENDING'
  decisionStatus: GraphCandidateDecisionStatus
  reviewLane: GraphReviewLane
  decisionComment: string | null
  decidedBy: string | null
  decidedAt: string | null
}
export interface GraphCandidateEdge {
  candidateId: string
  sourceId: string
  targetId: string
  relation: 'REQUIRES' | 'RELATED' | 'SUPPORTED_BY'
  evidence: string[]
  extractorConfidence: number
  reviewerConfidence: number
  reviewStatus: 'PENDING'
  decisionStatus: GraphCandidateDecisionStatus
  reviewLane: GraphReviewLane
  decisionComment: string | null
  decidedBy: string | null
  decidedAt: string | null
}
export interface GraphCandidateSource {
  candidateId: string
  id: string
  nodeId: string
  sourceType: 'TEXTBOOK' | 'TEACHING_RESOURCE'
  title: string
  resourceId: string | null
  fileId: string | null
  chapter: string | null
  pageFrom: number | null
  pageTo: number | null
  locator: string
  excerpt: string | null
  /** 由可信版权输入恢复；受限来源须经管理员显式授权后才可发布。 */
  rightsStatus: 'ALLOWED' | 'RESTRICTED'
  extractorConfidence: number
  reviewerConfidence: number
  reviewStatus: 'PENDING'
  decisionStatus: GraphCandidateDecisionStatus
  reviewLane: GraphReviewLane
  decisionComment: string | null
  decidedBy: string | null
  decidedAt: string | null
}
export interface GraphCandidate {
  candidateId: string
  candidateType: GraphCandidateType
  node: GraphCandidateNode | null
  edge: GraphCandidateEdge | null
  source: GraphCandidateSource | null
  reviewLane: GraphReviewLane
  decisionStatus: GraphCandidateDecisionStatus
  validationIssues: GraphValidationIssue[]
  humanReviewComment: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  revision: number
}
export interface GraphCandidatePage extends PageData<GraphCandidate> { draftRevision: number }
export interface GraphCandidateDecision { candidateType: GraphCandidateType; candidateId: string; decision: 'APPROVE' | 'REJECT'; comment: string; editedNode?: GraphCandidateNode; editedEdge?: GraphCandidateEdge; editedSource?: GraphCandidateSource }
