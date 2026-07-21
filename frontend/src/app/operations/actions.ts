"use server";

// 運用操作のServer Actions（API-RF-001/002・API-PY-002）。OPERATOR/ADMINロール。
import {
  BackendError,
  backendFetch,
  type ProblemDetails,
  type ReconcileResponse,
  type RefundResponse,
} from "@/lib/backend";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: ProblemDetails };

async function run<T>(fn: () => Promise<T>): Promise<ActionResult<T>> {
  try {
    return { ok: true, data: await fn() };
  } catch (e) {
    if (e instanceof BackendError) return { ok: false, error: e.problem };
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}

/** API-RF-001 返金要求。Idempotency-Key必須（サーバー側でUUID生成）。 */
export async function requestRefund(input: {
  supportId: string;
  reasonCode: string;
  comment: string | null;
  amount: number | null;
}): Promise<ActionResult<RefundResponse>> {
  return run(async () => {
    const envelope = await backendFetch<RefundResponse>(
      `/api/v1/operations/supports/${input.supportId}/refunds`,
      {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({
          reasonCode: input.reasonCode,
          comment: input.comment,
          amount: input.amount,
        }),
      },
    );
    return envelope.data;
  });
}

/** API-RF-002 返金再実行。 */
export async function retryRefund(refundId: string): Promise<ActionResult<RefundResponse>> {
  return run(async () => {
    const envelope = await backendFetch<RefundResponse>(`/api/v1/operations/refunds/${refundId}/retry`, {
      method: "POST",
      body: "{}",
    });
    return envelope.data;
  });
}

/** API-PY-002 決済照合・再確認。 */
export async function reconcilePayment(paymentId: string): Promise<ActionResult<ReconcileResponse>> {
  return run(async () => {
    const envelope = await backendFetch<ReconcileResponse>(
      `/api/v1/operations/payments/${paymentId}/reconcile`,
      { method: "POST", body: "{}" },
    );
    return envelope.data;
  });
}
