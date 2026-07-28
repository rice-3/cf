# CF-Training 問題と解決の記録（プロジェクト横断カタログ）

- 文書種別: 開発中に発見した問題と、その原因・対応の記録
- 対象リポジトリ: `F:\11\CF`（GitHub: `rice-3/cf`）
- 対象期間: 2026-07-20 〜 2026-07-28
- 最終更新: 2026-07-28

> **この文書の位置づけ**
>
> `ses_ai_ddd_remaining_tasks.md` §7「実装時の注意点」は**これから実装する人向けの短い注意書き**、
> 各 ADR は**設計判断の記録**である。本書はそれらを横断して
> **「何が起きて、なぜ起きて、どう直したか」**を事象単位で残す。
> 同じ罠を踏み直さないことと、判断の根拠を後から検証できることを目的とする。
>
> 出典として節番号・ADR番号・コミットハッシュを併記する。詳細はそちらを正とする。

---

## 1. 総括

記録した事象は **32件**。分類すると次のようになる。

| 分類 | 件数 | 主な性質 |
|---|---:|---|
| A. 設定・環境変数（IaC ⇄ アプリ） | 7 | **起動失敗より「無言の既定値」が多い** |
| B. 認証・認可・セキュリティ | 6 | 「通ってはいけないものが通る」型 |
| C. トランザクション・並行制御 | 4 | プロキシ・ロック・readOnly の誤解 |
| D. インフラ / IaC | 5 | 後戻りできない設定・destroy を止める設定 |
| E. CI・ツールチェーン | 6 | ローカルで緑・CIで赤／上流都合で突然赤 |
| F. テストの偽陽性・偽陰性 | 4 | **テスト側が原因なのにアプリのバグに見える** |

### 傾向として繰り返し現れたもの

1. **「動いているように見える」失敗が最も多く、最も遅く見つかった。**
   起動失敗はすぐ直せる。危険なのは既定値へ落ちて黙って動くもので、
   §2.3 の突き合わせを実施するまで4件が潜伏していた。
2. **検証ツールの守備範囲を超えた不整合は、ツールでは見つからない。**
   `terraform validate` は構文と型しか見ない。`typecheck`/`build` は lint の代わりにならない。
   ゲートが緑であることは「そのゲートが見る範囲で問題がない」以上の意味を持たない。
3. **環境差（ローカル/CI、local/dev プロファイル）が失敗の温床だった。**
   JDK差、AWS設定の有無、プロファイル指定漏れ、いずれも「手元では通る」状態から始まった。
4. **推測で直すと誤った結論が残る。**
   401本文欠落は「アプリのバグ」と一度報告して撤回した（F-1）。
   以後は実測で確かめる運用にし、環境変数名の束縛（A-6）などは起動して応答で確認した。

---

## 2. A. 設定・環境変数（IaC ⇄ アプリ）

### A-1. DataSource の環境変数名が Spring と不一致で、apply しても起動しない

| 項目 | 内容 |
|---|---|
| 事象 | `ecs.tf` が `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` を渡していたが、アプリはこれを読まない。apply するとタスクが起動せず、置き換えループで NAT / Fargate / ALB の課金だけが発生する |
| 原因 | Spring の Relaxed Binding は `SPRING_DATASOURCE_URL` 形式しか束縛しない。独自名は `application-{profile}.yml` で `${DB_URL}` と明示しない限り無視される |
| 対応 | `SPRING_DATASOURCE_*` へ改名。プロファイル別ファイルは追加せず Terraform 側だけで閉じた |
| 出典 | §2.2-(1)、`8bb0a67` |

### A-2. SES 送信元が未注入で、通知メールが送れない

| 項目 | 内容 |
|---|---|
| 事象 | `CF_SES_FROM_ADDRESS` が注入されず、アプリ既定の無効ドメイン（`no-reply@example.invalid`）のまま |
| 原因 | Terraform 側に送信元を決める変数が無かった |
| 対応 | `ses_from_address` 変数と `local.ses_from_address`（明示指定 > `ses_domain` から導出 > 無効ドメイン）を追加して注入 |
| 出典 | §2.2-(2)、`8bb0a67` |

