// SCR-020 プロジェクト一覧（起案者）
import Link from "next/link";
import { backendFetch, type ProjectListItem } from "@/lib/backend";
import { formatDateTime, formatYen, projectStatusLabel } from "@/lib/format";

export const dynamic = "force-dynamic";

export default async function OwnerProjectsPage() {
  let items: ProjectListItem[];
  try {
    const envelope = await backendFetch<ProjectListItem[]>("/api/v1/owner/projects");
    items = envelope.data;
  } catch {
    return (
      <section>
        <h1>自分のプロジェクト</h1>
        <p className="error-summary">プロジェクト一覧を取得できませんでした。</p>
      </section>
    );
  }

  return (
    <section>
      <h1>自分のプロジェクト</h1>
      <p>
        <Link href="/owner/projects/new" className="button-primary" style={{ textDecoration: "none" }}>
          新規プロジェクト作成
        </Link>
      </p>
      {items.length === 0 ? (
        <p>プロジェクトはまだありません。</p>
      ) : (
        <ul style={{ listStyle: "none", padding: 0 }}>
          {items.map((item) => (
            <li key={item.projectId} className="card">
              <h2>
                <Link href={`/owner/projects/${item.projectId}/edit`}>{item.title}</Link>
              </h2>
              <p>
                <span className="status-badge">{projectStatusLabel(item.status)}</span>{" "}
                目標金額: {formatYen(item.targetAmount)}
              </p>
              <p>最終更新: {formatDateTime(item.updatedAt)}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
