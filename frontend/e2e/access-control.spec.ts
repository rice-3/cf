import { expect, test } from "@playwright/test";
import { loginAs, logout } from "./helpers";

/**
 * ロール別アクセス制御（SCR-002、基本設計 §2.3）。
 * BFFのロール判定とバックエンドの認可（403）がUIへ反映されることを検証する。
 */
test.describe("ロール別アクセス制御", () => {
  test("起案者ログインで起案者ナビが表示される", async ({ page }) => {
    await loginAs(page, "owner");
    await expect(page.locator(".site-header")).toContainText("起案者");
    await page.goto("/owner/projects");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await logout(page);
  });

  test("管理者ログインで運用・会員管理・監査ログのナビが表示される", async ({ page }) => {
    await loginAs(page, "admin");
    const header = page.locator(".site-header");
    await expect(header).toContainText("運用");
    await expect(header).toContainText("会員管理");
    await expect(header).toContainText("監査ログ");
    await logout(page);
  });

  test("支援者は運用コンソールにアクセスできない", async ({ page }) => {
    await loginAs(page, "supporter");
    await page.goto("/operations");
    // OperationsPage は 403 を受けてアクセス拒否メッセージを表示する
    await expect(page.getByText("OPERATOR / ADMIN")).toBeVisible();
    await logout(page);
  });
});
