# ElastiCache 서브넷 그룹 (기존 리소스 import)
resource "aws_elasticache_subnet_group" "main" {
  name        = "batch-kafka"
  description = " "
  subnet_ids  = [
    "subnet-03e64a5c78e57a505",  # private_app_2a
    "subnet-01b092eda6da614fc",  # public_2a
    "subnet-028c65ac17ecc8447",  # public_2c
    "subnet-0e4e9986cda504b3e",  # public_2d
    "subnet-095abce6c4befae62",  # public_2b
  ]

  tags = {}
}

# AOF 활성화 커스텀 파라미터 그룹
# default.valkey7.cluster.on은 AWS 기본 그룹이라 수정 불가 → 커스텀으로 생성
# family는 반드시 "valkey7.cluster.on" — CME에는 standalone family("valkey7") 사용 불가
resource "aws_elasticache_parameter_group" "valkey_cluster_aof" {
  family = "valkey7.cluster.on"
  name   = "valkey7-cluster-aof"

  parameter {
    name  = "appendonly"
    value = "yes"
  }
}

# ElastiCache Valkey CME 3샤드 (Cluster Mode Enabled)
# CMD(단일 노드) → CME 전환은 in-place 불가 → terraform destroy 후 apply 필요
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "batch-kafka-redis"
  description                = "Valkey cluster 3 shards 1 replica"
  engine                     = "valkey"
  engine_version             = "7.2"
  node_type                  = "cache.t3.micro"
  num_node_groups            = 3    # 샤드 수
  replicas_per_node_group    = 1    # 샤드당 replica 1개 → primary 장애 시 auto-failover
  parameter_group_name       = aws_elasticache_parameter_group.valkey_cluster_aof.name
  automatic_failover_enabled = true

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.elasticache_redis.id]

  tags = {}
}

# terraform apply 완료 시 엔드포인트를 SSM에 자동 등록
# beforeInstall.sh가 이 값을 읽어 앱/exporter에 주입
resource "aws_ssm_parameter" "redis_cluster_nodes" {
  name  = "/batch-kafka/prod/SPRING_DATA_REDIS_CLUSTER_NODES"
  type  = "SecureString"
  value = "${aws_elasticache_replication_group.redis.configuration_endpoint_address}:6379"

  lifecycle {
    ignore_changes = [value]  # 수동 업데이트 허용 (엔드포인트 변경 시 덮어씌움 방지)
  }
}

resource "aws_ssm_parameter" "redis_exporter_addr" {
  name  = "/batch-kafka/prod/REDIS_EXPORTER_ADDR"
  type  = "SecureString"
  value = "redis://${aws_elasticache_replication_group.redis.configuration_endpoint_address}:6379"

  lifecycle {
    ignore_changes = [value]
  }
}

output "redis_configuration_endpoint" {
  description = "ElastiCache CME Configuration Endpoint (앱/exporter SSM 확인용)"
  value       = aws_elasticache_replication_group.redis.configuration_endpoint_address
}
