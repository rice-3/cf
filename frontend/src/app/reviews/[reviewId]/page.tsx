// SCR-031 審査詳細
import Link from "next/link";
import { notFound } from "next/navigation";
import {
  BackendError,
  backendFetch,
  type ProjectDetailView,
  type ReviewDetailView,
} from "@/lib/backend";
import { formatDateTime, formatYen } from "@/lib/format";
import { ReviewActions } from "./ReviewActions";

export const dynamic = "force-dynamic";

export default async function ReviewDetailPage({
  params,
}: {
  params: Promise<{ reviewId: string }>;
}) {
  const { reviewId } = await params;

  let review: ReviewDetailView;
  try {
    const envelope = await backendFetch<ReviewDetailView>(`/api/v1/reviews/${reviewId}`);
    review = envelope.data;
  } catch (e) {
    if (e instanceof BackendError) {
      if (e.problem.status === 404) notFound();
      if (e.problem.status === 403 || e.problem.status === 401) {
        return (
          <section>
            <h1>審査詳細</h1>
            <p className="error-summary">審査担当者（REVIEWER）でログインしてください。</p>
            <Link href="/login">ログイン画面へ</Link>
          </section>
        );
      }
    }
    throw e;
  }

  // 審査対象プロジェクトの内容（REVIEWERは非公開状態でも参照可）
  let project: ProjectDetailView | null = null;
  try {
    const envelope = await backendFetch<ProjectDetailView>(`/api/v1/projects/${review.projectId}`);
    project = envelope.data;
  } catch {
    project = null;
  }

  return (
    <section>
      <h1>審査詳細</h1>
      <p>
        <Link href="/reviews">← 審査一覧へ戻る</Link>
      </p>

      {project && (
        <article className="card">
          <h2>{project.title}</h2>
          <p>{project.summary}</p>
          <dl>
            <dt>目標金額</dt>
            <dd>{formatYen(project.targetAmount)}</dd>
            <dt>募集方式</dt>
            <dd>{project.fundingType === "ALL_OR_NOTHING" ? "All-or-Nothing" : "All-in"}</dd>
            <dt>募集期間</dt>
            <dd>
              {formatDateTime(project.startAt)} 〜 {formatDateTime(project.endAt)}
            </dd>
          </dl>
          <h3>本文</h3>
          <p style={{ whiteSpace: "pre-wrap" }}>{project.body}</p>
          <h3>リターン</h3>
          {project.rewardPlans.map((r) => (
            <div key={r.rewardPlanId}>
              <strong>{r.name}</strong>（{formatYen(r.unitAmount)}）— {r.description}
            </div>
          ))}
        </article>
      )}

      <h2>審査操作</h2>
      <ReviewActions reviewId={review.reviewId} status={review.status} version={review.version} />

      <h2>審査履歴</h2>
      {review.histories.length === 0 ? (
        <p>履歴はまだありません。</p>
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>操作</th>
                <th>理由</th>
                <th>コメント</th>
                <th>日時</th>
              </tr>
            </thead>
            <tbody>
              {review.histories.map((h, i) => (
                <tr key={i}>
                  <td>{h.action}</td>
                  <td>{h.reasonCode ?? "-"}</td>
                  <td>{h.comment ?? "-"}</td>
                  <td>{formatDateTime(h.actedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
