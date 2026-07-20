import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "CF-Training",
  description: "クラウドファンディング型教育・実践開発システム",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>
        <header className="site-header">
          <nav aria-label="メインナビゲーション">
            <Link href="/projects" className="brand">
              CF-Training
            </Link>
            <Link href="/projects">プロジェクト検索</Link>
            <Link href="/owner/projects">起案者メニュー</Link>
          </nav>
          <span className="env-badge" aria-label="環境表示">
            LOCAL
          </span>
        </header>
        <main className="site-main">{children}</main>
        <footer className="site-footer">教育用システム — 実際の資金移動は行いません</footer>
      </body>
    </html>
  );
}
