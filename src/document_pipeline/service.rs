use std::collections::HashMap;
use std::sync::Arc;
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::Utc;
use tracing::{info, debug, warn, error};
use crate::models::error::ModelGuardError;
use crate::models::Result;
use super::types::*;

#[async_trait::async_trait]
pub trait DocumentParser: Send + Sync {
    fn format(&self) -> DocumentFormat;
    async fn parse(&self, filename: &str, content: &[u8]) -> Result<ParseResult>;
}

pub struct TxtParser;

#[async_trait::async_trait]
impl DocumentParser for TxtParser {
    fn format(&self) -> DocumentFormat {
        DocumentFormat::Txt
    }

    async fn parse(&self, filename: &str, content: &[u8]) -> Result<ParseResult> {
        let text = String::from_utf8_lossy(content).to_string();
        let cleaned = clean_text(&text);
        
        Ok(ParseResult {
            success: true,
            content: cleaned,
            metadata: HashMap::from([
                ("source".to_string(), filename.to_string()),
                ("encoding".to_string(), "utf-8".to_string()),
            ]),
            error: None,
        })
    }
}

pub struct MarkdownParser;

#[async_trait::async_trait]
impl DocumentParser for MarkdownParser {
    fn format(&self) -> DocumentFormat {
        DocumentFormat::Markdown
    }

    async fn parse(&self, filename: &str, content: &[u8]) -> Result<ParseResult> {
        let text = String::from_utf8_lossy(content).to_string();
        let mut cleaned = clean_text(&text);
        
        cleaned = cleaned.replace("```", "\n");
        
        let mut metadata = HashMap::new();
        metadata.insert("source".to_string(), filename.to_string());
        metadata.insert("format".to_string(), "markdown".to_string());
        
        if cleaned.starts_with("---") {
            if let Some(end) = cleaned.find("---") {
                let header = &cleaned[3..end];
                for line in header.lines() {
                    if let Some((key, value)) = line.split_once(':') {
                        metadata.insert(key.trim().to_string(), value.trim().to_string());
                    }
                }
            }
        }
        
        Ok(ParseResult {
            success: true,
            content: cleaned,
            metadata,
            error: None,
        })
    }
}

pub struct HtmlParser;

#[async_trait::async_trait]
impl DocumentParser for HtmlParser {
    fn format(&self) -> DocumentFormat {
        DocumentFormat::Html
    }

    async fn parse(&self, filename: &str, content: &[u8]) -> Result<ParseResult> {
        let text = String::from_utf8_lossy(content).to_string();
        
        let mut cleaned = text.clone();
        let patterns = [
            (r"<script[^>]*>.*?</script>", " "),
            (r"<style[^>]*>.*?</style>", " "),
            (r"<[^>]+>", " "),
            (r"&nbsp;", " "),
            (r"&amp;", "&"),
            (r"&lt;", "<"),
            (r"&gt;", ">"),
            (r"&quot;", "\""),
        ];
        
        for (pattern, replacement) in patterns {
            cleaned = replace_simple(&cleaned, pattern, replacement);
        }
        
        cleaned = clean_text(&cleaned);
        
        Ok(ParseResult {
            success: true,
            content: cleaned,
            metadata: HashMap::from([
                ("source".to_string(), filename.to_string()),
                ("original_format".to_string(), "html".to_string()),
            ]),
            error: None,
        })
    }
}

fn replace_simple(text: &str, pattern: &str, replacement: &str) -> String {
    let mut result = String::new();
    let mut last_end = 0;
    let pattern_lower = pattern.to_lowercase();
    let text_lower = text.to_lowercase();
    
    let mut i = 0;
    while i <= text_lower.len().saturating_sub(pattern_lower.len()) {
        if text_lower[i..].starts_with(&pattern_lower) {
            result.push_str(&text[last_end..i]);
            result.push_str(replacement);
            
            if pattern_lower.starts_with("<script") || pattern_lower.starts_with("<style") {
                if let Some(end) = text_lower[i..].find("</script").or_else(|| text_lower[i..].find("</style")) {
                    let close_end = text_lower[i + end..].find(">").map(|p| i + end + p + 1).unwrap_or(text.len());
                    i = close_end;
                    last_end = close_end;
                    continue;
                }
            }
            
            i += pattern_lower.len();
            last_end = i;
        } else {
            i += 1;
        }
    }
    
    result.push_str(&text[last_end..]);
    result
}

