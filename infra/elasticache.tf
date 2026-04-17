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

# ElastiCache Valkey 단일 노드 (클러스터링은 #5에서 num_cache_clusters 증설 예정)
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "batch-kafka-redis"
  description          = "Valkey single node"
  engine               = "valkey"
  engine_version       = "7.2"
  node_type            = "cache.t3.micro"
  num_cache_clusters   = 1
  parameter_group_name = "default.valkey7"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.elasticache_redis.id]

  tags = {}
}
