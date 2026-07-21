// SCR-040/041/042 支援入力〜確認〜結果（SupportFlowステッパー）
import Link from "next/link";
import { notFound } from "next/navigation";
import { BackendError, backendFetch, type ProjectDetailView } from "@/lib/backend";
import { SupportFlow } from "./SupportFlow";

export const dynamic = "force-dynamic";

export default async function SupportPage({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;

  let project: ProjectDetailView;
  try {
    const envelope = await backendFetch<ProjectDetailView>(`/api/v1/projects/${projectId}`);
    project = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && e.problem.status === 404) notFound();
    throw e;
  }

  if (project.status !== "PUBLISHED") {
    return (
      <section>
        <h1>支援</h1>
        <p className="error-summary">このプロジェクトは現在支援を受け付けていません。</p>
        <Link href={`/projects/${projectId}`}>プロジェクト詳細へ戻る</Link>
      </section>
    );
  }

  return (
    <section>
      <h1>「{project.title}」を支援する</h1>
      <p>
        <Link href={`/projects/${projectId}`}>← プロジェクト詳細へ戻る</Link>
      </p>
      <SupportFlow projectId={projectId} projectTitle={project.title} rewardPlans={project.rewardPlans} />
    </section>
  );
}
