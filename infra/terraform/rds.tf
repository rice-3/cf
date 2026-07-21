resource "aws_db_subnet_group" "main" {
  name       = "${local.name_prefix}-db-subnet"
  subnet_ids = aws_subnet.private[*].id
  tags       = { Name = "${local.name_prefix}-db-subnet" }
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = "18"
  instance_class = var.db_instance_class

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.db_username
  # マスターパスワードはSecrets Managerで自動管理（平文をTerraform stateへ残さない、§11.4）
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az                = var.environment == "production"
  backup_retention_period = 7 # 日次バックアップ（基本設計 §6.2）
  deletion_protection     = var.environment == "production"
  skip_final_snapshot     = var.environment != "production"
  apply_immediately       = var.environment != "production"

  tags = { Name = "${local.name_prefix}-postgres" }
}
