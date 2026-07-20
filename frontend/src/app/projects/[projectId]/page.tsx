// SCR-011 プロジェクト詳細
import { notFound } from "next/navigation";
import { BackendError, backendFetch, type ProjectDetailView } from "@/lib/backend";
import { formatDateTime, formatYen, projectStatusLabel } from "@/lib/format";

export const dynamic = "force-dynamic";

export default async function ProjectDetailPage({
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
    if (e instanceof BackendError && e.problem.status === 404) {
      notFound();
    }
    throw e;
  }

  return (
    <article>
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
        {/* 本文はプレーンテキストとして表示（XSS対策。Rich Text対応はADR-005で判断） */}
        <p style={{ whiteSpace: "pre-wrap" }}>{detail.body}</p>
      </section>
      <section>
        <h2>リターン</h2>
        {detail.rewardPlans.length === 0 && <p>リターンが登録されていません。</p>}
        {detail.rewardPlans.map((plan) => (
          <div key={plan.rewardPlanId} className="card">
            <h3>{plan.name}</h3>
            <p>{plan.description}</p>
            <p>
              {formatYen(plan.unitAmount)}
              {plan.remainingQuantity !== null && ` — 残り${plan.remainingQuantity}個`}
            </p>
          </div>
        ))}
      </section>
      {detail.status === "PUBLISHED" && (
        <p className="error-summary" role="note">
          支援機能（SCR-040）は別画面で対応予定です。現時点ではAPI（API-FD-001）のみ利用できます。
        </p>
      )}
    </article>
  );
}
