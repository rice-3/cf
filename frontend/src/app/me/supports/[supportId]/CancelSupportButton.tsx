"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { cancelSupport } from "@/app/projects/[projectId]/support/actions";

export function CancelSupportButton({ supportId }: { supportId: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleClick() {
    if (!window.confirm("この支援を取消しますか？")) return;
    setError(null);
    setSubmitting(true);
    try {
      const result = await cancelSupport(supportId);
      if (!result.ok) {
        setError(result.error.detail ?? "取消に失敗しました。");
        return;
      }
      router.refresh();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      {error && <div className="error-summary" role="alert">{error}</div>}
      <button type="button" onClick={handleClick} disabled={submitting}>
        支援を取消す
      </button>
    </div>
  );
}
