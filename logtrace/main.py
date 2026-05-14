import signal
import sys
from typing import List

from logtrace.core.config import ConfigManager
from logtrace.core.models import LogRecord
from logtrace.modules.collector import LogCollector
from logtrace.modules.aggregator import LogAggregator
from logtrace.modules.storage import ElasticsearchStorage
from logtrace.modules.search import LogSearcher
from logtrace.modules.anomaly_detector import AnomalyDetector
from logtrace.modules.stats import StatsAnalyzer
from logtrace.modules.visualization import VisualizationService
from logtrace.modules.alert import AlertManager
from logtrace.api.routes import create_app


class LogTraceService:
    def __init__(self):
        self.config = ConfigManager()
        self.storage = ElasticsearchStorage(self.config)
        self.searcher = LogSearcher(self.config)
        self.anomaly_detector = AnomalyDetector(self.config)
        self.alert_manager = AlertManager(self.config)
        self.stats_analyzer = StatsAnalyzer(self.config)
        self.viz_service = VisualizationService(self.config)

        self.aggregator = LogAggregator(batch_size=100, flush_interval=1.0)
        self.collector = LogCollector(self.config, on_log_collected=self.aggregator.aggregate)

        self._setup_callbacks()
        self._running = False

    def _setup_callbacks(self):
        self.aggregator.set_batch_handler(self._process_batch)
        self.anomaly_detector.set_alert_callback(self._handle_alert)

    def _process_batch(self, logs: List[LogRecord]):
        try:
            marked_logs = self.anomaly_detector.process_logs(logs)
            self.storage.store_logs(marked_logs)
            for log in marked_logs:
                self.storage.update_stats(log.node_id, log.log_level)
        except Exception as e:
            print(f"Error processing log batch: {e}")

    def _handle_alert(self, alert_info: dict):
        self.alert_manager.send_alert(alert_info, self.storage)

    def start(self):
        print("Starting LogTrace Service...")
        self.storage.connect()
        self.aggregator.start()
        self.collector.start()
        self._running = True

        api_config = self.config.get_api_config()
        app = create_app(
            searcher=self.searcher,
            stats_analyzer=self.stats_analyzer,
            viz_service=self.viz_service,
            alert_manager=self.alert_manager,
            config=self.config
        )

        print(f"LogTrace API Service running on {api_config['host']}:{api_config['port']}")

        try:
            app.run(
                host=api_config['host'],
                port=api_config['port'],
                debug=api_config.get('debug', False),
                use_reloader=False
            )
        except KeyboardInterrupt:
            self.stop()

    def stop(self):
        print("\nStopping LogTrace Service...")
        self._running = False
        try:
            self.collector.stop()
        except Exception:
            pass
        try:
            self.aggregator.stop()
        except Exception:
            pass
        print("LogTrace Service stopped.")


def signal_handler(signum, frame):
    print(f"\nReceived signal {signum}, shutting down...")
    sys.exit(0)


def main():
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    service = LogTraceService()
    service.start()


if __name__ == '__main__':
    main()
