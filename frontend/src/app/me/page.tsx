// SCR-050 マイページ（プロフィール表示、API-US-001）
import Link from "next/link";
import { BackendError, backendFetch } from "@/lib/backend";
import { formatDateTime } from "@/lib/format";
import { logout } from "../session-actions";

export const dynamic = "force-dynamic";

interface ProfileResponse {
  userId: string;
  email: string;
  displayName: string;
  status: string;
  roles: string[];
  version: number;
  updatedAt: string;
}

export default async function MyPage() {
  let profile: ProfileResponse;
  try {
    const envelope = await backendFetch<ProfileResponse>("/api/v1/me");
    profile = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && e.problem.status === 401) {
      return (
        <section>
          <h1>マイページ</h1>
          <p className="error-summary">ログインが必要です。</p>
          <Link href="/login">ログイン画面へ</Link>
        </section>
      );
    }
    throw e;
  }

  return (
    <section>
      <h1>マイページ</h1>
      <dl>
        <dt>表示名</dt>
        <dd>{profile.displayName}</dd>
        <dt>メールアドレス</dt>
        <dd>{profile.email}</dd>
        <dt>状態</dt>
        <dd>{profile.status}</dd>
        <dt>ロール</dt>
        <dd>{profile.roles.join(", ")}</dd>
        <dt>最終更新</dt>
        <dd>{formatDateTime(profile.updatedAt)}</dd>
      </dl>
      <p style={{ display: "flex", gap: "0.75rem" }}>
        <Link href="/me/edit">プロフィール編集</Link>
        <Link href="/me/supports">支援履歴</Link>
      </p>
      <form action={logout}>
        <button type="submit">ログアウト</button>
      </form>
    </section>
  );
}
