# CF-Training 技術スタックと選定理由・懸念・改善事項

- 文書種別: 採用技術の一覧と、選定理由・解決する課題・今後の懸念・改善事項
- 対象リポジトリ: `F:\11\CF`（GitHub: `rice-3/cf`）
- 基準日: 2026-07-28（実装から抽出した実際の依存関係に基づく）

> **この文書の位置づけ**
>
> 技術選定の**一次情報は `docs/des/ses_ai_ddd_requirements_tech_selection.md`**（要件定義・要件確認・
> 技術選定書）にある。本書はそこに書かれた方針と、**実装で実際に入っている依存関係**を突き合わせ、
> 「何を、なぜ選び、何が解決され、これから何が問題になりうるか」を一枚で追えるようにしたもの。
> 個別の設計判断は `docs/adr/`、踏んだ問題の実例は `ses_ai_ddd_problem_catalog.md` を参照。

---

## 0. このプロジェクトの性質（選定の前提）

**教育・実践開発プロジェクトであり、実決済・実顧客情報を扱わない**（`AGENTS.md`）。
題材はクラウドファンディング型業務サービス。想定利用者はSES向けの若手技術者で、
成果はスキルシートの実績にもなる。

この性質が選定の全体を規定している。

| 前提 | 選定への影響 |
|---|---|
| 教育が目的 | **業務で実際に使われている構成**を選ぶ。学習用の簡易版に逃げない |
| 実決済を扱わない | 決済はSandbox実装。**本物の決済事業者へ接続しない** |
| 12か月の教育課程 | 段階的に触れられること。初手からマイクロサービスにしない |
| AI駆動開発が主題 | AIが書いたコードを**人間が検証できる仕組み**（型・テスト・静的解析・ArchUnit）を厚くする |
| 少人数・低予算 | 運用複雑性とAWS費用を抑える。**使わないものは作らない** |

---

## 1. バックエンド

### 1.1 言語・実行環境

| 技術 | 版 | 選定理由 | 解決すること |
|---|---|---|---|
| **Amazon Corretto** | 25 | JavaとKotlinの**双方を同一JVMへ統一**する。長期サポートがあり、AWS上での実行と整合する | 「ローカルはJDK21、CIは25」のような実行環境差を排除する。JVMターゲット一致はDoDの項目でもある |
| **Kotlin** | 2.4.10 | 主言語。null安全・`sealed class`・data class が**ドメインモデルの表現に直接効く** | 状態遷移を型で表す（`SupportStatus` の9状態など）。Value Objectの記述量が少なく、DDDの実践コストが下がる |
| **Java** | 25 | 副言語。Identity / Audit コンテキストで採用 | **教育上、両方に触れる**ことが目的。またJava前提の現場へ出たときに困らない。`record` によるDTO表現も活用 |

> **Kotlin 60〜70% / Java 30〜40%** の比率を技術選定書 §12 で定めている。
> コンテキスト単位で言語を割り当て、同一コンテキスト内では混在させない（詳細設計 §2.2）。

### 1.2 フレームワーク

| 技術 | 版 | 選定理由 | 解決すること |
|---|---|---|---|
| **Spring Boot** | 4.1.0 | 国内SES案件で最も遭遇率が高い。DI・トランザクション・セキュリティ・Actuatorが揃う | 教育した内容がそのまま現場で通用する。個別技術の寄せ集めにならない |
| **Spring Security + OAuth2 Resource Server** | Boot同梱 | Cognito（OIDC）とJWT検証を標準機能で扱える | 認証を自前実装しない。ロールはDBを正とし、トークンには必要最小限だけ載せる（基本設計 §9.1） |
| **Spring Data JPA / Hibernate** | Boot同梱 | 実務での遭遇率。`ddl-auto: validate` がスキーマとEntityの不一致を**起動時に検出**する | Flyway管理のDDLとEntityマッピングのズレを早期に落とす。実際に `char(64)` 不一致を検出して設計を是正した |
| **Flyway** | Boot同梱 + `flyway-database-postgresql` | DDLの**唯一の管理者**。versioned migrationで再現性を担保 | 「誰かが手でALTERした」状態を作らない。`V202607230001` のようなロール作成もコード管理下に置ける |

### 1.3 ライブラリ