pub struct JsonParser;

#[async_trait::async_trait]
impl DocumentParser for JsonParser {
    fn format(&self) -> DocumentFormat {
        DocumentFormat::Json
    }

    async fn parse(&self, filename: &str, content: &[u8]) -> Result<ParseResult> {
        let text = String::from_utf8_lossy(content).to_string();
        
        let parsed: serde_json::Result<serde_json::Value> = serde_json::from_str(&text);
        
        match parsed {
            Ok(json) => {
                let mut content = String::new();
                let mut metadata = HashMap::new();
                
                extract_json_values(&json, "", &mut content, &mut metadata);
                
                content = clean_text(&content);
                
                metadata.insert("source".to_string(), filename.to_string());
                metadata.insert("format".to_string(), "json".to_string());
                
                Ok(ParseResult {
                    success: true,
                    content,
                    metadata,
                    error: None,
                })
            }
            Err(e) => Ok(ParseResult {
                success: false,
                content: text,
                metadata: HashMap::new(),
                error: Some(format!("JSON parse error: {}", e)),
            }),
        }
    }
}

fn extract_json_values(
    value: &serde_json::Value,
    prefix: &str,
    content: &mut String,
    metadata: &mut HashMap<String, String>,
) {
    match value {
        serde_json::Value::Object(obj) => {
            for (key, val) in obj {
                let new_prefix = if prefix.is_empty() {
                    key.clone()
                } else {
                    format!("{}.{}", prefix, key)
                };
                extract_json_values(val, &new_prefix, content, metadata);
            }
        }
        serde_json::Value::Array(arr) => {
            for (i, val) in arr.iter().enumerate() {
                let new_prefix = format!("{}[{}]", prefix, i);
                extract_json_values(val, &new_prefix, content, metadata);
            }
        }
        serde_json::Value::String(s) => {
            if !s.is_empty() {
                content.push_str(s);
                content.push_str(". ");
                if prefix.len() < 100 && s.len() < 500 {
                    metadata.insert(prefix.to_string(), s.clone());
                }
            }
        }
        serde_json::Value::Number(n) => {
            content.push_str(&format!("{}: {}. ", prefix, n));
            metadata.insert(prefix.to_string(), n.to_string());
        }
        serde_json::Value::Bool(b) => {
            metadata.insert(prefix.to_string(), b.to_string());
        }
        _ => {}
    }
}

pub struct CsvParser;

#[async_trait::async_trait]
impl DocumentParser for CsvParser {
    fn format(&self) -> DocumentFormat {
        DocumentFormat::Csv
    }

    async fn parse(&self, filename: &str, content: &[u8]) -> Result<ParseResult> {
        let text = String::from_utf8_lossy(content).to_string();
        let mut extracted = String::new();
        let mut metadata = HashMap::new();
        
        let lines: Vec<&str> = text.lines().collect();
        if !lines.is_empty() {
            let headers: Vec<&str> = lines[0].split(',').map(|s| s.trim()).collect();
            metadata.insert("columns".to_string(), headers.join(", "));
            metadata.insert("row_count".to_string(), (lines.len() - 1).to_string());
            
            for (i, line) in lines.iter().skip(1).enumerate() {
                let values: Vec<&str> = line.split(',').map(|s| s.trim()).collect();
                extracted.push_str(&format!("Row {}: ", i + 1));
                for (j, val) in values.iter().enumerate() {
                    if j < headers.len() {
                        extracted.push_str(&format!("{}: {}. ", headers[j], val));
                    }
                }
                extracted.push_str("\n");
            }
        }
        
        extracted = clean_text(&extracted);
        metadata.insert("source".to_string(), filename.to_string());
        
        Ok(ParseResult {
            success: true,
            content: extracted,
            metadata,
            error: None,
        })
    }
}

