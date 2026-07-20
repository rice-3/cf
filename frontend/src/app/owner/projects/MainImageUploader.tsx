"use client";

// メイン画像アップロード（API-FL-001/002、§10.2）。
// ブラウザでSHA-256を計算し、発行→（本来はS3へPUT）→完了の順で呼び出す。
import { useState } from "react";
import { completeUpload, issueUpload } from "./actions";

const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const MAX_SIZE_BYTES = 10 * 1024 * 1024;

async function sha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function MainImageUploader({
  initialFileId,
  onUploaded,
}: {
  initialFileId: string | null;
  onUploaded: (fileId: string) => void;
}) {
  const [fileId, setFileId] = useState<string | null>(initialFileId);
  const [fileName, setFileName] = useState<string | null>(null);
  const [status, setStatus] = useState<"idle" | "uploading" | "done" | "error">(
    initialFileId ? "done" : "idle",
  );
  const [error, setError] = useState<string | null>(null);

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);

    if (!ALLOWED_TYPES.includes(file.type)) {
      setError("JPEG/PNG/WebP形式の画像を選択してください。");
      setStatus("error");
      return;
    }
    if (file.size > MAX_SIZE_BYTES) {
      setError("ファイルサイズは10MB以下にしてください。");
      setStatus("error");
      return;
    }

    setStatus("uploading");
    try {
      const sha256 = await sha256Hex(file);
      const issued = await issueUpload({
        purpose: "PROJECT_MAIN",
        fileName: file.name,
        contentType: file.type,
        size: file.size,
        sha256,
      });
      if (!issued.ok) {
        setError(issued.error.detail ?? "アップロードURLの発行に失敗しました。");
        setStatus("error");
        return;
      }

      // 実S3環境ではここで issued.data.uploadUrl へ直接PUTする（§10.2）。
      // local/testのS3スタブは発行時点で完了扱いのため、本アプリではPUTを行わない
      // （backend結合テストと同じ挙動。dev以上のS3接続時はPUTの追加が必要）。

      const completed = await completeUpload(issued.data.fileId, sha256);
      if (!completed.ok) {
        setError(completed.error.detail ?? "アップロードの完了処理に失敗しました。");
        setStatus("error");
        return;
      }

      setFileId(completed.data.fileId);
      setFileName(file.name);
      setStatus("done");
      onUploaded(completed.data.fileId);
    } catch {
      setError("アップロード中にエラーが発生しました。");
      setStatus("error");
    }
  }

  return (
    <div className="form-field">
      <label htmlFor="mainFile">メイン画像</label>
      <input
        id="mainFile"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        onChange={handleFileChange}
        aria-describedby="mainFile-status"
      />
      <div id="mainFile-status">
        {status === "uploading" && <span aria-busy="true">アップロード中...</span>}
        {status === "done" && fileId && <span>アップロード済み{fileName ? `: ${fileName}` : ""}</span>}
        {status === "error" && error && <p className="error">{error}</p>}
      </div>
    </div>
  );
}