### A-3. ヘルスチェック猶予が既定 0 で、起動途中のタスクが殺される

| 項目 | 内容 |
|---|---|
| 事象 | Spring Boot 起動 + Flyway 移行の間に ALB ヘルスチェック（30s × unhealthy 3回 ≒ 90秒）で落とされ、置き換えループに入る |
| 原因 | `health_check_grace_period_seconds` 未設定（AWS既定 0） |
| 対応 | 変数化して既定 180 秒 |
| 出典 | §2.2-(3)、`8bb0a67` |

> A-1〜A-3 はいずれも **`terraform fmt` / `validate` を通る**。CI では原理的に検出できない類で、
> AWS 契約・構築手順書を書くためのコード読解で発見した。

### A-4. SES 設定セットが束縛されず、バウンス追跡が無言で無効

| 項目 | 内容 |
|---|---|
| 事象 | `CF_SES_CONFIGURATION_SET` を注入していたが、**どこにも束縛されていなかった**。SES送信は成功するが設定セットが付かず、バウンス・苦情のイベント追跡が効かない |
| 原因 | 束縛先は `cf.notification.ses.configuration-set-name`（環境変数形なら `CF_NOTIFICATION_SES_CONFIGURATION_SET_NAME`）。`application.yml` にプレースホルダも無かった |
| 対応 | `application.yml` に `configuration-set-name: ${CF_SES_CONFIGURATION_SET:}` を追加。未設定時は空文字が入るため `SesNotificationSender` 側で空白を除外 |
| 出典 | §2.3-(1)、`c4dc04d` |

### A-5. S3 キー接頭辞が全環境で `local`

| 項目 | 内容 |
|---|---|
| 事象 | `CF_FILE_KEY_PREFIX` を `ecs.tf` が注入しておらず、dev / staging / production が**同じキー空間**を使う（詳細設計 §10.2 は `env/userId/fileId/...`） |
| 原因 | アプリ既定 `"local"` に落ちていた。起動は成功するため気付けない |
| 対応 | `ecs.tf` で `var.environment` を注入 |
| 出典 | §2.3-(2)、`c4dc04d` |

### A-6. ハイフンを含む設定キーの環境変数名が紛らわしい

| 項目 | 内容 |
|---|---|
| 事象 | `springdoc.api-docs.enabled` の環境変数形が `SPRINGDOC_APIDOCS_ENABLED` か `SPRINGDOC_API_DOCS_ENABLED` か判断できない（Relaxed Binding はハイフンを除去する規則があるため） |
| 原因 | 束縛規則が経路（`@ConfigurationProperties` / `@ConditionalOnProperty`）によって異なる |
| 対応 | **推測せず実測した。** ローカルで両条件を起動して応答コードで確認（無効化時 404 / 対照 200・302）。`SPRINGDOC_API_DOCS_ENABLED` 形で効くことを確定 |
| 出典 | §2.4 の実測表、`58c28f4` |

### A-7. 渡しているが誰も読まない環境変数

| 項目 | 内容 |
|---|---|
| 事象 | `CF_OUTBOX_SQS_QUEUE_URL` を注入し、SQSキューと task role の権限も存在するのに、**アプリに読む実装が無い**（Outbox はアプリ内配送のまま）。「SQS 経由で配送されているつもり」になりうる |
| 原因 | 要判断B（SQS化するか）が未決のまま IaC だけ先行していた |
| 対応 | アプリ内配送を正式構成として確定（ADR-0008）し、キュー・DLQ・IAM権限・環境変数・output・VPCエンドポイントを削除 |
| 出典 | §2.3-(4)、§2.7、ADR-0008、`5981a36` |

---

## 3. B. 認証・認可・セキュリティ

### B-1. ID トークンを Bearer に載せても通ってしまう

