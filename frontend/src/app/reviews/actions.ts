"use server";

// 審査操作のServer Actions（API-RV-003〜006）。REVIEWERロールのBFF越し呼出し。
import { BackendError, backendFetch, type ProblemDetails, type ReviewActionResponse } from "@/lib/backend";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: ProblemDetails };

async function run<T>(fn: () => Promise<T>): Promise<ActionResult<T>> {
  try {
    return { ok: true, data: await fn() };
  } catch (e) {
    if (e instanceof BackendError) return { ok: false, error: e.problem };
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}

/** API-RV-003 審査開始。 */
export async function startReview(reviewId: string): Promise<ActionResult<ReviewActionResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ReviewActionResponse>(`/api/v1/reviews/${reviewId}/start`, {
      method: "POST",
      body: "{}",
    });
    return envelope.data;
  });
}

/** API-RV-004 承認。 */
export async function approveReview(
  reviewId: string,
  expectedVersion: number,
  checklist: Record<string, boolean>,
  comment: string | null,
): Promise<ActionResult<ReviewActionResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ReviewActionResponse>(`/api/v1/reviews/${reviewId}/approve`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion, checklist, comment }),
    });
    return envelope.data;
  });
}

/** API-RV-005 差戻し。コメント必須。 */
export async function returnReview(
  reviewId: string,
  expectedVersion: number,
  comment: string,
): Promise<ActionResult<ReviewActionResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ReviewActionResponse>(`/api/v1/reviews/${reviewId}/return`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion, comment }),
    });
    return envelope.data;
  });
}

/** API-RV-006 却下。理由区分・コメント必須。 */
export async function rejectReview(
  reviewId: string,
  expectedVersion: number,
  reasonCode: string,
  comment: string,
): Promise<ActionResult<ReviewActionResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ReviewActionResponse>(`/api/v1/reviews/${reviewId}/reject`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion, reasonCode, comment }),
    });
    return envelope.data;
  });
}
