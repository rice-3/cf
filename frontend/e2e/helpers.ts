import { expect, type Page } from "@playwright/test";

/** DevUserSeeder / devSession と一致する開発用ユーザーキー。 */
export type DevUserKey = "owner" | "reviewer" | "admin" | "supporter";

/**
 * SCR-001 開発用ログイン。ユーザーを選択してログインし、トップへ遷移する。
 * 認証Cookieは HttpOnly のためサーバーアクション経由（UI操作）でのみ設定できる。
 */
export async function loginAs(page: Page, key: DevUserKey): Promise<void> {
  await page.goto("/login");
  await page.selectOption("#devUser", key);
  await page.getByRole("button", { name: "ログイン" }).click();
  await page.waitForURL("**/"); // redirect("/")
}

/** 現在のセッションをログアウトする。 */
export async function logout(page: Page): Promise<void> {
  await page.getByRole("button", { name: "ログアウト" }).click();
  await page.waitForURL("**/login");
}

/** 一意なプロジェクトタイトル（実行間の衝突を避ける）。 */
export function uniqueTitle(prefix = "E2Eプロジェクト"): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`;
}

/** テスト用の最小PNG（1x1）。メイン画像アップロードの setInputFiles に用いる。 */
export function tinyPngBuffer(): Buffer {
  const base64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
  return Buffer.from(base64, "base64");
}

/** ヘッダーに現在ユーザーのラベルが表示されるまで待つ。 */
export async function expectLoggedIn(page: Page, label: string): Promise<void> {
  await expect(page.locator(".site-header")).toContainText(label);
}
