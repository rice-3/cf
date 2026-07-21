// SCR-070 会員・ロール管理
import Link from "next/link";
import { BackendError, backendFetch, type AdminUserListItem, type PageResult } from "@/lib/backend";
import { UserRow } from "./UserRow";

export const dynamic = "force-dynamic";

export default async function AdminUsersPage({
  searchParams,
}: {
  searchParams: Promise<{ keyword?: string; status?: string }>;
}) {
  const { keyword, status } = await searchParams;
  const query = new URLSearchParams();
  if (keyword) query.set("keyword", keyword);
  if (status) query.set("status", status);
  query.set("size", "50");

  let result: PageResult<AdminUserListItem>;
  try {
    const envelope = await backendFetch<PageResult<AdminUserListItem>>(`/api/v1/admin/users?${query}`);
    result = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && (e.problem.status === 403 || e.problem.status === 401)) {
      return (
        <section>
          <h1>会員・ロール管理</h1>
          <p className="error-summary">管理者（ADMIN）でログインしてください。</p>
          <Link href="/login">ログイン画面へ</Link>
        </section>
      );
    }
    throw e;
  }

  return (
    <section>
      <h1>会員・ロール管理</h1>
      <form method="get" role="search" style={{ marginBottom: "1rem" }}>
        <div className="form-field">
          <label htmlFor="keyword">キーワード（氏名・メール）</label>
          <input id="keyword" name="keyword" defaultValue={keyword ?? ""} />
        </div>
        <div className="form-field">
          <label htmlFor="status">状態</label>
          <select id="status" name="status" defaultValue={status ?? ""}>
            <option value="">すべて</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="SUSPENDED">SUSPENDED</option>
            <option value="WITHDRAWN">WITHDRAWN</option>
          </select>
        </div>
        <button type="submit" className="button-primary">
          検索
        </button>
      </form>

      <p>{result.totalElements}件</p>
      <div className="table-scroll">
        <table className="data-table">
          <thead>
            <tr>
              <th>表示名</th>
              <th>メール</th>
              <th>状態</th>
              <th>ロール</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {result.items.map((user) => (
              <UserRow key={user.userId} user={user} />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
