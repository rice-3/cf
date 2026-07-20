"use server";

// 起案者向けプロジェクト操作のServer Actions（Next.js BFF、§18.2「認証処理はBFF側へ寄せる」）。
// Client Componentから直接呼び出す。エラーはthrowせず判別可能な結果として返し、
// フォーム側で入力欄に対応したエラー表示を行う（§7.10 エラーの関連付け）。

import {
  BackendError,
  backendFetch,
  type CompleteUploadResponse,
  type IssueUploadResponse,
  type ProblemDetails,
  type ProjectCancelledResponse,
  type ProjectCreatedResponse,
  type ProjectUpdatedResponse,
  type SubmitReviewResponse,
} from "@/lib/backend";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: ProblemDetails };

async function run<T>(fn: () => Promise<T>): Promise<ActionResult<T>> {
  try {
    return { ok: true, data: await fn() };
  } catch (e) {
    if (e instanceof BackendError) {
      return { ok: false, error: e.problem };
    }
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}

export interface RewardPlanInput {
  name: string;
  description: string;
  unitAmount: number;
  quantityLimit: number | null;
  displayOrder: number;
}

export interface ProjectFormInput {
  title: string;
  summary: string;
  body: string;
  targetAmount: number;
  fundingType: "ALL_OR_NOTHING" | "ALL_IN";
  startAt: string;
  endAt: string;
  mainFileId: string | null;
  rewardPlans: RewardPlanInput[];
}

/** API-PJ-003 下書き作成。 */
export async function createProject(input: ProjectFormInput): Promise<ActionResult<ProjectCreatedResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ProjectCreatedResponse>("/api/v1/owner/projects", {
      method: "POST",
      body: JSON.stringify(input),
    });
    return envelope.data;
  });
}

/** API-PJ-004 下書き更新。 */
export async function updateProject(
  projectId: string,
  expectedVersion: number,
  input: ProjectFormInput,
): Promise<ActionResult<ProjectUpdatedResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ProjectUpdatedResponse>(`/api/v1/owner/projects/${projectId}`, {
      method: "PUT",
      body: JSON.stringify({ ...input, expectedVersion }),
    });
    return envelope.data;
  });
}

/** API-PJ-005 審査申請。 */
export async function submitProjectForReview(
  projectId: string,
  expectedVersion: number,
  confirmations: string[],
): Promise<ActionResult<SubmitReviewResponse>> {
  return run(async () => {
    const envelope = await backendFetch<SubmitReviewResponse>(
      `/api/v1/owner/projects/${projectId}/review-requests`,
      { method: "POST", body: JSON.stringify({ expectedVersion, confirmations }) },
    );
    return envelope.data;
  });
}

/** API-PJ-006 取消。 */
export async function cancelProject(
  projectId: string,
  expectedVersion: number,
  reason: string | null,
): Promise<ActionResult<ProjectCancelledResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ProjectCancelledResponse>(`/api/v1/owner/projects/${projectId}/cancel`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion, reason }),
    });
    return envelope.data;
  });
}

/** API-FL-001 署名付きアップロードURL発行。 */
export async function issueUpload(input: {
  purpose: "PROJECT_MAIN";
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
}): Promise<ActionResult<IssueUploadResponse>> {
  return run(async () => {
    const envelope = await backendFetch<IssueUploadResponse>("/api/v1/files/presigned-uploads", {
      method: "POST",
      body: JSON.stringify(input),
    });
    return envelope.data;
  });
}

/**
 * API-FL-002 アップロード完了。
 *
 * 本来はブラウザがuploadUrlへ直接PUTした後に呼ぶ（§10.2）。
 * local/testはS3スタブ（`StubFileStorageAdapter`）が発行時にHeadObject相当を
 * 同期記録するため、PUTなしでも完了できる（backendの結合テストと同じ挙動）。
 */
export async function completeUpload(
  fileId: string,
  sha256: string,
): Promise<ActionResult<CompleteUploadResponse>> {
  return run(async () => {
    const envelope = await backendFetch<CompleteUploadResponse>(`/api/v1/files/${fileId}/complete`, {
      method: "POST",
      body: JSON.stringify({ sha256 }),
    });
    return envelope.data;
  });
}
