use std::env;
use std::fs::File;
use std::io::Write;
use std::path::Path;

use crate::theme::Theme;
use crate::export::trait_::{ExportFormat, ExportProgress, ExportResult};
use crate::export::html::{generate_css, markdown_to_html};

pub struct PdfExporter {
    theme: Theme,
}

impl PdfExporter {
    pub fn new(theme: &Theme) -> Self {
        Self { theme: theme.clone() }
    }

    pub fn export_content(&self, content: &str, output_path: &Path) -> ExportResult<()> {
        let body_html = markdown_to_html(content);
        let css = generate_css(&self.theme);

        let print_css = r#"
        @media print {
            body {
                margin: 0;
                padding: 20mm;
                max-width: none;
            }
            pre, code {
                white-space: pre-wrap;
                word-wrap: break-word;
            }
            a {
                text-decoration: underline;
            }
            @page {
                margin: 20mm;
            }
        }
    "#;

        let full_html = format!(
            r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Note - Print to PDF</title>
    <style>
        {}
        {}
    </style>
</head>
<body>
{}
<script>
window.onload = function() {{
    window.print();
}};
</script>
</body>
</html>"#,
            css, print_css, body_html
        );

        let temp_dir = env::temp_dir();
        let temp_filename = format!("note_export_{}.html", 12345);
        let temp_path = temp_dir.join(temp_filename);

        let mut file = File::create(&temp_path)?;
        file.write_all(full_html.as_bytes())?;

        if webbrowser::open(&format!("file://{}", temp_path.display())).is_err() {
            eprintln!(
                "Could not open browser automatically. Please open this file in your browser and print to PDF: {}",
                temp_path.display()
            );
        }

        if let Some(parent) = output_path.parent() {
            std::fs::create_dir_all(parent).ok();
        }

        std::fs::copy(&temp_path, output_path)?;

        Ok(())
    }
}

impl ExportFormat for PdfExporter {
    fn name(&self) -> &'static str {
        "PDF"
    }

    fn extension(&self) -> &'static str {
        "pdf"
    }

    fn export(
        &self,
        content: &str,
        output_path: &Path,
        progress_callback: Option<&dyn Fn(ExportProgress)>,
    ) -> ExportResult<()> {
        if let Some(cb) = progress_callback {
            cb(0.1);
        }
        let result = self.export_content(content, output_path);
        if let Some(cb) = progress_callback {
            cb(1.0);
        }
        result
    }

    fn estimate_steps(&self, content: &str) -> usize {
        let lines = content.lines().count();
        (lines / 50).max(2)
    }
}
