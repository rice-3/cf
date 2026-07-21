"use client";

// SCR-023 審査申請確認フォーム（詳細設計 §6.5: confirmations必須）。
import { useRouter } from "next/navigation";
import { useState } from "react";
import { REQUIRED_SUBMIT_CONFIRMATIONS } from "@/lib/api-types";
import { submitProjectForReview } from "../../actions";

const CONFIRMATION_LABELS: Record<string, string> = {
  TERMS_ACCEPTED: "利用規約・出品ガイドラインを確認し、これに従います。",
  CONTENT_RESPONSIBILITY_ACCEPTED: "掲載内容の正確性について、自らが責任を負います。",
};

export function SubmitReviewForm({ projectId, version }: { projectId: string; version: number }) {
  const router = useRouter();
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [violations, setViolations] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const allChecked = REQUIRED_SUBMIT_CONFIRMATIONS.every((c) => checked[c]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setViolations([]);
    setSubmitting(true);
    try {
      const result = await submitProjectForReview(projectId, version, Object.keys(checked).filter((k) => checked[k]));
      if (!result.ok) {
        setError(result.error.detail ?? "審査申請に失敗しました。");
        setViolations(result.error.violations ?? []);
        return;
      }
      router.push("/owner/projects");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      {error && (
        <div className="error-summary" role="alert">
          <p>{error}</p>
          {violations.length > 0 && (
            <ul>
              {violations.map((v) => (
                <li key={v}>{v}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      <fieldset>
        <legend>確認事項</legend>
        {REQUIRED_SUBMIT_CONFIRMATIONS.map((code) => (
          <div className="form-field" key={code}>
            <label>
              <input
                type="checkbox"
                checked={!!checked[code]}
                onChange={(e) => setChecked((prev) => ({ ...prev, [code]: e.target.checked }))}
              />{" "}
              {CONFIRMATION_LABELS[code] ?? code}
            </label>
          </div>
        ))}
      </fieldset>
      <button type="submit" className="button-primary" disabled={!allChecked || submitting}>
        審査申請を送信する
      </button>
    </form>
  );
}
