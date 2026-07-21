# ADR-0002: 起案者向け通知の宛先解決はイベントに ownerUserId を持たせる

## 状態

採用

## 背景

Notificationコンテキストは業務イベントを購読して通知を登録する（基本設計 §4.6）。
支援者向け通知（SupportConfirmed / SupportPaymentFailed / RefundCompleted）は、
イベントの `supporterUserId`、または `supportId` からFundingの公開契約で宛先を解決できていた。

一方、起案者向け通知（ProjectApproved / ProjectReturned / ProjectRejected /
ProjectPublished / ProjectSucceeded / ProjectFailed）は、**イベントpayloadに起案者UserIdが
含まれておらず宛先を解決できない**。`NotificationEventHandler` に `TODO(question)` として
残していた課題。

選択肢は次の2つ。

1. **イベントに `ownerUserId` を追加する（event-carried state transfer）**
2. Projectの公開契約に所有者参照クエリ（`findOwner(projectId)`）を追加し、通知登録時に逆引きする

## 判断

**選択肢1**（イベントに `ownerUserId` を追加）を採用する。

## 判断理由

- **一貫性**: `ProjectCreated` / `ProjectSubmittedForReview` は既に `ownerUserId` を保持しており、
  同じ集約から発行される他イベントも揃える方が自然。
- **時間的結合の排除**: 選択肢2はNotification処理時にProjectの読み取りモデルへ問い合わせる必要があり、
  Outbox配送タイミングとProject側の状態に依存する。イベントが宛先解決に必要な情報を自己完結で持つ方が、
  配送遅延や後続変更に強い（event-carried state transfer）。
- **所有者は不変**: プロジェクトの起案者は生成後に変わらないため、イベント発生時点の `ownerUserId` を
  そのまま使ってよい（陳腐化リスクが無い）。
- **PII最小化と両立**: `ownerUserId` は内部IDでありメールアドレス等の個人情報ではない。宛先メールは
  従来どおり `UserReferenceQuery.findEmail` でIdentityからOutbox外で解決する（§10.3）。
- Outboxのpayloadはjsonbで、Value Class（UserId）はプレーン文字列へシリアライズされるため（実測確認済み）、
  フィールド追加は後方互換に問題ない。

## 結果

- `ProjectApproved` / `ProjectReturned` / `ProjectRejected` / `ProjectPublished` /
  `ProjectSucceeded` / `ProjectFailed` に `ownerUserId` を追加。
- `NotificationEventHandler` は購読テンプレートごとに宛先種別（OWNER / SUPPORTER）を持ち、
  OWNER種別は `payload.ownerUserId` から宛先を解決する。
- Projectの公開契約に所有者逆引きクエリを追加しないため、コンテキスト間結合が増えない。

## AI利用

Claude（Opus 4.8）が2案を比較し、event-carried state transferの一般原則と既存イベントとの
一貫性から選択肢1を提案・実装した。本ADRは人間の承認を前提とする。

## 承認者

（教育担当者の承認欄）2026-07-21
