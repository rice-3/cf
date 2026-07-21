"use server";

// 会員管理のServer Actions（API-AD-002/003）。ADMINロール。
import {
  BackendError,
  backendFetch,
  type ProblemDetails,
  type SuspendUserResponse,
  type UpdateRolesResponse,
} from "@/lib/backend";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: ProblemDetails };

async function run<T>(fn: () => Promise<T>): Promise<ActionResult<T>> {
  try {
    return { ok: true, data: await fn() };
  } catch (e) {
    if (e instanceof BackendError) return { ok: false, error: e.problem };
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}

/** API-AD-002 ロール更新（全置換）。 */
export async function updateRoles(
  userId: string,
  roles: string[],
  expectedVersion: number,
  reason: string,
): Promise<ActionResult<UpdateRolesResponse>> {
  return run(async () => {
    const envelope = await backendFetch<UpdateRolesResponse>(`/api/v1/admin/users/${userId}/roles`, {
      method: "PUT",
      body: JSON.stringify({ roles, expectedVersion, reason }),
    });
    return envelope.data;
  });
}

/** API-AD-003 会員停止。 */
export async function suspendUser(
  userId: string,
  expectedVersion: number,
  reason: string | null,
): Promise<ActionResult<SuspendUserResponse>> {
  return run(async () => {
    const envelope = await backendFetch<SuspendUserResponse>(`/api/v1/admin/users/${userId}/suspend`, {
      method: "POST",
      body: JSON.stringify({ expectedVersion, reason }),
    });
    return envelope.data;
  });
}
