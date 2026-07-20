# ADR-0001: 初期はGradle単一Backendプロジェクト＋パッケージ分割で開始する

## 状態

採用

## 背景

詳細設計書 §1.5 は Gradle マルチプロジェクト構成（module-project 等）を示すが、
「教育初期にGradleマルチプロジェクトが負担になる場合は、単一Backendプロジェクト内の
パッケージ分割から開始してよい。ただし依存規則はArchUnitとSpring Modulithで同等に検査する」
と許容している（ADR-001候補: 教育初期はpackage、3か月目以降module化）。

## 判断

第1段階は `backend/` 単一Gradleプロジェクトとし、
`com.example.cf.{shared,identity,project,review,funding,payment,notification,file,audit}`
のパッケージ分割で境界を表現する。依存規則は ArchUnit で検査する。

## 選択肢

1. Gradleマルチプロジェクト（module-*）
2. 単一プロジェクト＋パッケージ分割＋ArchUnit（採用）
3. 単一プロジェクト・境界検査なし

## 判断理由

- 教育初期の認知負荷とビルド設定コストを抑える。
- ArchUnitにより依存方向・境界違反はCIで同等に検出できる。
- 3か月目以降、境界が安定した時点でモジュール分割へ移行できる（パッケージ構造は維持）。

## 結果

- 利点: セットアップ簡素、IDE体験向上、テスト横断が容易。
- 不利益: コンパイル単位での物理的強制がない → ArchUnitテストを品質ゲート必須とする。

## AI利用

AI（Claude Code）が設計書の許容規定に基づき提案し、実装時に採用した。

## 承認者

（教育担当者の承認欄）2026-07-20
