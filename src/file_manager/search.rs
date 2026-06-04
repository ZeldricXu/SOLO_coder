use std::path::{Path, PathBuf};
use std::error::Error;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use tantivy::collector::TopDocs;
use tantivy::query::QueryParser;
use tantivy::schema::*;
use tantivy::{doc, Index, IndexReader, IndexWriter, Term};
use walkdir::WalkDir;

#[derive(Debug, Clone)]
pub struct SearchResult {
    pub file_path: PathBuf,
    pub snippet: String,
    pub score: f32,
    pub match_count: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchMode {
    GrepFallback,
    TantivyIndex,
}

#[derive(Debug, Clone)]
pub struct IndexStatus {
    pub is_ready: bool,
    pub progress: f32,
    pub total_files: usize,
    pub indexed_files: usize,
    pub mode: SearchMode,
}

pub struct SearchEngine {
    index: Option<Arc<Mutex<TantivyIndex>>>,
    index_dir: PathBuf,
    notebook_dir: PathBuf,
    status: Arc<Mutex<IndexStatus>>,
    index_thread: Option<std::thread::JoinHandle<()>>,
    file_mtimes: Arc<Mutex<std::collections::HashMap<PathBuf, u64>>>,
}

struct TantivyIndex {
    index: Index,
    reader: IndexReader,
    writer: IndexWriter,
    schema: Schema,
}

impl SearchEngine {
    pub fn new(index_dir: &Path, notebook_dir: &Path) -> Result<Self, Box<dyn Error>> {
        let status = Arc::new(Mutex::new(IndexStatus {
            is_ready: false,
            progress: 0.0,
            total_files: 0,
            indexed_files: 0,
            mode: SearchMode::GrepFallback,
        }));

        let file_mtimes = Arc::new(Mutex::new(std::collections::HashMap::new()));

        Ok(Self {
            index: None,
            index_dir: index_dir.to_path_buf(),
            notebook_dir: notebook_dir.to_path_buf(),
            status,
            index_thread: None,
            file_mtimes,
        })
    }

    pub fn start_background_index(&mut self) {
        let index_dir = self.index_dir.clone();
        let notebook_dir = self.notebook_dir.clone();
        let status = self.status.clone();
        let file_mtimes = self.file_mtimes.clone();

        self.index_thread = Some(thread::spawn(move || {
            if let Err(e) = Self::build_index_in_background(&index_dir, &notebook_dir, &status, &file_mtimes) {
                eprintln!("Background indexing failed: {}", e);
            }
        }));
    }

    fn build_index_in_background(
        index_dir: &Path,
        notebook_dir: &Path,
        status: &Arc<Mutex<IndexStatus>>,
        file_mtimes: &Arc<Mutex<std::collections::HashMap<PathBuf, u64>>>,
    ) -> Result<(), Box<dyn Error + Send + Sync>> {
        let md_files: Vec<PathBuf> = WalkDir::new(notebook_dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.path()
                    .extension()
                    .and_then(|ext| ext.to_str())
                    .map(|ext| ext.to_lowercase() == "md")
                    .unwrap_or(false)
            })
            .map(|e| e.path().to_path_buf())
            .collect();

        let total_files = md_files.len();
        
        if let Ok(mut status) = status.lock() {
            status.total_files = total_files;
        }

        let mut index = TantivyIndex::new(index_dir)?;

        let mut mtimes = file_mtimes.lock().unwrap();

        for (i, file_path) in md_files.iter().enumerate() {
            if let Ok(metadata) = std::fs::metadata(file_path) {
                let mtime = metadata
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0);

                let cached_mtime = mtimes.get(file_path).copied().unwrap_or(0);

                if mtime != cached_mtime {
                    if let Ok(content) = std::fs::read_to_string(file_path) {
                        let _ = index.index_file(file_path, &content);
                    }
                    mtimes.insert(file_path.clone(), mtime);
                }
            }

            if let Ok(mut status) = status.lock() {
                status.indexed_files = i + 1;
                status.progress = (i + 1) as f32 / total_files.max(1) as f32;
            }

            thread::sleep(std::time::Duration::from_millis(1));
        }

        index.commit()?;

        if let Ok(mut status) = status.lock() {
            status.is_ready = true;
            status.mode = SearchMode::TantivyIndex;
            status.progress = 1.0;
        }

