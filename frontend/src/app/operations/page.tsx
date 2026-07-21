// SCR-060 支援管理 / SCR-061 返金管理（運用コンソール）
import Link from "next/link";
import {
  BackendError,
  backendFetch,
  type OperationsSupportListItem,
  type PageResult,
  type RefundListItem,
  REFUND_STATUSES,
  SUPPORT_STATUSES,
} from "@/lib/backend";
import { OperationsRefundsTable } from "./OperationsRefundsTable";
import { OperationsSupportsTable } from "./OperationsSupportsTable";

export const dynamic = "force-dynamic";

const PAGE_SIZE = 50;

type SearchParams = {
  tab?: string;
  status?: string;
  projectId?: string;
  page?: string;
};

function accessDenied(message: string) {
  return (
    <section>
      <h1>運用コンソール</h1>
      <p className="error-summary">{message}</p>
      <Link href="/login">ログイン画面へ</Link>
    </section>
  );
}

export default async function OperationsPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const tab = params.tab === "refunds" ? "refunds" : "supports";
  const page = Math.max(0, Number.parseInt(params.page ?? "0", 10) || 0);
  const status = params.status ?? "";

  const query = new URLSearchParams();
  query.set("page", String(page));
  query.set("size", String(PAGE_SIZE));
  if (status) query.set("status", status);
  if (tab === "supports" && params.projectId) query.set("projectId", params.projectId);

  const path =
    tab === "refunds"
      ? `/api/v1/operations/refunds?${query}`
      : `/api/v1/operations/supports?${query}`;

  let supports: PageResult<OperationsSupportListItem> | null = null;
  let refunds: PageResult<RefundListItem> | null = null;
  let errorMessage: string | null = null;

  try {
    if (tab === "refunds") {
      refunds = (await backendFetch<PageResult<RefundListItem>>(path)).data;
    } else {
      supports = (await backendFetch<PageResult<OperationsSupportListItem>>(path)).data;
    }
  } catch (e) {
    if (e instanceof BackendError) {
      if (e.problem.status === 403 || e.problem.status === 401) {
        return accessDenied("運用担当者または管理者（OPERATOR / ADMIN）でログインしてください。");
      }
      errorMessage = e.problem.detail ?? "検索に失敗しました。";
    } else {
      throw e;
    }
  }

  const total = (tab === "refunds" ? refunds : supports)?.totalElements ?? 0;
  const totalPages = (tab === "refunds" ? refunds : supports)?.totalPages ?? 0;
  const statuses = tab === "refunds" ? REFUND_STATUSES : SUPPORT_STATUSES;

  function pageHref(target: number): string {
    const q = new URLSearchParams();
    q.set("tab", tab);
    if (status) q.set("status", status);
    if (tab === "supports" && params.projectId) q.set("projectId", params.projectId);
    q.set("page", String(target));
    return `/operations?${q}`;
  }

  return (
    <section>
      <h1>運用コンソール</h1>
      <p>
        支援・返金の検索と運用操作（返金要求・再実行、決済照合）を行います（OPERATOR / ADMIN）。
        すべての操作は監査ログへ記録されます。
      </p>

      <nav style={{ display: "flex", gap: "1rem", marginBottom: "1rem" }}>
        <Link href="/operations?tab=supports" aria-current={tab === "supports"}>
          支援管理（SCR-060）
        </Link>
        <Link href="/operations?tab=refunds" aria-current={tab === "refunds"}>
          返金管理（SCR-061）
        </Link>
      </nav>

      <form method="get" role="search" style={{ marginBottom: "1rem" }}>
        <input type="hidden" name="tab" value={tab} />
        <div className="form-field">
          <label htmlFor="status">状態（任意）</label>
          <select id="status" name="status" defaultValue={status}>
            <option value="">すべて</option>
            {statuses.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        {tab === "supports" && (
          <div className="form-field">
            <label htmlFor="projectId">プロジェクトID（完全一致・任意）</label>
            <input id="projectId" name="projectId" defaultValue={params.projectId ?? ""} />
          </div>
        )}
        <button type="submit" className="button-primary">
          検索
        </button>
      </form>

      {errorMessage && (
        <div className="error-summary" role="alert">
          {errorMessage}
        </div>
      )}

      {!errorMessage && (
        <>
          <p>{total}件</p>
          {tab === "supports" && supports && <OperationsSupportsTable items={supports.items} />}
          {tab === "refunds" && refunds && <OperationsRefundsTable items={refunds.items} />}

          {totalPages > 1 && (
            <nav style={{ display: "flex", gap: "1rem", marginTop: "1rem" }} aria-label="ページ送り">
              {page > 0 && <Link href={pageHref(page - 1)}>前へ</Link>}
              <span>
                {page + 1} / {totalPages}
              </span>
              {page + 1 < totalPages && <Link href={pageHref(page + 1)}>次へ</Link>}
            </nav>
          )}
        </>
      )}
    </section>
  );
}