| 技術 | 版 | 選定理由 | 解決すること |
|---|---|---|---|
| **ShedLock** | 7.7.0 | `desired_count > 1` でのバッチ多重起動防止（**ADR-0003**）。`lockAtMostFor` / `lockAtLeastFor` を持ち、advisory lockより堅牢 | 複数タスクで同一スケジュールが同時起動する問題。追加インフラは `shedlock` テーブルのみ |
| **ulid-creator** | 5.2.3 | 主キーをULIDにする。**時系列ソート可能でURLに載せられる** | 連番IDの推測可能性と、UUIDv4のインデックス断片化。`Instant.now()` 直接呼出し禁止と併せてClock注入で採番も決定論的にできる |
| **springdoc-openapi** | 3.0.3 | 実装からOpenAPI specを生成し、**contract-firstの起点**にする | フロントの型を spec から生成（`openapi-typescript`）。手書きの型定義とAPIの乖離を無くす |
| **Micrometer + Prometheus registry** | Boot同梱 | `/actuator/prometheus` でメトリクスを公開。CloudWatchへはCollectorで転送 | 監視の実装をベンダー非依存にする。ローカルでも同じ指標が見られる |
| **AWS SDK for Java v2**（`s3` / `sesv2`） | BOM 2.29.45 | S3署名付きURLとSES送信。**必要な2つだけ**を入れる | SDK全体を入れると起動時間と脆弱性面が増える。SQSは不採用のため入れていない（ADR-0008） |
| **Jackson**（`tools.jackson`） | Boot同梱 | Jackson 3系。日時はISO 8601で出力 | 日時表現のブレを無くす |

### 1.4 テスト

| 技術 | 版 | 選定理由 | 解決すること |
|---|---|---|---|
| **JUnit 5 + Kotest + MockK** | 5.9.1 / 1.14.2 | Kotlinのドメインテストを読みやすく書く | `FunSpec` の日本語テスト名で仕様が読める形になる |
| **ArchUnit** | 1.4.1 | **DDDの依存規則を機械的に強制する**（`adapter → application → domain`、コンテキスト間は公開契約のみ） | レビューでの「境界を越えている」指摘を自動化する。**AI生成コードの越境を検出する主装置** |
| **Testcontainers**（PostgreSQL 18） | BOM 1.21.3 | 結合テストを**実DBで**行う。H2等での代替をしない | `FOR UPDATE SKIP LOCKED` や部分一意インデックスなど、PostgreSQL固有の挙動を検証できる |
| **Playwright** | ^1.61.1 | E2E。起案→審査承認のジャーニーとロール別アクセス制御を検証 | 画面とAPIを跨いだ回帰を拾う |

---

## 2. フロントエンド

| 技術 | 版 | 選定理由 | 解決すること |
|---|---|---|---|
| **Next.js（App Router）** | 16.2.11 | Web と **BFF を1つのアプリで担う**（**ADR-0004**）。Server Componentsでバックエンドを直接呼べる | BFFを別サービスにせず運用点を増やさない。ブラウザへトークンを露出させずCookieセッションで扱える |
| **React** | 19.2.7 | 実務での遭遇率。Server Components前提の構成 | — |
| **TypeScript** | 5.9.3 | 型で契約を守る。`tsc --noEmit` をCIゲートに | APIとの型ズレをビルド前に落とす |
| **openapi-typescript** | ^7.13.0 | `docs/api/openapi.yaml` から型を生成。**CIに鮮度ゲートあり** | 手書き型とAPIの乖離。生成漏れがあればCIが落ちる |
| **React Hook Form + Zod** | 7.82.0 / 4.4.3 | 可変長入力（リターン設定の `useFieldArray`）とスキーマ検証 | フォーム状態管理の自作を避け、検証をスキーマに集約する |
| **ESLint（flat config）** | 10.8.0 | `@next/eslint-plugin-next` / `eslint-plugin-react-hooks` / `typescript-eslint` の組合せ | Hooksの誤用とNext固有の落とし穴を検出。**§5.2 に構成上の制約あり** |

> **TanStack Query と Vitest は技術選定書 §18.1 に挙がっているが未導入。**
> 前者はServer Components中心の構成で必要性が薄く、後者はフロントの単体テストが未整備であるため。
> §5.4 に改善事項として記載。

---

## 3. データベース・インフラ