pub struct DocumentPipelineService {
    parsers: DashMap<DocumentFormat, Arc<dyn DocumentParser>>,
    documents: DashMap<String, Document>,
    chunks: DashMap<String, DocumentChunk>,
    vectorized: DashMap<String, VectorizedChunk>,
    pipeline_cache: DashMap<String, PipelineResult>,
    stats: RwLock<PipelineStats>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PipelineStats {
    pub total_documents: usize,
    pub total_chunks: usize,
    pub total_tokens: usize,
    pub avg_chunks_per_doc: f64,
    pub processing_errors: usize,
}

impl DocumentPipelineService {
    pub fn new() -> Self {
        let service = Self {
            parsers: DashMap::new(),
            documents: DashMap::new(),
            chunks: DashMap::new(),
            vectorized: DashMap::new(),
            pipeline_cache: DashMap::new(),
            stats: RwLock::new(PipelineStats::default()),
        };
        
        service.register_parser(Arc::new(TxtParser));
        service.register_parser(Arc::new(MarkdownParser));
        service.register_parser(Arc::new(HtmlParser));
        service.register_parser(Arc::new(JsonParser));
        service.register_parser(Arc::new(CsvParser));
        
        service
    }

    pub fn register_parser(&self, parser: Arc<dyn DocumentParser>) {
        let format = parser.format();
        self.parsers.insert(format, parser);
        info!(format = ?format, "Document parser registered");
    }

    pub async fn parse_document(
        &self,
        filename: &str,
        content: &[u8],
        config: Option<PipelineConfig>,
    ) -> Result<PipelineResult> {
        let start = std::time::Instant::now();
        let config = config.unwrap_or_default();
        
        let format = DocumentFormat::from_filename(filename);
        
        let parser = self.parsers.get(&format).ok_or_else(|| {
            ModelGuardError::ValidationError(format!("No parser available for format: {:?}", format))
        })?;
        
        debug!(filename = %filename, format = ?format, "Starting document parsing");
        
        let parse_result = parser.parse(filename, content).await?;
        
        if !parse_result.success {
            let mut stats = self.stats.write();
            stats.processing_errors += 1;
            return Err(ModelGuardError::ParsingError(
                parse_result.error.unwrap_or_else(|| "Unknown parsing error".to_string())
            ));
        }
        
        let mut content_text = parse_result.content.clone();
        if config.clean_text {
            content_text = clean_text(&content_text);
        }
        
        let mut doc = Document::new(filename, content_text, Some(parse_result.metadata));
        
        if config.extract_metadata {
            doc.metadata.insert("word_count".to_string(), 
                doc.content.split_whitespace().count().to_string());
            doc.metadata.insert("line_count".to_string(), 
                doc.content.lines().count().to_string());
            doc.metadata.insert("estimated_tokens".to_string(), 
                estimate_token_count(&doc.content).to_string());
        }
        
        let chunks = self.chunk_document(&doc, &config.chunking);
        
        let total_tokens: usize = chunks.iter().map(|c| c.token_count).sum();
        
        let vectorized_chunks = if config.generate_embeddings {
            self.generate_embeddings(&chunks, &config.embedding_model).await?
        } else {
            Vec::new()
        };
        
        let result = PipelineResult {
            document: doc.clone(),
            chunks: chunks.clone(),
            vectorized_chunks: vectorized_chunks.clone(),
            total_chunks: chunks.len(),
            total_tokens,
            processing_time_ms: start.elapsed().as_millis() as u64,
        };
        
        self.store_result(&result)?;
        
        info!(
            document_id = %doc.document_id,
            filename = %filename,
            chunks = chunks.len(),
            tokens = total_tokens,
            "Document pipeline completed"
        );
        
        Ok(result)
    }

