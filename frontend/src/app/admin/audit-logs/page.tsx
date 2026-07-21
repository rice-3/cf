// SCR-071 監査ログ検索（API-AU-001 監査ログ / API-AU-002 AI利用記録）
import Link from "next/link";
import {
  type AiActivityLogItem,
  type AuditLogItem,
  BackendError,
  backendFetch,
  type PageResult,
} from "@/lib/backend";
import { formatDateTime } from "@/lib/format";

export const dynamic = "force-dynamic";

/** 既定の検索期間は直近7日（最大31日はバックエンドで検証、超過は400）。 */
function defaultRange(fromParam?: string, toParam?: string): { from: string; to: string } {
  const to = toParam ? new Date(toParam) : new Date();
  const from = fromParam ? new Date(fromParam) : new Date(to.getTime() - 7 * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}

/** datetime-local 入力用（秒以下を落としローカル時刻へ）。 */
function toLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default async function AuditLogsPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string; from?: string; to?: string; actorUserId?: string; action?: string }>;
}) {
  const params = await searchParams;
  const tab = params.tab === "ai" ? "ai" : "audit";
  const range = defaultRange(params.from, params.to);

  const query = new URLSearchParams();
  query.set("from", range.from);
  query.set("to", range.to);
  if (params.actorUserId) query.set("actorUserId", params.actorUserId);
  if (params.action && tab === "audit") query.set("action", params.action);
  query.set("size", "50");

  const path = tab === "ai" ? `/api/v1/ai-activities?${query}` : `/api/v1/audit-logs?${query}`;

  let audit: PageResult<AuditLogItem> | null = null;
  let ai: PageResult<AiActivityLogItem> | null = null;
  let errorMessage: string | null = null;

  try {
    if (tab === "ai") {
      ai = (await backendFetch<PageResult<AiActivityLogItem>>(path)).data;
    } else {
      audit = (await backendFetch<PageResult<AuditLogItem>>(path)).data;
    }
  } catch (e) {
    if (e instanceof BackendError) {
      if (e.problem.status === 403 || e.problem.status === 401) {
        return (
          <section>
            <h1>監査ログ検索</h1>
            <p className="error-summary">管理者または監査担当者（ADMIN / AUDITOR）でログインしてください。</p>
            <Link href="/login">ログイン画面へ</Link>
          </section>
        );
      }
      errorMessage =
        e.problem.code === "DATE_RANGE_TOO_LARGE"
          ? "検索期間は最大31日までです。"
          : (e.problem.detail ?? "検索に失敗しました。");
    } else {
      throw e;
    }
  }

  return (
    <section>
      <h1>監査ログ検索</h1>

      <nav style={{ display: "flex", gap: "1rem", marginBottom: "1rem" }}>
        <Link href="/admin/audit-logs?tab=audit" aria-current={tab === "audit"}>
          操作監査ログ
        </Link>
        <Link href="/admin/audit-logs?tab=ai" aria-current={tab === "ai"}>
          AI利用記録
        </Link>
      </nav>

      <form method="get" role="search" style={{ marginBottom: "1rem" }}>
        <input type="hidden" name="tab" value={tab} />
        <div className="form-field">
          <label htmlFor="from">開始日時</label>
          <input id="from" name="from" type="datetime-local" defaultValue={toLocalInput(range.from)} />
        </div>
        <div className="form-field">
          <label htmlFor="to">終了日時</label>
          <input id="to" name="to" type="datetime-local" defaultValue={toLocalInput(range.to)} />
        </div>
        <div className="form-field">
          <label htmlFor="actorUserId">実行者ユーザーID（任意）</label>
          <input id="actorUserId" name="actorUserId" defaultValue={params.actorUserId ?? ""} />
        </div>
        {tab === "audit" && (
          <div className="form-field">
            <label htmlFor="action">操作コード（完全一致・任意）</label>
            <input id="action" name="action" defaultValue={params.action ?? ""} placeholder="PROJECT_SUBMIT_REVIEW など" />
          </div>
        )}
        <button type="submit" className="button-primary">
          検索
        </button>
      </form>

      {errorMessage && <div className="error-summary" role="alert">{errorMessage}</div>}

      {tab === "audit" && audit && (
        <>
          <p>{audit.totalElements}件</p>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>日時</th>
                  <th>操作</th>
                  <th>対象</th>
                  <th>結果</th>
                  <th>実行者</th>
                  <th>相関ID</th>
                </tr>
              </thead>
              <tbody>
                {audit.items.map((item) => (
                  <tr key={item.auditId}>
                    <td>{formatDateTime(item.occurredAt)}</td>
                    <td>{item.action}</td>
                    <td>
                      {item.resourceType}
                      {item.resourceId ? `:${item.resourceId}` : ""}
                    </td>
                    <td>{item.result}</td>
                    <td>{item.actorUserId ?? "system"}</td>
                    <td>{item.correlationId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {tab === "ai" && ai && (
        <>
          <p>{ai.totalElements}件</p>
          {ai.items.length === 0 ? (
            <p>AI利用記録はありません。</p>
          ) : (
            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>日時</th>
                    <th>ツール</th>
                    <th>種別</th>
                    <th>リポジトリ</th>
                    <th>結果</th>
                    <th>承認者</th>
                  </tr>
                </thead>
                <tbody>
                  {ai.items.map((item) => (
                    <tr key={item.aiActivityId}>
                      <td>{formatDateTime(item.occurredAt)}</td>
                      <td>{item.toolName}</td>
                      <td>{item.actionType}</td>
                      <td>{item.repository}</td>
                      <td>{item.result}</td>
                      <td>{item.approvedBy ?? "-"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
