// 開発用セッション（local/test 専用）。
// 本番はCognito OIDC + BFFセッション（基本設計 §10.1）だが、教育用ローカル環境では
// 事前投入した開発用ユーザー（DevUserSeeder）を Cookie で切り替える。
// Cookie は HttpOnly とし、ブラウザJSからトークン相当の情報を参照させない（§7.9）。
import { cookies } from "next/headers";

export const DEV_SESSION_COOKIE = "cf_dev_user";

export interface DevUser {
  key: string;
  userId: string;
  roles: string; // カンマ区切り（X-Dev-Roles ヘッダーへそのまま渡す）
  label: string;
}

/** DevUserSeeder（backend）で投入される合成ユーザーと一致させる。 */
export const DEV_USERS: DevUser[] = [
  { key: "owner", userId: "01K00000000000000000000001", roles: "OWNER,SUPPORTER", label: "起案者（OWNER）" },
  { key: "reviewer", userId: "01K00000000000000000000002", roles: "REVIEWER", label: "審査担当者（REVIEWER）" },
  {
    key: "admin",
    userId: "01K00000000000000000000003",
    roles: "ADMIN,OPERATOR,AUDITOR",
    label: "管理者（ADMIN / OPERATOR / AUDITOR）",
  },
  { key: "supporter", userId: "01K00000000000000000000004", roles: "SUPPORTER", label: "支援者（SUPPORTER）" },
];

export function findDevUser(key: string | undefined): DevUser | undefined {
  if (!key) return undefined;
  return DEV_USERS.find((u) => u.key === key);
}

/**
 * 現在のリクエストの開発用セッションを返す。
 * Cookie が無い場合は環境変数の既定（DEV_USER_ID / DEV_USER_ROLES）へフォールバックする。
 */
export async function currentDevUser(): Promise<DevUser | null> {
  const store = await cookies();
  const key = store.get(DEV_SESSION_COOKIE)?.value;
  const fromCookie = findDevUser(key);
  if (fromCookie) return fromCookie;

  const envUserId = process.env.DEV_USER_ID;
  if (envUserId) {
    return {
      key: "env",
      userId: envUserId,
      roles: process.env.DEV_USER_ROLES ?? "",
      label: "環境変数の既定ユーザー",
    };
  }
  return null;
}

export function hasRole(user: DevUser | null, role: string): boolean {
  if (!user) return false;
  return user.roles
    .split(",")
    .map((r) => r.trim())
    .includes(role);
}
