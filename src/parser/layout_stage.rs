use crate::theme::Theme;
use crate::parser::ir::{DocumentIR, BlockIR, InlineIR, TableCell};

#[derive(Debug, Clone)]
pub struct LayoutInstruction {
    pub kind: LayoutKind,
    pub indent: f32,
    pub spacing: f32,
}

#[derive(Debug, Clone)]
pub enum LayoutKind {
    Heading {
        level: u8,
        content: LayoutContent,
    },
    Paragraph {
        content: LayoutContent,
    },
    CodeBlock {
        lang: String,
        content: String,
        height: f32,
    },
    ListItem {
        ordered: bool,
        number: Option<usize>,
        content: LayoutContent,
    },
    BlockQuote {
        content: LayoutContent,
    },
    Table {
        headers: Vec<LayoutContent>,
        rows: Vec<Vec<LayoutContent>>,
        column_widths: Vec<f32>,
    },
    HorizontalRule,
    MathBlock {
        content: String,
    },
}

#[derive(Debug, Clone)]
pub enum LayoutContent {
    PlainText(String),
    RichText(Vec<LayoutFragment>),
    Nested(Vec<LayoutInstruction>),
}

#[derive(Debug, Clone)]
pub struct LayoutFragment {
    pub text: String,
    pub fragment_kind: FragmentKind,
}

#[derive(Debug, Clone)]
pub enum FragmentKind {
    Normal,
    Bold,
    Italic,
    Code,
    Link { url: String },
    Strikethrough,
    WikiLink { target: String },
    MathInline { content: String },
    TaskList { checked: bool },
}

pub struct LayoutStage {
    theme: Theme,
}

impl LayoutStage {
    pub fn new(theme: Theme) -> Self {
        Self { theme }
    }

    pub fn layout(&self, document: &DocumentIR) -> Vec<LayoutInstruction> {
        let mut instructions = Vec::new();
        for block in &document.blocks {
            instructions.extend(self.layout_block(block, 0.0));
        }
        instructions
    }