| 項目 | 内容 |
|---|---|
| 事象 | Resource Server が Cognito の **ID トークンを受理**していた。ID トークンは利用者本人向けの身元情報であり、API 呼出しの認可根拠にしてはいけない |
| 原因 | Cognito は ID トークンとアクセストークンを**同じ issuer・同じ JWKS** で発行するため、署名と issuer の検証だけでは区別できない |
| 対応 | `token_use == "access"` の検証を追加。あわせて `client_id` を許可リストで検証（同一 User Pool に別クライアントを足しても通らない） |
| 出典 | §2.6、ADR-0007、`d9863b0` |

### B-2. アクセストークンに email / name が無いのに、あるものとして実装されていた

| 項目 | 内容 |
|---|---|
| 事象 | JIT 登録がトークンから `email` / `name` を読もうとし、取れなければ暗黙にフォールバックしていた。**アクセストークンでは必ずフォールバックする**経路だった |
| 原因 | `email` / `name` は ID トークン専用のクレーム |
| 対応 | プレースホルダで作ることを明示する形に変更（`<sub>@cognito.invalid` / `(未設定)`）。本人が API-US-002 で更新する前提とし、ADR に記載 |
| 出典 | §2.6、ADR-0007、`d9863b0` |

### B-3. production でも Swagger UI と actuator が無認証公開される

| 項目 | 内容 |
|---|---|
| 事象 | `/swagger-ui.html`・`/v3/api-docs`・`/actuator/prometheus`・`/actuator/info` が `permitAll` で、ALB は全パスをターゲットグループへ転送していた |
| 原因 | `SecurityConfig` のコメントは「本番では ALB がこのパスを外部公開しない前提」と書いていたが、**その前提が実装されていなかった**。`application-{dev,staging,production}.yml` も存在しないため、コメントの「本番では無効化する」も効いていない |
| 対応 | ALB リスナールールで `/actuator*` を全環境、`/swagger-ui*`・`/v3/api-docs*` を dev 以外で 404 固定応答。加えて dev 以外はアプリ側でも springdoc を無効化（多層防御） |
| 出典 | §2.3-(3)、§2.4、`58c28f4` |

### B-4. `permitAll` のパスで実体が消えると 404 ではなく 500 になる

| 項目 | 内容 |
|---|---|
| 事象 | springdoc を無効化すると `/v3/api-docs` が **500** を返す。外部スキャナが叩くだけで 5xx アラート（`monitoring.md`）を誘発できる |
| 原因 | `permitAll` はセキュリティを通過させるだけ。その先にハンドラが無いと `NoResourceFoundException` になり、`GlobalExceptionHandler` の汎用ハンドラ（`Exception`）に落ちて 500 になっていた |
| 対応 | `NoResourceFoundException` を 404 として扱うハンドラを追加 |
| 出典 | §2.4、`58c28f4` |

### B-5. GitHub OIDC の信頼条件が全ブランチ許可

| 項目 | 内容 |
|---|---|
| 事象 | `oidc.tf` の `sub` が `repo:<owner>/<repo>:*` で、リポジトリ内の**任意の ref からデプロイロールを assume できる** |
| 原因 | 初期実装のまま絞られていなかった |
| 対応 | `sub` を `environment:dev` / `environment:staging` に限定。ブランチ限定と承認者は GitHub の Environment 保護ルールで実施（`main` のみ、`staging` は承認者必須） |
| 出典 | §2.5、`357bdba`、`ce4672f` |

### B-6. 「`ref:refs/heads/main` へ限定」は CD を壊す（誤った定石）

| 項目 | 内容 |
|---|---|
| 事象 | runbook §20.4 が `ref:refs/heads/main` への限定を推奨していた。**そのとおりにすると CD が必ず AssumeRole に失敗する** |
| 原因 | `cd.yml` の deploy job は `environment:` を指定している。job が Environment を参照すると GitHub が発行する `sub` は `repo:<owner>/<repo>:environment:<名前>` になり、ref 形にならない |
| 対応 | `sub` は environment 形で限定し、ブランチは GitHub 側で縛る役割分担へ。runbook の記述も訂正 |
| 出典 | §2.5、`357bdba` |

---

## 4. C. トランザクション・並行制御

### C-1. `@Transactional(REQUIRES_NEW)` の自己呼出しが無効

