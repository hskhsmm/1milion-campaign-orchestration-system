import logging
from contextlib import asynccontextmanager

import uvicorn
from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI
from pydantic import BaseModel

import config
from monitor import run_consistency_check, run_monitor
from slack import send_alert
from tools import mcp_routes

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

scheduler = BackgroundScheduler()


@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.add_job(run_monitor, "interval", seconds=30, id="monitor")
    scheduler.add_job(run_consistency_check, "interval", hours=1, id="consistency")
    scheduler.start()
    logger.info("스케줄러 시작 — 30초 폴링, 1시간 정합성 검사")
    send_alert("OK", "모니터링 시작", "MCP 모니터링 서버가 정상 기동되었습니다.")
    yield
    scheduler.shutdown(wait=False)
    logger.info("스케줄러 종료")


app = FastAPI(title="MCP Monitor Server", routes=mcp_routes, lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


class CampaignConfig(BaseModel):
    campaign_id: int


@app.put("/config/campaign")
def update_campaign(body: CampaignConfig) -> dict:
    config.BATCH_CAMPAIGN_ID = body.campaign_id
    logger.info(f"BATCH_CAMPAIGN_ID 변경 → {body.campaign_id}")
    return {"campaign_id": config.BATCH_CAMPAIGN_ID}


@app.get("/config/campaign")
def get_campaign() -> dict:
    return {"campaign_id": config.BATCH_CAMPAIGN_ID}


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
