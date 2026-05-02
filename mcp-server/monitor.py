import logging

from detectors.p1_detector import check_p1
from detectors.p2_detector import check_p2
from detectors.p3_detector import check_p3

logger = logging.getLogger(__name__)


def run_monitor() -> None:
    for check in [check_p1, check_p2, check_p3]:
        try:
            check()
        except Exception as e:
            logger.error("%s 오류: %s", check.__name__, e)


def run_consistency_check() -> None:
    """1시간마다 실행되는 정합성 검사 (p1_detector에서 구현)."""
    try:
        from detectors.p1_detector import check_consistency
        check_consistency()
    except Exception as e:
        logger.error("consistency_check 오류: %s", e)
