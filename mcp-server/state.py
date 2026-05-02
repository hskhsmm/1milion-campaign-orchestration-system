import threading
import time

_alert_state: dict[str, float] = {}
_lock = threading.Lock()


def check_and_record(key: str, cooldown: int) -> bool:
    """can_alert + record_alert를 atomic하게 실행. True면 알림 발송 가능."""
    with _lock:
        if (time.time() - _alert_state.get(key, 0)) >= cooldown:
            _alert_state[key] = time.time()
            return True
        return False


def reset_alert(key: str) -> None:
    with _lock:
        _alert_state.pop(key, None)


def list_alerts() -> dict[str, float]:
    with _lock:
        return dict(_alert_state)


def reset_all() -> list[str]:
    with _lock:
        keys = list(_alert_state.keys())
        _alert_state.clear()
        return keys