| 技術 | 版 | 選定理由 | 解決すること |
|---|---|---|---|
| **PostgreSQL** | 18 | 単一インスタンス（ADR-0001の前提）。`FOR UPDATE SKIP LOCKED`・部分一意インデックス・`jsonb` を活用 | 競合コンシューマ（Outbox配送）と冪等制御をDBだけで実現でき、追加ミドルウェアが要らない |
| **AWS ECS Fargate** | — | コンテナ実行。**EC2の管理をしない** | OSパッチ・スケーリングの運用負荷を外す |
| **Amazon RDS** | PostgreSQL 18 | マスターパスワードをSecrets Managerが自動管理 | 認証情報をTerraform stateへ残さない |
| **Amazon S3** | — | ファイル本体（署名付きURLで直PUT/GET）と監査アーカイブ（**ADR-0009**） | アプリがファイル実体を持たない。バケットは非公開で、経路は署名付きURLに限定 |
| **Amazon Cognito** | — | OIDC + PKCE。Subjectと内部UserIdを分離 | 認証基盤を自前実装しない。ロールはDBを正とする |
| **Amazon SES** | — | 通知メール。テンプレートIDと変数で送る | 本文をアプリで組み立てない |
| **Terraform** | 1.15.8 / AWS Provider 5.x | IaC（**ADR-007**）。CIで `fmt` / `validate` をゲート化 | 手作業構築を排除し、環境差を無くす |
| **Docker / Docker Compose** | — | ローカルのPostgreSQL・Mailpit、本番のコンテナイメージ | 「自分の環境では動く」を減らす |

> **Amazon SQS は当初の選定に含まれていたが不採用**（**ADR-0008**）。
> 単一Backendプロセスでは購読側が同一JVMにあり、SQSを挟んでも分離の利得が出ない一方、
> SDK依存・ポーラー・重複対処・DLQ運用・LocalStackのコストが実在するため。
> キュー・IAM権限・VPCエンドポイントはTerraformから削除済み。

---

## 4. 開発プロセス・品質ゲート

### 4.1 CI/CD（GitHub Actions 8ワークフロー）

| ワークフロー | 内容 | 落とす条件 |
|---|---|---|
| `ci.yml` | backend build（単体・ArchUnit・Testcontainers）/ frontend lint・typecheck・build / gitleaks | いずれか失敗 |
| `e2e.yml` | Playwright E2E | 失敗 |
| `terraform.yml` | `fmt -check` / `init -backend=false` / `validate` | 失敗 |
| `openapi.yml` | `oasdiff breaking` で**破壊的変更を検出** | 破壊的変更（ERR） |
| `security-scan.yml` | Trivy（依存・コンテナ）+ SARIF | **修正可能な** HIGH/CRITICAL |
| `codeql.yml` / `semgrep.yml` | SAST | 検出 |
| `cd.yml` | ECSへのデプロイ（手動起動・承認付き） | — |

**AI生成コードを人間が検証できるようにする**という目的から、ゲートは重ね掛けにしている。
型（TypeScript / Kotlin）→ 静的解析（ESLint / Semgrep / CodeQL）→ 構造（ArchUnit）→
振る舞い（単体・結合・E2E）→ 契約（OpenAPI差分）→ 供給網（Trivy）の順に守備範囲が異なる。

### 4.2 設計判断の記録（ADR 9件）

| ADR | 判断 |
|---|---|
| 0001 | 単一Backendプロジェクト（Gradleマルチプロジェクトにしない） |
| 0002 | 起案者向け通知の宛先解決 |
| 0003 | バッチ多重起動防止に ShedLock |
| 0004 | BFFをNext.jsに置く |
| 0005 | 決済の非同期UI |
| 0006 | プロジェクト本文はプレーンテキスト |
| 0007 | Cognito JIT自動登録を許容し、トークン受入条件を狭める |
| 0008 | Outbox配送はアプリ内Handler（SQSを挟まない） |
| 0009 | 監査アーカイブは専用S3へ GLACIER_IR で1年保持 |

---

## 5. 今後の懸念事項

### 5.1 運用に入ると顕在化するもの

