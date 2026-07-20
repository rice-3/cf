"use client";

// SCR-021 プロジェクト編集フォーム（新規作成・更新共通、詳細設計 §7.3）。
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { z } from "zod";
import { fromDateTimeLocalValue, toDateTimeLocalValue } from "@/lib/format";
import { cancelProject, createProject, updateProject, type ProjectFormInput } from "./actions";
import { MainImageUploader } from "./MainImageUploader";

const rewardPlanSchema = z.object({
  name: z.string().min(1, "リターン名を入力してください。").max(100),
  description: z.string().min(1, "説明を入力してください。").max(2000),
  unitAmount: z.number().int().min(1, "1円以上にしてください。"),
  quantityLimit: z.number().int().min(1).optional(),
  displayOrder: z.number().int().min(0),
});

const formSchema = z.object({
  title: z.string().min(1, "タイトルを入力してください。").max(100, "100文字以内で入力してください。"),
  summary: z.string().min(1, "概要を入力してください。").max(300, "300文字以内で入力してください。"),
  body: z.string().min(1, "本文を入力してください。").max(20000, "20,000文字以内で入力してください。"),
  targetAmount: z
    .number()
    .int()
    .min(1000, "1,000円以上にしてください。")
    .max(100_000_000, "100,000,000円以下にしてください。"),
  fundingType: z.enum(["ALL_OR_NOTHING", "ALL_IN"]),
  startAt: z.string().min(1, "募集開始日時を入力してください。"),
  endAt: z.string().min(1, "募集終了日時を入力してください。"),
  rewardPlans: z.array(rewardPlanSchema).max(100),
});

type FormValues = z.infer<typeof formSchema>;

export interface ProjectFormInitial {
  projectId: string;
  version: number;
  status: string;
  title: string;
  summary: string;
  body: string;
  targetAmount: number;
  fundingType: "ALL_OR_NOTHING" | "ALL_IN";
  startAt: string;
  endAt: string;
  mainFileId: string | null;
  rewardPlans: {
    name: string;
    description: string;
    unitAmount: number;
    quantityLimit: number | null;
    displayOrder: number;
  }[];
}

type ProjectFormProps = { mode: "create" } | { mode: "edit"; initial: ProjectFormInitial };

