#!/bin/bash
set -e

# 패키지 업데이트
yum update -y

# Python 3.11 + pip 설치
yum install -y python3.11 python3.11-pip

# FastAPI + Uvicorn 설치
python3.11 -m pip install fastapi uvicorn

# 앱 디렉토리 생성
mkdir -p /app/mcp-server

# systemd 서비스 등록 (재부팅 시 자동 시작)
cat <<EOF > /etc/systemd/system/mcp-server.service
[Unit]
Description=MCP FastAPI Server
After=network.target

[Service]
WorkingDirectory=/app/mcp-server
ExecStart=/usr/local/bin/uvicorn main:app --host 0.0.0.0 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable mcp-server