        Ok(())
    }

    pub fn get_status(&self) -> IndexStatus {
        self.status.lock().map(|s| s.clone()).unwrap_or(IndexStatus {
            is_ready: false,
            progress: 0.0,
            total_files: 0,
            indexed_files: 0,
            mode: SearchMode::GrepFallback,
        })
    }

    pub fn search(&self, query_str: &str, limit: usize) -> Result<Vec<SearchResult>, Box<dyn Error>> {
        let status = self.get_status();
        
        if status.is_ready {
            self.search_tantivy(query_str, limit)
        } else {
            self.search_grep(query_str, limit)
        }
    }

    fn search_tantivy(&self, query_str: &str, limit: usize) -> Result<Vec<SearchResult>, Box<dyn Error>> {
        if let Some(index_arc) = &self.index {
            if let Ok(index) = index_arc.lock() {
                return index.search(query_str, limit);
            }
        }
        self.search_grep(query_str, limit)
    }

    fn search_grep(&self, query_str: &str, limit: usize) -> Result<Vec<SearchResult>, Box<dyn Error>> {
        let mut results = Vec::new();
        let query_lower = query_str.to_lowercase();

        for entry in WalkDir::new(&self.notebook_dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.path()
                    .extension()
                    .and_then(|ext| ext.to_str())
                    .map(|ext| ext.to_lowercase() == "md")
                    .unwrap_or(false)
            })
            .take(limit * 2)
        {
            if let Ok(content) = std::fs::read_to_string(entry.path()) {
                if content.to_lowercase().contains(&query_lower) {
                    let match_count = content.to_lowercase().matches(&query_lower).count();
                    
                    results.push(SearchResult {
                        file_path: entry.path().to_path_buf(),
                        snippet: create_snippet(&content, query_str),
                        score: match_count as f32,
                        match_count,
                    });

                    if results.len() >= limit {
                        break;
                    }
                }
            }
        }

        results.sort_by(|a, b| b.match_count.cmp(&a.match_count));
        Ok(results)
    }

    pub fn index_file(&mut self, path: &Path, content: &str) -> Result<(), Box<dyn Error>> {
        if let Some(index_arc) = &self.index {
            if let Ok(mut index) = index_arc.lock() {
                index.index_file(path, content).map_err(|e| e as Box<dyn Error>)?;
            }
        }

        if let Ok(mut mtimes) = self.file_mtimes.lock() {
            if let Ok(metadata) = std::fs::metadata(path) {
                let mtime = metadata
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                mtimes.insert(path.to_path_buf(), mtime);
            }
        }

        Ok(())
    }

    pub fn remove_file(&mut self, path: &Path) -> Result<(), Box<dyn Error>> {
        if let Some(index_arc) = &self.index {
            if let Ok(mut index) = index_arc.lock() {
                index.remove_file(path).map_err(|e| e as Box<dyn Error>)?;
            }
        }

        if let Ok(mut mtimes) = self.file_mtimes.lock() {
            mtimes.remove(path);
        }

        Ok(())
    }

    pub fn wait_for_index(&self) {
        while !self.get_status().is_ready {
            thread::sleep(std::time::Duration::from_millis(100));
        }
    }
}

impl TantivyIndex {
    pub fn new(index_dir: &Path) -> Result<Self, Box<dyn Error + Send + Sync>> {
        let mut schema_builder = Schema::builder();

        let path_field = schema_builder.add_text_field(
            "path",
            TEXT | STORED,
        );
        let content_field = schema_builder.add_text_field(
            "content",
            TEXT | STORED,
        );
        let title_field = schema_builder.add_text_field(
            "title",
            TEXT | STORED,
        );
        let tags_field = schema_builder.add_text_field(
            "tags",
            TEXT | STORED,
        );
        let mtime_field = schema_builder.add_u64_field(
            "mtime",
            STORED,
        );

        let schema = schema_builder.build();

        let index = if index_dir.exists() {
            Index::open_in_dir(index_dir)?
        } else {
            std::fs::create_dir_all(index_dir)?;
            Index::create_in_dir(index_dir, schema.clone())?
        };

        let writer = index.writer(200_000_000)?;

        let reader = index
            .reader_builder()
            .try_into()?;

        Ok(Self {
            index,
            reader,
            writer,
            schema,
        })
    }

    pub fn index_file(&mut self, path: &Path, content: &str) -> Result<(), Box<dyn Error + Send + Sync>> {
        let path_str = path.to_string_lossy().to_string();
        let title = path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_string();

        let tags = extract_tags(content);
        
        let mtime = std::fs::metadata(path)
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);

        let path_field = self.schema.get_field("path").unwrap();
        let content_field = self.schema.get_field("content").unwrap();
        let title_field = self.schema.get_field("title").unwrap();
        let tags_field = self.schema.get_field("tags").unwrap();
        let mtime_field = self.schema.get_field("mtime").unwrap();

