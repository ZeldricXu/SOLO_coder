use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use notify::{RecommendedWatcher, RecursiveMode, Watcher, Event, EventKind};

#[derive(Debug)]
pub enum FileChangeEvent {
    Created(PathBuf),
    Modified(PathBuf),
    Deleted(PathBuf),
    Renamed(PathBuf, PathBuf),
}

pub struct FileSystemWatcher {
    watcher: Option<RecommendedWatcher>,
    events: Arc<Mutex<Vec<FileChangeEvent>>>,
    last_refresh: Instant,
    debounce_duration: Duration,
    root_path: PathBuf,
    needs_refresh: Arc<Mutex<bool>>,
}

impl std::fmt::Debug for FileSystemWatcher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FileSystemWatcher")
            .field("root_path", &self.root_path)
            .field("debounce_duration", &self.debounce_duration)
            .field("last_refresh", &self.last_refresh)
            .field("watcher_active", &self.watcher.is_some())
            .finish()
    }
}

impl FileSystemWatcher {
    pub fn new(root_path: &Path) -> Self {
        let events = Arc::new(Mutex::new(Vec::new()));
        let needs_refresh = Arc::new(Mutex::new(false));
        
        Self {
            watcher: None,
            events,
            last_refresh: Instant::now(),
            debounce_duration: Duration::from_millis(500),
            root_path: root_path.to_path_buf(),
            needs_refresh,
        }
    }

    pub fn start(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let events = self.events.clone();
        let needs_refresh = self.needs_refresh.clone();
        let root_path = self.root_path.clone();

        let mut watcher = notify::recommended_watcher(move |res: notify::Result<Event>| {
            match res {
                Ok(event) => {
                    if let Some(change_event) = Self::filter_and_convert_event(&event, &root_path) {
                        if let Ok(mut events) = events.lock() {
                            events.push(change_event);
                        }
                        if let Ok(mut refresh) = needs_refresh.lock() {
                            *refresh = true;
                        }
                    }
                }
                Err(e) => eprintln!("Watch error: {:?}", e),
            }
        })?;

        watcher.watch(&self.root_path, RecursiveMode::Recursive)?;
        self.watcher = Some(watcher);

        Ok(())
    }

    fn filter_and_convert_event(event: &Event, root_path: &Path) -> Option<FileChangeEvent> {
        for path in &event.paths {
            if Self::is_temporary_file(path) {
                return None;
            }
            
            if !path.starts_with(root_path) {
                return None;
            }
            
            if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
                if ext.to_lowercase() != "md" && !path.is_dir() {
                    return None;
                }
            } else if !path.is_dir() {
                return None;
            }
        }

        match event.kind {
            EventKind::Create(_) => {
                event.paths.first().map(|p| FileChangeEvent::Created(p.clone()))
            }
            EventKind::Modify(_) => {
                event.paths.first().map(|p| FileChangeEvent::Modified(p.clone()))
            }
            EventKind::Remove(_) => {
                event.paths.first().map(|p| FileChangeEvent::Deleted(p.clone()))
            }
            _ => None,
        }
    }

    pub fn is_temporary_file(path: &Path) -> bool {
        if let Some(file_name) = path.file_name().and_then(|n| n.to_str()) {
            if file_name.starts_with("~$") || 
               file_name.starts_with(".~") ||
               file_name.starts_with(".") && file_name.ends_with(".swp") ||
               file_name.starts_with(".") && file_name.ends_with(".tmp") ||
               file_name.ends_with("~") {
                return true;
            }
        }
        
        if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
            let ext_lower = ext.to_lowercase();
            if ext_lower == "tmp" || 
               ext_lower == "temp" || 
               ext_lower == "swp" ||
               ext_lower == "swx" ||
               ext_lower == "part" {
                return true;
            }
        }
        
        false
    }

    pub fn should_refresh(&mut self) -> bool {
        let needs_refresh = self.needs_refresh.lock().map(|guard| *guard).unwrap_or(false);
        
        if needs_refresh {
            let now = Instant::now();
            if now.duration_since(self.last_refresh) >= self.debounce_duration {
                self.last_refresh = now;
                if let Ok(mut refresh) = self.needs_refresh.lock() {
                    *refresh = false;
                }
                return true;
            }
        }
        
        false
    }

    pub fn get_events(&mut self) -> Vec<FileChangeEvent> {
        let mut events = self.events.lock().unwrap();
        let result = std::mem::take(&mut *events);
        result
    }

    pub fn clear_events(&mut self) {
        if let Ok(mut events) = self.events.lock() {
            events.clear();
        }
    }

    pub fn file_was_modified(&self, path: &Path) -> bool {
        if let Ok(events) = self.events.lock() {
            events.iter().any(|e| match e {
                FileChangeEvent::Modified(p) => p == path,
                _ => false,
            })
        } else {
            false
        }
    }

    pub fn stop(&mut self) {
        self.watcher = None;
    }
}

impl Drop for FileSystemWatcher {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_temp_file_filter() {
        assert!(FileSystemWatcher::is_temporary_file(Path::new("~$test.doc")));
        assert!(FileSystemWatcher::is_temporary_file(Path::new(".test.swp")));
        assert!(FileSystemWatcher::is_temporary_file(Path::new("file.tmp")));
        assert!(!FileSystemWatcher::is_temporary_file(Path::new("note.md")));
        assert!(!FileSystemWatcher::is_temporary_file(Path::new("notes/test.md")));
    }
}
