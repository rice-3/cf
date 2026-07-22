import { expect, test } from "@playwright/test";
import { loginAs } from "./helpers";

/**
 * 運用コンソールの一覧・検索UI（SCR-060 支援管理 / SCR-061 返金管理）。
 * 検索API（API-FD-004 / API-RF-003）とタブ・検索フォームの動作を検証する。
 */
test.describe("運用コンソール", () => {
  test("支援管理・返金管理タブと検索フォームが動作する", async ({ page }) => {
    await loginAs(page, "admin");

    await page.goto("/operations");
    await expect(page.getByRole("heading", { name: "運用コンソール" })).toBeVisible();

    // 支援管理タブ（既定）
    await expect(page.getByRole("link", { name: /支援管理/ })).toBeVisible();
    await expect(page.locator("#status")).toBeVisible();
    await expect(page.locator("#projectId")).toBeVisible();

    // 状態で検索（結果0件でもエラーにならずUIが保たれる）
    await page.selectOption("#status", "PAID");
    await page.getByRole("button", { name: "検索" }).click();
    await expect(page).toHaveURL(/tab=supports/);
    await expect(page.getByRole("heading", { name: "運用コンソール" })).toBeVisible();

    // 返金管理タブへ切替（projectId欄は消え、状態は返金状態になる）
    await page.getByRole("link", { name: /返金管理/ }).click();
    await expect(page).toHaveURL(/tab=refunds/);
    await expect(page.locator("#projectId")).toHaveCount(0);
    await expect(page.locator("#status")).toBeVisible();
  });
});