| 項目 | 内容 |
|---|---|
| 事象 | BAT-004 で `No active transaction` |
| 原因 | 同一クラス内から自己呼出ししており、プロキシを経由せずトランザクションが開始されない。**工程7の `StartPaymentProcessingService` にも同じ潜在バグ**があり、Outbox Worker のトランザクション内で偶然動いていた |
| 対応 | トランザクション境界を `PaymentTransactionSteps` / `NotificationTransactionSteps` として別Beanへ切り出し |
| 出典 | `session_report_2026-07-20.md` §4、§7 |

### C-2. `readOnly` トランザクションで `SELECT ... FOR UPDATE` が実行できない

| 項目 | 内容 |
|---|---|
| 事象 | 対象取得で PostgreSQL がエラー |
| 原因 | `readOnly = true` の Tx では行ロックを取れない |
| 対応 | 対象取得メソッドの `readOnly` を解除。コード側にも理由をコメントで残した |
| 出典 | `session_report_2026-07-20.md` §4、§7 |

### C-3. Webhook の payload 不一致で ERROR 記録ごとロールバック

| 項目 | 内容 |
|---|---|
| 事象 | 不一致時に例外を投げていたため、記録しようとした ERROR ごとロールバックされ痕跡が残らない |
| 原因 | 制御フローに例外を使っていた |
| 対応 | 戻り値で扱う形へ変更 |
| 出典 | `50c7e39b`（2026-07-23 セッション） |

### C-4. マルチインスタンスでのバッチ多重起動

| 項目 | 内容 |
|---|---|
| 事象 | 対象単位の競合制御（`FOR UPDATE SKIP LOCKED`・条件付きUPDATE・`@Version`）はあったが、**ジョブ自体の多重起動防止**が無い。特に集合操作の BAT-009 は重複出力や件数検証失敗を起こしうる |
| 原因 | `desired_count > 1` 前提の設計が未実装だった |
| 対応 | ShedLock を採用（ADR-0003）。BAT-001/002/004/005/007/008/010 に `@SchedulerLock`。BAT-006 Outbox は競合コンシューマ設計が正しいので意図的に除外 |
| 出典 | ADR-0003、§2.3（`desired_count>1` の確認） |

---

## 5. D. インフラ / IaC

### D-1. Private 配置 RDS への保守経路が無い

| 項目 | 内容 |
|---|---|
| 事象 | RDS は Private サブネット・`publicly_accessible=false` で、ローカルから接続できない。DBユーザー作成などの手作業ができない |
| 原因 | 保守経路が設計に無かった |
| 対応 | ECS Exec（SSM）を有効化し、**SSMポートフォワードでローカルの psql を RDS へ繋ぐ**方式に。実行イメージ（`amazoncorretto:25`）に psql が無いため踏み台方式とした。セッション内容は専用ロググループへ365日保持（要件C-17） |
| 出典 | §2.1、`8bb0a67` |

### D-2. production でも ECS Exec が常時有効

| 項目 | 内容 |
|---|---|
| 事象 | 手順書は「production では `false` を基本」と定めていたのに、Terraform の既定は `true` |
| 原因 | 記述と実装が一致していなかった |
| 対応 | 変数を nullable（`default = null`）にし、`locals` で `environment` から導出（production のみ `false`）。`false` のときは `enable_execute_command`・`ssmmessages` 権限・Exec用ロググループが**すべて作られない** |
| 出典 | §2.9、`5981a36` |

### D-3. Object Lock は後付けできず、`terraform destroy` を止めうる

| 項目 | 内容 |
|---|---|
| 事象 | 監査アーカイブに改ざん防止（§7.7）を入れたいが、Object Lock は**バケット作成時にしか有効化できない**。かつ COMPLIANCE モードは**ルートでも解除できず、保持期間中はバケットを空にできない**ため destroy が失敗する |
| 原因 | S3 Object Lock の仕様。要判断F で「都度 apply/destroy」運用が候補に挙がっており、致命的になりうる |
| 対応 | `object_lock_enabled = true`（capability のみ）で作成し、**既定の保持ルールは置かない**。`audit_archive_lock_days`（既定 0）で後から有効化できる。モード既定は `GOVERNANCE`。destroy 手順にも注意を追記 |
| 出典 | §2.10、ADR-0009、`e56f71f` |

