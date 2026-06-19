use crate::config::FileSourceConfig;
use crate::collector::ring_buffer::RingBufferHandle;
use notify::{Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use notify_debouncer_mini::{new_debouncer, DebounceEventResult, DebouncedEvent};
use std::collections::HashMap;
use std::fs::{self, File, Metadata};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex as StdMutex};
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

struct FileState {
    path: PathBuf,
    inode: u64,
    file: Option<File>,
    offset: u64,
    buf_reader: Option<BufReader<File>>,
}

impl FileState {
    fn new(path: PathBuf, from_beginning: bool) -> Self {
        let (file, offset) = match File::options().read(true).open(&path) {
            Ok(mut f) => {
                let off = if from_beginning {
                    0
                } else {
                    f.seek(SeekFrom::End(0)).unwrap_or(0)
                };
                (Some(f), off)
            }
            Err(e) => {
                warn!("Failed to open {:?}: {}", path, e);
                (None, 0)
            }
        };
        let inode = get_inode(&path).unwrap_or(0);
        let buf_reader = file.as_ref().map(|f| {
            let mut br = BufReader::new(f.try_clone().unwrap());
            let _ = br.seek(SeekFrom::Start(offset));
            br
        });
        Self {
            path,
            inode,
            file,
            offset,
            buf_reader,
        }
    }

    fn reopen(&mut self, from_beginning: bool) {
        match File::options().read(true).open(&self.path) {
            Ok(mut f) => {
                let off = if from_beginning {
                    0
                } else {
                    f.seek(SeekFrom::End(0)).unwrap_or(0)
                };
                self.inode = get_inode(&self.path).unwrap_or(0);
                self.offset = off;
                let mut br = BufReader::new(f.try_clone().unwrap());
                let _ = br.seek(SeekFrom::Start(off));
                self.buf_reader = Some(br);
                self.file = Some(f);
                info!("Reopened {:?} at offset {}", self.path, off);
            }
            Err(e) => {
                warn!("Failed to reopen {:?}: {}", self.path, e);
            }
        }
    }

    fn read_lines(&mut self, buffer_size: usize) -> Vec<String> {
        let mut lines = Vec::new();
        if self.buf_reader.is_none() {
            return lines;
        }
        let br = self.buf_reader.as_mut().unwrap();
        let mut line = String::with_capacity(buffer_size);
        loop {
            line.clear();
            match br.read_line(&mut line) {
                Ok(0) => break,
                Ok(n) => {
                    self.offset += n as u64;
                    if line.ends_with('\n') {
                        line.pop();
                        if line.ends_with('\r') {
                            line.pop();
                        }
                    }
                    if !line.is_empty() {
                        lines.push(std::mem::take(&mut line));
                        line = String::with_capacity(buffer_size);
                    }
                }
                Err(e) => {
                    warn!("Error reading {:?}: {}", self.path, e);
                    break;
                }
            }
        }
        lines
    }

    fn check_rotation(&mut self) -> bool {
        let current_inode = get_inode(&self.path).unwrap_or(0);
        if current_inode != 0 && current_inode != self.inode {
            info!(
                "Detected log rotation for {:?}: inode changed from {} to {}",
                self.path, self.inode, current_inode
            );
            if let Some(br) = self.buf_reader.as_mut() {
                let mut line = String::new();
                loop {
                    line.clear();
                    match br.read_line(&mut line) {
                        Ok(0) => break,
                        Ok(n) => {
                            self.offset += n as u64;
                        }
                        Err(_) => break,
                    }
                }
            }
            self.reopen(true);
            return true;
        }
        false
    }
}

fn get_inode<P: AsRef<Path>>(path: P) -> std::io::Result<u64> {
    let meta: Metadata = fs::metadata(path)?;
    use std::os::unix::fs::MetadataExt;
    Ok(meta.ino())
}

fn expand_glob(pattern: &str) -> Vec<PathBuf> {
    match glob::glob(pattern) {
        Ok(paths) => paths
            .filter_map(|p| p.ok())
            .filter(|p| p.is_file())
            .collect(),
        Err(e) => {
            error!("Invalid glob pattern '{}': {}", pattern, e);
            Vec::new()
        }
    }
}

