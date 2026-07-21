"use client";

// SCR-031 審査操作（審査開始・承認・差戻し・却下）。
// 審査開始後のみ判断ボタンを有効化する（詳細設計 §7.4）。
import { useRouter } from "next/navigation";
import { useState } from "react";
import { REJECT_REASON_CODES, REVIEW_CHECKLIST_ITEMS } from "@/lib/api-types";
import { approveReview, rejectReview, returnReview, startReview } from "../actions";

const CHECKLIST_LABELS: Record<string, string> = {
  CONTENT_CONFIRMED: "掲載内容を確認した",
  LEGAL_CONFIRMED: "法令・ガイドライン適合を確認した",
  REWARD_CONFIRMED: "リターン内容の妥当性を確認した",
  PERIOD_CONFIRMED: "募集期間・目標金額の妥当性を確認した",
};

const REJECT_REASON_LABELS: Record<string, string> = {
  LEGAL_VIOLATION: "法令違反",
  INAPPROPRIATE_CONTENT: "不適切な内容",
  INSUFFICIENT_INFORMATION: "情報不足",
  DUPLICATE_PROJECT: "重複プロジェクト",
  OTHER: "その他",
};

export function ReviewActions({
  reviewId,
  status,
  version,
}: {
  reviewId: string;
  status: string;
  version: number;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [violations, setViolations] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const [checklist, setChecklist] = useState<Record<string, boolean>>({});
  const [approveComment, setApproveComment] = useState("");
  const [returnComment, setReturnComment] = useState("");
  const [rejectReasonCode, setRejectReasonCode] = useState<(typeof REJECT_REASON_CODES)[number]>(
    REJECT_REASON_CODES[0],
  );
  const [rejectComment, setRejectComment] = useState("");

  function handleResult(result: { ok: boolean; error?: { detail?: string; violations?: string[] } }) {
    if (!result.ok) {
      setError(result.error?.detail ?? "操作に失敗しました。");
      setViolations(result.error?.violations ?? []);
      return false;
    }
    router.refresh();
    return true;
  }

  async function withSubmit(fn: () => Promise<boolean>) {
    setError(null);
    setViolations([]);
    setSubmitting(true);
    try {
      await fn();
    } finally {
      setSubmitting(false);
    }
  }

  if (status === "REQUESTED") {
    return (
      <div>
        {error && <div className="error-summary" role="alert">{error}</div>}
        <p>この審査はまだ開始されていません。審査を開始すると担当者として割り当てられます。</p>
        <button
          type="button"
          className="button-primary"
          disabled={submitting}
          onClick={() => withSubmit(async () => handleResult(await startReview(reviewId)))}
        >
          審査を開始する
        </button>
      </div>
    );
  }

  if (status !== "UNDER_REVIEW") {
    return <p>この審査は完了しています（{status}）。これ以上の操作はできません。</p>;
  }

  const allChecked = REVIEW_CHECKLIST_ITEMS.every((c) => checklist[c]);

  return (
    <div>
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

      <section className="card">
        <h3>承認</h3>
        <fieldset>
          <legend>確認チェックリスト（全項目必須）</legend>
          {REVIEW_CHECKLIST_ITEMS.map((code) => (
            <div className="form-field" key={code}>
              <label>
                <input
                  type="checkbox"
                  checked={!!checklist[code]}
                  onChange={(e) => setChecklist((prev) => ({ ...prev, [code]: e.target.checked }))}
                />{" "}
                {CHECKLIST_LABELS[code] ?? code}
              </label>
            </div>
          ))}
        </fieldset>
        <div className="form-field">
          <label htmlFor="approveComment">コメント（任意）</label>
          <textarea
            id="approveComment"
            rows={2}
            maxLength={2000}
            value={approveComment}
            onChange={(e) => setApproveComment(e.target.value)}
          />
        </div>
        <button
          type="button"
          className="button-primary"
          disabled={!allChecked || submitting}
          onClick={() =>
            withSubmit(async () =>
              handleResult(await approveReview(reviewId, version, checklist, approveComment || null)),
            )
          }
        >
          承認する
        </button>
      </section>

      <section className="card">
        <h3>差戻し</h3>
        <div className="form-field">
          <label htmlFor="returnComment">差戻しコメント（必須）</label>
          <textarea
            id="returnComment"
            rows={2}
            maxLength={2000}
            value={returnComment}
            onChange={(e) => setReturnComment(e.target.value)}
          />
        </div>
        <button
          type="button"
          disabled={returnComment.trim().length === 0 || submitting}
          onClick={() =>
            withSubmit(async () => handleResult(await returnReview(reviewId, version, returnComment)))
          }
        >
          差戻す
        </button>
      </section>

      <section className="card">
        <h3>却下</h3>
        <div className="form-field">
          <label htmlFor="rejectReasonCode">理由区分（必須）</label>
          <select
            id="rejectReasonCode"
            value={rejectReasonCode}
            onChange={(e) => setRejectReasonCode(e.target.value as (typeof REJECT_REASON_CODES)[number])}
          >
            {REJECT_REASON_CODES.map((code) => (
              <option key={code} value={code}>
                {REJECT_REASON_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </div>
        <div className="form-field">
          <label htmlFor="rejectComment">却下コメント（必須）</label>
          <textarea
            id="rejectComment"
            rows={2}
            maxLength={2000}
            value={rejectComment}
            onChange={(e) => setRejectComment(e.target.value)}
          />
        </div>
        <button
          type="button"
          disabled={rejectComment.trim().length === 0 || submitting}
          onClick={() =>
            withSubmit(async () =>
              handleResult(await rejectReview(reviewId, version, rejectReasonCode, rejectComment)),
            )
          }
        >
          却下する
        </button>
      </section>
    </div>
  );
}
