use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DocumentFormat {
    Pdf,
    Txt,
    Markdown,
    Html,
    Docx,
    Json,
    Csv,
    Unknown,
}

impl DocumentFormat {
    pub fn from_filename(filename: &str) -> Self {
        let ext = filename.split('.').last().unwrap_or("").to_lowercase();
        match ext.as_str() {
            "pdf" => Self::Pdf,
            "txt" => Self::Txt,
            "md" | "markdown" => Self::Markdown,
            "html" | "htm" => Self::Html,
            "docx" => Self::Docx,
            "json" => Self::Json,
            "csv" => Self::Csv,
            _ => Self::Unknown,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChunkingStrategy {
    ByParagraph,
    BySentence,
    ByTokenCount,
    ByCharacterCount,
    Recursive,
    Semantic,
}

impl Default for ChunkingStrategy {
    fn default() -> Self {
        Self::Recursive
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Document {
    pub document_id: String,
    pub filename: String,
    pub format: DocumentFormat,
    pub content: String,
    pub metadata: HashMap<String, String>,
    pub size_bytes: usize,
    pub created_at: DateTime<Utc>,
}

impl Document {
    pub fn new(filename: &str, content: String, metadata: Option<HashMap<String, String>>) -> Self {
        let format = DocumentFormat::from_filename(filename);
        Self {
            document_id: Uuid::new_v4().to_string(),
            filename: filename.to_string(),
            format,
            size_bytes: content.len(),
            content,
            metadata: metadata.unwrap_or_default(),
            created_at: Utc::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentChunk {
    pub chunk_id: String,
    pub document_id: String,
    pub content: String,
    pub chunk_index: usize,
    pub start_position: usize,
    pub end_position: usize,
    pub token_count: usize,
    pub metadata: HashMap<String, String>,
}

impl DocumentChunk {
    pub fn new(
        document_id: &str,
        content: String,
        index: usize,
        start: usize,
        end: usize,
    ) -> Self {
        let token_count = estimate_token_count(&content);
        Self {
            chunk_id: Uuid::new_v4().to_string(),
            document_id: document_id.to_string(),
            content,
            chunk_index: index,
            start_position: start,
            end_position: end,
            token_count,
            metadata: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VectorizedChunk {
    pub chunk: DocumentChunk,
    pub vector: Vec<f32>,
    pub embedding_model: String,
    pub embedding_dim: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChunkingConfig {
    pub strategy: ChunkingStrategy,
    pub chunk_size: usize,
    pub chunk_overlap: usize,
    pub min_chunk_size: usize,
    pub max_token_count: usize,
}

impl Default for ChunkingConfig {
    fn default() -> Self {
        Self {
            strategy: ChunkingStrategy::Recursive,
            chunk_size: 512,
            chunk_overlap: 50,
            min_chunk_size: 100,
            max_token_count: 1024,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineConfig {
    pub extract_metadata: bool,
    pub clean_text: bool,
    pub chunking: ChunkingConfig,
    pub generate_embeddings: bool,
    pub embedding_model: String,
}

impl Default for PipelineConfig {
    fn default() -> Self {
        Self {
            extract_metadata: true,
            clean_text: true,
            chunking: ChunkingConfig::default(),
            generate_embeddings: true,
            embedding_model: "text-embedding-3-small".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineResult {
    pub document: Document,
    pub chunks: Vec<DocumentChunk>,
    pub vectorized_chunks: Vec<VectorizedChunk>,
    pub total_chunks: usize,
    pub total_tokens: usize,
    pub processing_time_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParseResult {
    pub success: bool,
    pub content: String,
    pub metadata: HashMap<String, String>,
    pub error: Option<String>,
}

pub fn estimate_token_count(text: &str) -> usize {
    let chars = text.chars().count();
    (chars as f64 * 0.4) as usize
}

pub fn clean_text(text: &str) -> String {
    let mut result = text.to_string();
    
    result = result.replace("\r\n", "\n");
    result = result.replace("\r", "\n");
    
    let lines: Vec<&str> = result.lines().collect();
    let cleaned_lines: Vec<&str> = lines
        .iter()
        .filter(|line| !line.trim().is_empty())
        .map(|line| line.trim())
        .collect();
    
    cleaned_lines.join("\n")
}

pub fn split_by_paragraph(text: &str) -> Vec<&str> {
    text.split("\n\n")
        .filter(|p| !p.trim().is_empty())
        .collect()
}

pub fn split_by_sentence(text: &str) -> Vec<String> {
    let mut sentences = Vec::new();
    let mut current = String::new();
    
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    
    while i < chars.len() {
        let c = chars[i];
        current.push(c);
        
        if matches!(c, '.' | '!' | '?') {
            if i + 1 < chars.len() && chars[i + 1].is_whitespace() {
                if !current.trim().is_empty() {
                    sentences.push(current.trim().to_string());
                }
                current.clear();
                i += 1;
                while i < chars.len() && chars[i].is_whitespace() {
                    i += 1;
                }
                continue;
            }
        }
        i += 1;
    }
    
    if !current.trim().is_empty() {
        sentences.push(current.trim().to_string());
    }
    
    sentences
}

pub fn split_recursive(text: &str, config: &ChunkingConfig) -> Vec<DocumentChunk> {
    let mut chunks = Vec::new();
    
    if text.len() <= config.chunk_size {
        if text.len() >= config.min_chunk_size {
            chunks.push(DocumentChunk::new("", text.to_string(), 0, 0, text.len()));
        }
        return chunks;
    }
    
    let separators = ["\n\n", "\n", ". ", "! ", "? ", " ", ""];
    split_recursive_with_separators(
        text,
        0,
        text.len(),
        &separators,
        config,
        0,
        &mut chunks,
    );
    
    for (i, chunk) in chunks.iter_mut().enumerate() {
        chunk.chunk_index = i;
    }
    
    chunks
}

fn split_recursive_with_separators(
    text: &str,
    start: usize,
    end: usize,
    separators: &[&str],
    config: &ChunkingConfig,
    chunk_index: usize,
    chunks: &mut Vec<DocumentChunk>,
) -> usize {
    let segment = &text[start..end];
    let segment_len = end - start;
    
    if segment_len <= config.chunk_size {
        if segment_len >= config.min_chunk_size {
            chunks.push(DocumentChunk::new(
                "",
                segment.to_string(),
                chunk_index,
                start,
                end,
            ));
            return chunk_index + 1;
        }
        return chunk_index;
    }
    
    if separators.is_empty() {
        let mid = start + config.chunk_size - config.chunk_overlap;
        let new_start = mid;
        let next_start = new_start.saturating_sub(config.chunk_overlap);
        
        chunks.push(DocumentChunk::new(
            "",
            text[start..mid].to_string(),
            chunk_index,
            start,
            mid,
        ));
        
        return split_recursive_with_separators(
            text,
            next_start,
            end,
            separators,
            config,
            chunk_index + 1,
            chunks,
        );
    }
    
    let separator = separators[0];
    
    let mid = start + config.chunk_size - config.chunk_overlap;
    let search_start = mid - config.chunk_overlap;
    let search_end = mid + config.chunk_overlap;
    
    let search_region = &text[search_start.min(end)..search_end.min(end)];
    
    if let Some(pos) = search_region.rfind(separator) {
        let split_pos = search_start + pos + separator.len();
        if split_pos > start && split_pos < end {
            chunks.push(DocumentChunk::new(
                "",
                text[start..split_pos].to_string(),
                chunk_index,
                start,
                split_pos,
            ));
            
            let overlap_start = split_pos.saturating_sub(config.chunk_overlap);
            return split_recursive_with_separators(
                text,
                overlap_start,
                end,
                separators,
                config,
                chunk_index + 1,
                chunks,
            );
        }
    }
    
    split_recursive_with_separators(
        text,
        start,
        end,
        &separators[1..],
        config,
        chunk_index,
        chunks,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_document_format_detection() {
        assert_eq!(DocumentFormat::from_filename("doc.pdf"), DocumentFormat::Pdf);
        assert_eq!(DocumentFormat::from_filename("readme.md"), DocumentFormat::Markdown);
        assert_eq!(DocumentFormat::from_filename("report.html"), DocumentFormat::Html);
        assert_eq!(DocumentFormat::from_filename("unknown.xyz"), DocumentFormat::Unknown);
    }

    #[test]
    fn test_estimate_token_count() {
        let text = "Hello, world! This is a test.";
        let count = estimate_token_count(text);
        assert!(count > 0);
        assert!(count < text.chars().count());
    }

    #[test]
    fn test_clean_text() {
        let text = "Line 1\r\nLine 2\n\nLine 3\n  Line 4  \n\n";
        let cleaned = clean_text(text);
        assert!(!cleaned.contains("\r"));
        assert_eq!(cleaned.matches("\n\n").count(), 0);
        assert!(!cleaned.contains("  "));
    }

    #[test]
    fn test_split_by_paragraph() {
        let text = "Paragraph 1.\n\nParagraph 2.\n\nParagraph 3.";
        let paragraphs = split_by_paragraph(text);
        assert_eq!(paragraphs.len(), 3);
    }

    #[test]
    fn test_split_by_sentence() {
        let text = "Hello! How are you? I am fine. Goodbye.";
        let sentences = split_by_sentence(text);
        assert_eq!(sentences.len(), 4);
    }

    #[test]
    fn test_recursive_chunking() {
        let text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(50);
        let config = ChunkingConfig {
            chunk_size: 200,
            chunk_overlap: 20,
            min_chunk_size: 50,
            ..Default::default()
        };
        
        let chunks = split_recursive(&text, &config);
        assert!(!chunks.is_empty());
        
        for chunk in &chunks {
            assert!(chunk.content.len() <= config.chunk_size);
            assert!(chunk.content.len() >= config.min_chunk_size);
        }
        
        let last_end = chunks.last().unwrap().end_position;
        assert_eq!(last_end, text.len());
    }

    #[test]
    fn test_document_creation() {
        let doc = Document::new("test.txt", "Hello world".to_string(), None);
        assert_eq!(doc.filename, "test.txt");
        assert_eq!(doc.format, DocumentFormat::Txt);
        assert_eq!(doc.size_bytes, 11);
        assert!(!doc.document_id.is_empty());
    }

    #[test]
    fn test_chunk_creation() {
        let chunk = DocumentChunk::new("doc-123", "Test content".to_string(), 0, 0, 12);
        assert_eq!(chunk.document_id, "doc-123");
        assert_eq!(chunk.chunk_index, 0);
        assert_eq!(chunk.token_count, estimate_token_count("Test content"));
    }
}