| # | 懸念 | 背景・影響 | 現時点の状況 |
|---|---|---|---|
| 1 | **AWS費用** | 常時稼働で月4.0〜4.6万円。**Interface VPCエンドポイント（10 ENI）とNAT Gatewayで約6割**を占める | 節約案は手順書 §3.3 に整理済み。稼働モードは**要判断F（予算責任者）待ち** |
| 2 | **実AWSでの未検証部分** | apply・疎通・監視の実配線・SES登録・Cognito実User Pool・監査アーカイブの実出力が未実施 | すべてAWS契約後。手順は `aws-contract-build-runbook.md` に確定済み |
| 3 | **メトリクスパイプライン未構成** | `/actuator/prometheus` は公開済みだが、CloudWatchへ発行するCollectorが未配置。**ビジネス/バッチのアラームは発火しない** | ADOTサイドカー構成を手順書 §17 に記載。実applyが必要 |
| 4 | **単一インスタンスDB** | RDS Single-AZ。可用性要件が上がるとMulti-AZ化が必要 | 教育用途では許容。費用と引き換え |
| 5 | **`desired_count > 1` の実機未検証** | ShedLockのロジックは実装・ローカル検証済みだが、実AWSで2タスク同時稼働の検証はしていない | apply後に確認する |

### 5.2 依存の陳腐化・上流都合

| # | 懸念 | 背景 |
|---|---|---|
| 6 | **Trivyゲートが恒常的に赤い** | `postcss` CVE-2026-45623。`next@16.2.12` もまだ `postcss@8.4.31` 固定で**上流待ち**。恒常的に赤いと**新規の本物のHIGHに気付けなくなる**（SARIFの中身で確認する運用にしている） |
| 7 | **ESLintの構成が本来形でない** | `eslint-config-next` はESLint 10で動かず（同梱の `eslint-plugin-react` が未対応）、ESLint 9に下げるとHIGH脆弱性が9件増えてTrivyゲートに当たる。**jsx-a11y / import / react ルールを落として**運用中 |
| 8 | **CodeQLがKotlin 2.4に未対応** | Semgrepで代替中。JVM側のSAST精度が本来より落ちている |
| 9 | **最先端寄りのバージョン構成** | Corretto 25 / Kotlin 2.4 / Spring Boot 4.1 / Next 16 / React 19 / PostgreSQL 18。**周辺ツールが追随していない事例を既に3件踏んだ**（google-java-format がJDK25で不可、eslint-plugin-react がESLint10未対応、CodeQLがKotlin2.4未対応） |

### 5.3 設計・実装に残る構造的リスク

| # | 懸念 | 背景 |
|---|---|---|
| 10 | **プロファイル別設定ファイルが無い** | dev以上は `application.yml` ＋ ECS環境変数だけ。「本番では無効化する」とコメントに書いた設定が**環境変数で明示的に上書きしない限り有効のまま**になる。実際にSwagger UIの公開で踏んだ |
| 11 | **環境変数の束縛は「無言で既定値」になる** | 束縛されない環境変数は起動を止めない。**§2.3 の突き合わせを実施するまで4件潜伏していた**。IaC側に変数を足すだけでは機能しない |
| 12 | **監査アーカイブは書き込み専用で読み直せない** | 改ざん防止のため `s3:PutObject` のみ付与。**運用者がどの権限でどう取り出すかの手順が未整備**（ADR-0009 の範囲外として明記） |
| 13 | **JIT登録のプロフィールがプレースホルダ** | アクセストークンに `email` / `name` が無いため `<sub>@cognito.invalid` / `(未設定)` で作る。**支援フローが実名・連絡先を要求する場合、入力を促す画面制御が別途必要** |
| 14 | **`prevent_self_review` が false** | GitHub Environmentの承認者が1人しかいないため。**自分で起票したデプロイを自分で承認できる**。承認者を増やしたら `true` へ切り替える必要がある |
| 15 | **教育用の割り切りが本番要件と衝突しうる** | 決済はSandbox、監査アーカイブのObject Lockは無効（destroyを止めないため）、リポジトリはpublic。**実運用へ転用するなら全部見直しが要る** |

---

## 6. 改善事項

優先度は「実運用へ近づけるための効果 ÷ 手間」で付けている。

### 6.1 優先度: 高

