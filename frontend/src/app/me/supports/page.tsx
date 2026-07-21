// SCR-051 支援履歴
import Link from "next/link";
import { BackendError, backendFetch, type PageResult, type SupportListItem } from "@/lib/backend";
import { formatDateTime, formatYen } from "@/lib/format";

export const dynamic = "force-dynamic";

const SUPPORT_STATUS_LABELS: Record<string, string> = {
  PENDING: "決済待ち",
  PAID: "決済済",
  PAYMENT_FAILED: "決済失敗",
  CANCEL_REQUESTED: "取消要求中",
  CANCELLED: "取消済",
  REFUND_REQUESTED: "返金要求中",
  REFUNDING: "返金処理中",
  REFUNDED: "返金済",
  REFUND_FAILED: "返金失敗",
};

export function supportStatusLabel(status: string): string {
  return SUPPORT_STATUS_LABELS[status] ?? status;
}

export default async function SupportHistoryPage() {
  let result: PageResult<SupportListItem>;
  try {
    const envelope = await backendFetch<PageResult<SupportListItem>>("/api/v1/me/supports?size=20");
    result = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && (e.problem.status === 403 || e.problem.status === 401)) {
      return (
        <section>
          <h1>支援履歴</h1>
          <p className="error-summary">支援者（SUPPORTER）でログインしてください。</p>
          <Link href="/login">ログイン画面へ</Link>
        </section>
      );
    }
    throw e;
  }

  return (
    <section>
      <h1>支援履歴</h1>
      <p>{result.totalElements}件</p>
      {result.items.length === 0 ? (
        <p>支援の履歴はありません。</p>
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>プロジェクト</th>
                <th>支援額</th>
                <th>状態</th>
                <th>申込日時</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((item) => (
                <tr key={item.supportId}>
                  <td>{item.projectTitle}</td>
                  <td>{formatYen(item.amount)}</td>
                  <td>{supportStatusLabel(item.status)}</td>
                  <td>{formatDateTime(item.createdAt)}</td>
                  <td>
                    <Link href={`/me/supports/${item.supportId}`}>詳細</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
