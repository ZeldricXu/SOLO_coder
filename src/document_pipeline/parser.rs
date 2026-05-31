use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use crate::utils::error::Result;
use crate::utils::id::{generate_id, hash_content};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DocumentFormat {
    Pdf,
    Docx,
    Txt,
    Md,
    Html,
    Json,
    Csv,
    Xlsx,
    Pptx,
    Unknown,
}

impl DocumentFormat {
    pub fn from_extension(ext: &str) -> Self {
        match ext.to_lowercase().as_str() {
            "pdf" => DocumentFormat::Pdf,
            "docx" | "doc" => DocumentFormat::Docx,
            "txt" => DocumentFormat::Txt,
            "md" | "markdown" => DocumentFormat::Md,
            "html" | "htm" => DocumentFormat::Html,
            "json" => DocumentFormat::Json,
            "csv" => DocumentFormat::Csv,
            "xlsx" | "xls" => DocumentFormat::Xlsx,
            "pptx" | "ppt" => DocumentFormat::Pptx,
            _ => DocumentFormat::Unknown,
        }
    }

    pub fn from_mime(mime: &str) -> Self {
        match mime.to_lowercase().as_str() {
            "application/pdf" => DocumentFormat::Pdf,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => DocumentFormat::Docx,
            "text/plain" => DocumentFormat::Txt,
            "text/markdown" => DocumentFormat::Md,
            "text/html" => DocumentFormat::Html,
            "application/json" => DocumentFormat::Json,
            "text/csv" => DocumentFormat::Csv,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => DocumentFormat::Xlsx,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" => DocumentFormat::Pptx,
            _ => DocumentFormat::Unknown,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Document {
    pub document_id: String,
    pub title: String,
    pub content: String,
    pub format: DocumentFormat,
    pub metadata: HashMap<String, String>,
    pub content_hash: String,
    pub size_bytes: usize,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

impl Document {
    pub fn new(title: String, content: String, format: DocumentFormat) -> Self {
        let content_hash = hash_content(content.as_bytes());
        let size_bytes = content.len();
        Self {
            document_id: generate_id("doc"),
            title,
            content,
            format,
            metadata: HashMap::new(),
            content_hash,
            size_bytes,
            created_at: chrono::Utc::now(),
        }
    }

    pub fn with_metadata<K: Into<String>, V: Into<String>>(mut self, key: K, value: V) -> Self {
        self.metadata.insert(key.into(), value.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParseResult {
    pub document: Document,
    pub parse_time_ms: u64,
    pub warnings: Vec<String>,
}

#[async_trait]
pub trait DocumentParser: Send + Sync {
    async fn parse(&self, data: &[u8], format: DocumentFormat) -> Result<ParseResult>;
    
    fn supported_formats(&self) -> Vec<DocumentFormat>;
}

pub struct GenericParser;

impl GenericParser {
    pub fn new() -> Self {
        Self
    }

    fn parse_pdf(&self, data: &[u8]) -> Result<String> {
        if data.starts_with(b"%PDF-") {
            Ok(String::from_utf8_lossy(data)
                .chars()
                .filter(|c| c.is_ascii_graphic() || c.is_whitespace())
                .collect())
        } else {
            Ok(String::from_utf8_lossy(data).into_owned())
        }
    }

    fn parse_html(&self, data: &[u8]) -> Result<String> {
        let content = String::from_utf8_lossy(data);
        let re = regex::Regex::new(r"<[^>]*>").unwrap();
        Ok(re.replace_all(&content, " ").to_string())
    }

    fn parse_json(&self, data: &[u8]) -> Result<String> {
        let parsed: serde_json::Value = serde_json::from_slice(data)?;
        Ok(Self::flatten_json(&parsed))
    }

    fn flatten_json(value: &serde_json::Value) -> String {
        match value {
            serde_json::Value::Object(obj) => {
                obj.iter()
                    .map(|(k, v)| format!("{}: {}", k, Self::flatten_json(v)))
                    .collect::<Vec<_>>()
                    .join("\n")
            }
            serde_json::Value::Array(arr) => {
                arr.iter()
                    .map(Self::flatten_json)
                    .collect::<Vec<_>>()
                    .join("\n")
            }
            serde_json::Value::String(s) => s.clone(),
            serde_json::Value::Number(n) => n.to_string(),
            serde_json::Value::Bool(b) => b.to_string(),
            serde_json::Value::Null => String::new(),
        }
    }

    fn parse_csv(&self, data: &[u8]) -> Result<String> {
        let content = String::from_utf8_lossy(data);
        Ok(content.lines().collect::<Vec<_>>().join("\n"))
    }
}

#[async_trait]
impl DocumentParser for GenericParser {
    async fn parse(&self, data: &[u8], format: DocumentFormat) -> Result<ParseResult> {
        let start = std::time::Instant::now();
        let mut warnings = Vec::new();

        let content = match format {
            DocumentFormat::Pdf => self.parse_pdf(data)?,
            DocumentFormat::Html => self.parse_html(data)?,
            DocumentFormat::Json => self.parse_json(data)?,
            DocumentFormat::Csv => self.parse_csv(data)?,
            DocumentFormat::Unknown => {
                warnings.push("Unknown format, using raw text extraction".to_string());
                String::from_utf8_lossy(data).into_owned()
            }
            _ => String::from_utf8_lossy(data).into_owned(),
        };

        let title = content
            .lines()
            .next()
            .unwrap_or("Untitled")
            .trim()
            .to_string();

        let document = Document::new(title, content, format);
        let parse_time_ms = start.elapsed().as_millis() as u64;

        Ok(ParseResult {
            document,
            parse_time_ms,
            warnings,
        })
    }

    fn supported_formats(&self) -> Vec<DocumentFormat> {
        vec![
            DocumentFormat::Pdf,
            DocumentFormat::Docx,
            DocumentFormat::Txt,
            DocumentFormat::Md,
            DocumentFormat::Html,
            DocumentFormat::Json,
            DocumentFormat::Csv,
            DocumentFormat::Xlsx,
            DocumentFormat::Pptx,
            DocumentFormat::Unknown,
        ]
    }
}

impl Default for GenericParser {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_parse_txt() {
        let parser = GenericParser::new();
        let data = b"Hello World\nThis is a test document.";
        let result = parser.parse(data, DocumentFormat::Txt).await.unwrap();
        
        assert_eq!(result.document.title, "Hello World");
        assert!(result.document.content.contains("test document"));
        assert_eq!(result.document.format, DocumentFormat::Txt);
    }

    #[tokio::test]
    async fn test_parse_html() {
        let parser = GenericParser::new();
        let data = b"<html><body><h1>Title</h1><p>Content</p></body></html>";
        let result = parser.parse(data, DocumentFormat::Html).await.unwrap();
        
        assert!(!result.document.content.contains("<html>"));
        assert!(result.document.content.contains("Title"));
        assert!(result.document.content.contains("Content"));
    }

    #[tokio::test]
    async fn test_parse_json() {
        let parser = GenericParser::new();
        let data = br#"{"name": "test", "value": 123, "items": [1,2,3]}"#;
        let result = parser.parse(data, DocumentFormat::Json).await.unwrap();
        
        assert!(result.document.content.contains("name: test"));
        assert!(result.document.content.contains("value: 123"));
    }

    #[test]
    fn test_format_detection() {
        assert_eq!(DocumentFormat::from_extension("pdf"), DocumentFormat::Pdf);
        assert_eq!(DocumentFormat::from_extension("unknown"), DocumentFormat::Unknown);
        assert_eq!(DocumentFormat::from_mime("text/plain"), DocumentFormat::Txt);
    }

    #[test]
    fn test_document_creation() {
        let doc = Document::new(
            "Test".to_string(),
            "Content".to_string(),
            DocumentFormat::Txt
        ).with_metadata("source", "test");
        
        assert!(doc.document_id.starts_with("doc_"));
        assert_eq!(doc.title, "Test");
        assert_eq!(doc.metadata.get("source"), Some(&"test".to_string()));
        assert_eq!(doc.content_hash.len(), 64);
    }
}
