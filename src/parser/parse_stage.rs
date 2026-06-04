use super::MarkdownEvent;
use super::parse_markdown;

pub struct ParseStage {
    content: String,
}

impl ParseStage {
    pub fn new(content: &str) -> Self {
        ParseStage {
            content: content.to_string(),
        }
    }

    pub fn parse(&self) -> Vec<MarkdownEvent> {
        parse_markdown(&self.content)
    }
}
