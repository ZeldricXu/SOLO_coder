use anyhow::{Context, Result};
use arboard::Clipboard;

use super::html::markdown_to_html;

pub fn copy_as_markdown(content: &str) -> Result<()> {
    let mut clipboard = Clipboard::new().context("Failed to access clipboard")?;
    clipboard.set_text(content.to_string())
        .context("Failed to set clipboard text")?;
    Ok(())
}

pub fn copy_rich_text(content: &str) -> Result<()> {
    let mut clipboard = Clipboard::new().context("Failed to access clipboard")?;
    let html_content = markdown_to_html(content);
    clipboard.set_text(content.to_string())
        .context("Failed to set clipboard text")?;
    if let Err(e) = clipboard.set_html(html_content, Some(content.to_string())) {
        eprintln!("Warning: Could not set HTML clipboard format: {}", e);
    }
    Ok(())
}
