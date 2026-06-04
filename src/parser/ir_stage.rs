use super::{MarkdownEvent, ir, DocumentIR};

pub struct IRStage;

impl IRStage {
    pub fn new() -> Self {
        IRStage
    }

    pub fn convert(&self, events: &[MarkdownEvent]) -> DocumentIR {
        ir::ir_from_events(events)
    }
}