### D-4. S3 ライフサイクルは小さいオブジェクトを Glacier へ移さない

| 項目 | 内容 |
|---|---|
| 事象 | 監査アーカイブを安いクラスへ置きたいが、ライフサイクル遷移では意図どおりにならない |
| 原因 | S3 のライフサイクル遷移は既定で **128KB 未満を Glacier 系へ遷移させない**。小さなアーカイブが STANDARD に残り続ける |
| 対応 | **PUT 時に直接ストレージクラス（`GLACIER_IR`）を指定**する方式に |
| 出典 | §2.10、ADR-0009、`e56f71f` |

### D-5. 使わない VPC エンドポイントが費用を押し上げていた

| 項目 | 内容 |
|---|---|
| 事象 | Interface VPC Endpoint が費用の支配要因（NAT と合わせて全体の約6割） |
| 原因 | SQS を使わない構成に確定した後も `sqs` エンドポイントが残っていた |
| 対応 | ADR-0008 に伴い削除（6サービス→5サービス、12→10 ENI）。手順書の見積りも更新（常時稼働 約4.3〜5.0万→約4.0〜4.6万円/月） |
| 出典 | §2.7、`5981a36` |

---

## 6. E. CI・ツールチェーン

### E-1. ローカルで緑・CI で赤（spotless の折返し不整合）

| 項目 | 内容 |
|---|---|
| 事象 | `spotlessApply` 後はローカル緑なのに CI で違反 |
| 原因 | `binary-expression-wrapping` が `max_line_length`（IntelliJ スタイル既定 120）を参照して折り返すため、環境で結果が変わる |
| 対応 | `max_line_length=off` + 当該ルール無効化（`.editorconfig` + `build.gradle`） |
| 出典 | `a55d1df` |

### E-2. Java 整形ツールが JDK 25 で動かない

| 項目 | 内容 |
|---|---|
| 事象 | google-java-format / palantir-java-format が使えない |
| 原因 | JDK 25 の javac 内部API変更 |
| 対応 | JDK非依存の **Eclipse JDT フォーマッタ**を Spotless に採用（コメントは保全しコードのみ整形） |
| 出典 | `8a928a5`、`ai-handoff-2026-07-23.md` §4 |

### E-3. OpenAPI spec の比較で日本語が文字化け

| 項目 | 内容 |
|---|---|
| 事象 | `OpenApiSpecIntegrationTest` が日本語部分で不一致になる |
| 原因 | `RestTemplate` が `application/vnd.oai.openapi`（charset 無し）を ISO-8859-1 と解釈していた |
| 対応 | バイト列で受け取り UTF-8 で復号。末尾改行は `trim()` で吸収 |
| 出典 | `b942a4ca`（2026-07-26 セッション） |

### E-4. 公開 spec がローカル環境に依存していた

| 項目 | 内容 |
|---|---|
| 事象 | 生成される spec が環境によって変わる |
| 原因 | gitignore 対象の `.env.local`（`DEV_USER_ID` を入れて既定ログイン状態にする利便設定）が影響していた |
| 対応 | 公開 spec を環境非依存に修正 |
| 出典 | `b942a4ca` |

### E-5. Trivy ゲートが新規 CVE 公開で突然赤くなる

| 項目 | 内容 |
|---|---|
| 事象 | コードを変えていないのに CI が赤化する。**同じコミットで以前は success だったランが後から failure になる** |
| 原因 | `trivy fs --severity HIGH,CRITICAL --ignore-unfixed` は脆弱性DBを参照するため、新規 CVE 公開で結果が変わる |
| 対応（過去） | `sharp`・`next` を更新して解消（`a55d1df`、`9225621`） |
| 対応（現在） | `postcss` CVE-2026-45623 が未解消。`next@16.2.12` もまだ `postcss@8.4.31` 固定のため**上流待ち**とした（§4.3、`b8d546f`） |
| 出典 | §4.3、`ai-handoff-2026-07-23.md` §4 |

