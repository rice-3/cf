// プロフィール編集（API-US-002）
import Link from "next/link";
import { BackendError, backendFetch } from "@/lib/backend";
import { ProfileEditForm } from "./ProfileEditForm";

export const dynamic = "force-dynamic";

interface ProfileResponse {
  displayName: string;
  email: string;
  version: number;
}

export default async function ProfileEditPage() {
  let profile: ProfileResponse;
  try {
    const envelope = await backendFetch<ProfileResponse>("/api/v1/me");
    profile = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && e.problem.status === 401) {
      return (
        <section>
          <h1>プロフィール編集</h1>
          <p className="error-summary">ログインが必要です。</p>
          <Link href="/login">ログイン画面へ</Link>
        </section>
      );
    }
    throw e;
  }

  return (
    <section>
      <h1>プロフィール編集</h1>
      <ProfileEditForm
        initial={{ displayName: profile.displayName, email: profile.email, version: profile.version }}
      />
    </section>
  );
}