    fn chunk_document(&self, doc: &Document, config: &ChunkingConfig) -> Vec<DocumentChunk> {
        let mut chunks = match config.strategy {
            ChunkingStrategy::ByParagraph => {
                let paragraphs = split_by_paragraph(&doc.content);
                paragraphs
                    .into_iter()
                    .enumerate()
                    .map(|(i, p)| {
                        let start = doc.content.find(p).unwrap_or(0);
                        DocumentChunk::new(&doc.document_id, p.to_string(), i, start, start + p.len())
                    })
                    .collect()
            }
            ChunkingStrategy::BySentence => {
                let sentences = split_by_sentence(&doc.content);
                let mut pos = 0;
                sentences
                    .into_iter()
                    .enumerate()
                    .map(|(i, s)| {
                        let start = pos;
                        let end = start + s.len();
                        pos = end;
                        DocumentChunk::new(&doc.document_id, s, i, start, end)
                    })
                    .collect()
            }
            ChunkingStrategy::ByCharacterCount => {
                let mut chunks = Vec::new();
                let mut pos = 0;
                let mut idx = 0;
                while pos < doc.content.len() {
                    let end = std::cmp::min(pos + config.chunk_size, doc.content.len());
                    chunks.push(DocumentChunk::new(
                        &doc.document_id,
                        doc.content[pos..end].to_string(),
                        idx,
                        pos,
                        end,
                    ));
                    pos = end.saturating_sub(config.chunk_overlap);
                    if pos == end {
                        break;
                    }
                    idx += 1;
                }
                chunks
            }
            ChunkingStrategy::ByTokenCount => {
                let mut chunks = Vec::new();
                let mut current = String::new();
                let mut current_tokens = 0;
                let mut idx = 0;
                let mut pos = 0;
                
                for sentence in split_by_sentence(&doc.content) {
                    let tokens = estimate_token_count(&sentence);
                    if current_tokens + tokens > config.max_token_count && !current.is_empty() {
                        let start = pos;
                        let end = start + current.len();
                        chunks.push(DocumentChunk::new(
                            &doc.document_id,
                            current.clone(),
                            idx,
                            start,
                            end,
                        ));
                        idx += 1;
                        pos = end;
                        current.clear();
                        current_tokens = 0;
                    }
                    current.push_str(&sentence);
                    current.push(' ');
                    current_tokens += tokens;
                }
                
                if !current.is_empty() {
                    let start = pos;
                    let end = start + current.len();
                    chunks.push(DocumentChunk::new(
                        &doc.document_id,
                        current,
                        idx,
                        start,
                        end,
                    ));
                }
                chunks
            }
            ChunkingStrategy::Recursive | ChunkingStrategy::Semantic => {
                split_recursive(&doc.content, config)
                    .into_iter()
                    .map(|mut c| {
                        c.document_id = doc.document_id.clone();
                        c
                    })
                    .collect()
            }
        };
        
        for (i, chunk) in chunks.iter_mut().enumerate() {
            chunk.chunk_index = i;
        }
        
        chunks.retain(|c| c.content.len() >= config.min_chunk_size);
        
        chunks
    }

    async fn generate_embeddings(
        &self,
        chunks: &[DocumentChunk],
        model: &str,
    ) -> Result<Vec<VectorizedChunk>> {
        let dim = 1536;
        let mut vectorized = Vec::with_capacity(chunks.len());
        
        for chunk in chunks {
            let vector = self.mock_embedding(&chunk.content, dim);
            vectorized.push(VectorizedChunk {
                chunk: chunk.clone(),
                vector,
                embedding_model: model.to_string(),
                embedding_dim: dim,
            });
        }
        
        Ok(vectorized)
    }

    fn mock_embedding(&self, text: &str, dim: usize) -> Vec<f32> {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        
        let mut hasher = DefaultHasher::new();
        text.hash(&mut hasher);
        let seed = hasher.finish();
        
        let mut vec = Vec::with_capacity(dim);
        let mut rng = seed;
        for i in 0..dim {
            rng = rng.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let val = (rng as f64 / u64::MAX as f64) * 2.0 - 1.0;
            vec.push(val as f32);
        }
        
        let norm: f32 = vec.iter().map(|x| x * x).sum::<f32>().sqrt();
        if norm > 0.0 {
            vec.iter_mut().for_each(|x| *x /= norm);
        }
        
        vec
    }

    fn store_result(&self, result: &PipelineResult) -> Result<()> {
        let doc_id = result.document.document_id.clone();
        
        self.documents.insert(doc_id.clone(), result.document.clone());
        
        for chunk in &result.chunks {
            self.chunks.insert(chunk.chunk_id.clone(), chunk.clone());
        }
        
        for vc in &result.vectorized_chunks {
            self.vectorized.insert(vc.chunk.chunk_id.clone(), vc.clone());
        }
        
        self.pipeline_cache.insert(doc_id.clone(), result.clone());
        
        let mut stats = self.stats.write();
        stats.total_documents += 1;
        stats.total_chunks += result.total_chunks;
        stats.total_tokens += result.total_tokens;
        stats.avg_chunks_per_doc = stats.total_chunks as f64 / stats.total_documents as f64;
        
        Ok(())
    }

