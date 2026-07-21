"use server";

import { BackendError, backendFetch, type ProblemDetails } from "@/lib/backend";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: ProblemDetails };

interface ProfileUpdateResponse {
  userId: string;
  displayName: string;
  email: string;
  version: number;
}

/** API-US-002 プロフィール更新。 */
export async function updateProfile(input: {
  displayName: string;
  email: string;
  expectedVersion: number;
}): Promise<ActionResult<ProfileUpdateResponse>> {
  try {
    const envelope = await backendFetch<ProfileUpdateResponse>("/api/v1/me", {
      method: "PUT",
      body: JSON.stringify(input),
    });
    return { ok: true, data: envelope.data };
  } catch (e) {
    if (e instanceof BackendError) return { ok: false, error: e.problem };
    return { ok: false, error: { status: 500, detail: "予期しないエラーが発生しました。" } };
  }
}
