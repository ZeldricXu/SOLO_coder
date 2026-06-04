use super::MarkdownEvent;

#[derive(Debug, Clone, PartialEq)]
pub enum InlineIR {
    Text(String),
    Bold(Vec<InlineIR>),
    Italic(Vec<InlineIR>),
    Code(String),
    Link { text: Vec<InlineIR>, url: String },
    Image { alt: String, url: String },
    Strikethrough(Vec<InlineIR>),
    TaskList { checked: bool, content: Vec<InlineIR> },
    MathInline(String),
    WikiLink(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum BlockIR {
    Heading { level: u8, content: Vec<InlineIR> },
    Paragraph(Vec<InlineIR>),
    CodeBlock { lang: String, content: String },
    List { ordered: bool, items: Vec<ListItem> },
    BlockQuote(Vec<BlockIR>),
    Table { headers: Vec<TableCell>, rows: Vec<Vec<TableCell>> },
    HorizontalRule,
    MathBlock(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct ListItem {
    pub content: Vec<BlockIR>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TableCell {
    pub content: Vec<InlineIR>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DocumentIR {
    pub blocks: Vec<BlockIR>,
}

impl DocumentIR {
    pub fn count_blocks(&self) -> usize {
        self.blocks.len()
    }

    pub fn iter_inline_len(&self) -> usize {
        self.blocks.iter().map(|b| count_inlines_in_block(b)).sum()
    }
}

fn count_inlines_in_block(block: &BlockIR) -> usize {
    match block {
        BlockIR::Heading { content, .. } => count_inlines(content),
        BlockIR::Paragraph(content) => count_inlines(content),
        BlockIR::CodeBlock { .. } => 0,
        BlockIR::List { items, .. } => items.iter().map(|item| {
            item.content.iter().map(|b| count_inlines_in_block(b)).sum::<usize>()
        }).sum(),
        BlockIR::BlockQuote(blocks) => blocks.iter().map(|b| count_inlines_in_block(b)).sum(),
        BlockIR::Table { headers, rows } => {
            headers.iter().map(|h| count_inlines(&h.content)).sum::<usize>() +
            rows.iter().flatten().map(|c| count_inlines(&c.content)).sum::<usize>()
        },
        BlockIR::HorizontalRule => 0,
        BlockIR::MathBlock(_) => 0,
    }
}

fn count_inlines(inlines: &[InlineIR]) -> usize {
    inlines.iter().map(|i| match i {
        InlineIR::Text(_) => 1,
        InlineIR::Bold(children) => 1 + count_inlines(children),
        InlineIR::Italic(children) => 1 + count_inlines(children),
        InlineIR::Code(_) => 1,
        InlineIR::Link { text, .. } => 1 + count_inlines(text),
        InlineIR::Image { .. } => 1,
        InlineIR::Strikethrough(children) => 1 + count_inlines(children),
        InlineIR::TaskList { content, .. } => 1 + count_inlines(content),
        InlineIR::MathInline(_) => 1,
        InlineIR::WikiLink(_) => 1,
    }).sum()
}

pub fn ir_from_events(events: &[MarkdownEvent]) -> DocumentIR {
    let mut converter = IRConverter::new();
    converter.convert(events)
}

struct IRConverter {
    blocks: Vec<BlockIR>,
    block_stack: Vec<BlockStackEntry>,
    inline_stack: Vec<InlineStackEntry>,
    current_inlines: Vec<InlineIR>,
    in_table_head: bool,
    current_table_headers: Vec<TableCell>,
    current_table_rows: Vec<Vec<TableCell>>,
    current_table_row: Vec<TableCell>,
    current_cell_inlines: Vec<InlineIR>,
    in_table_cell: bool,
}

enum BlockStackEntry {
    Heading(u8, Vec<InlineIR>),
    Paragraph(Vec<InlineIR>),
    List(bool, Vec<ListItem>),
    ListItem(Vec<BlockIR>),
    BlockQuote(Vec<BlockIR>),
}

enum InlineStackEntry {
    Bold(Vec<InlineIR>),
    Italic(Vec<InlineIR>),
    Strikethrough(Vec<InlineIR>),
    TaskList(bool, Vec<InlineIR>),
}

impl IRConverter {
    fn new() -> Self {
        IRConverter {
            blocks: Vec::new(),
            block_stack: Vec::new(),
            inline_stack: Vec::new(),
            current_inlines: Vec::new(),
            in_table_head: false,
            current_table_headers: Vec::new(),
            current_table_rows: Vec::new(),
            current_table_row: Vec::new(),
            current_cell_inlines: Vec::new(),
            in_table_cell: false,
        }
    }

    fn convert(mut self, events: &[MarkdownEvent]) -> DocumentIR {
        for event in events {
            self.process_event(event);
        }
        DocumentIR { blocks: self.blocks }
    }

    fn process_event(&mut self, event: &MarkdownEvent) {
        match event {
            MarkdownEvent::Text(text) => {
                let inline = InlineIR::Text(text.clone());
                if self.in_table_cell {
                    self.current_cell_inlines.push(inline);
                } else {
                    self.current_inlines.push(inline);
                }
            },
            MarkdownEvent::BoldStart => {
                self.inline_stack.push(InlineStackEntry::Bold(std::mem::take(&mut self.current_inlines)));
            },
            MarkdownEvent::BoldEnd => {
                if let Some(InlineStackEntry::Bold(saved)) = self.inline_stack.pop() {
                    let content = std::mem::take(&mut self.current_inlines);
                    self.current_inlines = saved;
                    self.current_inlines.push(InlineIR::Bold(content));
                }
            },
            MarkdownEvent::ItalicStart => {
                self.inline_stack.push(InlineStackEntry::Italic(std::mem::take(&mut self.current_inlines)));
            },
            MarkdownEvent::ItalicEnd => {
                if let Some(InlineStackEntry::Italic(saved)) = self.inline_stack.pop() {
                    let content = std::mem::take(&mut self.current_inlines);
                    self.current_inlines = saved;
                    self.current_inlines.push(InlineIR::Italic(content));
                }
            },
            MarkdownEvent::Code(code) => {
                let inline = InlineIR::Code(code.clone());
                if self.in_table_cell {
                    self.current_cell_inlines.push(inline);
                } else {
                    self.current_inlines.push(inline);
                }
            },
            MarkdownEvent::Link { url, text } => {
                let link = InlineIR::Link {
                    text: vec![InlineIR::Text(text.clone())],
                    url: url.clone(),
                };
                if self.in_table_cell {
                    self.current_cell_inlines.push(link);
                } else {
                    self.current_inlines.push(link);
                }
            },
            MarkdownEvent::Image { url, alt } => {
                let image = InlineIR::Image { alt: alt.clone(), url: url.clone() };
                if self.in_table_cell {
                    self.current_cell_inlines.push(image);
                } else {
                    self.current_inlines.push(image);
                }
            },
            MarkdownEvent::Heading(level) => {
                self.block_stack.push(BlockStackEntry::Heading(*level, std::mem::take(&mut self.current_inlines)));
            },
            MarkdownEvent::HeadingEnd => {
                if let Some(BlockStackEntry::Heading(level, _saved)) = self.block_stack.pop() {
                    let content = std::mem::take(&mut self.current_inlines);
                    let block = BlockIR::Heading { level, content };
                    self.add_block(block);
                }
            },
            MarkdownEvent::ParagraphStart => {
                self.block_stack.push(BlockStackEntry::Paragraph(std::mem::take(&mut self.current_inlines)));
            },
            MarkdownEvent::ParagraphEnd => {
                if let Some(BlockStackEntry::Paragraph(_saved)) = self.block_stack.pop() {
                    let content = std::mem::take(&mut self.current_inlines);
                    if !content.is_empty() {
                        let block = BlockIR::Paragraph(content);
                        self.add_block(block);
                    }
                }
            },
            MarkdownEvent::ListStart(ordered) => {
                self.block_stack.push(BlockStackEntry::List(*ordered, Vec::new()));
            },
            MarkdownEvent::ListItem => {
                self.block_stack.push(BlockStackEntry::ListItem(Vec::new()));
            },
            MarkdownEvent::ListItemEnd => {
                if let Some(BlockStackEntry::ListItem(item_blocks)) = self.block_stack.pop() {
                    if let Some(BlockStackEntry::List(_, ref mut items)) = self.block_stack.last_mut() {
                        items.push(ListItem { content: item_blocks });
                    }
                }
            },
            MarkdownEvent::ListEnd => {
                if let Some(BlockStackEntry::List(ordered, items)) = self.block_stack.pop() {
                    let block = BlockIR::List { ordered, items };
                    self.add_block(block);
                }
            },
            MarkdownEvent::BlockQuoteStart => {
                self.block_stack.push(BlockStackEntry::BlockQuote(Vec::new()));
            },
            MarkdownEvent::BlockQuoteEnd => {
                if let Some(BlockStackEntry::BlockQuote(quote_blocks)) = self.block_stack.pop() {
                    let block = BlockIR::BlockQuote(quote_blocks);
                    self.add_block(block);
                }
            },
            MarkdownEvent::CodeBlock { lang, content } => {
                let block = BlockIR::CodeBlock { lang: lang.clone(), content: content.clone() };
                self.add_block(block);
            },
            MarkdownEvent::TableStart => {
                self.current_table_headers.clear();
                self.current_table_rows.clear();
                self.current_table_row.clear();
            },
            MarkdownEvent::TableHead => {
                self.in_table_head = true;
                self.current_table_row.clear();
            },
            MarkdownEvent::TableRow => {
                if !self.current_table_row.is_empty() {
                    if self.in_table_head {
                        self.current_table_headers = std::mem::take(&mut self.current_table_row);
                        self.in_table_head = false;
                    } else {
                        self.current_table_rows.push(std::mem::take(&mut self.current_table_row));
                    }
                }
                self.current_table_row.clear();
            },
            MarkdownEvent::TableCell => {
                if !self.current_cell_inlines.is_empty() || self.in_table_cell {
                    let cell = TableCell { content: std::mem::take(&mut self.current_cell_inlines) };
                    self.current_table_row.push(cell);
                }
                self.in_table_cell = true;
                self.current_cell_inlines.clear();
            },
            MarkdownEvent::TableEnd => {
                if !self.current_cell_inlines.is_empty() {
                    let cell = TableCell { content: std::mem::take(&mut self.current_cell_inlines) };
                    self.current_table_row.push(cell);
                }
                if !self.current_table_row.is_empty() {
                    if self.in_table_head {
                        self.current_table_headers = std::mem::take(&mut self.current_table_row);
                    } else {
                        self.current_table_rows.push(std::mem::take(&mut self.current_table_row));
                    }
                }
                let block = BlockIR::Table {
                    headers: std::mem::take(&mut self.current_table_headers),
                    rows: std::mem::take(&mut self.current_table_rows),
                };
                self.add_block(block);
                self.in_table_cell = false;
                self.in_table_head = false;
            },
            MarkdownEvent::HorizontalRule => {
                self.add_block(BlockIR::HorizontalRule);
            },
            MarkdownEvent::WikiLink(name) => {
                let inline = InlineIR::WikiLink(name.clone());
                if self.in_table_cell {
                    self.current_cell_inlines.push(inline);
                } else {
                    self.current_inlines.push(inline);
                }
            },
            MarkdownEvent::MathInline(math) => {
                let inline = InlineIR::MathInline(math.clone());
                if self.in_table_cell {
                    self.current_cell_inlines.push(inline);
                } else {
                    self.current_inlines.push(inline);
                }
            },
            MarkdownEvent::MathBlock(math) => {
                let block = BlockIR::MathBlock(math.clone());
                self.add_block(block);
            },
            MarkdownEvent::TaskList { checked } => {
                self.inline_stack.push(InlineStackEntry::TaskList(*checked, std::mem::take(&mut self.current_inlines)));
            },
        }
    }

    fn add_block(&mut self, block: BlockIR) {
        if let Some(BlockStackEntry::BlockQuote(ref mut blocks)) = self.block_stack.last_mut() {
            blocks.push(block);
        } else if let Some(BlockStackEntry::ListItem(ref mut blocks)) = self.block_stack.last_mut() {
            blocks.push(block);
        } else {
            self.blocks.push(block);
        }
    }
}