| # | 改善 | 理由・効果 |
|---|---|---|
| 1 | **メトリクスパイプラインの構成**（ADOTサイドカー） | 現状ビジネス/バッチのアラームが発火しない。**監視設計が絵に描いた餅のまま**。apply時に必ず実施する |
| 2 | **`postcss` 解消後のTrivyゲート復旧** | 赤が常態化すると新規HIGHを見逃す。`npm view next dependencies.postcss` の監視だけで追随判断できる |
| 3 | **DB接続分離の切り替え完了** | `cf_app_rw` / `cf_app_login` の配線は済んでいるが、実行時接続はまだオーナーのまま。**最小権限が効いていない** |
| 4 | **プロファイル別設定ファイルの導入検討** | `application-{dev,staging,production}.yml` を置けば、懸念#10・#11 の再発を構造的に減らせる。現状はTerraform側だけで閉じている |

### 6.2 優先度: 中

| # | 改善 | 理由・効果 |
|---|---|---|
| 5 | **`eslint-config-next` への回帰** | `eslint-plugin-react` のESLint 10対応後。**jsx-a11y（アクセシビリティ）が戻る**のが大きい。画面に `aria-*` を書いている以上、検査があるべき |
| 6 | **フロントの単体テスト（Vitest）導入** | 技術選定書 §18.1 に挙がっているが未導入。現状フロントの検証はtypecheck / build / E2E のみで、**コンポーネント単位の回帰が拾えない** |
| 7 | **監査アーカイブの参照手順の整備** | 書き込み専用にした結果、**取り出し方が決まっていない**。`runbook.md` へ追記する |
| 8 | **`desired_count > 1` での実機検証** | ShedLockと競合コンシューマの設計が実環境で意図どおり動くかを確認する |
| 9 | **GitHub Environment の承認者追加** | 承認者を増やして `prevent_self_review = true` にする。要件C-17（AI単独の本番反映禁止）の実効性が上がる |

### 6.3 優先度: 低 / 将来

| # | 改善 | 理由・効果 |
|---|---|---|
| 10 | **CodeQL の Kotlin 対応後の切替** | 上流待ち。Semgrepとの併用か置換かを判断する |
| 11 | **Spring Modulith の導入検討** | 詳細設計 §1.5 が言及しているが未導入。現状ArchUnitで境界を守れているので急がない |
| 12 | **Outbox配送のSQS化** | ADR-0008 で不採用としたが、**Workerを別サービスへ切り出す / アプリ外の購読者が増える / 配送がAPIレイテンシを圧迫する**のいずれかが起きたら再検討する（ADR-0008 に条件を明記済み） |
| 13 | **RDS Multi-AZ 化** | 可用性要件が上がった場合 |
| 14 | **BAT-009 監査アーカイブの参照UI** | 現状は運用者がS3を直接見る前提 |

---

## 7. 総評

**選定そのものは目的（教育・実務接続）に対して妥当である。** 実務での遭遇率が高い構成を選び、
DDDの規律をArchUnitで機械的に守り、AI生成コードを多層のゲートで検証する形になっている。

一方で、**バージョンを最先端に寄せた代償**が周辺ツールの非対応という形で3件顕在化しており
（§5.2-9）、これは今後も繰り返し起きる。**「本体は最新、周辺ツールは追いつかない」ことを
前提に、回避策と復旧条件をADRや文書へ残す運用**（本プロジェクトで実際にやっていること）が
そのまま対処になる。

最も注意すべきは **§5.3 の構造的リスク**で、いずれも「動いてしまうので気付けない」種類である。
特に #10・#11（プロファイル別ファイルが無く、環境変数が無言で既定値に落ちる）は
実際に4件の潜伏バグを生んでおり、改善事項 #4 を検討する価値がある。

---

## 8. 出典

| 種別 | 場所 |
|---|---|
| 技術選定の一次情報 | `docs/des/ses_ai_ddd_requirements_tech_selection.md` |
| 上位設計 | `docs/des/ses_ai_ddd_basic_design.md`（BD-CF-001）/ `ses_ai_ddd_detailed_design.md`（DD-CF-001） |
| 設計判断 | `docs/adr/ADR-0001` 〜 `ADR-0009` |
| 実際の依存関係 | `backend/build.gradle.kts` / `frontend/package.json` / `infra/terraform/` |
| 踏んだ問題の実例 | `docs/ses_ai_ddd_problem_catalog.md` |
| 残タスク | `docs/ses_ai_ddd_remaining_tasks.md` |
| 費用・構築手順 | `docs/ops/aws-contract-build-runbook.md` |
