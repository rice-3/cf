"use client";

// SCR-070 会員1行分のロール編集・停止操作。
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ASSIGNABLE_ROLES, type AdminUserListItem } from "@/lib/api-types";
import { suspendUser, updateRoles } from "./actions";

export function UserRow({ user }: { user: AdminUserListItem }) {
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [roles, setRoles] = useState<string[]>(user.roles);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function toggleRole(role: string) {
    setRoles((prev) => (prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role]));
  }

  async function saveRoles() {
    setError(null);
    if (roles.length === 0) {
      setError("1つ以上のロールを選択してください。");
      return;
    }
    if (!reason.trim()) {
      setError("変更理由を入力してください。");
      return;
    }
    setSubmitting(true);
    try {
      const result = await updateRoles(user.userId, roles, user.version, reason.trim());
      if (!result.ok) {
        if (result.error.code === "ROLE_UPDATE_FORBIDDEN") {
          setError("自分自身のADMINロールは剥奪できません。");
        } else if (result.error.code === "OPTIMISTIC_LOCK_CONFLICT") {
          setError("他の場所で更新されています。再読み込みしてください。");
        } else {
          setError(result.error.detail ?? "ロール更新に失敗しました。");
        }
        return;
      }
      setEditing(false);
      router.refresh();
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSuspend() {
    if (!window.confirm(`${user.displayName} を停止しますか？`)) return;
    setError(null);
    setSubmitting(true);
    try {
      const result = await suspendUser(user.userId, user.version, reason.trim() || null);
      if (!result.ok) {
        if (result.error.code === "USER_SUSPEND_FORBIDDEN") {
          setError("自分自身は停止できません。");
        } else if (result.error.code === "USER_INVALID_STATE") {
          setError("この利用者は既に停止済みです。");
        } else {
          setError(result.error.detail ?? "会員停止に失敗しました。");
        }
        return;
      }
      router.refresh();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <tr>
        <td>{user.displayName}</td>
        <td>{user.email}</td>
        <td>{user.status}</td>
        <td>{editing ? "編集中" : user.roles.join(", ")}</td>
        <td>
          <div style={{ display: "flex", gap: "0.5rem" }}>
            <button type="button" onClick={() => setEditing((v) => !v)}>
              {editing ? "閉じる" : "ロール編集"}
            </button>
            {user.status !== "SUSPENDED" && (
              <button type="button" onClick={handleSuspend} disabled={submitting}>
                停止
              </button>
            )}
          </div>
        </td>
      </tr>
      {editing && (
        <tr>
          <td colSpan={5}>
            {error && <div className="error-summary" role="alert">{error}</div>}
            <fieldset>
              <legend>ロール（全置換）</legend>
              <div style={{ display: "flex", flexWrap: "wrap", gap: "0.75rem" }}>
                {ASSIGNABLE_ROLES.map((role) => (
                  <label key={role}>
                    <input type="checkbox" checked={roles.includes(role)} onChange={() => toggleRole(role)} /> {role}
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="form-field">
              <label htmlFor={`reason-${user.userId}`}>変更理由</label>
              <input id={`reason-${user.userId}`} value={reason} onChange={(e) => setReason(e.target.value)} />
            </div>
            <button type="button" className="button-primary" onClick={saveRoles} disabled={submitting}>
              ロールを保存
            </button>
          </td>
        </tr>
      )}
    </>
  );
}
