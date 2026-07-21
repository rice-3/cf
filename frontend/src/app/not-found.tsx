import Link from "next/link";

export default function NotFound() {
  return (
    <section>
      <h1>ページが見つかりません</h1>
      <p>お探しのページは存在しないか、閲覧権限がありません。</p>
      <Link href="/projects">プロジェクト検索へ</Link>
    </section>
  );
}
