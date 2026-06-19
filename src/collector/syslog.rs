use crate::config::SyslogSourceConfig;
use crate::collector::ring_buffer::RingBufferHandle;
use std::net::UdpSocket;
use std::sync::Arc;
use std::time::Duration;
use tracing::{debug, error, info, warn};

const UDP_BUFFER_SIZE: usize = 65536;
const SYSLOG_POLL_MS: u64 = 50;

pub struct SyslogListener {
    config: SyslogSourceConfig,
    buffer: RingBufferHandle,
}

impl SyslogListener {
    pub fn new(config: SyslogSourceConfig, buffer: RingBufferHandle) -> Self {
        Self { config, buffer }
    }

    pub fn start(self) -> Result<std::thread::JoinHandle<()>, Box<dyn std::error::Error + Send + Sync>> {
        let addr = format!("{}:{}", self.config.host, self.config.port);
        let socket = Arc::new(UdpSocket::bind(&addr)?);
        socket.set_read_timeout(Some(Duration::from_millis(SYSLOG_POLL_MS)))?;
        socket.set_nonblocking(false)?;

        info!("Syslog UDP listener started on {}", addr);

        let service = self.config.service.clone();
        let source_name = self.config.name.clone();
        let buffer = self.buffer.clone();

        let handle = std::thread::Builder::new()
            .name(format!("syslog-{}", source_name))
            .spawn(move || {
                let mut buf = vec![0u8; UDP_BUFFER_SIZE];
                loop {
                    match socket.recv_from(&mut buf) {
                        Ok((len, _src)) => {
                            if len == 0 {
                                continue;
                            }
                            let raw = match std::str::from_utf8(&buf[..len]) {
                                Ok(s) => s.to_string(),
                                Err(_) => {
                                    String::from_utf8_lossy(&buf[..len]).to_string()
                                }
                            };
                            let cleaned = raw.trim().to_string();
                            if cleaned.is_empty() {
                                continue;
                            }
                            let mut rec = crate::LogRecord::new();
                            rec.source = source_name.clone();
                            rec.service = service.clone();
                            rec.raw = cleaned.clone();
                            rec.message = cleaned;
                            if let Err(rec) = buffer.push(rec) {
                                debug!("Syslog buffer full, dropping one record (non-blocking)");
                            }
                        }
                        Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            continue;
                        }
                        Err(ref e) if e.kind() == std::io::ErrorKind::TimedOut => {
                            continue;
                        }
                        Err(e) => {
                            warn!("Syslog recv error: {}", e);
                            std::thread::sleep(Duration::from_millis(100));
                        }
                    }
                }
            })?;

        Ok(handle)
    }
}
