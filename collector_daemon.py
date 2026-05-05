import logging
import signal
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

project_root = Path(__file__).parent
sys.path.insert(0, str(project_root))

from app.services.storage import InfluxDBStorage
from app.services.collector import MetricCollector, CollectMode
from app.services.alert_engine import AlertEngine
from app.services.ssh_pool import SSHConnectionPool
from app import config

logger = logging.getLogger(__name__)


class CollectorDaemon:
    def __init__(self):
        self._shutdown = False
        self._shutdown_event = threading.Event()
        
        self._interval_seconds = config.get('collector', {}).get('interval_seconds', 60)
        
        self._storage: InfluxDBStorage = None
        self._collector: MetricCollector = None
        self._alert_engine: AlertEngine = None
        self._ssh_pool: SSHConnectionPool = None
        
        self._last_collect_time: datetime = None
        self._collect_count = 0
        self._alert_count = 0
        
        self._setup_logging()
    
    def _setup_logging(self):
        log_dir = Path("logs")
        log_dir.mkdir(exist_ok=True)
        
        log_level = logging.DEBUG if config['server'].get('debug', False) else logging.INFO
        
        logging.basicConfig(
            level=log_level,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            handlers=[
                logging.StreamHandler(),
                logging.handlers.RotatingFileHandler(
                    log_dir / "collector.log",
                    maxBytes=10 * 1024 * 1024,
                    backupCount=5,
                    encoding='utf-8'
                )
            ]
        )
        
        logging.getLogger('werkzeug').setLevel(logging.WARNING)
        logging.getLogger('urllib3').setLevel(logging.WARNING)
        logging.getLogger('paramiko').setLevel(logging.WARNING)
        logging.getLogger('influxdb_client').setLevel(logging.WARNING)
    
    def _init_components(self):
        logger.info("Initializing collector components...")
        
        try:
            self._storage = InfluxDBStorage(config['influxdb'])
            logger.info("Storage initialized")
        except Exception as e:
            logger.error(f"Failed to initialize storage: {e}")
            raise
        
        ssh_pool_config = config.get('ssh_pool', {})
        self._ssh_pool = SSHConnectionPool(
            max_connections_per_server=ssh_pool_config.get('max_connections_per_server', 3),
            max_total_connections=ssh_pool_config.get('max_total_connections', 10),
            idle_timeout_seconds=ssh_pool_config.get('idle_timeout_seconds', 300),
            cleanup_interval_seconds=ssh_pool_config.get('cleanup_interval_seconds', 60)
        )
        logger.info("SSH connection pool initialized")
        
        self._collector = MetricCollector(
            collector_config=config.get('collector', {}),
            ssh_pool=self._ssh_pool
        )
        logger.info(f"Metric collector initialized (mode: {self._collector.collect_mode.value})")
        
        self._alert_engine = AlertEngine()
        
        notification_config = config.get('notification', {})
        if notification_config.get('async_enabled', True):
            self._alert_engine.start()
        
        logger.info("Alert engine initialized")
    
    def _cleanup(self):
        logger.info("Cleaning up...")
        
        if self._alert_engine:
            self._alert_engine.stop()
            logger.info("Alert engine stopped")
        
        if self._ssh_pool:
            self._ssh_pool.shutdown()
            logger.info("SSH pool shut down")
        
        if self._storage:
            self._storage.close()
            logger.info("Storage closed")
        
        logger.info("Cleanup complete")
    
    def _collect_once(self):
        try:
            start_time = time.time()
            
            logger.info("Starting metric collection...")
            
            all_metrics_dict = self._collector.collect_all()
            
            total_metrics = 0
            all_metrics_list = []
            
            for server_id, metrics in all_metrics_dict.items():
                total_metrics += len(metrics)
                all_metrics_list.extend(metrics)
                logger.debug(f"Collected {len(metrics)} metrics for {server_id}")
            
            if all_metrics_list:
                written = self._storage.write_metrics_batch(all_metrics_list)
                logger.info(f"Written {written} metrics to storage")
                
                alert_events = self._alert_engine.process_metrics_batch(all_metrics_list)
                
                if alert_events:
                    self._alert_count += len(alert_events)
                    logger.info(f"Generated {len(alert_events)} alert events")
            
            self._collect_count += 1
            self._last_collect_time = datetime.utcnow()
            
            elapsed = time.time() - start_time
            logger.info(f"Collection cycle complete in {elapsed:.2f} seconds")
            
        except Exception as e:
            logger.error(f"Collection error: {e}", exc_info=True)
    
    def _run_loop(self):
        logger.info(f"Collector daemon started. Interval: {self._interval_seconds} seconds")
        
        while not self._shutdown:
            cycle_start = time.time()
            
            if not self._storage:
                self._init_components()
            
            self._collect_once()
            
            elapsed = time.time() - cycle_start
            sleep_time = max(0, self._interval_seconds - elapsed)
            
            if sleep_time > 0:
                logger.debug(f"Sleeping for {sleep_time:.2f} seconds")
                self._shutdown_event.wait(sleep_time)
            
            if self._shutdown:
                break
        
        self._cleanup()
    
    def start(self):
        def signal_handler(signum, frame):
            logger.info(f"Received signal {signum}, shutting down...")
            self._shutdown = True
            self._shutdown_event.set()
        
        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)
        
        try:
            self._run_loop()
        except KeyboardInterrupt:
            logger.info("Keyboard interrupt received")
            self._shutdown = True
            self._cleanup()
    
    def get_status(self) -> dict:
        return {
            "running": not self._shutdown,
            "collect_count": self._collect_count,
            "alert_count": self._alert_count,
            "last_collect_time": self._last_collect_time.isoformat() if self._last_collect_time else None,
            "interval_seconds": self._interval_seconds
        }


def main():
    daemon = CollectorDaemon()
    daemon.start()


if __name__ == '__main__':
    main()
