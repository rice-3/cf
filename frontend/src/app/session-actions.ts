"use server";

// 開発用セッション（ロール切替）のServer Actions。
// 本番はCognito OIDCのログイン/ログアウトに置き換わる（基本設計 §10.1）。
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { DEV_SESSION_COOKIE, findDevUser } from "@/lib/devSession";

export async function loginAsDevUser(formData: FormData) {
  const key = String(formData.get("devUser") ?? "");
  const user = findDevUser(key);
  if (!user) {
    redirect("/login?error=invalid");
  }
  const store = await cookies();
  // HttpOnly でブラウザJSから参照させない（§7.9）。localのみで使う開発用識別子。
  store.set(DEV_SESSION_COOKIE, key, {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    maxAge: 60 * 60 * 8, // Absolute 8時間（§10.1 セッション方針に合わせる）
  });
  redirect("/");
}

export async function logout() {
  const store = await cookies();
  store.delete(DEV_SESSION_COOKIE);
  redirect("/login");
}
