import time

_alert_state: dict[str, float] = {}


def can_alert(key: str, cooldown: int) -> bool:
    return (time.time() - _alert_state.get(key, 0)) >= cooldown


def record_alert(key: str) -> None:
    _alert_state[key] = time.time()


def reset_alert(key: str) -> None:
    _alert_state.pop(key, None)
