"use server";

// 支援申込・取消のServer Actions（API-FD-001/004）。
import {
  BackendError,
  backendFetch,
  type CancelSupportResponse,
  type ProblemDetails,
  type RequestSupportResponse,
} from "@/lib/backend";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: ProblemDetails };

export interface RequestSupportInput {
  projectId: string;
  rewardPlanId: string | null;
  quantity: number;
  additionalAmount: number;
  contactEmail: string;
  termsAccepted: boolean;
  /** クライアントで生成し、確定処理のリトライ間で不変に保つ（§5.6 冪等制御）。 */
  idempotencyKey: string;
}

/** API-FD-001 支援申込。Idempotency-Keyヘッダー必須。 */
export async function requestSupport(input: RequestSupportInput): Promise<ActionResult<RequestSupportResponse>> {
  try {
    const envelope = await backendFetch<RequestSupportResponse>(
      `/api/v1/projects/${input.projectId}/supports`,
      {
        method: "POST",
        headers: { "Idempotency-Key": input.idempotencyKey },
        body: JSON.stringify({
          rewardPlanId: input.rewardPlanId,
          quantity: input.quantity,
          additionalAmount: input.additionalAmount,
          contactEmail: input.contactEmail,
          termsAccepted: input.termsAccepted,
        }),
      },
    );
    return { ok: true, data: envelope.data };
  } catch (e) {
    if (e instanceof BackendError) return { ok: false, error: e.problem };
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}

/** API-FD-004 支援取消要求。 */
export async function cancelSupport(supportId: string): Promise<ActionResult<CancelSupportResponse>> {
  try {
    const envelope = await backendFetch<CancelSupportResponse>(`/api/v1/me/supports/${supportId}/cancel`, {
      method: "POST",
      body: "{}",
    });
    return { ok: true, data: envelope.data };
  } catch (e) {
    if (e instanceof BackendError) return { ok: false, error: e.problem };
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}
