// SCR-021 プロジェクト編集（新規作成モード）
import { ProjectForm } from "../ProjectForm";

export default function NewProjectPage() {
  return (
    <section>
      <h1>新規プロジェクト作成</h1>
      <ProjectForm mode="create" />
    </section>
  );
}
