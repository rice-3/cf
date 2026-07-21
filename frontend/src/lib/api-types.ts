// Backend APIの型・定数（クライアント/サーバー共用、server-only依存を含まない）。
// `next/headers` 等のサーバー専用APIに依存する実処理は lib/backend.ts に置く。

export interface ApiEnvelope<T> {
  data: T;
  meta: { correlationId: string; timestamp: string };
}

export interface ProblemDetails {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; code: string; message: string }[];
  violations?: string[];
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProjectListItem {
  projectId: string;
  title: string;
  summary: string;
  targetAmount: number;
  fundingType: string;
  startAt: string;
  endAt: string;
  status: string;
  updatedAt: string;
}

export interface RewardPlanView {
  rewardPlanId: string;
  name: string;
  description: string;
  unitAmount: number;
  quantityLimit: number | null;
  remainingQuantity: number | null;
  displayOrder: number;
}

export interface ProjectDetailView {
  projectId: string;
  ownerUserId: string;
  title: string;
  summary: string;
  body: string;
  targetAmount: number;
  fundingType: string;
  startAt: string;
  endAt: string;
  status: string;
  mainFileId: string | null;
  rewardPlans: RewardPlanView[];
  version: number;
  updatedAt: string;
}

export interface ProjectCreatedResponse {
  projectId: string;
  status: string;
}

export interface ProjectUpdatedResponse {
  projectId: string;
  status: string;
  version: number;
  updatedAt: string;
}

export interface SubmitReviewResponse {
  reviewId: string;
  projectStatus: string;
  submittedAt: string;
}

export interface ProjectCancelledResponse {
  projectId: string;
  status: string;
}

export interface IssueUploadResponse {
  fileId: string;
  uploadUrl: string;
  headers: Record<string, string>;
  expiresAt: string;
}

export interface CompleteUploadResponse {
  fileId: string;
  status: string;
  downloadReference: string;
}

/** DRAFT/RETURNEDのみ起案者が編集可能（基本設計 §3.2）。 */
export const EDITABLE_PROJECT_STATUSES = new Set(["DRAFT", "RETURNED"]);

/** SCR-023の必須確認事項（詳細設計 §5.2、UseCase側と一致させる）。 */
export const REQUIRED_SUBMIT_CONFIRMATIONS = ["TERMS_ACCEPTED", "CONTENT_RESPONSIBILITY_ACCEPTED"] as const;

// ---- Review（SCR-030/031、API-RV-001〜006） ---------------------------------

export interface ReviewListItem {
  reviewId: string;
  projectId: string;
  projectTitle: string;
  status: string;
  reviewerUserId: string | null;
  submittedAt: string;
}

export interface ReviewHistoryView {
  action: string;
  reasonCode: string | null;
  comment: string | null;
  actedAt: string;
  actedBy: string;
}

export interface ReviewDetailView {
  reviewId: string;
  projectId: string;
  status: string;
  reviewerUserId: string | null;
  submittedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  version: number;
  histories: ReviewHistoryView[];
}

export interface ReviewActionResponse {
  reviewId: string;
  reviewStatus: string;
  projectStatus: string;
  reviewVersion: number;
  actedAt: string;
}

/** 審査承認時の必須チェックリスト（backend ReviewChecklist.REQUIRED_ITEMS と一致させる）。 */
export const REVIEW_CHECKLIST_ITEMS = [
  "CONTENT_CONFIRMED",
  "LEGAL_CONFIRMED",
  "REWARD_CONFIRMED",
  "PERIOD_CONFIRMED",
] as const;

/** 却下理由区分（backend RejectReasonCode と一致させる）。 */
export const REJECT_REASON_CODES = [
  "LEGAL_VIOLATION",
  "INAPPROPRIATE_CONTENT",
  "INSUFFICIENT_INFORMATION",
  "DUPLICATE_PROJECT",
  "OTHER",
] as const;

// ---- Support（SCR-040〜042/051、API-FD-001〜004） ---------------------------

export interface SupportListItem {
  supportId: string;
  projectId: string;
  projectTitle: string;
  amount: number;
  status: string;
  paymentStatus: string | null;
  createdAt: string;
}

export interface SupportItemView {
  rewardPlanId: string | null;
  quantity: number;
  unitAmount: number;
  amount: number;
}

export interface SupportDetailView {
  supportId: string;
  projectId: string;
  projectTitle: string;
  supporterUserId: string;
  amount: number;
  status: string;
  paymentStatus: string | null;
  contactEmail: string;
  items: SupportItemView[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface RequestSupportResponse {
  supportId: string;
  paymentStatus: string;
  statusUrl: string;
}

export interface CancelSupportResponse {
  supportId: string;
  status: string;
}

// ---- Operations（SCR-060/061、API-RF-001/002・PY-002） ----------------------

export interface RefundResponse {
  refundId: string;
  status: string;
}

export interface ReconcileResponse {
  paymentId: string;
  status: string;
}

export const REFUND_REASON_CODES = ["PROJECT_FAILED", "OPERATIONAL", "USER_CANCEL"] as const;

/** 運用者向け支援一覧項目（SCR-060 支援管理、API-FD-004）。 */
export interface OperationsSupportListItem {
  supportId: string;
  projectId: string;
  projectTitle: string;
  supporterUserId: string;
  amount: number;
  status: string;
  paymentId: string | null;
  paymentStatus: string | null;
  createdAt: string;
}

/** 運用者向け返金一覧項目（SCR-061 返金管理、API-RF-003）。 */
export interface RefundListItem {
  refundId: string;
  supportId: string;
  paymentId: string;
  amount: number;
  reasonCode: string;
  status: string;
  retryCount: number;
  createdAt: string;
  updatedAt: string;
}

/** 支援状態（support.status、詳細設計 §8.13）。運用検索の絞り込みに用いる。 */
export const SUPPORT_STATUSES = [
  "PENDING",
  "PAID",
  "PAYMENT_FAILED",
  "CANCEL_REQUESTED",
  "CANCELLED",
  "REFUND_REQUESTED",
  "REFUNDING",
  "REFUNDED",
  "REFUND_FAILED",
] as const;

/** 返金状態（refund.status、詳細設計 §8.14）。 */
export const REFUND_STATUSES = [
  "REQUESTED",
  "PROCESSING",
  "RETRY_WAIT",
  "SUCCEEDED",
  "FAILED",
] as const;

// ---- Admin / Audit（SCR-070/071、API-AD/AU） -------------------------------

export interface AdminUserListItem {
  userId: string;
  email: string;
  displayName: string;
  status: string;
  roles: string[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateRolesResponse {
  userId: string;
  roles: string[];
  version: number;
}

export interface SuspendUserResponse {
  userId: string;
  status: string;
  version: number;
}

export interface AuditLogItem {
  auditId: string;
  occurredAt: string;
  actorUserId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  result: string;
  correlationId: string;
  detail: Record<string, unknown>;
}

export interface AiActivityLogItem {
  aiActivityId: string;
  occurredAt: string;
  actorUserId: string;
  toolName: string;
  taskId: string | null;
  actionType: string;
  repository: string;
  result: string;
  approvedBy: string | null;
}

/** 会員へ付与可能なロール（backend role.assignable = true と一致。GUEST/AI_AGENTは対象外）。 */
export const ASSIGNABLE_ROLES = [
  "SUPPORTER",
  "OWNER",
  "REVIEWER",
  "OPERATOR",
  "ADMIN",
  "AUDITOR",
  "DEVELOPER",
] as const;
