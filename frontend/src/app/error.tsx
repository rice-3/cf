"use client";

// SCR-080 システムエラー（グローバルエラーバウンダリ）。
// 予期しない例外を捕捉し、問い合わせ用の識別子（digest）とともに表示する（基本設計 §5.7 MSG-E-999）。
import { useEffect } from "react";
import Link from "next/link";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // クライアント側の詳細はコンソールのみ。画面へはスタックトレースを出さない（§11.2）。
    console.error(error);
  }, [error]);

  return (
    <section>
      <h1>システムエラー</h1>
      <p className="error-summary" role="alert">
        処理を完了できませんでした。お問い合わせの際は次の識別子をお伝えください。
      </p>
      {error.digest && (
        <p>
          エラー識別子: <code>{error.digest}</code>
        </p>
      )}
      <p style={{ display: "flex", gap: "0.75rem" }}>
        <button type="button" className="button-primary" onClick={() => reset()}>
          再試行
        </button>
        <Link href="/projects">プロジェクト検索へ</Link>
      </p>
    </section>
  );
}
