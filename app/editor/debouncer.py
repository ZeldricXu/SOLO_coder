from PyQt6.QtCore import QObject, QTimer, pyqtSignal


class DebouncedTimer(QObject):
    triggered = pyqtSignal()

    def __init__(self, interval_ms: int = 500, parent: QObject = None):
        super().__init__(parent)
        self.interval_ms = interval_ms
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self._on_timeout)
        self._call_count = 0
        self._trigger_count = 0

    @property
    def call_count(self) -> int:
        return self._call_count

    @property
    def trigger_count(self) -> int:
        return self._trigger_count

    @property
    def is_active(self) -> bool:
        return self._timer.isActive()

    @property
    def remaining_time(self) -> int:
        return self._timer.remainingTime()

    def start(self):
        self._call_count += 1
        self._timer.start(self.interval_ms)

    def stop(self):
        self._timer.stop()

    def reset(self):
        self._call_count = 0
        self._trigger_count = 0
        self._timer.stop()

    def _on_timeout(self):
        self._trigger_count += 1
        self.triggered.emit()


class DebouncedCallable:
    def __init__(self, func, interval_ms: int = 500):
        self._func = func
        self._timer = QTimer()
        self._timer.setSingleShot(True)
        self._timer.setInterval(interval_ms)
        self._timer.timeout.connect(self._execute)
        self._interval_ms = interval_ms
        self._call_count = 0
        self._execute_count = 0
        self._last_args = None
        self._last_kwargs = None

    @property
    def call_count(self) -> int:
        return self._call_count

    @property
    def execute_count(self) -> int:
        return self._execute_count

    @property
    def is_pending(self) -> bool:
        return self._timer.isActive()

    def __call__(self, *args, **kwargs):
        self._call_count += 1
        self._last_args = args
        self._last_kwargs = kwargs
        self._timer.start(self._interval_ms)

    def _execute(self):
        self._execute_count += 1
        if self._last_args or self._last_kwargs:
            args = self._last_args or ()
            kwargs = self._last_kwargs or {}
            self._func(*args, **kwargs)
        else:
            self._func()

    def flush(self):
        if self._timer.isActive():
            self._timer.stop()
            self._execute()

    def cancel(self):
        self._timer.stop()

    def reset(self):
        self._call_count = 0
        self._execute_count = 0
        self._timer.stop()


def debounce(interval_ms: int = 500):
    def decorator(func):
        return DebouncedCallable(func, interval_ms)
    return decorator
