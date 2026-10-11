"""정합성 검사 직전에 현재 ALB 주소를 조회한다."""

import boto3

import config


def current_batch_api_url() -> str:
    response = boto3.client("elbv2", region_name=config.AWS_REGION).describe_load_balancers(
        Names=[config.ALB_NAME]
    )
    load_balancers = response.get("LoadBalancers", [])
    if len(load_balancers) != 1:
        raise RuntimeError(f"ALB 조회 결과가 1건이 아닙니다: {config.ALB_NAME}")

    load_balancer = load_balancers[0]
    if load_balancer.get("State", {}).get("Code") != "active":
        raise RuntimeError(f"ALB가 active 상태가 아닙니다: {config.ALB_NAME}")

    dns_name = load_balancer.get("DNSName", "")
    if not dns_name.endswith(".elb.amazonaws.com"):
        raise RuntimeError(f"ALB DNS가 유효하지 않습니다: {config.ALB_NAME}")

    return f"http://{dns_name}"
