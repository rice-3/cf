// Backend API呼出しヘルパー（サーバー側専用）。
// 認証ヘッダーはBFF（サーバー）側でのみ付与し、ブラウザへ認証情報を渡さない（§7.9）。
// `next/headers` に依存するため、このモジュールは Server Component / Server Action からのみ
// import すること。クライアント（"use client"）は型・定数を lib/api-types.ts から import する。
import "server-only";
import { currentDevUser } from "./devSession";
import type { ApiEnvelope, ProblemDetails } from "./api-types";

// 型・定数はクライアントと共用するため api-types から再エクスポートする。
export * from "./api-types";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** 開発用認証ヘッダー（localのみ）。現在のdevセッション（Cookie）から解決する。 */
async function devAuthHeaders(): Promise<Record<string, string>> {
  const user = await currentDevUser();
  if (!user) return {};
  return {
    "X-Dev-User": user.userId,
    "X-Dev-Roles": user.roles,
  };
}

export class BackendError extends Error {
  constructor(public readonly problem: ProblemDetails) {
    super(problem.detail ?? problem.title ?? "Backend error");
  }
}

export async function backendFetch<T>(
  path: string,
  init: RequestInit = {},
): Promise<ApiEnvelope<T>> {
  const auth = await devAuthHeaders();
  const response = await fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...auth,
      ...(init.headers ?? {}),
    },
    cache: "no-store",
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({ status: response.status }))) as ProblemDetails;
    throw new BackendError({ ...problem, status: response.status });
  }
  return (await response.json()) as ApiEnvelope<T>;
}
