"use client";

// SCR-060 支援管理: 運用者向け支援一覧＋行内アクション（返金要求・決済照合）。
import { useState } from "react";
import { type OperationsSupportListItem, REFUND_REASON_CODES } from "@/lib/api-types";
import { formatDateTime, formatYen } from "@/lib/format";
import { reconcilePayment, requestRefund } from "./actions";

const REFUND_REASON_LABELS: Record<string, string> = {
  PROJECT_FAILED: "募集不成立",
  OPERATIONAL: "運用対応",
  USER_CANCEL: "利用者都合",
};

/** 返金操作が意味を持つ状態（決済確定後・返金前）。 */
const REFUNDABLE = new Set(["PAID", "REFUND_FAILED"]);

function RefundForm({
  supportId,
  onDone,
}: {
  supportId: string;
  onDone: (message: string) => void;
}) {
  const [reasonCode, setReasonCode] = useState<(typeof REFUND_REASON_CODES)[number]>(REFUND_REASON_CODES[0]);
  const [comment, setComment] = useState("");
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    setError(null);
    setSubmitting(true);
    try {
      const result = await requestRefund({
        supportId,
        reasonCode,
        comment: comment.trim() || null,
        amount: amount.trim() ? Number(amount) : null,
      });
      if (!result.ok) {
        setError(result.error.detail ?? "返金要求に失敗しました。");
        return;
      }
      onDone(`返金要求を受け付けました（refundId=${result.data.refundId}）。反映まで数分かかる場合があります。`);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="card" style={{ marginTop: "0.5rem" }}>
      {error && (
        <div className="error-summary" role="alert">
          {error}
        </div>
      )}
      <div className="form-field">
        <label htmlFor={`reason-${supportId}`}>返金理由</label>
        <select
          id={`reason-${supportId}`}
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
        <label htmlFor={`comment-${supportId}`}>コメント（運用対応時は必須）</label>
        <textarea
          id={`comment-${supportId}`}
          rows={2}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </div>
      <div className="form-field">
        <label htmlFor={`amount-${supportId}`}>返金額（空欄で全額）</label>
        <input
          id={`amount-${supportId}`}
          type="number"
          min={1}
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
        />
      </div>
      <button type="button" className="button-primary" disabled={submitting} onClick={submit}>
        返金を要求する
      </button>
    </div>
  );
}

function SupportRow({ item }: { item: OperationsSupportListItem }) {
  const [openRefund, setOpenRefund] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [reconciling, setReconciling] = useState(false);

  async function reconcile() {
    if (!item.paymentId) return;
    setMessage(null);
    setReconciling(true);
    try {
      const result = await reconcilePayment(item.paymentId);
      setMessage(
        result.ok
          ? `決済を照合しました（状態=${result.data.status}）。`
          : (result.error.detail ?? "照合に失敗しました。"),
      );
    } finally {
      setReconciling(false);
    }
  }

  return (
    <>
      <tr>
        <td>
          <code>{item.supportId}</code>
        </td>
        <td>{item.projectTitle || item.projectId}</td>
        <td>{item.supporterUserId}</td>
        <td style={{ textAlign: "right" }}>{formatYen(item.amount)}</td>
        <td>{item.status}</td>
        <td>{item.paymentStatus ?? "-"}</td>
        <td>{formatDateTime(item.createdAt)}</td>
        <td>
          {REFUNDABLE.has(item.status) && (
            <button type="button" onClick={() => setOpenRefund((v) => !v)}>
              {openRefund ? "閉じる" : "返金要求"}
            </button>
          )}
          {item.paymentId && (
            <button type="button" disabled={reconciling} onClick={reconcile}>
              決済照合
            </button>
          )}
        </td>
      </tr>
      {(openRefund || message) && (
        <tr>
          <td colSpan={8}>
            {message && (
              <div className="card" role="status">
                {message}
              </div>
            )}
            {openRefund && (
              <RefundForm
                supportId={item.supportId}
                onDone={(m) => {
                  setMessage(m);
                  setOpenRefund(false);
                }}
              />
            )}
          </td>
        </tr>
      )}
    </>
  );
}

export function OperationsSupportsTable({ items }: { items: OperationsSupportListItem[] }) {
  if (items.length === 0) {
    return <p>該当する支援はありません。</p>;
  }
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th>支援ID</th>
            <th>プロジェクト</th>
            <th>支援者</th>
            <th>金額</th>
            <th>状態</th>
            <th>決済状態</th>
            <th>支援日時</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <SupportRow key={item.supportId} item={item} />
          ))}
        </tbody>
      </table>
    </div>
  );
}
