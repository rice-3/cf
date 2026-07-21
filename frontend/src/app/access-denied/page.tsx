// SCR-002 アクセス拒否
import Link from "next/link";

export default function AccessDeniedPage() {
  return (
    <section>
      <h1>アクセスできません</h1>
      <p className="error-summary" role="alert">
        この操作を実行する権限がありません（MSG-E-002）。
      </p>
      <p>必要なロールでログインし直すか、管理者へ権限付与を依頼してください。</p>
      <p style={{ display: "flex", gap: "0.75rem" }}>
        <Link href="/login">ログイン画面へ</Link>
        <Link href="/projects">プロジェクト検索へ</Link>
      </p>
    </section>
  );
}
