// SCR-021 プロジェクト編集（更新モード）
import { notFound } from "next/navigation";
import { BackendError, backendFetch, type ProjectDetailView } from "@/lib/backend";
import { ProjectForm } from "../../ProjectForm";

export const dynamic = "force-dynamic";

export default async function EditProjectPage({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;

  let detail: ProjectDetailView;
  try {
    // 起案者向けの単体取得APIはないため、所有者/公開判定込みの公開詳細APIを使う（PublicProjectController）
    const envelope = await backendFetch<ProjectDetailView>(`/api/v1/projects/${projectId}`);
    detail = envelope.data;
  } catch (e) {
    if (e instanceof BackendError && (e.problem.status === 404 || e.problem.status === 403)) {
      notFound();
    }
    throw e;
  }

  return (
    <section>
      <h1>プロジェクト編集</h1>
      <ProjectForm
        mode="edit"
        initial={{
          projectId: detail.projectId,
          version: detail.version,
          status: detail.status,
          title: detail.title,
          summary: detail.summary,
          body: detail.body,
          targetAmount: detail.targetAmount,
          fundingType: detail.fundingType as "ALL_OR_NOTHING" | "ALL_IN",
          startAt: detail.startAt,
          endAt: detail.endAt,
          mainFileId: detail.mainFileId,
          rewardPlans: detail.rewardPlans.map((r) => ({
            name: r.name,
            description: r.description,
            unitAmount: r.unitAmount,
            quantityLimit: r.quantityLimit,
            displayOrder: r.displayOrder,
          })),
        }}
      />
    </section>
  );
}
