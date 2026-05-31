use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use crate::utils::error::Result;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Chunk {
    pub chunk_id: String,
    pub document_id: String,
    pub content: String,
    pub index: usize,
    pub start_pos: usize,
    pub end_pos: usize,
    pub word_count: usize,
    pub token_count: usize,
    pub metadata: HashMap<String, String>,
    pub embedding: Option<Vec<f32>>,
}

impl Chunk {
    pub fn new(
        document_id: String,
        content: String,
        index: usize,
        start_pos: usize,
        end_pos: usize,
    ) -> Self {
        let word_count = content.split_whitespace().count();
        let token_count = content.chars().count() / 4;

        Self {
            chunk_id: format!("chk_{}", Uuid::new_v4().simple()),
            document_id,
            content,
            index,
            start_pos,
            end_pos,
            word_count,
            token_count,
            metadata: HashMap::new(),
            embedding: None,
        }
    }

    pub fn with_embedding(mut self, embedding: Vec<f32>) -> Self {
        self.embedding = Some(embedding);
        self
    }

    pub fn with_metadata<K: Into<String>, V: Into<String>>(mut self, key: K, value: V) -> Self {
        self.metadata.insert(key.into(), value.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SplitConfig {
    pub chunk_size: usize,
    pub chunk_overlap: usize,
    pub separator: String,
    pub strategy: SplitStrategy,
    pub min_chunk_size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SplitStrategy {
    FixedSize,
    Recursive,
    Semantic,
    ByParagraph,
    BySentence,
}

impl Default for SplitConfig {
    fn default() -> Self {
        Self {
            chunk_size: 1000,
            chunk_overlap: 200,
            separator: "\n\n".to_string(),
            strategy: SplitStrategy::FixedSize,
            min_chunk_size: 100,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SplitResult {
    pub chunks: Vec<Chunk>,
    pub total_chunks: usize,
    pub split_time_ms: u64,
}

pub struct DocumentSplitter {
    config: SplitConfig,
}

impl DocumentSplitter {
    pub fn new(config: SplitConfig) -> Self {
        Self { config }
    }

    pub fn split(&self, document_id: &str, content: &str) -> Result<SplitResult> {
        let start = std::time::Instant::now();

        let chunks = match self.config.strategy {
            SplitStrategy::FixedSize => self.split_fixed_size(document_id, content),
            SplitStrategy::Recursive => self.split_recursive(document_id, content),
            SplitStrategy::Semantic => self.split_semantic(document_id, content),
            SplitStrategy::ByParagraph => self.split_by_paragraph(document_id, content),
            SplitStrategy::BySentence => self.split_by_sentence(document_id, content),
        }?;

        let split_time_ms = start.elapsed().as_millis() as u64;
        let total_chunks = chunks.len();

        Ok(SplitResult {
            chunks,
            total_chunks,
            split_time_ms,
        })
    }

    fn split_fixed_size(&self, document_id: &str, content: &str) -> Result<Vec<Chunk>> {
        let mut chunks = Vec::new();
        let chars: Vec<char> = content.chars().collect();
        let mut index = 0;
        let mut start_pos = 0;

        while start_pos < chars.len() {
            let end_pos = std::cmp::min(
                start_pos + self.config.chunk_size,
                chars.len()
            );

            let chunk_content: String = chars[start_pos..end_pos].iter().collect();
            
            if chunk_content.trim().len() >= self.config.min_chunk_size {
                chunks.push(Chunk::new(
                    document_id.to_string(),
                    chunk_content,
                    index,
                    start_pos,
                    end_pos,
                ));
                index += 1;
            }

            if end_pos >= chars.len() {
                break;
            }

            start_pos = if end_pos > self.config.chunk_overlap {
                end_pos - self.config.chunk_overlap
            } else {
                end_pos
            };
        }

        Ok(chunks)
    }

    fn split_recursive(&self, document_id: &str, content: &str) -> Result<Vec<Chunk>> {
        let separators = vec![
            "\n\n", "\n", ". ", "! ", "? ", "; ", ": ", ", ", " ", ""
        ];
        self.split_with_separators(document_id, content, &separators, 0)
    }

    fn split_with_separators(
        &self,
        document_id: &str,
        content: &str,
        separators: &[&str],
        index: usize,
    ) -> Result<Vec<Chunk>> {
        if content.chars().count() <= self.config.chunk_size {
            return Ok(vec![Chunk::new(
                document_id.to_string(),
                content.to_string(),
                index,
                0,
                content.chars().count(),
            )]);
        }

        if separators.is_empty() {
            return self.split_fixed_size(document_id, content);
        }

        let separator = separators[0];
        let parts: Vec<&str> = content.split(separator).collect();
        
        if parts.len() <= 1 {
            return self.split_with_separators(document_id, content, &separators[1..], index);
        }

        let mut chunks = Vec::new();
        let mut current_chunk = String::new();
        let mut chunk_index = index;
        let mut pos = 0;

        for part in parts {
            let part_with_sep = if current_chunk.is_empty() {
                part.to_string()
            } else {
                format!("{}{}{}", current_chunk, separator, part)
            };

            if part_with_sep.chars().count() > self.config.chunk_size && !current_chunk.is_empty() {
                chunks.push(Chunk::new(
                    document_id.to_string(),
                    current_chunk,
                    chunk_index,
                    pos,
                    pos + current_chunk.chars().count(),
                ));
                chunk_index += 1;
                pos += current_chunk.chars().count() + separator.chars().count();
                current_chunk = part.to_string();
            } else {
                current_chunk = part_with_sep;
            }
        }

        if !current_chunk.is_empty() {
            if current_chunk.chars().count() >= self.config.min_chunk_size {
                chunks.push(Chunk::new(
                    document_id.to_string(),
                    current_chunk,
                    chunk_index,
                    pos,
                    pos + current_chunk.chars().count(),
                ));
            } else if let Some(last) = chunks.last_mut() {
                last.content.push_str(separator);
                last.content.push_str(&current_chunk);
                last.end_pos += current_chunk.chars().count() + separator.chars().count();
            }
        }

        Ok(chunks)
    }

    fn split_semantic(&self, document_id: &str, content: &str) -> Result<Vec<Chunk>> {
        let paragraphs: Vec<&str> = content.split("\n\n").collect();
        let mut chunks = Vec::new();
        let mut current_chunk = String::new();
        let mut index = 0;
        let mut pos = 0;

        for para in paragraphs {
            let candidate = if current_chunk.is_empty() {
                para.to_string()
            } else {
                format!("{}\n\n{}", current_chunk, para)
            };

            if candidate.chars().count() > self.config.chunk_size && !current_chunk.is_empty() {
                chunks.push(Chunk::new(
                    document_id.to_string(),
                    current_chunk,
                    index,
                    pos,
                    pos + current_chunk.chars().count(),
                ).with_metadata("semantic_boundary", "true"));
                index += 1;
                pos += current_chunk.chars().count() + 2;
                current_chunk = para.to_string();
            } else {
                current_chunk = candidate;
            }
        }

        if !current_chunk.is_empty() {
            chunks.push(Chunk::new(
                document_id.to_string(),
                current_chunk,
                index,
                pos,
                pos + current_chunk.chars().count(),
            ).with_metadata("semantic_boundary", "true"));
        }

        Ok(chunks)
    }

    fn split_by_paragraph(&self, document_id: &str, content: &str) -> Result<Vec<Chunk>> {
        let mut chunks = Vec::new();
        let mut pos = 0;
        let paragraphs: Vec<&str> = content.split("\n\n").collect();

        for (index, para) in paragraphs.iter().enumerate() {
            if para.trim().chars().count() >= self.config.min_chunk_size {
                chunks.push(Chunk::new(
                    document_id.to_string(),
                    para.to_string(),
                    index,
                    pos,
                    pos + para.chars().count(),
                ).with_metadata("type", "paragraph"));
            }
            pos += para.chars().count() + 2;
        }

        Ok(chunks)
    }

    fn split_by_sentence(&self, document_id: &str, content: &str) -> Result<Vec<Chunk>> {
        let re = regex::Regex::new(r"(?<=[.!?])\s+").unwrap();
        let sentences: Vec<&str> = re.split(content).collect();
        let mut chunks = Vec::new();
        let mut pos = 0;

        for (index, sent) in sentences.iter().enumerate() {
            if sent.trim().chars().count() >= self.config.min_chunk_size {
                chunks.push(Chunk::new(
                    document_id.to_string(),
                    sent.to_string(),
                    index,
                    pos,
                    pos + sent.chars().count(),
                ).with_metadata("type", "sentence"));
            }
            pos += sent.chars().count() + 1;
        }

        Ok(chunks)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_content() -> String {
        let mut content = String::new();
        for i in 0..10 {
            content.push_str(&format!("Paragraph {}. This is a test paragraph with some content. It has multiple sentences to make it longer.\n\n", i));
        }
        content
    }

    #[test]
    fn test_fixed_size_split() {
        let config = SplitConfig {
            chunk_size: 200,
            chunk_overlap: 50,
            ..Default::default()
        };
        let splitter = DocumentSplitter::new(config);
        let content = create_test_content();
        let result = splitter.split("doc_123", &content).unwrap();

        assert!(result.total_chunks > 0);
        for chunk in &result.chunks {
            assert!(chunk.content.chars().count() <= 200);
            assert_eq!(chunk.document_id, "doc_123");
            assert!(chunk.chunk_id.starts_with("chk_"));
        }
    }

    #[test]
    fn test_recursive_split() {
        let config = SplitConfig {
            chunk_size: 500,
            chunk_overlap: 100,
            strategy: SplitStrategy::Recursive,
            ..Default::default()
        };
        let splitter = DocumentSplitter::new(config);
        let content = create_test_content();
        let result = splitter.split("doc_456", &content).unwrap();

        assert!(result.total_chunks > 0);
        for chunk in &result.chunks {
            assert!(chunk.content.chars().count() <= 500);
            assert!(chunk.word_count > 0);
        }
    }

    #[test]
    fn test_semantic_split() {
        let config = SplitConfig {
            chunk_size: 1000,
            strategy: SplitStrategy::Semantic,
            ..Default::default()
        };
        let splitter = DocumentSplitter::new(config);
        let content = create_test_content();
        let result = splitter.split("doc_789", &content).unwrap();

        assert!(result.total_chunks > 0);
        for chunk in &result.chunks {
            assert_eq!(chunk.metadata.get("semantic_boundary"), Some(&"true".to_string()));
        }
    }

    #[test]
    fn test_paragraph_split() {
        let config = SplitConfig {
            strategy: SplitStrategy::ByParagraph,
            min_chunk_size: 10,
            ..Default::default()
        };
        let splitter = DocumentSplitter::new(config);
        let content = create_test_content();
        let result = splitter.split("doc_para", &content).unwrap();

        assert_eq!(result.total_chunks, 10);
    }

    #[test]
    fn test_chunk_metadata() {
        let chunk = Chunk::new("doc_1".to_string(), "test content".to_string(), 0, 0, 12)
            .with_metadata("section", "intro")
            .with_embedding(vec![0.1, 0.2, 0.3]);

        assert_eq!(chunk.metadata.get("section"), Some(&"intro".to_string()));
        assert_eq!(chunk.embedding, Some(vec![0.1, 0.2, 0.3]));
        assert_eq!(chunk.word_count, 2);
    }
}
