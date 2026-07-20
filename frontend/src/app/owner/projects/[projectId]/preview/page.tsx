// SCR-022 プレビュー
import Link from "next/link";
import { notFound } from "next/navigation";
import { BackendError, backendFetch, type ProjectDetailView } from "@/lib/backend";
import { formatDateTime, formatYen, projectStatusLabel } from "@/lib/format";

export const dynamic = "force-dynamic";

export default async function ProjectPreviewPage({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;

  let detail: ProjectDetailView;
  try {
    const envelope = await backendFetch<ProjectDetailView>(`/api/v1/projects/${projectId}`);
    detail = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && (e.problem.status === 404 || e.problem.status === 403)) {
      notFound();
    }
    throw e;
  }

  return (
    <article>
      <p className="status-badge">プレビュー（公開時の表示イメージ）</p>
      <h1>{detail.title}</h1>
      <p>
        <span className="status-badge">{projectStatusLabel(detail.status)}</span>
      </p>
      <p>{detail.summary}</p>
      <section className="card">
        <h2>募集条件</h2>
        <dl>
          <dt>目標金額</dt>
          <dd>{formatYen(detail.targetAmount)}</dd>
          <dt>募集方式</dt>
          <dd>{detail.fundingType === "ALL_OR_NOTHING" ? "All-or-Nothing" : "All-in"}</dd>
          <dt>募集期間</dt>
          <dd>
            {formatDateTime(detail.startAt)} 〜 {formatDateTime(detail.endAt)}
          </dd>
        </dl>
      </section>
      <section className="card">
        <h2>本文</h2>
        <p style={{ whiteSpace: "pre-wrap" }}>{detail.body}</p>
      </section>
      <section>
        <h2>リターン</h2>
        {detail.rewardPlans.length === 0 && <p>リターンが登録されていません。</p>}
        {detail.rewardPlans.map((plan) => (
          <div key={plan.rewardPlanId} className="card">
            <h3>{plan.name}</h3>
            <p>{plan.description}</p>
            <p>{formatYen(plan.unitAmount)}</p>
          </div>
        ))}
      </section>
      <p style={{ display: "flex", gap: "0.75rem" }}>
        <Link href={`/owner/projects/${projectId}/edit`}>編集に戻る</Link>
        <Link href={`/owner/projects/${projectId}/submit-review`}>審査申請へ</Link>
      </p>
    </article>
  );
}
