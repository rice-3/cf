// 日時・金額の表示/入力変換ヘルパー（SCR共通方針 §7.2 DateTimeInput: JST表示/UTC送信）。

/** UTC ISO文字列 → `<input type="datetime-local">` 用のローカル時刻文字列。 */
export function toDateTimeLocalValue(isoUtc: string | null | undefined): string {
  if (!isoUtc) return "";
  const d = new Date(isoUtc);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** `datetime-local` のローカル時刻文字列 → UTC ISO文字列。 */
export function fromDateTimeLocalValue(local: string): string {
  return new Date(local).toISOString();
}

export function formatYen(amount: number): string {
  return `${amount.toLocaleString("ja-JP")}円`;
}

export function formatDateTime(isoUtc: string): string {
  return new Date(isoUtc).toLocaleString("ja-JP", { dateStyle: "medium", timeStyle: "short" });
}

export const PROJECT_STATUS_LABELS: Record<string, string> = {
  DRAFT: "下書き",
  REVIEW_REQUESTED: "審査申請済",
  UNDER_REVIEW: "審査中",
  RETURNED: "差戻し",
  APPROVED: "承認済",
  PUBLISHED: "公開中",
  SUSPENDED: "公開停止",
  SUCCEEDED: "成立",
  FAILED: "不成立",
  REFUNDING: "返金中",
  REFUNDED: "返金完了",
  SETTLED: "精算完了",
  REJECTED: "却下",
  CANCELLED: "取消",
};

export function projectStatusLabel(status: string): string {
  return PROJECT_STATUS_LABELS[status] ?? status;
}
