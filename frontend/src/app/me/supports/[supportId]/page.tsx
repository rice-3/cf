// 支援詳細（SCR-051 からの遷移、API-FD-003）
import Link from "next/link";
import { notFound } from "next/navigation";
import { BackendError, backendFetch, type SupportDetailView } from "@/lib/backend";
import { formatDateTime, formatYen } from "@/lib/format";
import { supportStatusLabel } from "../page";
import { CancelSupportButton } from "./CancelSupportButton";

export const dynamic = "force-dynamic";

/** 取消要求が可能な状態（決済確定前・確定後の運用取消はOPERATOR側で扱う）。 */
const CANCELLABLE = new Set(["PENDING", "PAID"]);

export default async function SupportDetailPage({
  params,
}: {
  params: Promise<{ supportId: string }>;
}) {
  const { supportId } = await params;

  let support: SupportDetailView;
  try {
    const envelope = await backendFetch<SupportDetailView>(`/api/v1/me/supports/${supportId}`);
    support = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && (e.problem.status === 404 || e.problem.status === 403)) notFound();
    throw e;
  }

  return (
    <section>
      <h1>支援詳細</h1>
      <p>
        <Link href="/me/supports">← 支援履歴へ戻る</Link>
      </p>
      <dl className="card">
        <dt>プロジェクト</dt>
        <dd>
          <Link href={`/projects/${support.projectId}`}>{support.projectTitle}</Link>
        </dd>
        <dt>支援額</dt>
        <dd>{formatYen(support.amount)}</dd>
        <dt>状態</dt>
        <dd>{supportStatusLabel(support.status)}</dd>
        <dt>決済状態</dt>
        <dd>{support.paymentStatus ?? "-"}</dd>
        <dt>連絡先</dt>
        <dd>{support.contactEmail}</dd>
        <dt>申込日時</dt>
        <dd>{formatDateTime(support.createdAt)}</dd>
      </dl>

      <h2>内訳</h2>
      <div className="table-scroll">
        <table className="data-table">
          <thead>
            <tr>
              <th>リターン</th>
              <th>数量</th>
              <th>単価</th>
              <th>小計</th>
            </tr>
          </thead>
          <tbody>
            {support.items.map((item, i) => (
              <tr key={i}>
                <td>{item.rewardPlanId ?? "寄付"}</td>
                <td>{item.quantity}</td>
                <td>{formatYen(item.unitAmount)}</td>
                <td>{formatYen(item.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {CANCELLABLE.has(support.status) && (
        <div style={{ marginTop: "1.5rem" }}>
          <CancelSupportButton supportId={support.supportId} />
        </div>
      )}
    </section>
  );
}
