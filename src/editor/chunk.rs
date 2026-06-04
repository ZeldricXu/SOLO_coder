#[derive(Debug, Clone)]
pub struct Chunk {
    pub content: String,
    pub start_pos: usize,
    pub end_pos: usize,
    pub is_visible: bool,
}

#[derive(Debug, Clone)]
pub struct DocumentChunker {
    pub chunks: Vec<Chunk>,
    pub chunk_size: usize,
}

impl DocumentChunker {
    pub fn new(content: &str, chunk_size: usize) -> Self {
        let mut chunks = Vec::new();
        let mut start = 0;
        let chars: Vec<char> = content.chars().collect();

        while start < chars.len() {
            let end = (start + chunk_size).min(chars.len());
            let chunk_content: String = chars[start..end].iter().collect();
            chunks.push(Chunk {
                content: chunk_content,
                start_pos: start,
                end_pos: end,
                is_visible: true,
            });
            start = end;
        }

        if chunks.is_empty() {
            chunks.push(Chunk {
                content: String::new(),
                start_pos: 0,
                end_pos: 0,
                is_visible: true,
            });
        }

        Self {
            chunks,
            chunk_size,
        }
    }

    pub fn update_viewport(&mut self, scroll_offset: f32, viewport_height: f32, line_height: f32) {
        let chars_per_line = 80.0;
        let lines_above = scroll_offset / line_height.max(1.0);
        let visible_lines = viewport_height / line_height.max(1.0);
        let start_char = (lines_above * chars_per_line) as usize;
        let end_char = ((lines_above + visible_lines) * chars_per_line) as usize;

        for chunk in &mut self.chunks {
            chunk.is_visible = chunk.end_pos > start_char && chunk.start_pos < end_char;
        }
    }

    pub fn get_visible_chunks(&self) -> Vec<&Chunk> {
        self.chunks
            .iter()
            .filter(|c| c.is_visible)
            .collect()
    }

    pub fn insert_char(&mut self, pos: usize, c: char) {
        let mut global_pos = 0;
        let mut found_idx = None;

        for (chunk_idx, chunk) in self.chunks.iter().enumerate() {
            let chunk_len = chunk.content.chars().count();
            if pos <= global_pos + chunk_len {
                found_idx = Some((chunk_idx, global_pos));
                break;
            }
            global_pos += chunk_len;
        }

        if let Some((chunk_idx, g_pos)) = found_idx {
            let local_pos = pos - g_pos;
            {
                let chunk = &mut self.chunks[chunk_idx];
                let chars: Vec<char> = chunk.content.chars().collect();
                let mut new_chars = chars[..local_pos].to_vec();
                new_chars.push(c);
                new_chars.extend_from_slice(&chars[local_pos..]);
                chunk.content = new_chars.into_iter().collect();
                chunk.end_pos += 1;
            }

            let needs_split = self.chunks[chunk_idx].content.chars().count() > self.chunk_size;
            if needs_split {
                self.split_chunk(chunk_idx);
            }

            for idx in (chunk_idx + 1)..self.chunks.len() {
                self.chunks[idx].start_pos += 1;
                self.chunks[idx].end_pos += 1;
            }
            return;
        }

        if let Some(last) = self.chunks.last_mut() {
            last.content.push(c);
            last.end_pos += 1;
        }
    }

    pub fn delete_char(&mut self, pos: usize) {
        let mut global_pos = 0;
        let mut found_idx = None;

        for (chunk_idx, chunk) in self.chunks.iter().enumerate() {
            let chunk_len = chunk.content.chars().count();
            if pos < global_pos + chunk_len {
                found_idx = Some((chunk_idx, global_pos));
                break;
            }
            global_pos += chunk_len;
        }

        if let Some((chunk_idx, g_pos)) = found_idx {
            let local_pos = pos - g_pos;
            {
                let chunk = &mut self.chunks[chunk_idx];
                let chars: Vec<char> = chunk.content.chars().collect();
                if local_pos < chars.len() {
                    let mut new_chars = chars[..local_pos].to_vec();
                    new_chars.extend_from_slice(&chars[local_pos + 1..]);
                    chunk.content = new_chars.into_iter().collect();
                    chunk.end_pos -= 1;
                }
            }

            if chunk_idx < self.chunks.len() - 1 {
                let next_len = self.chunks[chunk_idx + 1].content.chars().count();
                let current_len = self.chunks[chunk_idx].content.chars().count();
                let can_merge = current_len + next_len <= self.chunk_size;

                if can_merge {
                    let next_content = self.chunks[chunk_idx + 1].content.clone();
                    self.chunks[chunk_idx].content.push_str(&next_content);
                    self.chunks[chunk_idx].end_pos += next_content.chars().count();
                    self.chunks.remove(chunk_idx + 1);
                }
            }

            for idx in (chunk_idx + 1)..self.chunks.len() {
                self.chunks[idx].start_pos -= 1;
                self.chunks[idx].end_pos -= 1;
            }
        }
    }

    fn split_chunk(&mut self, idx: usize) {
        if idx >= self.chunks.len() {
            return;
        }

        let (right, chunk_end) = {
            let chunk = &mut self.chunks[idx];
            let chars: Vec<char> = chunk.content.chars().collect();
            if chars.len() <= self.chunk_size {
                return;
            }
            let mid = self.chunk_size;
            let left: String = chars[..mid].iter().collect();
            let right: String = chars[mid..].iter().collect();
            chunk.content = left;
            let new_end = chunk.start_pos + mid;
            chunk.end_pos = new_end;
            (right, new_end)
        };

        let right_len = right.chars().count();

        let new_chunk = Chunk {
            content: right,
            start_pos: chunk_end,
            end_pos: chunk_end + right_len,
            is_visible: self.chunks[idx].is_visible,
        };

        self.chunks.insert(idx + 1, new_chunk);

        for i in (idx + 2)..self.chunks.len() {
            self.chunks[i].start_pos += right_len;
            self.chunks[i].end_pos += right_len;
        }
    }
}
