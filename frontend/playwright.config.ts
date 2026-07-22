import { defineConfig, devices } from "@playwright/test";

/**
 * E2Eテスト設定（詳細設計 §14.4）。
 *
 * 前提: バックエンド（local プロファイル）+ PostgreSQL が http://localhost:8080 で稼働していること。
 *   docker compose -f infra/docker-compose.yml up -d postgres
 *   (backend) ./gradlew bootRun --args='--spring.profiles.active=local'
 * フロントエンドは本設定の webServer が起動する（`npm run start`、事前に `npm run build` が必要）。
 *
 * 実行:  npm run test:e2e
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false, // 起案→審査の状態遷移を共有DB上で行うため直列実行する
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "on-first-retry",
    locale: "ja-JP",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "npm run start",
    url: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      BACKEND_URL: process.env.BACKEND_URL ?? "http://localhost:8080",
    },
  },
});
