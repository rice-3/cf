// SCR-010 プロジェクト検索（Server Component、§5.2）
import Link from "next/link";
import { backendFetch, type PageResult, type ProjectListItem } from "@/lib/backend";

export const dynamic = "force-dynamic";

export default async function ProjectSearchPage({
  searchParams,
}: {
  searchParams: Promise<{ keyword?: string; page?: string }>;
}) {
  const params = await searchParams;
  const page = Number(params.page ?? "0");
  const query = new URLSearchParams();
  if (params.keyword) query.set("keyword", params.keyword);
  query.set("page", String(page));
  query.set("size", "20");

  let result: PageResult<ProjectListItem>;
  try {
    const envelope = await backendFetch<PageResult<ProjectListItem>>(`/api/v1/projects?${query}`);
    result = envelope.data;
  } catch {
    return (
      <section>
        <h1>プロジェクト検索</h1>
        <p className="error-summary">
          プロジェクト情報を取得できませんでした。Backend API（http://localhost:8080）の起動を確認してください。
        </p>
      </section>
    );
  }

  return (
    <section>
      <h1>プロジェクト検索</h1>
      <form method="get" role="search" aria-label="プロジェクト検索">
        <div className="form-field">
          <label htmlFor="keyword">キーワード</label>
          <input id="keyword" name="keyword" defaultValue={params.keyword ?? ""} />
        </div>
        <button type="submit" className="button-primary">
          検索
        </button>
      </form>

      <p>
        {result.totalElements}件中 {result.items.length}件を表示
      </p>
      {result.items.length === 0 ? (
        <p>公開中のプロジェクトはありません。</p>
      ) : (
        <ul style={{ listStyle: "none", padding: 0 }}>
          {result.items.map((item) => (
            <li key={item.projectId} className="card">
              <h2>
                <Link href={`/projects/${item.projectId}`}>{item.title}</Link>
              </h2>
              <p>{item.summary}</p>
              <p>
                目標金額: {item.targetAmount.toLocaleString()}円{" "}
                <span className="status-badge">{item.status}</span>
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
