# Terraform state は暗号化S3 + DynamoDBロックで管理する（基本設計 §11.4）。
# bucket / dynamodb_table は環境ごとに異なるため partial backend とし、init時に指定する:
#
#   terraform init \
#     -backend-config="bucket=cftraining-tfstate" \
#     -backend-config="key=dev/terraform.tfstate" \
#     -backend-config="region=ap-northeast-1" \
#     -backend-config="dynamodb_table=cftraining-tflock" \
#     -backend-config="encrypt=true"
#
# 構文検証（CI）は `terraform init -backend=false` で行うため、この設定は無視される。
terraform {
  backend "s3" {}
}
