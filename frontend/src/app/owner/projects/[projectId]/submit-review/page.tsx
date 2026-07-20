// SCR-023 審査申請確認
import { notFound } from "next/navigation";
import { BackendError, backendFetch, type ProjectDetailView } from "@/lib/backend";
import { SubmitReviewForm } from "./SubmitReviewForm";

export const dynamic = "force-dynamic";

export default async function SubmitReviewPage({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;

  let detail: ProjectDetailView;
  try {
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
      <h1>審査申請確認</h1>
      <p>
        「{detail.title}」を審査へ申請します。申請後は差戻しまで内容を編集できません。
      </p>
      <SubmitReviewForm projectId={projectId} version={detail.version} />
    </section>
  );
}
