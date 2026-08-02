# SSM Parameter Store에서 DB 비밀번호 가져오기
data "aws_ssm_parameter" "db_password" {
  name            = "/batch-kafka/prod/SPRING_DATASOURCE_PASSWORD"
  with_decryption = true
}

resource "aws_db_parameter_group" "slow" {
  name        = "slow"
  family      = "mysql8.0"
  description = "slow query active"

  parameter {
    name  = "slow_query_log"
    value = "1"
  }

  parameter {
    name  = "long_query_time"
    value = "0.1"
  }

  parameter {
    name         = "log_output"
    value        = "TABLE"
    apply_method = "pending-reboot"
  }

  tags = {}
}

# MySQL 8.4 업그레이드용 파라미터 그룹.
# family는 불변 속성이라 기존 slow(mysql8.0)를 in-place로 바꾸면
# 인스턴스가 참조 중인 그룹을 destroy하려다 실패하므로 별도 리소스로 분리한다.
# 업그레이드 확인 후 위 aws_db_parameter_group.slow는 별도로 정리한다.
# mysql8.4 family용 옵션 그룹.
# "default:mysql-8-4"는 계정에 8.4를 한 번도 안 써서 아직 없고(OptionGroupNotFoundFault),
# 이름에 "default:" 접두사는 AWS가 예약해서 직접 생성할 수 없다.
# 이 인스턴스는 현재도 옵션이 하나도 없는 상태(default:mysql-8-0)라
# 옵션 없는 빈 옵션 그룹을 만들어 붙이면 동일하게 동작한다.
resource "aws_db_option_group" "mysql84" {
  name                     = "batch-kafka-mysql84"
  option_group_description = "MySQL 8.4 (no options)"
  engine_name              = "mysql"
  major_engine_version     = "8.4"

  tags = {}
}

resource "aws_db_parameter_group" "slow_mysql84" {
  name        = "slow-mysql84"
  family      = "mysql8.4"
  description = "slow query active (mysql8.4)"

  parameter {
    name  = "slow_query_log"
    value = "1"
  }

  parameter {
    name  = "long_query_time"
    value = "0.1"
  }

  parameter {
    name  = "log_output"
    value = "TABLE"
  }

  tags = {}
}

# RDS MySQL 인스턴스
resource "aws_db_instance" "batch_kafka_db" {
  identifier                  = "batch-kafka-db"
  engine                      = "mysql"
  engine_version              = "8.4.10"
  allow_major_version_upgrade = true
  instance_class              = "db.t3.micro"
  username                    = "batchuser"
  password                    = data.aws_ssm_parameter.db_password.value

  # 스토리지
  allocated_storage     = 20
  max_allocated_storage = 1000
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = "arn:aws:kms:ap-northeast-2:631124976154:key/8ad3a522-6d4f-4891-8c39-9f9d33cb9e38"

  # 네트워크
  db_subnet_group_name   = "default-vpc-02bacd8c658dc632e"
  vpc_security_group_ids = [aws_security_group.rds_mysql.id]
  availability_zone      = "ap-northeast-2b"
  publicly_accessible    = false
  multi_az               = false

  # 백업 및 유지보수
  apply_immediately       = true
  backup_retention_period = 1
  copy_tags_to_snapshot   = true
  skip_final_snapshot     = true
  deletion_protection     = false

  lifecycle {
    ignore_changes = [password]
  }

  # 파라미터/옵션 그룹
  parameter_group_name = aws_db_parameter_group.slow_mysql84.name
  option_group_name    = aws_db_option_group.mysql84.name

  tags = {}
}