### E-6. `npm run lint` が一度も動いたことがなかった

| 項目 | 内容 |
|---|---|
| 事象 | `next lint` が Next 16 で廃止され、スクリプトがエラーになる。調べると **ESLint 本体も設定ファイルも入っていなかった**（`create-next-app` の残骸） |
| 原因 | CI が `typecheck` と `build` しか呼んでいなかったため、壊れていることに誰も気付かなかった |
| 対応 | ESLint flat config を新規作成し `eslint .` へ。CI にも Lint ステップを追加 |
| 補足 | `eslint-config-next` は採用できなかった。ESLint 10 では同梱の `eslint-plugin-react` が落ち、ESLint 9 では本体が脆弱な `minimatch@3` を引き込んで HIGH が 5→14 に増え Trivy ゲートに当たる。ESLint 10 + 個別 plugin 構成とし、jsx-a11y / import / react は上流対応待ちで見送った |
| 出典 | §4.2、`2e3be77` |

---

## 7. F. テストの偽陽性・偽陰性

### F-1. 「401 の本文が空」はアプリではなくテスト側の問題だった（**誤報告を訂正**）

| 項目 | 内容 |
|---|---|
| 事象 | 401 応答で Problem Details の本文が空（`Content-Type` は `application/problem+json` なのに `Content-Length=0`）。403 なら本文が届く |
| 一度出した誤結論 | 「アプリの不具合。Spring Security とサーブレットのエラーディスパッチのどちらが原因か切り分けられない」として `TODO(question)` を残した |
| 真因 | **テストクライアント側**。`RestTemplate()` 既定の `SimpleClientHttpRequestFactory` は `HttpURLConnection` を使い、401 応答を受け取ると認証再試行処理の一環で**本文を読み捨てる**。403 では認証処理が走らないため本文が届く |
| 対応 | 結合テストを `RestTemplate(JdkClientHttpRequestFactory())` に統一し、401 の `code` も検証。誤った報告は撤回した |
| 教訓 | 「アプリのバグ」と結論する前に、観測系（テストクライアント）を疑う |
| 出典 | `50c7e39b`、`session_report_2026-07-20.md` §4、§7 |

### F-2. 既存テストが一度も呼んでいない API に 500 バグが潜んでいた

| 項目 | 内容 |
|---|---|
| 事象 | `GET /api/v1/projects`（キーワード未指定）が 500 |
| 原因 | JPQL の null パラメータで型推論が `bytea` になり `character varying ~~ bytea` エラー。**既存の結合テストがこの API を一度も呼んでいなかった**ため未検出 |
| 対応 | 呼出し側で空文字へ正規化し、回帰テストを追加 |
| 出典 | `b942a4ca`、§7 |

### F-3. スタブ実装が全環境で生き、監査ログが黙って消える状態だった

| 項目 | 内容 |
|---|---|
| 事象 | `LocalAuditArchiveAdapter` が `@Profile` を持たない**無条件Bean**で、S3 へ何も出さないのにハッシュを返していた。BAT-009 はハッシュが返れば「出力できた」と判断して DB 行を削除するため、**dev 以上で動かすと監査ログが出力されずに消える** |
| 原因 | スタブ実装にプロファイル指定が無く、本番実装も存在しなかった |
| 対応 | `@Profile({"local","test"})` を付け、`S3AuditArchiveAdapter`（`@Profile("!local & !test")`）を追加。バケット未設定時は `DependencyException` で落として**ハッシュを返さないことで DB 削除を止める** |
| 出典 | §2.10、ADR-0009、`e56f71f` |

### F-4. 手元で通るテストが CI で落ちた（AWS 設定の有無）

