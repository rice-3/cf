import type { Metadata } from "next";
import Link from "next/link";
import { currentDevUser, hasRole } from "@/lib/devSession";
import { logout } from "./session-actions";
import "./globals.css";

export const metadata: Metadata = {
  title: "CF-Training",
  description: "クラウドファンディング型教育・実践開発システム",
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const user = await currentDevUser();

  return (
    <html lang="ja">
      <body>
        <header className="site-header">
          <nav aria-label="メインナビゲーション">
            <Link href="/projects" className="brand">
              CF-Training
            </Link>
            <Link href="/projects">プロジェクト検索</Link>
            {hasRole(user, "OWNER") && <Link href="/owner/projects">起案者</Link>}
            {hasRole(user, "REVIEWER") && <Link href="/reviews">審査</Link>}
            {hasRole(user, "SUPPORTER") && <Link href="/me/supports">支援履歴</Link>}
            {hasRole(user, "OPERATOR") && <Link href="/operations">運用</Link>}
            {hasRole(user, "ADMIN") && <Link href="/admin/users">会員管理</Link>}
            {(hasRole(user, "ADMIN") || hasRole(user, "AUDITOR")) && (
              <Link href="/admin/audit-logs">監査ログ</Link>
            )}
          </nav>
          <span className="header-right">
            <span className="env-badge" aria-label="環境表示">
              LOCAL
            </span>
            {user ? (
              <>
                <Link href="/me">{user.label}</Link>
                <form action={logout} style={{ display: "inline" }}>
                  <button type="submit" className="link-button">
                    ログアウト
                  </button>
                </form>
              </>
            ) : (
              <Link href="/login">ログイン</Link>
            )}
          </span>
        </header>
        <main className="site-main">{children}</main>
        <footer className="site-footer">教育用システム — 実際の資金移動は行いません</footer>
      </body>
    </html>
  );
}
