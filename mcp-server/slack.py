import logging

import requests

from config import SLACK_WEBHOOK_URL

logger = logging.getLogger(__name__)

LEVEL_EMOJI = {
    "P1": ":red_circle:",
    "P2": ":large_yellow_circle:",
    "P3": ":large_blue_circle:",
    "OK": ":white_check_mark:",
}


def send_alert(level: str, title: str, body: str) -> None:
    emoji = LEVEL_EMOJI.get(level, "")
    text = f"{emoji} *[{level}] {title}*\n{body}"
    try:
        resp = requests.post(
            SLACK_WEBHOOK_URL,
            json={"text": text},
            timeout=5,
        )
        resp.raise_for_status()
    except Exception as e:
        logger.error("Slack 전송 실패: %s", e)