    fn layout_block(&self, block: &BlockIR, indent: f32) -> Vec<LayoutInstruction> {
        let mut instructions = Vec::new();
        let spacing = 8.0;

        match block {
            BlockIR::Heading { level, content } => {
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::Heading {
                        level: *level,
                        content: self.layout_inline(content),
                    },
                    indent,
                    spacing,
                });
            }
            BlockIR::Paragraph(content) => {
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::Paragraph {
                        content: self.layout_inline(content),
                    },
                    indent,
                    spacing,
                });
            }
            BlockIR::CodeBlock { lang, content } => {
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::CodeBlock {
                        lang: lang.clone(),
                        content: content.clone(),
                        height: self.estimate_code_block_height(content),
                    },
                    indent,
                    spacing,
                });
            }
            BlockIR::List { ordered, items } => {
                for (i, item) in items.iter().enumerate() {
                    let number = if *ordered { Some(i + 1) } else { None };
                    let mut content_instr = Vec::new();
                    for block in &item.content {
                        content_instr.extend(self.layout_block(block, indent + 20.0));
                    }
                    instructions.push(LayoutInstruction {
                        kind: LayoutKind::ListItem {
                            ordered: *ordered,
                            number,
                            content: LayoutContent::Nested(content_instr),
                        },
                        indent,
                        spacing: 4.0,
                    });
                }
            }
            BlockIR::BlockQuote(blocks) => {
                let mut content_instr = Vec::new();
                for block in blocks {
                    content_instr.extend(self.layout_block(block, indent + 20.0));
                }
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::BlockQuote {
                        content: LayoutContent::Nested(content_instr),
                    },
                    indent,
                    spacing,
                });
            }
            BlockIR::Table { headers, rows } => {
                let column_widths = self.calculate_column_widths(headers, rows);
                let layout_headers: Vec<LayoutContent> = headers
                    .iter()
                    .map(|h| self.layout_inline(&h.content))
                    .collect();
                let layout_rows: Vec<Vec<LayoutContent>> = rows
                    .iter()
                    .map(|row| {
                        row.iter()
                            .map(|c| self.layout_inline(&c.content))
                            .collect()
                    })
                    .collect();
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::Table {
                        headers: layout_headers,
                        rows: layout_rows,
                        column_widths,
                    },
                    indent,
                    spacing,
                });
            }
            BlockIR::HorizontalRule => {
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::HorizontalRule,
                    indent,
                    spacing,
                });
            }
            BlockIR::MathBlock(content) => {
                instructions.push(LayoutInstruction {
                    kind: LayoutKind::MathBlock {
                        content: content.clone(),
                    },
                    indent,
                    spacing,
                });
            }
        }

        instructions
    }

    fn layout_inline(&self, inline: &[InlineIR]) -> LayoutContent {
        let mut fragments = Vec::new();
        self.collect_fragments(inline, FragmentKind::Normal, &mut fragments);
        
        if fragments.is_empty() {
            LayoutContent::PlainText(String::new())
        } else if fragments.len() == 1 {
            if let FragmentKind::Normal = fragments[0].fragment_kind {
                LayoutContent::PlainText(fragments[0].text.clone())
            } else {
                LayoutContent::RichText(fragments)
            }
        } else {
            LayoutContent::RichText(fragments)
        }
    }

    fn collect_fragments(&self, inlines: &[InlineIR], current_kind: FragmentKind, fragments: &mut Vec<LayoutFragment>) {
        for inline in inlines {
            match inline {
                InlineIR::Text(text) => {
                    fragments.push(LayoutFragment {
                        text: text.clone(),
                        fragment_kind: current_kind.clone(),
                    });
                }
                InlineIR::Bold(children) => {
                    self.collect_fragments(children, FragmentKind::Bold, fragments);
                }
                InlineIR::Italic(children) => {
                    self.collect_fragments(children, FragmentKind::Italic, fragments);
                }
                InlineIR::Code(code) => {
                    fragments.push(LayoutFragment {
                        text: code.clone(),
                        fragment_kind: FragmentKind::Code,
                    });
                }
                InlineIR::Link { text, url } => {
                    let mut link_fragments = Vec::new();
                    self.collect_fragments(text, FragmentKind::Link { url: url.clone() }, &mut link_fragments);
                    fragments.extend(link_fragments);
                }
                InlineIR::Image { alt, url } => {
                    fragments.push(LayoutFragment {
                        text: alt.clone(),
                        fragment_kind: FragmentKind::Link { url: url.clone() },
                    });
                }
                InlineIR::Strikethrough(children) => {
                    self.collect_fragments(children, FragmentKind::Strikethrough, fragments);
                }
                InlineIR::TaskList { checked, content } => {
                    let mut task_fragments = Vec::new();
                    self.collect_fragments(content, FragmentKind::TaskList { checked: *checked }, &mut task_fragments);
                    fragments.extend(task_fragments);
                }
                InlineIR::MathInline(math) => {
                    fragments.push(LayoutFragment {
                        text: math.clone(),
                        fragment_kind: FragmentKind::MathInline { content: math.clone() },
                    });
                }
                InlineIR::WikiLink(target) => {
                    fragments.push(LayoutFragment {
                        text: target.clone(),
                        fragment_kind: FragmentKind::WikiLink { target: target.clone() },
                    });
                }
            }
        }
    }

    fn estimate_code_block_height(&self, content: &str) -> f32 {
        let line_count = content.lines().count().max(1);
        let line_height = self.theme.text_font_size * 1.3;
        (line_count as f32) * line_height + 16.0
    }

    fn calculate_column_widths(&self, headers: &[TableCell], rows: &[Vec<TableCell>]) -> Vec<f32> {
        let num_columns = headers.len();
        let mut widths: Vec<f32> = vec![0.0; num_columns];

        for (i, header) in headers.iter().enumerate() {
            let len = self.inline_content_length(&header.content);
            widths[i] = f32::max(widths[i], len as f32 * self.theme.text_font_size * 0.6);
        }

        for row in rows {
            for (i, cell) in row.iter().enumerate() {
                if i < num_columns {
                    let len = self.inline_content_length(&cell.content);
                    widths[i] = f32::max(widths[i], len as f32 * self.theme.text_font_size * 0.6);
                }
            }
        }

        for width in widths.iter_mut() {
            *width = f32::clamp(*width, 40.0, 300.0);
        }

        widths
    }

    fn inline_content_length(&self, inlines: &[InlineIR]) -> usize {
        let mut len = 0;
        for inline in inlines {
            match inline {
                InlineIR::Text(text) => len += text.len(),
                InlineIR::Bold(children) => len += self.inline_content_length(children),
                InlineIR::Italic(children) => len += self.inline_content_length(children),
                InlineIR::Code(code) => len += code.len(),
                InlineIR::Link { text, .. } => len += self.inline_content_length(text),
                InlineIR::Image { alt, .. } => len += alt.len(),
                InlineIR::Strikethrough(children) => len += self.inline_content_length(children),
                InlineIR::TaskList { content, .. } => len += self.inline_content_length(content),
                InlineIR::MathInline(math) => len += math.len(),
                InlineIR::WikiLink(target) => len += target.len(),
            }
        }
        len.max(1)
    }
}
