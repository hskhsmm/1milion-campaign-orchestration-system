output "terraform_mcp_instance_id" {
  value = aws_instance.terraform_mcp.id
}

output "terraform_mcp_private_ip" {
  value = aws_instance.terraform_mcp.private_ip
}

output "terraform_mcp_public_ip" {
  value = aws_instance.terraform_mcp.public_ip
}
