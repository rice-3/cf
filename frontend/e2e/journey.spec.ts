import { expect, test } from "@playwright/test";
import { loginAs, logout, tinyPngBuffer, uniqueTitle } from "./helpers";

/**
 * 主要ユーザーストーリーのE2E（詳細設計 §14.4）: 起案 → 審査申請 → 審査承認。
 *
 * ブラウザ操作で「OWNERがプロジェクトを起案・審査申請」→「REVIEWERが審査開始・承認」までを検証する。
 * これ以降（公開→支援→決済→返金）はバッチ起動・決済Webhookに依存するため、
 * バックエンド結合テスト（BatchFlowIntegrationTest / PaymentWebhookFlowIntegrationTest /
 * OperationsApiIntegrationTest）で網羅する。E2Eは画面操作で到達できる状態遷移を対象とする。
 */
test.describe("起案→審査承認ジャーニー", () => {
  test("OWNERが起案・審査申請し、REVIEWERが承認する", async ({ page }) => {
    const title = uniqueTitle();

    // --- 起案（OWNER, SCR-021/023） ---
    await loginAs(page, "owner");
    await page.goto("/owner/projects/new");

    await page.fill("#title", title);
    await page.fill("#summary", "E2Eテスト用の合成プロジェクトです。");
    await page.fill("#body", "本文テキスト。教育用の合成データで、実際の資金移動は行いません。");
    await page.fill("#targetAmount", "500000");
    await page.selectOption("#fundingType", "ALL_IN");
    await page.fill("#startAt", "2027-01-01T00:00");
    await page.fill("#endAt", "2027-02-01T00:00");

    // メイン画像（local/testのS3スタブは発行時点で完了扱い）
    await page.setInputFiles("#mainFile", {
      name: "main.png",
      mimeType: "image/png",
      buffer: tinyPngBuffer(),
    });
    await expect(page.locator("#mainFile-status")).toContainText("アップロード済み");

    // リターンを1件追加
    await page.getByRole("button", { name: "リターンを追加" }).click();
    await page.fill("#rewardPlans\\.0\\.name", "お礼メール");
    await page.fill("#rewardPlans\\.0\\.description", "感謝のメールをお送りします。");
    await page.fill("#rewardPlans\\.0\\.unitAmount", "3000");

    // 下書き保存 → 編集ページへ遷移
    await page.getByRole("button", { name: "下書き保存" }).click();
    await page.waitForURL(/\/owner\/projects\/[^/]+\/edit$/);

    // 審査申請（SCR-023、確認事項2件必須）
    await page.getByRole("link", { name: "審査申請へ" }).click();
    await page.waitForURL(/\/submit-review$/);
    for (const checkbox of await page.locator('input[type="checkbox"]').all()) {
      await checkbox.check();
    }
    await page.getByRole("button", { name: "審査申請を送信する" }).click();
    await page.waitForURL(/\/owner\/projects$/);
    await logout(page);

    // --- 審査（REVIEWER, SCR-030/031） ---
    await loginAs(page, "reviewer");
    await page.goto("/reviews?status=REQUESTED");

    const row = page.locator("tr", { hasText: title });
    await expect(row).toBeVisible();
    await row.getByRole("link", { name: "審査する" }).click();
    await page.waitForURL(/\/reviews\/[^/]+$/);

    // 審査開始 → チェックリスト全項目 → 承認
    await page.getByRole("button", { name: "審査を開始する" }).click();
    await expect(page.getByRole("heading", { name: "承認" })).toBeVisible();
    for (const checkbox of await page.locator('input[type="checkbox"]').all()) {
      await checkbox.check();
    }
    await page.getByRole("button", { name: "承認する" }).click();

    // 承認後は完了状態（APPROVED）になり、追加操作ができない旨が表示される
    await expect(page.getByText(/APPROVED/)).toBeVisible();
    await logout(page);
  });
});
