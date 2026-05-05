import logging
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Dict, Optional, List, Any
from concurrent.futures import ThreadPoolExecutor

import paramiko
from paramiko import SSHClient, AutoAddPolicy
from paramiko.ssh_exception import SSHException, AuthenticationException, NoValidConnectionsError

logger = logging.getLogger(__name__)


@dataclass
class SSHConnectionConfig:
    server_id: str
    host: str
    port: int = 22
    username: str = "root"
    password: Optional[str] = None
    private_key_path: Optional[str] = None
    private_key_passphrase: Optional[str] = None
    timeout: int = 10
    keepalive_interval: int = 30
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "server_id": self.server_id,
            "host": self.host,
            "port": self.port,
            "username": self.username,
            "timeout": self.timeout
        }


@dataclass
class SSHConnectionWrapper:
    client: SSHClient
    config: SSHConnectionConfig
    created_at: datetime = field(default_factory=datetime.utcnow)
    last_used_at: datetime = field(default_factory=datetime.utcnow)
    last_health_check_at: datetime = field(default_factory=datetime.utcnow)
    use_count: int = 0
    is_busy: bool = False
    is_healthy: bool = True
    health_check_failures: int = 0
    
    def mark_used(self):
        self.last_used_at = datetime.utcnow()
        self.use_count += 1
        self.is_busy = True
    
    def mark_free(self):
        self.is_busy = False
    
    def get_idle_seconds(self) -> float:
        return (datetime.utcnow() - self.last_used_at).total_seconds()
    
    def get_age_seconds(self) -> float:
        return (datetime.utcnow() - self.created_at).total_seconds()
    
    def is_alive(self, check_command: str = "echo 1", timeout: int = 5) -> bool:
        try:
            transport = self.client.get_transport()
            if not transport or not transport.is_active():
                logger.debug(f"Connection to {self.config.server_id} transport is not active")
                return False
            
            try:
                transport.send_ignore()
            except Exception:
                logger.debug(f"Connection to {self.config.server_id} send_ignore failed")
                return False
            
            if check_command:
                try:
                    stdin, stdout, stderr = self.client.exec_command(check_command, timeout=timeout)
                    output = stdout.read().decode('utf-8', errors='ignore').strip()
                    exit_code = stdout.channel.recv_exit_status()
                    if exit_code != 0:
                        logger.debug(f"Connection to {self.config.server_id} health check command failed, exit_code={exit_code}")
                        return False
                    if not output:
                        logger.debug(f"Connection to {self.config.server_id} health check command returned no output")
                        return False
                except Exception as e:
                    logger.debug(f"Connection to {self.config.server_id} health check command failed: {e}")
                    return False
            
            self.is_healthy = True
            self.health_check_failures = 0
            self.last_health_check_at = datetime.utcnow()
            return True
            
        except Exception as e:
            logger.debug(f"Connection to {self.config.server_id} is not alive: {e}")
            self.is_healthy = False
            self.health_check_failures += 1
            return False
    
    def close(self):
        try:
            self.client.close()
            logger.debug(f"Closed SSH connection to {self.config.host}")
        except Exception:
            pass


@dataclass
class SSHPoolHealthStatus:
    server_id: str
    total_connections: int = 0
    busy_connections: int = 0
    idle_connections: int = 0
    healthy_connections: int = 0
    unhealthy_connections: int = 0
    last_health_check_at: Optional[datetime] = None
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "server_id": self.server_id,
            "total_connections": self.total_connections,
            "busy_connections": self.busy_connections,
            "idle_connections": self.idle_connections,
            "healthy_connections": self.healthy_connections,
            "unhealthy_connections": self.unhealthy_connections,
            "last_health_check_at": self.last_health_check_at.isoformat() if self.last_health_check_at else None
        }


