import logging
import platform
import socket
import re
from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any, Tuple
from uuid import uuid4
from enum import Enum

import psutil

from app.models.metric import Metric, MetricType
from app.services.ssh_pool import SSHConnectionPool, SSHConnectionConfig
from app import config

logger = logging.getLogger(__name__)


class CollectMode(str, Enum):
    LOCAL = "local"
    REMOTE = "remote"
    MIXED = "mixed"


class MetricCollector:
    def __init__(self, collector_config=None, ssh_pool: SSHConnectionPool = None):
        if collector_config is None:
            collector_config = config.get('collector', {})
        
        self.server_id = collector_config.get('server_id', self._get_default_server_id())
        self.interval_seconds = collector_config.get('interval_seconds', 60)
        self.enabled_metrics = collector_config.get('enabled_metrics', [
            'cpu_usage', 'memory_usage', 'disk_usage', 'network_io'
        ])
        
        collect_mode_str = collector_config.get('collect_mode', 'local')
        try:
            self.collect_mode = CollectMode(collect_mode_str)
        except ValueError:
            logger.warning(f"Invalid collect_mode '{collect_mode_str}', using 'local'")
            self.collect_mode = CollectMode.LOCAL
        
        self._ssh_pool = ssh_pool
        self._remote_servers: Dict[str, SSHConnectionConfig] = {}
        
        remote_servers_config = collector_config.get('remote_servers', [])
        for server_config in remote_servers_config:
            try:
                ssh_config = SSHConnectionConfig(
                    server_id=server_config['server_id'],
                    host=server_config['host'],
                    port=server_config.get('port', 22),
                    username=server_config.get('username', 'root'),
                    password=server_config.get('password'),
                    private_key_path=server_config.get('private_key_path'),
                    private_key_passphrase=server_config.get('private_key_passphrase'),
                    timeout=server_config.get('timeout', 10)
                )
                self._remote_servers[ssh_config.server_id] = ssh_config
                
                if self._ssh_pool:
                    self._ssh_pool.register_server(ssh_config)
                    
            except Exception as e:
                logger.error(f"Failed to configure remote server: {e}")
        
        self._last_network_counters: Dict[str, Any] = {}
        self._last_network_time: Dict[str, datetime] = {}
        
        logger.info(
            f"MetricCollector initialized: mode={self.collect_mode.value}, "
            f"server_id={self.server_id}, "
            f"remote_servers={list(self._remote_servers.keys())}"
        )
    
    def _get_default_server_id(self) -> str:
        hostname = socket.gethostname()
        return f"server_{hostname.replace('.', '_')}"
    
    def set_ssh_pool(self, ssh_pool: SSHConnectionPool):
        self._ssh_pool = ssh_pool
        for server_config in self._remote_servers.values():
            self._ssh_pool.register_server(server_config)
        logger.info("SSH pool configured for collector")
    
    def add_remote_server(self, config: SSHConnectionConfig):
        self._remote_servers[config.server_id] = config
        if self._ssh_pool:
            self._ssh_pool.register_server(config)
        logger.info(f"Added remote server: {config.server_id}")
    
    def remove_remote_server(self, server_id: str):
        if server_id in self._remote_servers:
            del self._remote_servers[server_id]
            if self._ssh_pool:
                self._ssh_pool.unregister_server(server_id)
            logger.info(f"Removed remote server: {server_id}")
            return True
        return False
    
    def get_remote_servers(self) -> List[str]:
        return list(self._remote_servers.keys())
    
    def collect_all(self) -> Dict[str, List[Metric]]:
        all_metrics = {}
        
        if self.collect_mode in [CollectMode.LOCAL, CollectMode.MIXED]:
            local_metrics = self._collect_local()
            all_metrics[self.server_id] = local_metrics
        
        if self.collect_mode in [CollectMode.REMOTE, CollectMode.MIXED]:
            for server_id in self._remote_servers.keys():
                remote_metrics = self._collect_remote(server_id)
                if remote_metrics:
                    all_metrics[server_id] = remote_metrics
        
        total_count = sum(len(m) for m in all_metrics.values())
        logger.info(f"Collected {total_count} metrics from {len(all_metrics)} servers")
        
        return all_metrics
    
    def collect_server(self, server_id: str) -> List[Metric]:
        if server_id == self.server_id:
            return self._collect_local()
        elif server_id in self._remote_servers:
            return self._collect_remote(server_id)
        else:
            logger.error(f"Unknown server: {server_id}")
            return []
    
    def _collect_local(self) -> List[Metric]:
        metrics = []
        collected_at = datetime.utcnow()
        
        if 'cpu_usage' in self.enabled_metrics:
            cpu_metric = self._collect_cpu_usage_local(collected_at)
            if cpu_metric:
                metrics.append(cpu_metric)
        
        if 'memory_usage' in self.enabled_metrics:
            memory_metric = self._collect_memory_usage_local(collected_at)
            if memory_metric:
                metrics.append(memory_metric)
        
        if 'disk_usage' in self.enabled_metrics:
            disk_metric = self._collect_disk_usage_local(collected_at)
            if disk_metric:
                metrics.append(disk_metric)
        
        if 'network_io' in self.enabled_metrics:
            network_metrics = self._collect_network_io_local(collected_at)
            metrics.extend(network_metrics)
        
        return metrics
    
    def _collect_remote(self, server_id: str) -> List[Metric]:
        if not self._ssh_pool:
            logger.error("SSH pool not configured for remote collection")
            return []
        
        if server_id not in self._remote_servers:
            logger.error(f"Server {server_id} not configured")
            return []
        
        metrics = []
        collected_at = datetime.utcnow()
        
        try:
            if 'cpu_usage' in self.enabled_metrics:
                cpu_metric = self._collect_cpu_usage_remote(server_id, collected_at)
                if cpu_metric:
                    metrics.append(cpu_metric)
            
            if 'memory_usage' in self.enabled_metrics:
                memory_metric = self._collect_memory_usage_remote(server_id, collected_at)
                if memory_metric:
                    metrics.append(memory_metric)
            
            if 'disk_usage' in self.enabled_metrics:
                disk_metric = self._collect_disk_usage_remote(server_id, collected_at)
                if disk_metric:
                    metrics.append(disk_metric)
            
            if 'network_io' in self.enabled_metrics:
                network_metrics = self._collect_network_io_remote(server_id, collected_at)
                metrics.extend(network_metrics)
            
            logger.debug(f"Collected {len(metrics)} metrics from remote server {server_id}")
            
        except Exception as e:
            logger.error(f"Failed to collect from remote server {server_id}: {e}")
        
        return metrics
    
    def _collect_cpu_usage_local(self, collected_at: datetime) -> Optional[Metric]:
        try:
            cpu_percent = psutil.cpu_percent(interval=1)
            
            return Metric(
                metric_id=f"{self.server_id}_cpu_usage_{uuid4().hex[:8]}",
                server_id=self.server_id,
                metric_type=MetricType.CPU_USAGE.value,
                value=round(cpu_percent, 2),
                unit="percent",
                collected_at=collected_at
            )
        except Exception as e:
            logger.error(f"Failed to collect CPU usage: {e}")
            return None
    
    def _collect_cpu_usage_remote(self, server_id: str, collected_at: datetime) -> Optional[Metric]:
        try:
            command = "grep 'cpu ' /proc/stat && sleep 1 && grep 'cpu ' /proc/stat"
            output, error, exit_code = self._ssh_pool.execute_command(server_id, command)
            
            if exit_code != 0 or not output:
                command = "top -bn1 | grep 'Cpu(s)'"
                output, error, exit_code = self._ssh_pool.execute_command(server_id, command)
                
                if exit_code != 0 or not output:
                    return None
                
                match = re.search(r'(\d+\.\d+)\s*us', output)
                if match:
                    cpu_percent = float(match.group(1))
                else:
                    return None
            else:
                lines = output.strip().split('\n')
                if len(lines) >= 2:
                    first = lines[0].split()
                    second = lines[1].split()
                    
                    if len(first) >= 5 and len(second) >= 5:
                        idle1 = int(first[4])
                        idle2 = int(second[4])
                        
                        total1 = sum(int(x) for x in first[1:])
                        total2 = sum(int(x) for x in second[1:])
                        
                        total_diff = total2 - total1
                        idle_diff = idle2 - idle1
                        
                        if total_diff > 0:
                            cpu_percent = 100.0 * (1.0 - idle_diff / total_diff)
                        else:
                            return None
                    else:
                        return None
                else:
                    return None
            
            return Metric(
                metric_id=f"{server_id}_cpu_usage_{uuid4().hex[:8]}",
                server_id=server_id,
                metric_type=MetricType.CPU_USAGE.value,
                value=round(cpu_percent, 2),
                unit="percent",
                collected_at=collected_at
            )
        except Exception as e:
            logger.error(f"Failed to collect remote CPU usage for {server_id}: {e}")
            return None
    
    def _collect_memory_usage_local(self, collected_at: datetime) -> Optional[Metric]:
        try:
            mem = psutil.virtual_memory()
            memory_percent = mem.percent
            
            return Metric(
                metric_id=f"{self.server_id}_memory_usage_{uuid4().hex[:8]}",
                server_id=self.server_id,
                metric_type=MetricType.MEMORY_USAGE.value,
                value=round(memory_percent, 2),
                unit="percent",
                collected_at=collected_at,
                fields={
                    "total_gb": round(mem.total / (1024**3), 2),
                    "available_gb": round(mem.available / (1024**3), 2),
                    "used_gb": round(mem.used / (1024**3), 2)
                }
            )
        except Exception as e:
            logger.error(f"Failed to collect memory usage: {e}")
            return None
    
    def _collect_memory_usage_remote(self, server_id: str, collected_at: datetime) -> Optional[Metric]:
        try:
            command = "free -m"
            output, error, exit_code = self._ssh_pool.execute_command(server_id, command)
            
            if exit_code != 0 or not output:
                return None
            
            lines = output.strip().split('\n')
            if len(lines) < 2:
                return None
            
            mem_line = lines[1]
            parts = mem_line.split()
            
            if len(parts) < 4:
                return None
            
            total_mb = int(parts[1])
            used_mb = int(parts[2])
            
            if total_mb > 0:
                memory_percent = (used_mb / total_mb) * 100
            else:
                return None
            
            return Metric(
                metric_id=f"{server_id}_memory_usage_{uuid4().hex[:8]}",
                server_id=server_id,
                metric_type=MetricType.MEMORY_USAGE.value,
                value=round(memory_percent, 2),
                unit="percent",
                collected_at=collected_at,
                fields={
                    "total_gb": round(total_mb / 1024, 2),
                    "used_gb": round(used_mb / 1024, 2),
                    "free_gb": round((total_mb - used_mb) / 1024, 2)
                }
            )
        except Exception as e:
            logger.error(f"Failed to collect remote memory usage for {server_id}: {e}")
            return None
    
    def _collect_disk_usage_local(self, collected_at: datetime) -> Optional[Metric]:
        try:
            partitions = psutil.disk_partitions()
            
            if platform.system() == 'Windows':
                root_partition = next((p for p in partitions if p.mountpoint == 'C:\\'), None)
            else:
                root_partition = next((p for p in partitions if p.mountpoint == '/'), None)
            
            if root_partition is None:
                root_partition = partitions[0] if partitions else None
            
            if root_partition is None:
                logger.warning("No disk partitions found")
                return None
            
            usage = psutil.disk_usage(root_partition.mountpoint)
            
            return Metric(
                metric_id=f"{self.server_id}_disk_usage_{uuid4().hex[:8]}",
                server_id=self.server_id,
                metric_type=MetricType.DISK_USAGE.value,
                value=round(usage.percent, 2),
                unit="percent",
                collected_at=collected_at,
                tags={
                    "mountpoint": root_partition.mountpoint,
                    "fstype": root_partition.fstype
                },
                fields={
                    "total_gb": round(usage.total / (1024**3), 2),
                    "used_gb": round(usage.used / (1024**3), 2),
                    "free_gb": round(usage.free / (1024**3), 2)
                }
            )
        except Exception as e:
            logger.error(f"Failed to collect disk usage: {e}")
            return None
    
    def _collect_disk_usage_remote(self, server_id: str, collected_at: datetime) -> Optional[Metric]:
        try:
            command = "df -P /"
            output, error, exit_code = self._ssh_pool.execute_command(server_id, command)
            
            if exit_code != 0 or not output:
                return None
            
            lines = output.strip().split('\n')
            if len(lines) < 2:
                return None
            
            parts = lines[1].split()
            if len(parts) < 5:
                return None
            
            total_blocks = int(parts[1])
            used_blocks = int(parts[2])
            mountpoint = parts[5]
            
            percent_str = parts[4]
            if percent_str.endswith('%'):
                disk_percent = float(percent_str[:-1])
            elif total_blocks > 0:
                disk_percent = (used_blocks / total_blocks) * 100
            else:
                return None
            
            return Metric(
                metric_id=f"{server_id}_disk_usage_{uuid4().hex[:8]}",
                server_id=server_id,
                metric_type=MetricType.DISK_USAGE.value,
                value=round(disk_percent, 2),
                unit="percent",
                collected_at=collected_at,
                tags={
                    "mountpoint": mountpoint
                },
                fields={
                    "total_gb": round(total_blocks * 512 / (1024**3), 2),
                    "used_gb": round(used_blocks * 512 / (1024**3), 2)
                }
            )
        except Exception as e:
            logger.error(f"Failed to collect remote disk usage for {server_id}: {e}")
            return None
    
    def _collect_network_io_local(self, collected_at: datetime) -> List[Metric]:
        metrics = []
        cache_key = self.server_id
        
        try:
            current_counters = psutil.net_io_counters()
            current_time = datetime.utcnow()
            
            if cache_key in self._last_network_counters and cache_key in self._last_network_time:
                last_counters = self._last_network_counters[cache_key]
                last_time = self._last_network_time[cache_key]
                
                time_diff = (current_time - last_time).total_seconds()
                if time_diff > 0:
                    bytes_recv_diff = current_counters.bytes_recv - last_counters.bytes_recv
                    bytes_sent_diff = current_counters.bytes_sent - last_counters.bytes_sent
                    
                    bytes_recv_per_sec = bytes_recv_diff / time_diff
                    bytes_sent_per_sec = bytes_sent_diff / time_diff
                    
                    metrics.append(Metric(
                        metric_id=f"{self.server_id}_network_in_{uuid4().hex[:8]}",
                        server_id=self.server_id,
                        metric_type=MetricType.NETWORK_IN.value,
                        value=round(bytes_recv_per_sec / 1024, 2),
                        unit="kbps",
                        collected_at=collected_at,
                        fields={
                            "bytes_recv_total": current_counters.bytes_recv,
                            "packets_recv": current_counters.packets_recv
                        }
                    ))
                    
                    metrics.append(Metric(
                        metric_id=f"{self.server_id}_network_out_{uuid4().hex[:8]}",
                        server_id=self.server_id,
                        metric_type=MetricType.NETWORK_OUT.value,
                        value=round(bytes_sent_per_sec / 1024, 2),
                        unit="kbps",
                        collected_at=collected_at,
                        fields={
                            "bytes_sent_total": current_counters.bytes_sent,
                            "packets_sent": current_counters.packets_sent
                        }
                    ))
            
            self._last_network_counters[cache_key] = current_counters
            self._last_network_time[cache_key] = current_time
            
        except Exception as e:
            logger.error(f"Failed to collect network IO: {e}")
        
        return metrics
    
    def _collect_network_io_remote(self, server_id: str, collected_at: datetime) -> List[Metric]:
        metrics = []
        
        try:
            command = "cat /proc/net/dev"
            output, error, exit_code = self._ssh_pool.execute_command(server_id, command)
            
            if exit_code != 0 or not output:
                return metrics
            
            current_time = datetime.utcnow()
            
            total_bytes_recv = 0
            total_bytes_sent = 0
            total_packets_recv = 0
            total_packets_sent = 0
            
            lines = output.strip().split('\n')
            for line in lines[2:]:
                parts = line.split()
                if len(parts) >= 10:
                    iface = parts[0].rstrip(':')
                    if iface == 'lo':
                        continue
                    
                    total_bytes_recv += int(parts[1])
                    total_packets_recv += int(parts[2])
                    total_bytes_sent += int(parts[9])
                    total_packets_sent += int(parts[10])
            
            if server_id in self._last_network_counters and server_id in self._last_network_time:
                last_counters = self._last_network_counters[server_id]
                last_time = self._last_network_time[server_id]
                
                time_diff = (current_time - last_time).total_seconds()
                if time_diff > 0:
                    bytes_recv_diff = total_bytes_recv - last_counters.get('bytes_recv', 0)
                    bytes_sent_diff = total_bytes_sent - last_counters.get('bytes_sent', 0)
                    
                    bytes_recv_per_sec = bytes_recv_diff / time_diff
                    bytes_sent_per_sec = bytes_sent_diff / time_diff
                    
                    metrics.append(Metric(
                        metric_id=f"{server_id}_network_in_{uuid4().hex[:8]}",
                        server_id=server_id,
                        metric_type=MetricType.NETWORK_IN.value,
                        value=round(bytes_recv_per_sec / 1024, 2),
                        unit="kbps",
                        collected_at=collected_at,
                        fields={
                            "bytes_recv_total": total_bytes_recv,
                            "packets_recv": total_packets_recv
                        }
                    ))
                    
                    metrics.append(Metric(
                        metric_id=f"{server_id}_network_out_{uuid4().hex[:8]}",
                        server_id=server_id,
                        metric_type=MetricType.NETWORK_OUT.value,
                        value=round(bytes_sent_per_sec / 1024, 2),
                        unit="kbps",
                        collected_at=collected_at,
                        fields={
                            "bytes_sent_total": total_bytes_sent,
                            "packets_sent": total_packets_sent
                        }
                    ))
            
            self._last_network_counters[server_id] = {
                'bytes_recv': total_bytes_recv,
                'bytes_sent': total_bytes_sent,
                'packets_recv': total_packets_recv,
                'packets_sent': total_packets_sent
            }
            self._last_network_time[server_id] = current_time
            
        except Exception as e:
            logger.error(f"Failed to collect remote network IO for {server_id}: {e}")
        
        return metrics
    
    def get_system_info(self) -> Dict[str, Any]:
        try:
            info = {
                "server_id": self.server_id,
                "hostname": socket.gethostname(),
                "platform": platform.system(),
                "platform_version": platform.version(),
                "architecture": platform.machine(),
                "cpu_count": psutil.cpu_count(logical=True),
                "cpu_count_physical": psutil.cpu_count(logical=False),
                "memory_total_gb": round(psutil.virtual_memory().total / (1024**3), 2),
                "boot_time": datetime.fromtimestamp(psutil.boot_time()).isoformat(),
                "collect_mode": self.collect_mode.value,
                "remote_servers": list(self._remote_servers.keys())
            }
            return info
        except Exception as e:
            logger.error(f"Failed to get system info: {e}")
            return {"server_id": self.server_id, "error": str(e)}
    
    def get_ssh_pool_status(self) -> Optional[Dict[str, Any]]:
        if self._ssh_pool:
            return self._ssh_pool.get_pool_status()
        return None
