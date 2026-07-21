# ADR-0003: スケジュールバッチの多重起動防止に ShedLock を採用する

## 状態

採用

## 背景

基本設計 §8.3 は「スケジュールバッチは分散ロックまたはDBロックで多重起動を防止する。
1対象の処理は状態条件付きUPDATEまたは楽観ロックで競合を制御する」と定める。

実装では**対象単位の競合制御**（`FOR UPDATE SKIP LOCKED`・条件付きUPDATE・`@Version`）は
済んでいたが、**ジョブ自体の多重起動防止**（複数インスタンスで同一スケジュールが同時起動する
ケース）は未対応だった。特に集合操作を行うBAT-009 監査アーカイブ（全件SELECT→出力→DELETE）は、
同時起動でアーカイブ重複出力や件数検証失敗を起こしうる。

選択肢:

1. PostgreSQL Advisory Lock（`pg_try_advisory_lock`）で各ジョブをラップ（DBロック）
2. **ShedLock**（`shedlock`テーブル + `@SchedulerLock`）
3. 自前のDBロックテーブル（`job_lock(locked_until)`）
4. 影響の大きいバッチのみ個別対策

## 判断

**選択肢2（ShedLock）** を採用する。

## 判断理由

- 業界標準の実装で宣言的（`@SchedulerLock`）。`lockAtMostFor`（インスタンス異常終了時の最大保持）と
  `lockAtLeastFor`（クロックずれによる直後の二重実行防止）を持ち、advisory lockより堅牢。
- 単一PostgreSQL構成（ADR-0001）でJDBCプロバイダがそのまま使え、追加インフラは`shedlock`テーブルのみ。
- `usingDbTime()` によりアプリ/DBのクロックずれに影響されず、ロック期限をDB時刻で判定できる。
- 将来SQSでマルチインスタンス化しても、DBが単一である限り有効に機能する。

advisory lock（選択肢1）も§8.3の「DBロック」に該当するが、`lockAtMostFor`相当の期限管理を持たず、
接続ピン留めの考慮が必要。ShedLockはこれらを標準で解決するため採用した。

## 適用範囲と除外

`@SchedulerLock` を付与するバッチ:

| ジョブ | lockAtMostFor | 周期 |
|---|---|---|
| BAT-001 公開開始 | 5分 | 1分 |
| BAT-002 募集終了 | 5分 | 1分 |
| BAT-004 返金実行 | 5分 | 1分 |
| BAT-005 通知送信 | 5分 | 1分 |
| BAT-007 決済照合 | 10分 | 15分 |
| BAT-008 ファイル清掃 | 30分 | 日次 |
| BAT-009 監査アーカイブ | 1時間 | 月次 |
| BAT-010 冪等記録削除 | 30分 | 日次 |

**除外: BAT-006 Outbox配送**。競合コンシューマ設計（`FOR UPDATE SKIP LOCKED` + 指数バックオフ）で
複数ワーカーが並列配送することがスループット上むしろ望ましく、ロックで直列化すべきではないため。
対象行のロックにより二重配送は起きない。

## 結果

- 依存追加: `net.javacrumbs.shedlock:shedlock-spring` / `shedlock-provider-jdbc-template` 7.7.0
- Flyway `V202607200009__create_shedlock_table.sql` で `shedlock` テーブルを作成
  （JPA非マップのため `ddl-auto: validate` の対象外）
- `SchedulingConfig`（`@Profile("!test")`）で `@EnableScheduling` + `@EnableSchedulerLock` +
  `LockProvider` を有効化。テストプロファイルではスケジューリングを無効化し、結合テストは
  バッチ処理を直接呼び出す（`@Scheduled` の自動起動に依存しない）。
- 実機（local）で BAT-001/002/004/005/007 がロック行を記録し、BAT-006 は記録しないことを確認。

## AI利用

Claude（Opus 4.8）が4案を比較提示し、人間が選択肢2（ShedLock）を選定。実装・テスト・本ADR起草をClaudeが行った。

## 承認者

（教育担当者の承認欄）2026-07-21