class SSHConnectionPool:
    def __init__(
        self,
        max_connections_per_server: int = 3,
        max_total_connections: int = 10,
        idle_timeout_seconds: int = 300,
        cleanup_interval_seconds: int = 60,
        health_check_interval_seconds: int = 30,
        health_check_command: str = "echo 1",
        connection_retry_count: int = 3,
        connection_retry_delay_seconds: int = 2,
        max_health_check_failures: int = 3,
        max_connection_age_seconds: int = 3600
    ):
        self._connections: Dict[str, List[SSHConnectionWrapper]] = {}
        self._configs: Dict[str, SSHConnectionConfig] = {}
        self._health_statuses: Dict[str, SSHPoolHealthStatus] = {}
        
        self.max_connections_per_server = max_connections_per_server
        self.max_total_connections = max_total_connections
        self.idle_timeout_seconds = idle_timeout_seconds
        self.cleanup_interval_seconds = cleanup_interval_seconds
        self.health_check_interval_seconds = health_check_interval_seconds
        self.health_check_command = health_check_command
        self.connection_retry_count = connection_retry_count
        self.connection_retry_delay_seconds = connection_retry_delay_seconds
        self.max_health_check_failures = max_health_check_failures
        self.max_connection_age_seconds = max_connection_age_seconds
        
        self._lock = threading.RLock()
        self._shutdown = False
        
        self._cleanup_thread: Optional[threading.Thread] = None
        self._cleanup_stop = threading.Event()
        
        self._health_check_thread: Optional[threading.Thread] = None
        self._health_check_stop = threading.Event()
        
        self._executor = ThreadPoolExecutor(max_workers=4)
        
        self._last_health_check_at: Optional[datetime] = None
        
        logger.info(
            f"SSHConnectionPool initialized: "
            f"max_per_server={max_connections_per_server}, "
            f"max_total={max_total_connections}, "
            f"idle_timeout={idle_timeout_seconds}s, "
            f"health_check_interval={health_check_interval_seconds}s, "
            f"max_age={max_connection_age_seconds}s"
        )
        
        self._start_cleanup_thread()
        self._start_health_check_thread()
    
    def _start_cleanup_thread(self):
        self._cleanup_thread = threading.Thread(
            target=self._cleanup_loop,
            name="SSH-Connection-Cleaner",
            daemon=True
        )
        self._cleanup_thread.start()
        logger.debug("SSH connection cleanup thread started")
    
    def _start_health_check_thread(self):
        self._health_check_thread = threading.Thread(
            target=self._health_check_loop,
            name="SSH-Health-Checker",
            daemon=True
        )
        self._health_check_thread.start()
        logger.debug("SSH health check thread started")
    
    def _cleanup_loop(self):
        while not self._cleanup_stop.is_set():
            try:
                self._cleanup_idle_connections()
                self._cleanup_expired_connections()
            except Exception as e:
                logger.error(f"Error in cleanup loop: {e}")
            
            self._cleanup_stop.wait(self.cleanup_interval_seconds)
    
    def _health_check_loop(self):
        while not self._health_check_stop.is_set():
            try:
                self._perform_health_checks()
            except Exception as e:
                logger.error(f"Error in health check loop: {e}")
            
            self._health_check_stop.wait(self.health_check_interval_seconds)
    
    def _perform_health_checks(self):
        with self._lock:
            self._last_health_check_at = datetime.utcnow()
            
            for server_id, connections in self._connections.items():
                server_status = self._health_statuses.setdefault(
                    server_id,
                    SSHPoolHealthStatus(server_id=server_id)
                )
                server_status.last_health_check_at = datetime.utcnow()
                
                to_remove = []
                healthy_count = 0
                unhealthy_count = 0
                
                for i, conn in enumerate(connections):
                    if conn.is_busy:
                        continue
                    
                    is_alive = conn.is_alive(
                        check_command=self.health_check_command,
                        timeout=5
                    )
                    
                    if is_alive:
                        healthy_count += 1
                    else:
                        unhealthy_count += 1
                        
                        if conn.health_check_failures >= self.max_health_check_failures:
                            logger.warning(
                                f"Connection to {server_id} failed health check {conn.health_check_failures} times, "
                                f"marking for removal"
                            )
                            to_remove.append(i)
                
                server_status.healthy_connections = healthy_count
                server_status.unhealthy_connections = unhealthy_count
                
                for i in reversed(to_remove):
                    conn = connections.pop(i)
                    conn.close()
                    logger.info(f"Removed unhealthy SSH connection to {server_id}")
                
                config = self._configs.get(server_id)
                if config and len(connections) < self.max_connections_per_server:
                    needed = self.max_connections_per_server - len(connections)
                    for _ in range(needed):
                        new_conn = self._create_connection_with_retry(config)
                        if new_conn:
                            connections.append(new_conn)
                            logger.info(f"Replaced unhealthy connection with new connection to {server_id}")
                        else:
                            logger.warning(f"Failed to replace unhealthy connection to {server_id}")
                            break
                
                server_status.total_connections = len(connections)
                server_status.busy_connections = sum(1 for c in connections if c.is_busy)
                server_status.idle_connections = sum(1 for c in connections if not c.is_busy)
    
    def _cleanup_idle_connections(self):
        with self._lock:
            total_closed = 0
            
            for server_id, connections in self._connections.items():
                to_remove = []
                
                for i, conn in enumerate(connections):
                    if not conn.is_busy:
                        idle_seconds = conn.get_idle_seconds()
                        
                        if idle_seconds >= self.idle_timeout_seconds:
                            logger.debug(
                                f"Connection to {server_id} idle for {idle_seconds:.1f}s, "
                                f"exceeds timeout {self.idle_timeout_seconds}s"
                            )
                            to_remove.append(i)
                
                for i in reversed(to_remove):
                    conn = connections.pop(i)
                    conn.close()
                    total_closed += 1
            
            if total_closed > 0:
                logger.debug(f"Cleaned up {total_closed} idle SSH connections")
    
    def _cleanup_expired_connections(self):
        with self._lock:
            total_closed = 0
            
            for server_id, connections in self._connections.items():
                to_remove = []
                
                for i, conn in enumerate(connections):
                    if not conn.is_busy:
                        age_seconds = conn.get_age_seconds()
                        
                        if age_seconds >= self.max_connection_age_seconds:
                            logger.debug(
                                f"Connection to {server_id} age {age_seconds:.1f}s, "
                                f"exceeds max age {self.max_connection_age_seconds}s"
                            )
                            to_remove.append(i)
                
                for i in reversed(to_remove):
                    conn = connections.pop(i)
                    conn.close()
                    total_closed += 1
                
                config = self._configs.get(server_id)
                if config and len(connections) < self.max_connections_per_server:
                    while len(connections) < self.max_connections_per_server:
                        new_conn = self._create_connection_with_retry(config)
                        if new_conn:
                            connections.append(new_conn)
                        else:
                            break
            
            if total_closed > 0:
                logger.info(f"Recycled {total_closed} expired SSH connections")
    
    def register_server(self, config: SSHConnectionConfig):
        with self._lock:
            self._configs[config.server_id] = config
            
            if config.server_id not in self._connections:
                self._connections[config.server_id] = []
            
            existing_count = len(self._connections[config.server_id])
            if existing_count < self.max_connections_per_server:
                needed = self.max_connections_per_server - existing_count
                for _ in range(needed):
                    conn = self._create_connection_with_retry(config)
                    if conn:
                        self._connections[config.server_id].append(conn)
            
            logger.info(f"Registered server: {config.server_id} ({config.host}:{config.port})")
    
    def register_servers(self, configs: List[SSHConnectionConfig]):
        for config in configs:
            self.register_server(config)
    
    def _create_connection_with_retry(self, config: SSHConnectionConfig) -> Optional[SSHConnectionWrapper]:
        last_error = None
        
        for attempt in range(1, self.connection_retry_count + 1):
            try:
                conn = self._create_connection(config)
                if conn:
                    logger.info(
                        f"Successfully connected to {config.host}:{config.port} "
                        f"(attempt {attempt}/{self.connection_retry_count})"
                    )
                    return conn
            except Exception as e:
                last_error = e
                logger.warning(
                    f"Connection attempt {attempt}/{self.connection_retry_count} "
                    f"for {config.host}:{config.port} failed: {e}"
                )
                
                if attempt < self.connection_retry_count:
                    time.sleep(self.connection_retry_delay_seconds)
        
        logger.error(
            f"All {self.connection_retry_count} connection attempts failed "
            f"for {config.host}:{config.port}. Last error: {last_error}"
        )
        return None
    
    def _create_connection(self, config: SSHConnectionConfig) -> Optional[SSHConnectionWrapper]:
        try:
            client = SSHClient()
            client.set_missing_host_key_policy(AutoAddPolicy())
            
            connect_kwargs = {
                "hostname": config.host,
                "port": config.port,
                "username": config.username,
                "timeout": config.timeout,
            }
            
            if config.private_key_path:
                try:
                    private_key = paramiko.RSAKey.from_private_key_file(
                        config.private_key_path,
                        password=config.private_key_passphrase
                    )
                    connect_kwargs["pkey"] = private_key
                except Exception as e:
                    logger.error(f"Failed to load private key: {e}")
                    if config.password:
                        connect_kwargs["password"] = config.password
                    else:
                        raise
            elif config.password:
                connect_kwargs["password"] = config.password
            
            client.connect(**connect_kwargs)
            
            transport = client.get_transport()
            if transport:
                transport.set_keepalive(config.keepalive_interval)
            
            wrapper = SSHConnectionWrapper(
                client=client,
                config=config
            )
            
            return wrapper
            
        except AuthenticationException as e:
            logger.error(f"Authentication failed for {config.host}: {e}")
            raise
        except NoValidConnectionsError as e:
            logger.error(f"Connection failed for {config.host}: {e}")
            raise
        except SSHException as e:
            logger.error(f"SSH error for {config.host}: {e}")
            raise
        except Exception as e:
            logger.error(f"Unexpected error connecting to {config.host}: {e}")
            raise
    
    def get_connection(self, server_id: str) -> Optional[SSHConnectionWrapper]:
        with self._lock:
            if server_id not in self._configs:
                logger.error(f"Server {server_id} not registered in connection pool")
                return None
            
            config = self._configs[server_id]
            
            if server_id not in self._connections:
                self._connections[server_id] = []
            
            connections = self._connections[server_id]
            
            for conn in connections:
                if not conn.is_busy and conn.is_alive():
                    conn.mark_used()
                    logger.debug(f"Reusing existing SSH connection to {server_id}")
                    return conn
            
            for conn in connections:
                if conn.is_busy:
                    continue
                if not conn.is_alive():
                    conn.close()
                    connections.remove(conn)
                    logger.debug(f"Removed dead connection from pool for {server_id}")
            
            total_connections = sum(len(conns) for conns in self._connections.values())
            
            if len(connections) >= self.max_connections_per_server:
                logger.warning(
                    f"Max connections per server reached for {server_id} "
                    f"({self.max_connections_per_server})"
                )
                return None
            
            if total_connections >= self.max_total_connections:
                logger.warning(
                    f"Max total connections reached ({self.max_total_connections})"
                )
                return None
            
            logger.debug(f"Creating new SSH connection to {server_id}")
            conn = self._create_connection_with_retry(config)
            
            if conn:
                conn.mark_used()
                connections.append(conn)
                return conn
            
            return None
    
    def return_connection(self, conn: SSHConnectionWrapper):
        if conn:
            with self._lock:
                conn.mark_free()
                logger.debug(f"Returned SSH connection to {conn.config.server_id}")
    
    def execute_command(
        self,
        server_id: str,
        command: str,
        timeout: Optional[int] = None
    ) -> tuple[Optional[str], Optional[str], int]:
        conn = self.get_connection(server_id)
        
        if not conn:
            error_msg = f"No connection available for {server_id}"
            logger.error(error_msg)
            return None, error_msg, -1
        
        try:
            actual_timeout = timeout or conn.config.timeout
            
            stdin, stdout, stderr = conn.client.exec_command(
                command,
                timeout=actual_timeout
            )
            
            output = stdout.read().decode('utf-8', errors='ignore')
            error = stderr.read().decode('utf-8', errors='ignore')
            exit_code = stdout.channel.recv_exit_status()
            
            logger.debug(
                f"Executed command on {server_id}: '{command[:50]}...' "
                f"exit_code={exit_code}"
            )
            
            return output.strip(), error.strip(), exit_code
            
        except SSHException as e:
            logger.error(f"SSH error executing command on {server_id}: {e}")
            conn.is_healthy = False
            conn.health_check_failures += 1
            return None, f"SSH error: {e}", -1
        except Exception as e:
            logger.error(f"Command execution failed on {server_id}: {e}")
            return None, str(e), -1
        finally:
            self.return_connection(conn)
    
    def execute_command_async(
        self,
        server_id: str,
        command: str,
        callback=None,
        timeout: Optional[int] = None
    ):
        def wrapper():
            result = self.execute_command(server_id, command, timeout)
            if callback:
                try:
                    callback(server_id, command, result)
                except Exception as e:
                    logger.error(f"Callback error: {e}")
            return result
        
        return self._executor.submit(wrapper)
    
    def test_connection(self, server_id: str) -> tuple[bool, str]:
        config = self._configs.get(server_id)
        if not config:
            return False, f"Server {server_id} not registered"
        
        conn = self._create_connection_with_retry(config)
        if conn:
            conn.close()
            return True, "Connection successful"
        return False, "Connection failed"
    
    def force_reconnect(self, server_id: str) -> tuple[bool, str]:
        with self._lock:
            if server_id not in self._configs:
                return False, f"Server {server_id} not registered"
            
            if server_id in self._connections:
                for conn in self._connections[server_id]:
                    conn.close()
                self._connections[server_id].clear()
            
            config = self._configs[server_id]
            connected_count = 0
            
            for _ in range(self.max_connections_per_server):
                conn = self._create_connection_with_retry(config)
                if conn:
                    if server_id not in self._connections:
                        self._connections[server_id] = []
                    self._connections[server_id].append(conn)
                    connected_count += 1
            
            if connected_count > 0:
                return True, f"Reconnected {connected_count} connections"
            return False, "Failed to reconnect"
    
    def close_all_connections(self, server_id: Optional[str] = None):
        with self._lock:
            if server_id:
                if server_id in self._connections:
                    for conn in self._connections[server_id]:
                        conn.close()
                    del self._connections[server_id]
                    logger.info(f"Closed all connections to {server_id}")
            else:
                total_closed = 0
                for connections in self._connections.values():
                    for conn in connections:
                        conn.close()
                        total_closed += 1
                self._connections.clear()
                logger.info(f"Closed all {total_closed} SSH connections")
    
    def unregister_server(self, server_id: str):
        with self._lock:
            self.close_all_connections(server_id)
            if server_id in self._configs:
                del self._configs[server_id]
            if server_id in self._health_statuses:
                del self._health_statuses[server_id]
            logger.info(f"Unregistered server: {server_id}")
    
    def get_pool_status(self) -> Dict[str, Any]:
        with self._lock:
            server_statuses = {}
            
            for server_id in self._configs.keys():
                connections = self._connections.get(server_id, [])
                status = SSHPoolHealthStatus(
                    server_id=server_id,
                    total_connections=len(connections),
                    busy_connections=sum(1 for c in connections if c.is_busy),
                    idle_connections=sum(1 for c in connections if not c.is_busy),
                    healthy_connections=sum(1 for c in connections if c.is_healthy),
                    unhealthy_connections=sum(1 for c in connections if not c.is_healthy),
                    last_health_check_at=self._health_statuses.get(
                        server_id, SSHPoolHealthStatus(server_id=server_id)
                    ).last_health_check_at
                )
                server_statuses[server_id] = status.to_dict()
                
                config = self._configs.get(server_id)
                if config:
                    server_statuses[server_id]["config"] = config.to_dict()
            
            total_connections = sum(len(conns) for conns in self._connections.values())
            
            return {
                "total_connections": total_connections,
                "max_total": self.max_total_connections,
                "max_per_server": self.max_connections_per_server,
                "idle_timeout_seconds": self.idle_timeout_seconds,
                "health_check_interval_seconds": self.health_check_interval_seconds,
                "last_health_check_at": self._last_health_check_at.isoformat() if self._last_health_check_at else None,
                "servers": server_statuses,
                "registered_servers": list(self._configs.keys())
            }
    
    def shutdown(self):
        logger.info("Shutting down SSH connection pool...")
        
        self._cleanup_stop.set()
        self._health_check_stop.set()
        self.close_all_connections()
        self._executor.shutdown(wait=False)
        self._shutdown = True
        
        logger.info("SSH connection pool shutdown complete")
    
    def __enter__(self):
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.shutdown()
