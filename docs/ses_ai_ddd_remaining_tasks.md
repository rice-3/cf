# CF-Training 残タスク一覧

- 対象リポジトリ: `F:\11\CF`（GitHub: `https://github.com/rice-3/cf.git`）
- 上位文書: 基本設計 BD-CF-001 v1.2 / 詳細設計 DD-CF-001 v1.2（`G:\マイドライブ\CF\`）
- 更新日: 2026-07-27（早見表を **AWS要否**で再編。Flyway接続分離のTerraform配線を完了、ローカル検証環境を整備）
- 実装済み範囲の詳細は `ses_ai_ddd_implementation_status.md` を参照。
- 本書は**残タスク**を主役とする。完了済みは §5 に要約のみ記載。

---

## 1. サマリ

| # | 工程（詳細設計 §16.2） | 状態 |
|---|---|---|
| 1〜9 | Shared Kernel 〜 Identity/Admin/Audit（バックエンド全機能） | ✅ 完了 |
| — | フロントエンド 全19画面（基本設計 §5.2） | ✅ 完了 |
| 10 | CI/CD・スキャン・IaC（コア）・メトリクス公開・E2E・運用手順書・AWS構築手順書 | ✅ 完了 |
| 10 | 監視アラート実配線・IaC実apply・SES登録 | ⬜ **残タスクの中心（AWS依存）** |

- バックエンドの業務API（API-PJ/RV/FL/FD/PY/RF/US/AD/AU）は全系列実装済み。
- 業務フローは「起案 → 審査 → 公開 → 支援 → 決済 → 募集終了 → 返金 → 通知」まで一気通貫で動作。
- CI/CD 一式（ビルド/テスト/SAST/依存・コンテナscan/OpenAPI互換/Terraform検証/CD雛形）は構築・ゲート化済み。
- 監視メトリクス（Micrometer → `/actuator/prometheus`、ビジネス滞留/バッチ稼働/APIレイテンシ）と
  アラート閾値定義（`docs/ops/monitoring.md`）は完了。残るは監視基盤への実配線（§2.1）。
- E2E（Playwright）で「起案→審査承認」ジャーニー・ロール別アクセス制御・運用コンソールを検証（`e2e.yml`）。
- 運用手順書（`docs/ops/runbook.md`）、AWS契約・構築手順書（`docs/ops/aws-contract-build-runbook.md`）を整備。
- AWS契約〜CD稼働までの手順を文書化した際のコード読解で、apply してもECSタスクが起動しない設定不整合を3件検出し、
  **修正済み（§2.2、ローカルで fmt/validate 通過を確認）**。
- **残るのはほぼAWS依存の運用基盤（監視アラートの実配線・実apply・SES登録）** と、少数の要判断事項・軽微なフォローアップ。
- **進め方**: AWS契約・apply はローカルテスト完了後。それまでは早見表 A（AWS不要）のみを進める。
  中でも §2.3（タスク定義とアプリ設定の突き合わせ）は、apply 時の手戻りと無駄な課金を最も減らす。

### 残タスク早見表

AWS要否で3分割する（A → B の順に進める）。

#### A. AWS不要 — ローカルで着手できる（当面の作業対象）

| 優先 | 区分 | タスク | 節 |
|---|---|---|---|
| 高 | IaC/検証 | **タスク定義 ⇄ アプリ設定の突き合わせ** — `validate` で検出できない実行時不整合の洗い出し（§2.2 で3件踏んだ類） | 2.3 |
| 中 | セキュリティ | 要判断G: GitHub OIDC の信頼条件を `repo:<owner>/<repo>:*`（全ブランチ）から `main` 等へ限定 | 6-G |
| 中 | 認証 | 要判断C: 未登録Cognito SubjectのJIT自動登録の可否。分岐実装とテストはローカルで完結 | 6-C |
| 中 | 設計/実装 | 要判断B: Outbox配送のSQS切替。ADR起票＋実装（ローカル検証はスタブ/LocalStack） | 6-B |
| 中 | 実装 | メイン画像のブラウザ直PUT（現状 local/test はS3スタブで実PUTなし。dev以上で必要） | 5.4 |
| 低 | 運用判断 | production の `enable_ecs_exec` 既定値（常時 `false` にして必要時のみ有効化するか） | 2.1 |

#### B. AWS必須 — 認証情報・実アカウントが要る（ローカルテスト完了後）

| 優先 | 区分 | タスク | 節 |
|---|---|---|---|
| 高 | IaC | 実AWSでの `apply`・疎通確認（state置き場の手動作成、ECR先行applyを含む） | 2.1 |
| 高 | 監視 | メトリクスパイプライン構成（ADOT/CW Agent サイドカー）＋実apply | 2.1 |
| 高 | DB | 接続分離の**切り替え**: `cf_app_login` 作成 → Secret値投入 → `ecs.tf` 2行変更 → apply | 2.1 |
| 中 | 通知 | SESテンプレート実登録・サンドボックス解除申請 | 3.1 |
| 中 | 判断 | 要判断A: 監査アーカイブの実出力先（S3バケット/ストレージクラス/保持年数） | 6-A |
| 中 | 判断 | 要判断E: Cognito実User Poolでの結合確認 | 6-E |

#### C. 外部待ち — こちらから動かせない

| 区分 | 内容 | 節 |
|---|---|---|
| 予算 | 要判断F: dev環境の稼働モードとコスト構成（予算責任者の決定） | 6-F |
| 上流 | CodeQL の Kotlin 2.4 対応（現状 Semgrep で代替中） | 4.1 |

> 完了済みで早見表から落としたもの: apply前の必須修正3件（§2.2）、Flyway接続分離のTerraform配線（§2.1）、
> ECS Exec による保守経路（§2.1）、contract-first型生成/swagger-ui・Java整形・設計書docx再出力（§4.1、いずれも §5.3）。
> 要判断D は ADR-0004/0005/0006 として起票済み。

---

## 2. 残タスク（優先度: 高）

### 2.1 IaC — 未カバーのAWSリソースと監視の実配線（Terraform、ADR-007）

- [x] **未カバーのAWSリソースの Terraform コード化** — ACM+HTTPS(`acm.tf`/`alb.tf`) / S3ファイルバケット(`s3.tf`) /
      SQS+DLQ(`sqs.tf`) / SES ドメイン検証・DKIM(`ses.tf`) / Cognito User Pool・Client(`cognito.tf`) /
      WAF(`waf.tf`) / VPCエンドポイント(`vpc_endpoints.tf`) を追加し、IAM/ECS環境変数へ配線。
      `terraform fmt -check` / `init` / `validate` 済み（provider aws v5.100）。ドメイン/SESはvarでゲート。
- [ ] **実AWSでの apply と疎通確認** — AWS認証情報が必要なため未実施。**手順は
      `docs/ops/aws-contract-build-runbook.md` に確定済み**（契約 → Budgets → Identity Center →
      state用S3/DynamoDB作成 → tfvars → apply → GitHub Variables → CD → 疎通確認 → destroy）。
      前提として §2.2 の必須修正3件を先に適用すること。
  - [ ] state置き場（S3 `cftraining-tfstate-<AccountID>` + DynamoDB `cftraining-tflock`）はTerraform管理外のため
        手動作成（手順書 §10）。
  - [ ] 初回は `-target=aws_ecr_repository.backend` で先にECRを作り `:bootstrap` タグを push してから全体applyする
        （初期タスク定義が存在しないイメージを参照するため。手順書 §13.2）。
  - [ ] `apply` 後の手作業: Secrets Manager の決済Webhookキー値投入 / Cognito App Client Secret のフロント設定 /
        GitHub Variables 7件 / ACM・SES の DNS レコード / SNS購読承認（手順書 §14）。
- [x] **Private配置RDSへの保守経路（ECS Exec）** — 2026-07-26 対応。`enable_ecs_exec`（既定 `true`）で
      `aws_ecs_service.enable_execute_command` + タスクロールへ `ssmmessages` 4アクション、
      セッションログ用の権限を付与。**セッション内容は `/ecs/<prefix>-exec`（保持365日）へ記録**する
      （クラスタの `execute_command_configuration` を `OVERRIDE`。手動操作の証跡、要件C-17）。
      実行イメージ（`amazoncorretto:25`）に psql は含まれないため、**SSMポートフォワードでローカルの psql を
      RDSへ繋ぐ**方式とした（手順書 §14.5 に手順。Session Manager plugin が必要）。
  - [ ] production で `enable_ecs_exec = false` とするか（必要時のみ一時有効化）の運用判断。
- [x] **アプリDBユーザーのプロビジョニング（最小権限）** — Flyway移行
      `V202607230001__create_app_runtime_role.sql`（`cf_app_rw`: DMLのみ + 将来テーブル自動付与、冪等）と
      ブートストラップSQL `infra/db/create-app-user.sql`（ログインユーザー、資格情報はGit外）を追加。
      ローカルDBで検証済み（SELECT/DML可・DDL拒否・将来テーブル自動SELECT可）、Testcontainersビルドも通過。
  - [x] 接続分離の**Terraform側配線**（2026-07-27）— Secretの器 `aws_secretsmanager_secret.app_login`
        （`<prefix>/app-login-password`、値はTerraform管理外）、実行ロールへの参照権限、
        `SPRING_FLYWAY_USER`（=`db_username`）/ `SPRING_FLYWAY_PASSWORD`（RDSマスターの`password`）注入、
        output `app_login_secret_id` を追加。`fmt -check` / `validate` 通過。
        現状は `SPRING_DATASOURCE_*` もオーナーを指すため挙動は分離前と同じ（無害）。
  - [ ] **残（apply後・AWS必須）**: ①`cf_app_login` ロール作成（`infra/db/create-app-user.sql` を
        SSMポートフォワード経由で実行）②生成パスワードを `app_login_secret_id` の Secret へ投入
        ③`ecs.tf` の `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` を2行切り替えて apply。
        **順序厳守**（ロール不在で切り替えると起動不能）。手順は `infra/terraform/README.md`。
- [x] **CloudWatch Alarm / ダッシュボードの Terraform 化** — `monitoring.tf` に SNSトピック（`alert_email` 購読）、
      インフラアラーム（ALB 5xx・p95レイテンシ / ECS CPU・メモリ / RDS CPU・空き容量・接続数）、
      ビジネス/バッチアラーム（Outbox滞留・通知失敗・返金失敗/再試行待ち・バッチ最終成功経過、
      閾値は `docs/ops/monitoring.md` 準拠）、CloudWatchダッシュボードを定義。`validate` 済み。
  - [ ] **メトリクスパイプラインの構成（apply時）** — ビジネス/バッチメトリクス（`var.metrics_namespace`）は
        `/actuator/prometheus` を CloudWatch Agent(Prometheus) / ADOT Collector で収集し CloudWatch へ発行する
        構成が前提（ECSサイドカー等）。インフラアラームは apply 後すぐ有効。実 apply とメール購読確認は AWS 必須。
        推奨構成は ADOT Collector のサイドカー（Prometheus receiver → `awsemf` exporter、タスクロールへ
        `cloudwatch:PutMetricData` 追加）。詳細は手順書 §17。

### 2.2 apply前の設定不整合（3件）— **対応済み 2026-07-26**

`docs/ops/aws-contract-build-runbook.md` 作成時のコード読解で検出。未修正のまま apply すると
タスク起動失敗のループで NAT / Fargate / ALB の課金だけが発生する状態だった。3件とも修正済み。

- [x] **(1) DataSource の環境変数名が Spring と不一致（起動不能）** — `ecs.tf` の
      `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` を Relaxed Binding に従う
      `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`（secrets）へ改名。
      `application-dev.yml` は追加せず Terraform 側だけで閉じた。
  - [x] 接続分離用の `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` 注入（2026-07-27、§2.1 参照）。
        実行時側の `cf_app_login` への切り替えは apply 後の作業として §2.1 に残す。
- [x] **(2) SES送信元アドレスが未注入** — `local.ses_from_address` を `CF_SES_FROM_ADDRESS` として注入。
      優先順位は `var.ses_from_address` → `no-reply@${var.ses_domain}` → `no-reply@example.invalid`(アプリ既定)。
      変数 `ses_from_address` を追加（`variables.tf` / `terraform.tfvars.example` / README 更新済み）。
      **`ses_domain` / `ses_from_address` のいずれも空だと従来どおり送信失敗する**点は仕様として明記。
- [x] **(3) ヘルスチェック猶予が未設定** — `aws_ecs_service.backend` に
      `health_check_grace_period_seconds = var.health_check_grace_period_seconds`（既定 `180`）を追加。

> 3件とも `terraform fmt` / `validate` は通るため CI では検出できなかった（**実行時の不整合**）。
> ローカル検証は 2026-07-27 に実施済み（Terraform 1.15.8 導入後、`fmt -check -recursive` / `init -backend=false` /
> `validate` すべて通過）。ただしこれは構文・型が正しいことしか示さない。同種の不整合は §2.3 で洗い出す。

### 2.3 タスク定義 ⇄ アプリ設定の突き合わせ（AWS不要・未実施）

§2.2 の3件は「`validate` は通るが apply するとタスクが起動しない」類で、CIでは原理的に検出できない。
同じ穴が他に残っていないかを、**AWS に触れずコード読解で**洗い出す。apply 前に済ませておく価値が最も高い。

- [ ] `ecs.tf` の `environment` / `secrets` と、アプリが実際に読む設定キーの全件突き合わせ
      （`application.yml` / `application-{profile}.yml` / `@Value` / `@ConfigurationProperties`）。
      Relaxed Binding で束縛されるか、既定値に落ちて無言で動くだけになっていないかを確認する。
- [ ] `dev` プロファイルで**未定義のまま参照している設定**がないか（local/test にしか無い定義への依存）。
- [ ] Flyway 実行順とアプリ起動の関係（`V202607230001` の `cf_app_rw` 作成が実行時ロールより先か）。
- [ ] バッチ／ShedLock／Outbox がマルチインスタンス（`desired_count` > 1）で破綻しないか（要判断B と関連）。
- [ ] ヘルスチェック経路（ALB ターゲットグループのパス）と `/actuator/health` の露出設定の整合。

> 突き合わせ結果は §7 の「実装時の注意点」へ追記し、再発防止の観点として残す。

---

## 3. 残タスク（優先度: 中）

### 3.1 SESテンプレートのAWS登録

- [ ] 本文は `NotificationTemplateCatalog` を正として定義済み。`aws_sesv2_email_template` またはCLIで
      実登録する（テンプレートIDはカタログのキー）。§2.1 の SES ドメイン検証と併せて実施。
- [ ] **SESサンドボックス解除の申請** — 初期状態は検証済みアドレス宛にしか送信できない。任意宛先へ送るには
      Production access を申請する（承認まで最大24時間）。申請しない運用なら、テスト用受信アドレスを
      個別に検証する（手順書 §14.4）。

---

## 4. 残タスク（優先度: 低 / フォローアップ）

### 4.1 CI・文書の軽微なフォローアップ

- [ ] **CodeQL Kotlin対応** — CodeQLがKotlin 2.4対応後、`codeql.yml` へ java-kotlin を追加
      （現状Semgrepで代替中。Semgrepと併用 or 置換を判断）。※上流（CodeQL）待ち。
- [x] **contract-first DTO自動生成 / swagger-ui**（§6.15）— 完了（§5.3）。
      swagger-ui（`/swagger-ui.html`）導入、フロント型を spec から生成（`npm run gen:api-types`）+ CI鮮度ゲート。
- [x] **Java整形** — 完了（§5.3）。google/palantir は JDK 25 で javac 内部API非互換のため、
      JDK非依存の Eclipse JDT フォーマッタを Spotless に採用（コメントは保全しコードのみ整形）。
- [x] **設計書 `.docx` の再出力** — 完了。`G:\マイドライブ\CF` の md から pandoc で再生成
      （原本の書式を `--reference-doc` で継承、旧版は `*.v1.0.docx` として退避）。
      なお md の版数表は両書とも「1.0」で、当初の「v1.2」記載は事実誤りだったため docx と内容を同期した。

---

## 5. 完了済みの要約（詳細は `ses_ai_ddd_implementation_status.md`）

### 5.1 バックエンド（工程1〜9）

- DDD/ヘキサゴナル/モジュラーモノリス。Project / Review / Funding / Payment / Notification /
  File / Identity / Audit の各コンテキスト。ArchUnitで境界を強制。
- API全系列、RFC 9457 Problem Details、Transactional Outbox、冪等制御、楽観ロック。
- バッチ BAT-001〜010（公開/募集終了/返金対象作成/返金実行/通知/Outbox配送/決済照合/
  ファイル清掃/監査アーカイブ/冪等記録削除）。
- 工程8の残タスク3件を完了:
  - 起案者向け通知の宛先解決（**ADR-0002**: イベントに`ownerUserId`追加、起案者向けテンプレート6種購読）
  - SESテンプレート本文を `NotificationTemplateCatalog` に一元定義（Mockがレンダリング、SES登録はカタログを正）
  - 冪等記録削除バッチ **BAT-010**
- バッチ多重起動防止（**ADR-0003**: ShedLock。BAT-006 Outboxは競合コンシューマ設計のため除外）。
- 工程9: API-US/AD/AU、Cognito JWT変換（`CognitoJwtAuthenticationConverter`）。

### 5.2 フロントエンド（Next.js 16、全19画面）

| 区分 | 画面 |
|---|---|
| 公開 | SCR-010 検索 / SCR-011 詳細 |
| OWNER | SCR-020 一覧 / SCR-021 編集 / SCR-022 プレビュー / SCR-023 審査申請確認 |
| REVIEWER | SCR-030 審査一覧 / SCR-031 審査詳細 |
| SUPPORTER | SCR-040 支援入力 / SCR-041 確認 / SCR-042 結果 / SCR-051 支援履歴 |
| OPERATOR | SCR-060 支援管理 / SCR-061 返金管理 |
| ADMIN | SCR-070 会員・ロール管理 / SCR-071 監査ログ検索 |
| 共通 | SCR-001 ログイン / SCR-002 アクセス拒否 / SCR-050 マイページ / SCR-080 システムエラー |

- 開発用ログイン（SCR-001）は HttpOnly Cookie でロール切替。BFFが `X-Dev-User`/`X-Dev-Roles` へ変換
  （§7.9。本番はCognito OIDCへ置換）。
- 型/定数は `lib/api-types.ts`、`next/headers`依存の `backendFetch` は `lib/backend.ts`（server-only）に分離。
- 実機（Docker+backend+frontend）で全ロール画面の表示・認可・主要フローを確認済み。

### 5.3 CI/CD・スキャン・IaC（工程10、構築済み）

- **CI** — `ci.yml`: backend（Wrapper検証 → Corretto 25 → `gradlew build` = compile/unit/ArchUnit/
  Testcontainers統合）、frontend（Node 24 → typecheck → build）、secret-scan（gitleaks）。
- **format** — Spotless + ktlint 1.5.0（`spotlessCheck` を `build` に自動組込み）。
- **SAST** — `codeql.yml`（JS/TS）+ `semgrep.yml`（JVM側 Kotlin/Java。CodeQLがKotlin 2.4未対応のため代替）。
- **依存/ライセンス/コンテナscan** — `security-scan.yml`（Trivy fs + image。`backend/Dockerfile` はマルチ
  ステージ/Corretto 25/非root）。ゲート化済み（Semgrep `--error` / Trivy fs は修正可能なHIGH,CRITICALで失敗、
  コンテナは非ブロッキング・レポート方式）。
- **OpenAPI** — springdoc 3.0.3 でコードから spec 生成 → `docs/api/openapi.yaml` にコミット。鮮度ゲート
  （`OpenApiSpecIntegrationTest`）+ 互換ゲート（`openapi.yml` の oasdiff、破壊的変更で失敗）。
  **Swagger UI**（`springdoc-openapi-starter-webmvc-ui`）を `/swagger-ui.html` で提供（本番は
  `springdoc.swagger-ui.enabled=false` で無効化可）。**contract-first 型生成**: `openapi-typescript` で
  spec からフロント型を生成（`npm run gen:api-types` → `frontend/src/lib/generated/api.ts`）、
  CIで再生成差分ゲート（`ci.yml`）。
- **コード整形（Java）** — Spotless に Eclipse JDT フォーマッタ（`spotless-java.properties`）を追加。
  google/palantir は JDK 25 で javac 内部API非互換のため不採用。コメントは保全しコードのみ整形。
- **IaC** — `infra/terraform/`。コア（VPC / サブネット / NAT / SG / ECR / ALB / ECS Fargate /
  RDS PostgreSQL 18 / IAM(タスク実行・タスク) / GitHub OIDC + デプロイロール / Secrets Manager /
  CloudWatch Logs）に加え、**未カバーリソースをコード化**: ACM+HTTPS / S3ファイルバケット /
  SQS+DLQ / SESドメイン・DKIM / Cognito User Pool・Client / WAF / VPCエンドポイント。IAM・ECS環境変数へ配線。
  `terraform.yml` で fmt / init / validate（provider aws v5.100）。ドメイン/SESはvarでゲート。
  `apply` はAWS認証が必要なため手動運用（残 §2.1、`infra/terraform/README.md`）。
- **ECS Exec（保守経路）** — `enable_ecs_exec`（既定true）でサービスの `enable_execute_command` +
  タスクロールへ `ssmmessages` 4アクション + セッションログ権限。クラスタの `execute_command_configuration` を
  `OVERRIDE` にして `/ecs/<prefix>-exec`（365日）へセッションを記録（要件C-17）。
  Private配置RDSへはSSMポートフォワード経由でローカルの psql を使う（イメージに psql 非同梱のため）。
- **最小権限DBユーザー** — Flyway移行 `V202607230001__create_app_runtime_role.sql`（`cf_app_rw`: DMLのみ・
  将来テーブル自動付与）+ ブートストラップSQL `infra/db/create-app-user.sql`（ログインユーザー）。
  実行時接続を最小権限ユーザー、移行をオーナーに分離する方針（本番はdev以上で適用）。ローカルDBで検証済み。
- **CD** — `cd.yml`（手動 `workflow_dispatch`、環境承認付き）。image build → ECR push → ECS ローリング更新
  （OIDC）。実AWS未提供のため apply/deploy 検証は未実施（残: §2.1 のリソース整備後）。
- **監視メトリクス** — Micrometer + `/actuator/prometheus`（`micrometer-registry-prometheus`）。
  ビジネス滞留（`BusinessMetrics`: outbox/notification/refund の未処理・失敗ゲージ）、
  バッチ稼働（`BatchMetrics`: `cf_batch_last_success_age_seconds` / `cf_batch_runs_total`）、
  通知送信レート（`cf_notification_delivery_total`）、API レイテンシ/5xx（`http.server.requests` ヒストグラム）。
  全メーターに `application` 共通タグ（`ObservabilityConfig`）。アラート閾値は `docs/ops/monitoring.md`。
  結合テスト `MetricsIntegrationTest` で公開を検証。
- **監視アラート/ダッシュボード（IaC）** — `infra/terraform/monitoring.tf`。SNSトピック（メール購読）+
  インフラアラーム（ALB 5xx・p95 / ECS CPU・メモリ / RDS CPU・空き容量・接続数）+ ビジネス/バッチアラーム
  （Outbox/通知/返金/バッチ最終成功経過、閾値は monitoring.md 準拠）+ CloudWatchダッシュボード。`validate` 済。
  ビジネス/バッチ系は Prometheus→CloudWatch パイプライン（`var.metrics_namespace`）の apply 時構成が前提。
- **E2E（Playwright）** — `frontend/e2e/`（`playwright.config.ts`）。ブラウザで「起案→審査承認」ジャーニー、
  ロール別アクセス制御、公開画面、運用コンソール一覧・検索を検証。`e2e.yml` で PostgreSQL(サービス) +
  backend(local, `java -jar`) + frontend(`next start`) を起動して実行。ローカルは
  `docker compose up -d postgres` + `bootRun` + `npm run test:e2e`。公開→支援→決済→返金は
  バッチ・Webhook依存のためバックエンド結合テストで網羅（E2Eは画面到達可能な状態遷移を対象）。
- **運用手順書** — `docs/ops/runbook.md`。バッチ運用（再実行方針）、決済照合、返金の手動対応、
  Outbox滞留・通知失敗、障害切り分け（相関ID/監査ログ/メトリクス）、アラート→一次対応表、
  デプロイ/ロールバックを記載。本番手動変更は監査対象（要件C-17）である旨を明記。
- **AWS契約・構築手順書** — `docs/ops/aws-contract-build-runbook.md`（2026-07-26）。アカウント契約・ルート保護・
  Budgets・IAM Identity Center から、state置き場の手動作成、`terraform.tfvars`、段階apply、apply後の手作業
  （Secrets値/Cognito Client Secret/GitHub Variables/DNS/DBユーザー/SNS購読）、CD実行、疎通確認、破棄までを
  本リポジトリの実定義に沿って記述。あわせて **費用概算（dev常時稼働で月4.3〜5.0万円。支配的なのは
  Interface VPCエンドポイント12 ENIとNAT Gatewayで全体の約6割）** と削減オプション、destroy時の落とし穴
  （S3バージョン残存・ECRイメージ残存・Secrets Managerの30日復旧猶予による名前衝突）を整理。
  本書作成時の読解で §2.2 の不整合3件を検出。

### 5.4 既知の暫定実装（要フォロー）

- **SCR-060/061（OPERATOR）**: 運用者向け検索API（支援検索/返金検索）を追加し、一覧・検索UIへ移行済み。
- **メイン画像アップロード**: local/testはS3スタブ（発行時点で完了扱い）のため実PUTを行わない。
  dev以上の実S3接続時はブラウザからの直接PUT追加が必要。

### 5.5 その他

- [x] git初回コミット・push済み（`main`）。CI 全ワークフロー緑を維持。

---

## 6. 未確定・要判断事項（人間の決定待ち）

| # | 内容 | 影響・対応 |
|---|---|---|
| A | 監査アーカイブ（BAT-009）の実出力先（S3バケット・ストレージクラス・保持年数） | 現状はハッシュ算出のみのローカル実装。`LocalAuditArchiveAdapter` に `TODO(question)`。§2.1 Terraformと併せて確定 |
| B | Outbox配送のSQS切替（現状 `InProcessOutboxDispatcher` のアプリ内配送） | マルチインスタンス構成時に必要。ADR候補（§2.1と関連） |
| C | 未登録Cognito Subjectの初回JIT自動登録（既定ロールSUPPORTER）の可否 | `CognitoJwtAuthenticationConverter` に `TODO(question)`。許容しない場合は管理者Invite方式へ変更。dev投入前に承認要 |
| D | ~~ADR-BFF配置 / 決済非同期UI / Rich Text形式 の3件が未起票~~ → **起票済み** | ADR-0004（BFF配置）/ ADR-0005（決済非同期UI）/ ADR-0006（本文プレーンテキスト）。既定動作を追認する形で文書化 |
| E | Cognito実User Poolでの結合確認 | 未実施（テストはlocal/testの開発用ヘッダー認証のみ）。dev環境構築時に実施 |
| F | dev環境の稼働モードとコスト構成 | 常時稼働は月4.3〜5.0万円。Interface VPCエンドポイント（12 ENI、約1.9万円/月）の要否、`desired_count`、Container Insights、WAFの取捨で1.5〜2.0万円まで低減可。「都度 apply/destroy」運用なら数千円。予算責任者の決定が必要（手順書 §3） |
| G | GitHub OIDC の信頼条件 | `oidc.tf` の `sub` は `repo:<owner>/<repo>:*` で全ブランチ許可。任意ブランチからデプロイ可能なため、`ref:refs/heads/main` 等への限定を検討（手順書 §20.4） |

> 解決済みの要判断: 起案者向け通知の宛先解決（ADR-0002）、冪等記録削除バッチ（BAT-010）、
> バッチ多重起動防止（ADR-0003: ShedLock）、BFF配置（ADR-0004）、決済非同期UI（ADR-0005）、
> 本文プレーンテキスト（ADR-0006）。詳細は §5。

---

## 7. 実装時の注意点（既知の落とし穴）

同種の実装を追加する際の参考。

| 項目 | 内容 |
|---|---|
| `@Transactional` 自己呼出し | `REQUIRES_NEW` は同一クラス内呼出しではプロキシを経由せず**無効**。外部呼出しを挟む処理は別Beanへ切り出す（`PaymentTransactionSteps` / `NotificationTransactionSteps`） |
| `readOnly` トランザクション | `SELECT ... FOR UPDATE SKIP LOCKED` は読み取り専用Txで**実行不可**（PostgreSQLがエラー） |
| テストのHTTPクライアント | `RestTemplate()` 既定（HttpURLConnection）は**401応答の本文を破棄**。エラーコード検証は `RestTemplate(JdkClientHttpRequestFactory())` |
| テスト時のスケジューラ | スケジューリングはtestプロファイルで無効（`SchedulingConfig` は `@Profile("!test")`）。バッチ検証はUseCase/バッチ処理を直接呼ぶ |
| Hibernateスキーマ検証 | `ddl-auto: validate` のため Migration型とEntityマッピングの不一致は起動失敗（`char(n)`は`bpchar`扱い）。`shedlock`等の非Entityテーブルは対象外 |
| JPQLのnullパラメータ | `LIKE`/`concat` にnullを渡すと型推論が`bytea`になり `character varying ~~ bytea` エラー。呼出し側で空文字へ正規化する（SCR-010で実際に踏んだ） |
| クライアント/サーバー境界 | Client Component（"use client"）から `next/headers` 依存モジュールをimportするとビルド失敗。型/定数は `lib/api-types.ts` に分離する |
| ECSの環境変数名 | Spring の Relaxed Binding は `SPRING_DATASOURCE_URL` 形式のみ束縛する。`DB_URL` のような独自名は、`application-{profile}.yml` で `${DB_URL}` と明示しない限り無視される（§2.2-(1) で実際に踏んだ） |
| IaCの検証範囲 | `terraform validate` は構文と型のみ。環境変数名の不一致・ヘルスチェック猶予・未注入の設定値といった**実行時の不整合はCIで検出できない**。apply前にタスク定義とアプリ設定を突き合わせる |
