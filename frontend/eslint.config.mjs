// ESLint flat config（Next.js 16 / ESLint 10）。
//
// Next 16 で `next lint` コマンドが廃止されたため、ESLint を直接起動する。
//
// **`eslint-config-next` は使わない。** 同パッケージが同梱する eslint-plugin-react は
// peerDependencies が `eslint ^9.7` までで ESLint 10 では実行時に落ちる。ESLint 9 へ下げると
// 今度は ESLint 本体が脆弱な minimatch@3 を引き込み、修正可能な HIGH 脆弱性が9件増えて
// security-scan.yml の Trivy ゲート（`--severity HIGH,CRITICAL --ignore-unfixed`）に当たる。
// そのため ESLint 10 で動く plugin だけで構成する。詳細は docs/ses_ai_ddd_remaining_tasks.md §4.2。
//
// 取りこぼす観点（eslint-config-next なら入っていたもの）:
//   - eslint-plugin-jsx-a11y（アクセシビリティ）
//   - eslint-plugin-import（import順序・解決）
//   - eslint-plugin-react（react/* のうち hooks 以外）
// eslint-plugin-react が ESLint 10 に対応したら eslint-config-next へ戻すこと。
import nextPlugin from "@next/eslint-plugin-next";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

const config = [
  {
    ignores: [
      ".next/**",
      "out/**",
      "node_modules/**",
      "playwright-report/**",
      "test-results/**",
      // OpenAPI spec から自動生成する（`npm run gen:api-types`）。手で直さないので対象外。
      "src/lib/generated/**",
    ],
  },

  // TypeScript の推奨ルール（型情報なしの軽量セット。CIのtypecheckが型は見る）
  ...tseslint.configs.recommended,

  // Next.js 固有ルール（next/core-web-vitals 相当）
  {
    plugins: { "@next/next": nextPlugin },
    rules: {
      ...nextPlugin.configs.recommended.rules,
      ...nextPlugin.configs["core-web-vitals"].rules,
    },
  },

  // React Hooks（rules-of-hooks / exhaustive-deps ほか）。
  // `configs["recommended-latest"]` は eslintrc 形式なので、flat 版を使う。
  reactHooks.configs.flat["recommended-latest"],

  {
    // Playwright の E2E は Node 実行でブラウザ向けの制約が異なる
    files: ["e2e/**/*.ts"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
];

export default config;