    pub fn get_document(&self, document_id: &str) -> Result<Document> {
        self.documents.get(document_id)
            .map(|d| d.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Document {} not found", document_id)))
    }

    pub fn get_chunks(&self, document_id: &str) -> Result<Vec<DocumentChunk>> {
        if !self.documents.contains_key(document_id) {
            return Err(ModelGuardError::NotFound(format!("Document {} not found", document_id)));
        }
        
        let chunks: Vec<DocumentChunk> = self.chunks
            .iter()
            .filter(|c| c.document_id == document_id)
            .map(|c| c.clone())
            .collect();
        
        Ok(chunks)
    }

    pub fn get_pipeline_result(&self, document_id: &str) -> Result<PipelineResult> {
        self.pipeline_cache.get(document_id)
            .map(|r| r.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Pipeline result {} not found", document_id)))
    }

    pub fn get_vectorized_chunks(&self, document_id: &str) -> Result<Vec<VectorizedChunk>> {
        if !self.documents.contains_key(document_id) {
            return Err(ModelGuardError::NotFound(format!("Document {} not found", document_id)));
        }
        
        let vectorized: Vec<VectorizedChunk> = self.vectorized
            .iter()
            .filter(|vc| vc.chunk.document_id == document_id)
            .map(|vc| vc.clone())
            .collect();
        
        Ok(vectorized)
    }

    pub fn similarity_search(
        &self,
        query_vector: &[f32],
        top_k: usize,
    ) -> Vec<(VectorizedChunk, f32)> {
        let mut results: Vec<(VectorizedChunk, f32)> = self.vectorized
            .iter()
            .map(|vc| {
                let score = cosine_similarity(query_vector, &vc.vector);
                (vc.clone(), score)
            })
            .collect();
        
        results.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        results.truncate(top_k);
        results
    }

    pub fn get_stats(&self) -> PipelineStats {
        self.stats.read().clone()
    }
}

pub fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
    if a.len() != b.len() {
        return 0.0;
    }
    
    let mut dot = 0.0;
    let mut norm_a = 0.0;
    let mut norm_b = 0.0;
    
    for i in 0..a.len() {
        dot += a[i] * b[i];
        norm_a += a[i] * a[i];
        norm_b += b[i] * b[i];
    }
    
    let denom = norm_a.sqrt() * norm_b.sqrt();
    if denom == 0.0 {
        0.0
    } else {
        dot / denom
    }
}

impl Default for DocumentPipelineService {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_txt_parsing() {
        let service = DocumentPipelineService::new();
        
        let content = b"Hello World.\n\nThis is a test document.\nIt has multiple lines.";
        let result = service.parse_document("test.txt", content, None).await.unwrap();
        
        assert_eq!(result.document.format, DocumentFormat::Txt);
        assert!(!result.chunks.is_empty());
        assert!(result.total_tokens > 0);
        assert_eq!(result.vectorized_chunks.len(), result.chunks.len());
    }

    #[tokio::test]
    async fn test_markdown_parsing() {
        let service = DocumentPipelineService::new();
        
        let content = b"---\ntitle: Test Doc\nauthor: John\n---\n\n# Heading\n\nThis is **bold** text.\n\n## Subheading\n\nMore content here.";
        let result = service.parse_document("test.md", content, None).await.unwrap();
        
        assert_eq!(result.document.format, DocumentFormat::Markdown);
        assert!(result.document.metadata.contains_key("title"));
        assert_eq!(result.document.metadata.get("title").unwrap(), "Test Doc");
    }

    #[tokio::test]
    async fn test_html_parsing() {
        let service = DocumentPipelineService::new();
        
        let content = b"<html><head><title>Test</title></head><body><p>Hello <b>World</b>!</p></body></html>";
        let result = service.parse_document("test.html", content, None).await.unwrap();
        
        assert!(!result.document.content.contains("<"));
        assert!(result.document.content.contains("Hello"));
        assert!(result.document.content.contains("World"));
    }