pub struct FileTailer {
    config: FileSourceConfig,
    buffer: RingBufferHandle,
    service: String,
    source_name: String,
}

impl FileTailer {
    pub fn new(config: FileSourceConfig, buffer: RingBufferHandle) -> Self {
        let service = config.service.clone();
        let source_name = config.name.clone();
        Self {
            config,
            buffer,
            service,
            source_name,
        }
    }

    pub fn start(self) -> Result<tokio::task::JoinHandle<()>, Box<dyn std::error::Error + Send + Sync>> {
        let (tx, mut rx) = mpsc::channel::<DebounceEventResult>(100);

        let config = self.config.clone();
        let pattern = config.glob_pattern.clone();

        let watcher_handle = std::thread::Builder::new()
            .name(format!("watcher-{}", self.source_name))
            .spawn(move || {
                let mut debouncer = match new_debouncer(
                    Duration::from_millis(200),
                    move |res: DebounceEventResult| {
                        let _ = tx.blocking_send(res);
                    },
                ) {
                    Ok(d) => d,
                    Err(e) => {
                        error!("Failed to create file watcher: {}", e);
                        return;
                    }
                };

                fn watch_parent_dirs(w: &mut RecommendedWatcher, pattern: &str) {
                    if let Some(parent) = Path::new(pattern).parent() {
                        let watch_dir = if parent.as_os_str().is_empty() {
                            Path::new(".")
                        } else {
                            parent
                        };
                        if let Err(e) = w.watch(watch_dir, RecursiveMode::NonRecursive) {
                            warn!("Failed to watch {:?}: {}", watch_dir, e);
                        }
                    }
                }

                watch_parent_dirs(debouncer.watcher(), pattern);

                loop {
                    std::thread::sleep(Duration::from_secs(600));
                }
            })?;

        let handle = tokio::spawn(async move {
            let file_states: Arc<StdMutex<HashMap<PathBuf, FileState>>> =
                Arc::new(StdMutex::new(HashMap::new()));

            for path in expand_glob(&self.config.glob_pattern) {
                let mut states = file_states.lock().unwrap();
                if !states.contains_key(&path) {
                    debug!("Initial file found: {:?}", path);
                    states.insert(
                        path.clone(),
                        FileState::new(path, self.config.from_beginning),
                    );
                }
            }

            let buffer_size = self.config.line_buffer_size;
            let service = self.service.clone();
            let source_name = self.source_name.clone();
            let buffer = self.buffer.clone();

            loop {
                tokio::select! {
                    Some(event_res) = rx.recv() => {
                        match event_res {
                            Ok(events) => {
                                for DebouncedEvent { path, .. } in events {
                                    if glob_match_check(&self.config.glob_pattern, &path) {
                                        let mut states = file_states.lock().unwrap();
                                        if path.exists() && path.is_file() && !states.contains_key(&path) {
                                            info!("New file detected: {:?}", path);
                                            states.insert(
                                                path.clone(),
                                                FileState::new(path.clone(), true),
                                            );
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                error!("Watcher error: {:?}", e);
                            }
                        }
                    }
                    _ = tokio::time::sleep(Duration::from_millis(100)) => {
                        let paths: Vec<PathBuf>;
                        {
                            let states = file_states.lock().unwrap();
                            paths = states.keys().cloned().collect();
                        }

                        for path in paths {
                            let mut record_lines: Vec<String> = Vec::new();
                            {
                                let mut states = file_states.lock().unwrap();
                                if let Some(state) = states.get_mut(&path) {
                                    state.check_rotation();
                                    record_lines = state.read_lines(buffer_size);
                                }
                            }
                            for line in record_lines {
                                let mut rec = crate::LogRecord::new();
                                rec.source = source_name.clone();
                                rec.service = service.clone();
                                rec.raw = line.clone();
                                rec.message = line;
                                buffer.push(rec);
                            }
                        }

                        let mut states = file_states.lock().unwrap();
                        states.retain(|p, _| p.exists());
                    }
                }
            }
        });

        Ok(handle)
    }
}

fn glob_match_check(pattern: &str, path: &Path) -> bool {
    let path_str = path.to_string_lossy().to_string();
    let p = match glob::Pattern::new(pattern) {
        Ok(p) => p,
        Err(_) => return false,
    };
    p.matches(&path_str)
}