        let term = Term::from_field_text(path_field, &path_str);
        self.writer.delete_term(term);

        self.writer.add_document(doc!(
            path_field => path_str,
            content_field => content,
            title_field => title,
            tags_field => tags.join(" "),
            mtime_field => mtime,
        ))?;

        Ok(())
    }

    pub fn commit(&mut self) -> Result<(), Box<dyn Error + Send + Sync>> {
        self.writer.commit()?;
        self.reader.reload()?;
        Ok(())
    }

    pub fn remove_file(&mut self, path: &Path) -> Result<(), Box<dyn Error + Send + Sync>> {
        let path_str = path.to_string_lossy().to_string();
        let path_field = self.schema.get_field("path").unwrap();
        let term = Term::from_field_text(path_field, &path_str);
        self.writer.delete_term(term);
        self.commit()?;
        Ok(())
    }

    pub fn search(&self, query_str: &str, limit: usize) -> Result<Vec<SearchResult>, Box<dyn Error>> {
        let searcher = self.reader.searcher();

        let path_field = self.schema.get_field("path").unwrap();
        let content_field = self.schema.get_field("content").unwrap();
        let title_field = self.schema.get_field("title").unwrap();
        let tags_field = self.schema.get_field("tags").unwrap();

        let query_parser = QueryParser::for_index(
            &self.index,
            vec![content_field, title_field, tags_field],
        );

        let query = match query_parser.parse_query(query_str) {
            Ok(q) => q,
            Err(_) => return Ok(Vec::new()),
        };

        let top_docs = searcher.search(&query, &TopDocs::with_limit(limit))?;

        let mut results = Vec::new();

        for (score, doc_address) in top_docs {
            let retrieved_doc = searcher.doc::<tantivy::TantivyDocument>(doc_address)?;

            let file_path = retrieved_doc
                .get_first(path_field)
                .and_then(|v| v.as_str())
                .map(PathBuf::from)
                .unwrap_or_default();

            let content = retrieved_doc
                .get_first(content_field)
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();

            let snippet = create_snippet(&content, query_str);
            let match_count = snippet.matches(char::is_alphabetic).count();

            results.push(SearchResult {
                file_path,
                snippet,
                score,
                match_count,
            });
        }

        Ok(results)
    }
}

fn create_snippet(content: &str, query: &str) -> String {
    let max_length = 200;
    
    let query_lower = query.to_lowercase();
    let content_lower = content.to_lowercase();
    
    if let Some(pos) = content_lower.find(&query_lower) {
        let start = pos.saturating_sub(50);
        let end = (pos + query.len() + 100).min(content.len());
        
        let mut snippet = content[start..end].to_string();
        if start > 0 {
            snippet.insert_str(0, "... ");
        }
        if end < content.len() {
            snippet.push_str(" ...");
        }
        
        return snippet;
    }

    let lines: Vec<&str> = content.lines().collect();
    for (i, line) in lines.iter().enumerate() {
        if line.len() > 10 {
            let mut snippet = line.to_string();
            if snippet.len() > max_length {
                snippet = snippet.chars().take(max_length).collect();
                snippet.push_str("...");
            }

            let words: Vec<&str> = snippet
                .split_whitespace()
                .take(10)
                .collect();

            return format!("... {} ...", words.join(" "));
        }
    }

    let mut snippet: String = content.chars().take(max_length).collect();
    if content.len() > max_length {
        snippet.push_str("...");
    }
    snippet
}

fn extract_tags(content: &str) -> Vec<String> {
    let mut tags = Vec::new();
    for word in content.split_whitespace() {
        if word.starts_with('#') && word.len() > 1 {
            let tag = word.trim_start_matches('#').to_string();
            if !tags.contains(&tag) {
                tags.push(tag);
            }
        }
    }
    tags
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_grep_search() {
        let temp = tempdir().unwrap();
        let index_dir = temp.path().join("index");
        let notebook_dir = temp.path().join("notes");
        
        std::fs::create_dir_all(&notebook_dir).unwrap();
        std::fs::write(notebook_dir.join("test1.md"), "Hello world, this is a test document.").unwrap();
        std::fs::write(notebook_dir.join("test2.md"), "Another document with different content.").unwrap();

        let engine = SearchEngine::new(&index_dir, &notebook_dir).unwrap();
        
        let results = engine.search_grep("test", 10).unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].file_path.ends_with("test1.md"));
    }

    #[test]
    fn test_snippet_creation() {
        let content = "This is a long document with some important content that we want to search for.";
        let snippet = create_snippet(content, "important");
        assert!(snippet.contains("important"));
    }
}
