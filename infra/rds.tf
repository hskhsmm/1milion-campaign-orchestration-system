# SSM Parameter Store에서 DB 비밀번호 가져오기
data "aws_ssm_parameter" "db_password" {
  name            = "/batch-kafka/prod/SPRING_DATASOURCE_PASSWORD"
  with_decryption = true
}

# RDS MySQL 인스턴스
resource "aws_db_instance" "batch_kafka_db" {
  identifier        = "batch-kafka-db"
  engine            = "mysql"
  engine_version    = "8.0.44"
  instance_class    = "db.t3.micro"
  username          = "batchuser"
  password          = data.aws_ssm_parameter.db_password.value

  # 스토리지
  allocated_storage     = 20
  max_allocated_storage = 1000
  storage_type          = "gp2"
  storage_encrypted     = true
  kms_key_id            = "arn:aws:kms:ap-northeast-2:631124976154:key/8ad3a522-6d4f-4891-8c39-9f9d33cb9e38"

  # 네트워크
  db_subnet_group_name   = "default-vpc-02bacd8c658dc632e"
  vpc_security_group_ids = [aws_security_group.rds_mysql.id]
  availability_zone      = "ap-northeast-2b"
  publicly_accessible    = false
  multi_az               = false

  # 백업 및 유지보수
  backup_retention_period = 1
  copy_tags_to_snapshot   = true
  skip_final_snapshot     = true
  deletion_protection     = false

  lifecycle {
    ignore_changes = [password]
  }

  # 파라미터/옵션 그룹
  parameter_group_name = "default.mysql8.0"
  option_group_name    = "default:mysql-8-0"

  tags = {}
}