| 項目 | 内容 |
|---|---|
| 事象 | `S3AuditArchiveAdapterTest` の1件が CI でのみ失敗（222テスト中1件） |
| 原因 | `S3AuditArchiveAdapter` がコンストラクタで `S3Client.create()` を呼んでおり、**AWS リージョンが解決できない CI では検証対象のガードに到達する前に落ちる**。手元は AWS CLI が設定済みだったため通っていた |
| 対応 | S3クライアントを遅延生成に変更し、設定不備の検知を AWS へ触れる前に行えるようにした。**AWS 設定を全て外した環境で再検証**してから push |
| 教訓 | 外部サービスのクライアントをコンストラクタで作ると、テストが実行環境の設定に依存する |
| 出典 | `b3a8d39` |

---

## 8. 設計・文書に関する不整合（参考）

コードの不具合ではないが、判断を要した食い違い。

| # | 事象 | 対応 |
|---|---|---|
| 1 | 基本設計と詳細設計でドメインイベント名が異なる（`SupportAccepted` vs `SupportRequested` 等） | 実装は詳細設計準拠。基本設計へ寄せると Project コンテキストと outbox の `event_type` まで波及するため、勝手に変えず判断を仰いだ |
| 2 | `char(64)` vs `varchar` | 実装（varchar）に寄せた。PostgreSQL の `char(n)` は空白埋めで利点が無く、`ddl-auto: validate` が `bpchar` 不一致を検出するため維持すると品質ゲートを弱める必要が出る |
| 3 | 設計書 md の版数表が「1.0」なのに残タスクは「v1.2」と記載 | **残タスク側が事実誤り**。docx を現行 md と同期し、その旨を明記 |
| 4 | 設計書に**会員登録の画面もAPIも無い**（`app_user` 行の生成手段が未定義） | JIT 自動登録を正式採用（ADR-0007）。否定すると設計書に無い機能一式の新規実装が必要になるため |
| 5 | BAT-004/005 のトリガが基本設計では「SQS」だが実装は `@Scheduled` ポーリング | 基本設計 §8.1 が「SQSまたはアプリ内」を許容しているため、アプリ内配送を正式構成として追認（ADR-0008） |

---

## 9. 未解決・上流待ち（2026-07-28 時点）

| # | 内容 | 状態 |
|---|---|---|
| 1 | `postcss` CVE-2026-45623 により **Trivy ゲートが赤い** | 上流待ち。`next@16.2.12` もまだ `postcss@8.4.31` 固定。`overrides` は `sharp` の libvips CVE を表面化させ npm の提案が Next のメジャーダウングレードになるため見送り（§4.3） |
| 2 | `eslint-plugin-react` の ESLint 10 対応 | 上流待ち。対応後に `eslint-config-next` へ戻すと jsx-a11y / import ルールが復活する（§4.2） |
| 3 | CodeQL の Kotlin 2.4 対応 | 上流待ち。現状 Semgrep で代替（§4.1） |
| 4 | 実 AWS での apply・疎通確認 | AWS 契約後。§2.10 の監査アーカイブ実出力、要判断E（Cognito 実 User Pool）を含む |
| 5 | 要判断F（dev の稼働モードとコスト構成） | 予算責任者の判断待ち |

> **1 に関する運用上の注意**: ゲートが恒常的に赤いと、新しく増えた本物の HIGH に気付けなくなる。
> 赤/緑ではなく **GitHub Security タブの Trivy SARIF の中身**で新規指摘を確認すること。

---

## 10. 出典

| 種別 | 場所 |
|---|---|
| 残タスク・注意点 | `docs/ses_ai_ddd_remaining_tasks.md`（§2.2〜§2.10、§4、§7） |
| 設計判断 | `docs/adr/ADR-0001` 〜 `ADR-0009` |
| 過去セッション記録 | `docs/session_report_2026-07-20.md`、`docs/ai-handoff-2026-07-23.md` |
| 運用手順 | `docs/ops/runbook.md`、`docs/ops/monitoring.md`、`docs/ops/aws-contract-build-runbook.md` |
| コミット | `a55d1df` / `8a928a5` / `9225621` / `8bb0a67` / `c4dc04d` / `58c28f4` / `357bdba` / `d9863b0` / `5981a36` / `ce4672f` / `e56f71f` / `2e3be77` / `b3a8d39` / `b8d546f` |
