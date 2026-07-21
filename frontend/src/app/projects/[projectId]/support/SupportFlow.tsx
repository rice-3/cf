"use client";

// SCR-040 支援入力 → SCR-041 支援確認 → SCR-042 支援結果 を1つのステッパーで実装する。
// 一時的な支援内容はコンポーネントのメモリ上にのみ保持し、localStorageへは保存しない（§7.9）。
import Link from "next/link";
import { useMemo, useState } from "react";
import type { RewardPlanView } from "@/lib/api-types";
import { formatYen } from "@/lib/format";
import { requestSupport } from "./actions";

type Step = "input" | "confirm" | "result";

export function SupportFlow({
  projectId,
  projectTitle,
  rewardPlans,
}: {
  projectId: string;
  projectTitle: string;
  rewardPlans: RewardPlanView[];
}) {
  const [step, setStep] = useState<Step>("input");

  const [rewardPlanId, setRewardPlanId] = useState<string>("");
  const [quantity, setQuantity] = useState(1);
  const [additionalAmount, setAdditionalAmount] = useState(0);
  const [contactEmail, setContactEmail] = useState("");
  const [termsAccepted, setTermsAccepted] = useState(false);

  const [inputError, setInputError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // 確定処理のリトライ間で不変にするため、確認画面へ進む時点で一度だけ生成する（§5.6）
  const [idempotencyKey, setIdempotencyKey] = useState<string>("");
  const [resultSupportId, setResultSupportId] = useState<string>("");
  const [resultPaymentStatus, setResultPaymentStatus] = useState<string>("");

  const selectedReward = useMemo(
    () => rewardPlans.find((r) => r.rewardPlanId === rewardPlanId) ?? null,
    [rewardPlans, rewardPlanId],
  );

  const totalAmount = useMemo(() => {
    const rewardTotal = selectedReward ? selectedReward.unitAmount * quantity : 0;
    return rewardTotal + (Number.isFinite(additionalAmount) ? additionalAmount : 0);
  }, [selectedReward, quantity, additionalAmount]);

  function goConfirm(e: React.FormEvent) {
    e.preventDefault();
    setInputError(null);

    if (!contactEmail.trim()) {
      setInputError("連絡先メールアドレスを入力してください。");
      return;
    }
    if (!termsAccepted) {
      setInputError("規約への同意が必要です。");
      return;
    }
    if (selectedReward) {
      if (quantity < 1 || quantity > 99) {
        setInputError("数量は1〜99の範囲で入力してください。");
        return;
      }
      if (selectedReward.remainingQuantity !== null && quantity > selectedReward.remainingQuantity) {
        setInputError(`残数（${selectedReward.remainingQuantity}）を超えています。`);
        return;
      }
    }
    if (totalAmount < 1) {
      setInputError("支援額が0円です。リターンを選択するか追加支援額を入力してください。");
      return;
    }

    // crypto.randomUUID はブラウザ標準（§5.6 冪等キー）
    setIdempotencyKey(crypto.randomUUID());
    setStep("confirm");
  }

  async function confirmSupport() {
    setSubmitError(null);
    setSubmitting(true);
    try {
      const result = await requestSupport({
        projectId,
        rewardPlanId: rewardPlanId || null,
        quantity: selectedReward ? quantity : 1,
        additionalAmount,
        contactEmail,
        termsAccepted,
        idempotencyKey,
      });
      if (!result.ok) {
        const code = result.error.code;
        if (code === "REWARD_SOLD_OUT") {
          setSubmitError("選択されたリターンは売り切れました。");
        } else if (code === "PROJECT_NOT_SUPPORTABLE") {
          setSubmitError("現在このプロジェクトは支援を受け付けていません。");
        } else {
          setSubmitError(result.error.detail ?? "支援の申込に失敗しました。");
        }
        return;
      }
      setResultSupportId(result.data.supportId);
      setResultPaymentStatus(result.data.paymentStatus);
      setStep("result");
    } finally {
      setSubmitting(false);
    }
  }

  if (step === "result") {
    return (
      <div>
        <h2>支援を受け付けました（SCR-042）</h2>
        <p className="card">
          支援ID: <code>{resultSupportId}</code>
          <br />
          決済状態: {resultPaymentStatus}
        </p>
        <p>
          決済結果は非同期で確定します。最新状態は
          <Link href="/me/supports"> 支援履歴 </Link>
          でご確認ください（MSG-W-002）。
        </p>
      </div>
    );
  }

  if (step === "confirm") {
    return (
      <div>
        <h2>支援内容の確認（SCR-041）</h2>
        {submitError && <div className="error-summary" role="alert">{submitError}</div>}
        <dl className="card">
          <dt>プロジェクト</dt>
          <dd>{projectTitle}</dd>
          <dt>リターン</dt>
          <dd>{selectedReward ? `${selectedReward.name}（×${quantity}）` : "リターンなし（寄付）"}</dd>
          <dt>追加支援額</dt>
          <dd>{formatYen(additionalAmount)}</dd>
          <dt>合計支援額</dt>
          <dd>
            <strong>{formatYen(totalAmount)}</strong>
          </dd>
          <dt>連絡先</dt>
          <dd>{contactEmail}</dd>
        </dl>
        <div style={{ display: "flex", gap: "0.75rem" }}>
          <button type="button" onClick={() => setStep("input")} disabled={submitting}>
            修正する
          </button>
          <button type="button" className="button-primary" onClick={confirmSupport} disabled={submitting}>
            この内容で支援する
          </button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={goConfirm} noValidate>
      <h2>支援内容の入力（SCR-040）</h2>
      {inputError && <div className="error-summary" role="alert">{inputError}</div>}

      <div className="form-field">
        <label htmlFor="rewardPlanId">リターン</label>
        <select id="rewardPlanId" value={rewardPlanId} onChange={(e) => setRewardPlanId(e.target.value)}>
          <option value="">リターンなし（寄付のみ）</option>
          {rewardPlans.map((r) => (
            <option
              key={r.rewardPlanId}
              value={r.rewardPlanId}
              disabled={r.remainingQuantity !== null && r.remainingQuantity <= 0}
            >
              {r.name}（{formatYen(r.unitAmount)}）
              {r.remainingQuantity !== null ? ` 残り${r.remainingQuantity}` : ""}
            </option>
          ))}
        </select>
      </div>

      {selectedReward && (
        <div className="form-field">
          <label htmlFor="quantity">数量</label>
          <input
            id="quantity"
            type="number"
            min={1}
            max={99}
            value={quantity}
            onChange={(e) => setQuantity(Number(e.target.value))}
          />
        </div>
      )}

      <div className="form-field">
        <label htmlFor="additionalAmount">追加支援額（円）</label>
        <input
          id="additionalAmount"
          type="number"
          min={0}
          value={additionalAmount}
          onChange={(e) => setAdditionalAmount(Number(e.target.value))}
        />
      </div>

      <div className="form-field">
        <label htmlFor="contactEmail">連絡先メールアドレス</label>
        <input
          id="contactEmail"
          type="email"
          value={contactEmail}
          onChange={(e) => setContactEmail(e.target.value)}
        />
      </div>

      <p>
        合計支援額: <strong>{formatYen(totalAmount)}</strong>
      </p>

      <div className="form-field">
        <label>
          <input type="checkbox" checked={termsAccepted} onChange={(e) => setTermsAccepted(e.target.checked)} />{" "}
          規約に同意します
        </label>
      </div>

      <button type="submit" className="button-primary">
        確認へ進む
      </button>
    </form>
  );
}
