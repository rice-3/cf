// SCR-001 ログイン（教育用ローカル版）
// 本番はCognitoのHosted UIへリダイレクトする（基本設計 §10.1）。localでは
// 事前投入した開発用ユーザーを選択してロールを切り替える。
import { DEV_USERS } from "@/lib/devSession";
import { loginAsDevUser } from "../session-actions";

export const dynamic = "force-dynamic";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) {
  const { error } = await searchParams;

  return (
    <section>
      <h1>ログイン</h1>
      <p>
        教育用ローカル環境では、事前登録された開発用ユーザーを選択してログインします。
        本番環境ではAmazon Cognitoによる認証に置き換わります。
      </p>
      {error && (
        <p className="error-summary" role="alert">
          ログインに失敗しました。ユーザーを選択してください。
        </p>
      )}
      <form action={loginAsDevUser}>
        <div className="form-field">
          <label htmlFor="devUser">利用者</label>
          <select id="devUser" name="devUser" defaultValue="owner">
            {DEV_USERS.map((u) => (
              <option key={u.key} value={u.key}>
                {u.label}
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="button-primary">
          ログイン
        </button>
      </form>
    </section>
  );
}
