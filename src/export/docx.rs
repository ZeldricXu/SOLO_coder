use docx_rs::*;
use pulldown_cmark::{Event, Parser, Tag, TagEnd};
use std::path::Path;

use crate::export::trait_::{ExportFormat, ExportProgress, ExportResult, ExportError};

pub struct DocxExporter;

impl DocxExporter {
    pub fn new() -> Self {
        Self
    }

    pub fn export_content(&self, content: &str, output_path: &Path) -> ExportResult<()> {
        let parser = Parser::new(content);

        let mut doc = Docx::new();
        let mut paragraphs: Vec<Paragraph> = Vec::new();
        let mut current_paragraph = Paragraph::new();
        let mut current_runs: Vec<Run> = Vec::new();
        let mut in_list = false;
        let mut in_table = false;
        let mut table_rows: Vec<TableRow> = Vec::new();
        let mut table_cells: Vec<TableCell> = Vec::new();
        let mut in_code_block = false;
        let mut code_content = String::new();
        let mut is_bold = false;
        let mut is_italic = false;

        for event in parser {
            match event {
                Event::Start(tag) => match tag {
                    Tag::Heading { .. } => {
                        if !current_runs.is_empty() {
                            for run in current_runs.drain(..) {
                                current_paragraph = current_paragraph.add_run(run);
                            }
                            paragraphs.push(current_paragraph);
                            current_paragraph = Paragraph::new();
                        }
                    }
                    Tag::Paragraph => {
                        if !current_runs.is_empty() {
                            for run in current_runs.drain(..) {
                                current_paragraph = current_paragraph.add_run(run);
                            }
                            paragraphs.push(current_paragraph);
                            current_paragraph = Paragraph::new();
                        }
                    }
                    Tag::Emphasis => {
                        is_italic = true;
                    }
                    Tag::Strong => {
                        is_bold = true;
                    }
                    Tag::CodeBlock(_) => {
                        in_code_block = true;
                        code_content = String::new();
                    }
                    Tag::List(_) => {
                        in_list = true;
                    }
                    Tag::Item => {
                        if !current_runs.is_empty() {
                            for run in current_runs.drain(..) {
                                current_paragraph = current_paragraph.add_run(run);
                            }
                            paragraphs.push(current_paragraph);
                            current_paragraph = Paragraph::new();
                        }
                    }
                    Tag::Table(_) => {
                        in_table = true;
                        table_rows = Vec::new();
                    }
                    Tag::TableHead => {}
                    Tag::TableRow => {
                        table_cells = Vec::new();
                    }
                    Tag::TableCell => {}
                    Tag::Link { .. } => {}
                    Tag::Image { .. } => {}
                    Tag::FootnoteDefinition(_) => {}
                    Tag::MetadataBlock(_) => {}
                    Tag::HtmlBlock => {}
                    Tag::Strikethrough => {}
                    _ => {}
                },
                Event::End(tag) => match tag {
                    TagEnd::Heading(level) => {
                        let heading_style = match level {
                            pulldown_cmark::HeadingLevel::H1 => "Heading1",
                            pulldown_cmark::HeadingLevel::H2 => "Heading2",
                            pulldown_cmark::HeadingLevel::H3 => "Heading3",
                            pulldown_cmark::HeadingLevel::H4 => "Heading4",
                            pulldown_cmark::HeadingLevel::H5 => "Heading5",
                            pulldown_cmark::HeadingLevel::H6 => "Heading6",
                        };
                        for run in current_runs.drain(..) {
                            current_paragraph = current_paragraph.add_run(run);
                        }
                        current_paragraph = current_paragraph.style(heading_style);
                        paragraphs.push(current_paragraph);
                        current_paragraph = Paragraph::new();
                    }
                    TagEnd::Paragraph => {
                        for run in current_runs.drain(..) {
                            current_paragraph = current_paragraph.add_run(run);
                        }
                        paragraphs.push(current_paragraph);
                        current_paragraph = Paragraph::new();
                    }
                    TagEnd::Emphasis => {
                        is_italic = false;
                    }
                    TagEnd::Strong => {
                        is_bold = false;
                    }
                    TagEnd::CodeBlock => {
                        in_code_block = false;
                        let code_run = Run::new()
                            .add_text(&code_content)
                            .fonts(RunFonts::new().ascii("Courier New"))
                            .size(20);
                        current_paragraph = current_paragraph.add_run(code_run);
                        paragraphs.push(current_paragraph);
                        current_paragraph = Paragraph::new();
                    }
                    TagEnd::List(_) => {
                        in_list = false;
                    }
                    TagEnd::Item => {}
                    TagEnd::Table => {
                        let table = Table::new(std::mem::take(&mut table_rows));
                        doc = doc.add_table(table);
                        in_table = false;
                    }
                    TagEnd::TableHead => {}
                    TagEnd::TableRow => {
                        table_rows.push(TableRow::new(table_cells.clone()));
                    }
                    TagEnd::TableCell => {
                        for run in current_runs.drain(..) {
                            current_paragraph = current_paragraph.add_run(run);
                        }
                        table_cells.push(TableCell::new().add_paragraph(current_paragraph));
                        current_paragraph = Paragraph::new();
                    }
                    TagEnd::Link => {}
                    TagEnd::Image => {}
                    TagEnd::FootnoteDefinition => {}
                    TagEnd::MetadataBlock(_) => {}
                    TagEnd::HtmlBlock => {}
                    TagEnd::Strikethrough => {}
                    _ => {}
                },
                Event::Text(text) => {
                    if in_code_block {
                        code_content.push_str(&text);
                    } else {
                        let mut run = Run::new().add_text(text.to_string());
                        if is_bold {
                            run = run.bold();
                        }
                        if is_italic {
                            run = run.italic();
                        }
                        current_runs.push(run);
                    }
                }
                Event::Code(code) => {
                    let run = Run::new()
                        .add_text(code.to_string())
                        .fonts(RunFonts::new().ascii("Courier New"));
                    current_runs.push(run);
                }
                Event::SoftBreak => {
                    current_runs.push(Run::new().add_text(" "));
                }
                Event::HardBreak => {
                    current_runs.push(Run::new().add_break(BreakType::TextWrapping));
                }
                Event::Rule => {
                    for run in current_runs.drain(..) {
                        current_paragraph = current_paragraph.add_run(run);
                    }
                    paragraphs.push(current_paragraph);
                    current_paragraph = Paragraph::new();
                }
                Event::FootnoteReference(_) => {}
                Event::Html(_) => {}
                Event::InlineHtml(_) => {}
                Event::InlineMath(_) => {}
                Event::DisplayMath(_) => {}
                Event::TaskListMarker(_) => {}
            }
        }

        for run in current_runs.drain(..) {
            current_paragraph = current_paragraph.add_run(run);
        }
        paragraphs.push(current_paragraph);

        for para in paragraphs {
            doc = doc.add_paragraph(para);
        }

        let file = std::fs::File::create(output_path)?;
        doc.build().pack(std::io::BufWriter::new(file)).map_err(|e| ExportError::ZipError(e.to_string()))?;

        Ok(())
    }
}

impl Default for DocxExporter {
    fn default() -> Self {
        Self::new()
    }
}

impl ExportFormat for DocxExporter {
    fn name(&self) -> &'static str {
        "DOCX"
    }

    fn extension(&self) -> &'static str {
        "docx"
    }

    fn export(
        &self,
        content: &str,
        output_path: &Path,
        progress_callback: Option<&dyn Fn(ExportProgress)>,
    ) -> ExportResult<()> {
        if let Some(cb) = progress_callback {
            cb(0.0);
        }
        let result = self.export_content(content, output_path);
        if let Some(cb) = progress_callback {
            cb(1.0);
        }
        result
    }

    fn estimate_steps(&self, content: &str) -> usize {
        let lines = content.lines().count();
        (lines / 30).max(3)
    }
}
