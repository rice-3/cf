// SCR-060 支援管理 / SCR-061 返金管理（運用コンソール）
import { OperationsConsole } from "./OperationsConsole";

export const dynamic = "force-dynamic";

export default function OperationsPage() {
  return (
    <section>
      <h1>運用コンソール</h1>
      <p>返金の要求・再実行、決済の照合を行います（OPERATOR / ADMIN）。すべての操作は監査ログへ記録されます。</p>
      <OperationsConsole />
    </section>
  );
}
