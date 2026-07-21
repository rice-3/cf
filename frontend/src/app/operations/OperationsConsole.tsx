"use client";

// SCR-060 支援管理 / SCR-061 返金管理（運用コンソール）。
//
// 注: バックエンドに運用者向けの一覧・検索API（支援検索/返金検索）が未実装のため、
// 本画面はID指定のアクション（返金要求・返金再実行・決済照合）で構成する。
// 対象IDは監査ログ検索（SCR-071）や障害調査で特定する運用を想定する。
// 一覧・検索が必要になった時点でバックエンドへ検索APIを追加すること（残タスク §5.3 参照）。
import { useState } from "react";
import { REFUND_REASON_CODES } from "@/lib/api-types";
import { reconcilePayment, requestRefund, retryRefund } from "./actions";

const REFUND_REASON_LABELS: Record<string, string> = {
  PROJECT_FAILED: "募集不成立",
  OPERATIONAL: "運用対応",
  USER_CANCEL: "利用者都合",
};

function useActionState() {
  const [message, setMessage] = useState<string | null>(null);
  const [isError, setIsError] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  return { message, setMessage, isError, setIsError, submitting, setSubmitting };
}

function RefundRequestCard() {
  const s = useActionState();
  const [supportId, setSupportId] = useState("");
  const [reasonCode, setReasonCode] = useState<(typeof REFUND_REASON_CODES)[number]>(REFUND_REASON_CODES[0]);
  const [comment, setComment] = useState("");
  const [amount, setAmount] = useState("");

  async function submit() {
    s.setMessage(null);
    s.setIsError(false);
    s.setSubmitting(true);
    try {
      const result = await requestRefund({
        supportId: supportId.trim(),
        reasonCode,
        comment: comment.trim() || null,
        amount: amount.trim() ? Number(amount) : null,
      });
      if (!result.ok) {
        s.setIsError(true);
        s.setMessage(result.error.detail ?? "返金要求に失敗しました。");
        return;
      }
      s.setMessage(`返金要求を受け付けました（refundId=${result.data.refundId}, 状態=${result.data.status}）。`);
    } finally {
      s.setSubmitting(false);
    }
  }

  return (
    <section className="card">
      <h2>返金要求（SCR-061 / API-RF-001）</h2>
      {s.message && (
        <div className={s.isError ? "error-summary" : "card"} role={s.isError ? "alert" : undefined}>
          {s.message}
        </div>
      )}
      <div className="form-field">
        <label htmlFor="rf-supportId">支援ID</label>
        <input id="rf-supportId" value={supportId} onChange={(e) => setSupportId(e.target.value)} />
      </div>
      <div className="form-field">
        <label htmlFor="rf-reason">返金理由</label>
        <select
          id="rf-reason"
          value={reasonCode}
          onChange={(e) => setReasonCode(e.target.value as (typeof REFUND_REASON_CODES)[number])}
        >
          {REFUND_REASON_CODES.map((c) => (
            <option key={c} value={c}>
              {REFUND_REASON_LABELS[c] ?? c}
            </option>
          ))}
        </select>
      </div>
      <div className="form-field">
        <label htmlFor="rf-comment">コメント（運用理由時は必須）</label>
        <textarea id="rf-comment" rows={2} value={comment} onChange={(e) => setComment(e.target.value)} />
      </div>
      <div className="form-field">
        <label htmlFor="rf-amount">返金額（空欄で全額）</label>
        <input id="rf-amount" type="number" min={1} value={amount} onChange={(e) => setAmount(e.target.value)} />
      </div>
      <button
        type="button"
        className="button-primary"
        disabled={s.submitting || !supportId.trim()}
        onClick={submit}
      >
        返金を要求する
      </button>
    </section>
  );
}

function RefundRetryCard() {
  const s = useActionState();
  const [refundId, setRefundId] = useState("");

  async function submit() {
    s.setMessage(null);
    s.setIsError(false);
    s.setSubmitting(true);
    try {
      const result = await retryRefund(refundId.trim());
      if (!result.ok) {
        s.setIsError(true);
        s.setMessage(result.error.detail ?? "再実行に失敗しました。");
        return;
      }
      s.setMessage(`再実行を受け付けました（状態=${result.data.status}）。次回の返金バッチで処理されます。`);
    } finally {
      s.setSubmitting(false);
    }
  }

  return (
    <section className="card">
      <h2>返金再実行（SCR-061 / API-RF-002）</h2>
      {s.message && (
        <div className={s.isError ? "error-summary" : "card"} role={s.isError ? "alert" : undefined}>
          {s.message}
        </div>
      )}
      <div className="form-field">
        <label htmlFor="rt-refundId">返金ID</label>
        <input id="rt-refundId" value={refundId} onChange={(e) => setRefundId(e.target.value)} />
      </div>
      <button type="button" disabled={s.submitting || !refundId.trim()} onClick={submit}>
        再実行する
      </button>
    </section>
  );
}

function ReconcileCard() {
  const s = useActionState();
  const [paymentId, setPaymentId] = useState("");

  async function submit() {
    s.setMessage(null);
    s.setIsError(false);
    s.setSubmitting(true);
    try {
      const result = await reconcilePayment(paymentId.trim());
      if (!result.ok) {
        s.setIsError(true);
        s.setMessage(result.error.detail ?? "照合に失敗しました。");
        return;
      }
      s.setMessage(`照合しました（決済状態=${result.data.status}）。`);
    } finally {
      s.setSubmitting(false);
    }
  }

  return (
    <section className="card">
      <h2>決済照合（SCR-060 / API-PY-002）</h2>
      {s.message && (
        <div className={s.isError ? "error-summary" : "card"} role={s.isError ? "alert" : undefined}>
          {s.message}
        </div>
      )}
      <div className="form-field">
        <label htmlFor="rc-paymentId">決済ID</label>
        <input id="rc-paymentId" value={paymentId} onChange={(e) => setPaymentId(e.target.value)} />
      </div>
      <button type="button" disabled={s.submitting || !paymentId.trim()} onClick={submit}>
        照合する
      </button>
    </section>
  );
}

export function OperationsConsole() {
  return (
    <div>
      <p className="error-summary" role="note">
        現在、運用者向けの一覧・検索APIは未実装のため、対象IDを指定して操作します。
        対象IDは監査ログ検索や障害調査で特定してください。
      </p>
      <RefundRequestCard />
      <RefundRetryCard />
      <ReconcileCard />
    </div>
  );
}