export function ProjectForm(props: ProjectFormProps) {
  const router = useRouter();
  const [mainFileId, setMainFileId] = useState<string | null>(
    props.mode === "edit" ? props.initial.mainFileId : null,
  );
  const [serverError, setServerError] = useState<string | null>(null);
  const [violations, setViolations] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const defaults: FormValues =
    props.mode === "edit"
      ? {
          title: props.initial.title,
          summary: props.initial.summary,
          body: props.initial.body,
          targetAmount: props.initial.targetAmount,
          fundingType: props.initial.fundingType,
          startAt: toDateTimeLocalValue(props.initial.startAt),
          endAt: toDateTimeLocalValue(props.initial.endAt),
          rewardPlans: props.initial.rewardPlans.map((r) => ({
            ...r,
            quantityLimit: r.quantityLimit ?? undefined,
          })),
        }
      : {
          title: "",
          summary: "",
          body: "",
          targetAmount: 1000,
          fundingType: "ALL_OR_NOTHING",
          startAt: "",
          endAt: "",
          rewardPlans: [],
        };

  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(formSchema), defaultValues: defaults });

  const { fields, append, remove } = useFieldArray({ control, name: "rewardPlans" });

  const editable = props.mode === "create" || props.initial.status === "DRAFT" || props.initial.status === "RETURNED";

  async function onSubmit(values: FormValues) {
    setServerError(null);
    setViolations([]);
    setSubmitting(true);
    try {
      const input: ProjectFormInput = {
        title: values.title,
        summary: values.summary,
        body: values.body,
        targetAmount: values.targetAmount,
        fundingType: values.fundingType,
        startAt: fromDateTimeLocalValue(values.startAt),
        endAt: fromDateTimeLocalValue(values.endAt),
        mainFileId,
        rewardPlans: values.rewardPlans.map((r, i) => ({
          name: r.name,
          description: r.description,
          unitAmount: r.unitAmount,
          quantityLimit: r.quantityLimit ?? null,
          displayOrder: r.displayOrder ?? i,
        })),
      };

      if (props.mode === "create") {
        const result = await createProject(input);
        if (!result.ok) {
          setServerError(result.error.detail ?? "作成に失敗しました。");
          setViolations(result.error.violations ?? []);
          return;
        }
        router.push(`/owner/projects/${result.data.projectId}/edit`);
        return;
      }

      const result = await updateProject(props.initial.projectId, props.initial.version, input);
      if (!result.ok) {
        if (result.error.code === "OPTIMISTIC_LOCK_CONFLICT") {
          setServerError(
            "他の利用者により更新されています。ページを再読み込みしてから再度お試しください（MSG-W-001）。",
          );
        } else {
          setServerError(result.error.detail ?? "更新に失敗しました。");
        }
        setViolations(result.error.violations ?? []);
        return;
      }
      router.push(`/owner/projects/${props.initial.projectId}/edit`);
      router.refresh();
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCancel() {
    if (props.mode === "create") return;
    if (!window.confirm("このプロジェクトを取消しますか？")) return;
    const result = await cancelProject(props.initial.projectId, props.initial.version, null);
    if (!result.ok) {
      setServerError(result.error.detail ?? "取消に失敗しました。");
      return;
    }
    router.push("/owner/projects");
  }

  if (!editable) {
    return (
      <p className="error-summary">
        現在の状態（{props.mode === "edit" ? props.initial.status : ""}）では編集できません。
        差戻し後に編集可能になります。
      </p>
    );
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      {serverError && (
        <div className="error-summary" role="alert">
          <p>{serverError}</p>
          {violations.length > 0 && (
            <ul>
              {violations.map((v) => (
                <li key={v}>{v}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="form-field">
        <label htmlFor="title">タイトル</label>
        <input id="title" {...register("title")} aria-invalid={!!errors.title} aria-describedby="title-error" />
        {errors.title && <p id="title-error" className="error">{errors.title.message}</p>}
      </div>

      <div className="form-field">
        <label htmlFor="summary">概要</label>
        <textarea id="summary" rows={2} {...register("summary")} aria-invalid={!!errors.summary} />
        {errors.summary && <p className="error">{errors.summary.message}</p>}
      </div>

      <div className="form-field">
        <label htmlFor="body">本文</label>
        <textarea id="body" rows={8} {...register("body")} aria-invalid={!!errors.body} />
        {errors.body && <p className="error">{errors.body.message}</p>}
      </div>

      <div className="form-field">
        <label htmlFor="targetAmount">目標金額（円）</label>
        <input
          id="targetAmount"
          type="number"
          {...register("targetAmount", { valueAsNumber: true })}
          aria-invalid={!!errors.targetAmount}
        />
        {errors.targetAmount && <p className="error">{errors.targetAmount.message}</p>}
      </div>

      <div className="form-field">
        <label htmlFor="fundingType">募集方式</label>
        <select id="fundingType" {...register("fundingType")}>
          <option value="ALL_OR_NOTHING">All-or-Nothing</option>
          <option value="ALL_IN">All-in</option>
        </select>
      </div>

      <div className="form-field">
        <label htmlFor="startAt">募集開始日時</label>
        <input id="startAt" type="datetime-local" {...register("startAt")} aria-invalid={!!errors.startAt} />
        {errors.startAt && <p className="error">{errors.startAt.message}</p>}
      </div>

      <div className="form-field">
        <label htmlFor="endAt">募集終了日時</label>
        <input id="endAt" type="datetime-local" {...register("endAt")} aria-invalid={!!errors.endAt} />
        {errors.endAt && <p className="error">{errors.endAt.message}</p>}
      </div>

      <MainImageUploader initialFileId={mainFileId} onUploaded={setMainFileId} />

      <fieldset>
        <legend>リターン</legend>
        {fields.map((field, index) => (
          <div key={field.id} className="card">
            <div className="form-field">
              <label htmlFor={`rewardPlans.${index}.name`}>名称</label>
              <input id={`rewardPlans.${index}.name`} {...register(`rewardPlans.${index}.name`)} />
              {errors.rewardPlans?.[index]?.name && (
                <p className="error">{errors.rewardPlans[index]?.name?.message}</p>
              )}
            </div>
            <div className="form-field">
              <label htmlFor={`rewardPlans.${index}.description`}>説明</label>
              <textarea
                id={`rewardPlans.${index}.description`}
                rows={2}
                {...register(`rewardPlans.${index}.description`)}
              />
            </div>
            <div className="form-field">
              <label htmlFor={`rewardPlans.${index}.unitAmount`}>金額（円）</label>
              <input
                id={`rewardPlans.${index}.unitAmount`}
                type="number"
                {...register(`rewardPlans.${index}.unitAmount`, { valueAsNumber: true })}
              />
            </div>
            <div className="form-field">
              <label htmlFor={`rewardPlans.${index}.quantityLimit`}>数量上限（任意）</label>
              <input
                id={`rewardPlans.${index}.quantityLimit`}
                type="number"
                {...register(`rewardPlans.${index}.quantityLimit`, {
                  setValueAs: (v) => (v === "" || v === null || v === undefined ? undefined : Number(v)),
                })}
              />
            </div>
            <input
              type="hidden"
              {...register(`rewardPlans.${index}.displayOrder`, { valueAsNumber: true })}
              value={index}
            />
            <button type="button" onClick={() => remove(index)}>
              このリターンを削除
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() =>
            append({ name: "", description: "", unitAmount: 1000, quantityLimit: undefined, displayOrder: fields.length })
          }
        >
          リターンを追加
        </button>
      </fieldset>

      <div style={{ display: "flex", gap: "0.75rem", marginTop: "1.5rem" }}>
        <button type="submit" className="button-primary" disabled={submitting}>
          下書き保存
        </button>
        {props.mode === "edit" && (
          <>
            <Link href={`/owner/projects/${props.initial.projectId}/preview`}>プレビュー</Link>
            <Link href={`/owner/projects/${props.initial.projectId}/submit-review`}>審査申請へ</Link>
            <button type="button" onClick={handleCancel}>
              取消
            </button>
          </>
        )}
      </div>
    </form>
  );
}
