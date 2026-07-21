"use client";

// SCR-061 返金管理: 運用者向け返金一覧＋行内アクション（返金再実行）。
import { useState } from "react";
import type { RefundListItem } from "@/lib/api-types";
import { formatDateTime, formatYen } from "@/lib/format";
import { retryRefund } from "./actions";

const REFUND_REASON_LABELS: Record<string, string> = {
  PROJECT_FAILED: "募集不成立",
  OPERATIONAL: "運用対応",
  USER_CANCEL: "利用者都合",
};

function RefundRow({ item }: { item: RefundListItem }) {
  const [message, setMessage] = useState<string | null>(null);
  const [isError, setIsError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function retry() {
    setMessage(null);
    setIsError(false);
    setSubmitting(true);
    try {
      const result = await retryRefund(item.refundId);
      if (!result.ok) {
        setIsError(true);
        setMessage(result.error.detail ?? "再実行に失敗しました。");
        return;
      }
      setMessage(`再実行を受け付けました（状態=${result.data.status}）。次回の返金バッチで処理されます。`);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <tr>
        <td>
          <code>{item.refundId}</code>
        </td>
        <td>
          <code>{item.supportId}</code>
        </td>
        <td style={{ textAlign: "right" }}>{formatYen(item.amount)}</td>
        <td>{REFUND_REASON_LABELS[item.reasonCode] ?? item.reasonCode}</td>
        <td>{item.status}</td>
        <td style={{ textAlign: "right" }}>{item.retryCount}</td>
        <td>{formatDateTime(item.updatedAt)}</td>
        <td>
          {item.status === "FAILED" && (
            <button type="button" disabled={submitting} onClick={retry}>
              再実行
            </button>
          )}
        </td>
      </tr>
      {message && (
        <tr>
          <td colSpan={8}>
            <div className={isError ? "error-summary" : "card"} role={isError ? "alert" : "status"}>
              {message}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

export function OperationsRefundsTable({ items }: { items: RefundListItem[] }) {
  if (items.length === 0) {
    return <p>該当する返金はありません。</p>;
  }
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th>返金ID</th>
            <th>支援ID</th>
            <th>金額</th>
            <th>理由</th>
            <th>状態</th>
            <th>再試行</th>
            <th>更新日時</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <RefundRow key={item.refundId} item={item} />
          ))}
        </tbody>
      </table>
    </div>
  );
}
