import { expect, test } from "@playwright/test";

/**
 * 公開画面（未ログイン）の表示（SCR-010 検索 / SCR-001 ログイン）。
 */
test.describe("公開画面", () => {
  test("プロジェクト検索ページが表示される", async ({ page }) => {
    await page.goto("/projects");
    // 検索画面（SCR-010）の見出しと検索フォームが表示される
    await expect(page.getByRole("heading", { name: "プロジェクト検索" })).toBeVisible();
    await expect(page.getByRole("search")).toBeVisible();
    // ナビのブランド/検索リンクは常時表示される
    await expect(page.locator(".site-header")).toContainText("プロジェクト検索");
  });

  test("ログイン画面に開発用ユーザー選択が表示される", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "ログイン" })).toBeVisible();
    await expect(page.locator("#devUser")).toBeVisible();
    // DevUserSeeder の4ロールが選択肢にある
    await expect(page.locator("#devUser option")).toHaveCount(4);
  });
});
