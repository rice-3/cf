# cd.yml のリポジトリVariablesへ設定する値（terraform apply後に確認）。
output "aws_region" {
  value = var.aws_region
}

output "deploy_role_arn" {
  description = "GitHub Variables: AWS_ROLE_ARN"
  value       = aws_iam_role.github_deploy.arn
}

output "ecr_repository" {
  description = "GitHub Variables: ECR_REPOSITORY"
  value       = aws_ecr_repository.backend.name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster" {
  description = "GitHub Variables: ECS_CLUSTER"
  value       = aws_ecs_cluster.main.name
}

output "ecs_service" {
  description = "GitHub Variables: ECS_SERVICE"
  value       = aws_ecs_service.backend.name
}

output "ecs_task_family" {
  description = "GitHub Variables: ECS_TASK_FAMILY"
  value       = aws_ecs_task_definition.backend.family
}

output "container_name" {
  description = "GitHub Variables: CONTAINER_NAME"
  value       = local.container_name
}

output "alb_dns_name" {
  description = "アプリのエンドポイント（ALB）"
  value       = aws_lb.main.dns_name
}
