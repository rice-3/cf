// Backend API呼出しヘルパー（サーバー側専用）。
// 認証ヘッダーはBFF（サーバー）側でのみ付与し、ブラウザへ認証情報を渡さない（§7.9）。

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** 開発用認証ヘッダー（localのみ）。第2段階でCognitoセッションへ置き換える。 */
function devAuthHeaders(): Record<string, string> {
  const userId = process.env.DEV_USER_ID;
  const roles = process.env.DEV_USER_ROLES;
  if (!userId) return {};
  return {
    "X-Dev-User": userId,
    "X-Dev-Roles": roles ?? "",
  };
}

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

export class BackendError extends Error {
  constructor(public readonly problem: ProblemDetails) {
    super(problem.detail ?? problem.title ?? "Backend error");
  }
}

export async function backendFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...devAuthHeaders(),
      ...(init.headers ?? {}),
    },
    cache: "no-store",
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({ status: response.status }))) as ProblemDetails;
    throw new BackendError({ ...problem, status: response.status });
  }
  return (await response.json()) as ApiEnvelope<T>;
}
