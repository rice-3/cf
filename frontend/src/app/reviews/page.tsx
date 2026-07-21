// SCR-030 審査一覧
import Link from "next/link";
import { BackendError, backendFetch, type PageResult, type ReviewListItem } from "@/lib/backend";
import { formatDateTime } from "@/lib/format";

export const dynamic = "force-dynamic";

const STATUS_OPTIONS = ["REQUESTED", "UNDER_REVIEW", "APPROVED", "RETURNED", "REJECTED"];

const REVIEW_STATUS_LABELS: Record<string, string> = {
  REQUESTED: "申請済",
  UNDER_REVIEW: "審査中",
  APPROVED: "承認済",
  RETURNED: "差戻し",
  REJECTED: "却下",
  WITHDRAWN: "取下げ",
};

export default async function ReviewListPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const { status } = await searchParams;
  const query = new URLSearchParams();
  if (status) query.set("status", status);
  query.set("size", "20");

  let result: PageResult<ReviewListItem>;
  try {
    const envelope = await backendFetch<PageResult<ReviewListItem>>(`/api/v1/reviews?${query}`);
    result = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && (e.problem.status === 403 || e.problem.status === 401)) {
      return (
        <section>
          <h1>審査一覧</h1>
          <p className="error-summary">審査担当者（REVIEWER）でログインしてください。</p>
          <Link href="/login">ログイン画面へ</Link>
        </section>
      );
    }
    throw e;
  }

  return (
    <section>
      <h1>審査一覧</h1>
      <form method="get" role="search" style={{ marginBottom: "1rem" }}>
        <div className="form-field">
          <label htmlFor="status">状態で絞り込み</label>
          <select id="status" name="status" defaultValue={status ?? "REQUESTED"}>
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {REVIEW_STATUS_LABELS[s] ?? s}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button-primary">
          絞り込み
        </button>
      </form>

      <p>{result.totalElements}件</p>
      {result.items.length === 0 ? (
        <p>対象の審査はありません。</p>
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>プロジェクト</th>
                <th>状態</th>
                <th>担当</th>
                <th>申請日時</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((item) => (
                <tr key={item.reviewId}>
                  <td>{item.projectTitle}</td>
                  <td>{REVIEW_STATUS_LABELS[item.status] ?? item.status}</td>
                  <td>{item.reviewerUserId ? "割当済" : "未割当"}</td>
                  <td>{formatDateTime(item.submittedAt)}</td>
                  <td>
                    <Link href={`/reviews/${item.reviewId}`}>審査する</Link>
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
