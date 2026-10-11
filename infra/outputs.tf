output "terraform_mcp_instance_id" {
  value = aws_instance.terraform_mcp.id
}

output "terraform_mcp_private_ip" {
  value = aws_instance.terraform_mcp.private_ip
}

output "terraform_mcp_public_ip" {
  value = aws_instance.terraform_mcp.public_ip
}

output "alb_base_url" {
  description = "현재 생성된 ALB의 API 주소. alb_enabled=false이면 null."
  value       = var.alb_enabled ? "http://${aws_lb.main[0].dns_name}" : null
}