    #[tokio::test]
    async fn test_json_parsing() {
        let service = DocumentPipelineService::new();
        
        let content = br#"{"name": "Test", "value": 42, "nested": {"key": "value"}}"#;
        let result = service.parse_document("test.json", content, None).await.unwrap();
        
        assert!(result.document.metadata.contains_key("name"));
        assert_eq!(result.document.metadata.get("name").unwrap(), "Test");
        assert!(result.document.content.contains("Test"));
    }

    #[tokio::test]
    async fn test_csv_parsing() {
        let service = DocumentPipelineService::new();
        
        let content = b"name,age,city\nAlice,30,NYC\nBob,25,LA";
        let result = service.parse_document("test.csv", content, None).await.unwrap();
        
        assert!(result.document.metadata.contains_key("columns"));
        assert_eq!(result.document.metadata.get("row_count").unwrap(), "2");
        assert!(result.document.content.contains("Alice"));
        assert!(result.document.content.contains("Bob"));
    }

    #[tokio::test]
    async fn test_chunking_strategies() {
        let service = DocumentPipelineService::new();
        
        let text = "Sentence one. Sentence two! Sentence three? ".repeat(20);
        let content = text.as_bytes();
        
        let config = PipelineConfig {
            chunking: ChunkingConfig {
                strategy: ChunkingStrategy::BySentence,
                ..Default::default()
            },
            ..Default::default()
        };
        
        let result = service.parse_document("test.txt", content, Some(config)).await.unwrap();
        assert!(!result.chunks.is_empty());
        
        let config2 = PipelineConfig {
            chunking: ChunkingConfig {
                strategy: ChunkingStrategy::ByParagraph,
                ..Default::default()
            },
            ..Default::default()
        };
        
        let result2 = service.parse_document("test2.txt", content, Some(config2)).await.unwrap();
        assert!(!result2.chunks.is_empty());
    }

    #[tokio::test]
    async fn test_similarity_search() {
        let service = DocumentPipelineService::new();
        
        let content1 = b"Machine learning is a subset of artificial intelligence.";
        let content2 = b"Deep learning uses neural networks with many layers.";
        let content3 = b"Cooking recipes involve ingredients and preparation steps.";
        
        let r1 = service.parse_document("ai.txt", content1, None).await.unwrap();
        let r2 = service.parse_document("dl.txt", content2, None).await.unwrap();
        let r3 = service.parse_document("cook.txt", content3, None).await.unwrap();
        
        let query_vector = r1.vectorized_chunks[0].vector.clone();
        let results = service.similarity_search(&query_vector, 5);
        
        assert_eq!(results[0].0.chunk.document_id, r1.document.document_id);
        assert!(results[0].1 > 0.99);
        
        assert_eq!(results[1].0.chunk.document_id, r2.document.document_id);
        assert!(results[1].1 < 0.5);
    }

    #[test]
    fn test_cosine_similarity() {
        let a = vec![1.0, 0.0, 0.0];
        let b = vec![1.0, 0.0, 0.0];
        let c = vec![0.0, 1.0, 0.0];
        
        assert!((cosine_similarity(&a, &b) - 1.0).abs() < 0.001);
        assert!((cosine_similarity(&a, &c) - 0.0).abs() < 0.001);
    }

    #[tokio::test]
    async fn test_unsupported_format() {
        let service = DocumentPipelineService::new();
        
        let content = b"test content";
        let result = service.parse_document("test.unknown", content, None).await;
        
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_get_document_and_chunks() {
        let service = DocumentPipelineService::new();
        
        let content = b"Test document content for retrieval.";
        let result = service.parse_document("test.txt", content, None).await.unwrap();
        
        let doc = service.get_document(&result.document.document_id).unwrap();
        assert_eq!(doc.document_id, result.document.document_id);
        
        let chunks = service.get_chunks(&result.document.document_id).unwrap();
        assert_eq!(chunks.len(), result.chunks.len());
    }

    #[tokio::test]
    async fn test_pipeline_stats() {
        let service = DocumentPipelineService::new();
        
        let content = b"First document.";
        service.parse_document("test1.txt", content, None).await.unwrap();
        
        let content2 = b"Second document with more content.";
        service.parse_document("test2.txt", content2, None).await.unwrap();
        
        let stats = service.get_stats();
        assert_eq!(stats.total_documents, 2);
        assert!(stats.total_chunks >= 2);
        assert!(stats.avg_chunks_per_doc >= 1.0);
    }
}
