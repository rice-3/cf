"use client";

// プロフィール編集フォーム（API-US-002）。SCR-050から遷移。
import { useRouter } from "next/navigation";
import { useState } from "react";
import { updateProfile } from "./actions";

export function ProfileEditForm({
  initial,
}: {
  initial: { displayName: string; email: string; version: number };
}) {
  const router = useRouter();
  const [displayName, setDisplayName] = useState(initial.displayName);
  const [email, setEmail] = useState(initial.email);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const result = await updateProfile({ displayName, email, expectedVersion: initial.version });
      if (!result.ok) {
        if (result.error.code === "OPTIMISTIC_LOCK_CONFLICT") {
          setError("他の場所で更新されています。再読み込みしてください（MSG-W-001）。");
        } else if (result.error.code === "EMAIL_ALREADY_USED") {
          setError("このメールアドレスは既に使用されています。");
        } else {
          setError(result.error.detail ?? "更新に失敗しました。");
        }
        return;
      }
      router.push("/me");
      router.refresh();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      {error && (
        <div className="error-summary" role="alert">
          {error}
        </div>
      )}
      <div className="form-field">
        <label htmlFor="displayName">表示名</label>
        <input id="displayName" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={100} />
      </div>
      <div className="form-field">
        <label htmlFor="email">メールアドレス</label>
        <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>
      <button type="submit" className="button-primary" disabled={submitting}>
        保存
      </button>
    </form>
  );
}
