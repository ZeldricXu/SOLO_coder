use egui::Color32;
use pulldown_cmark::{html, Parser};
use std::fs::File;
use std::io::Write;
use std::path::Path;

use crate::theme::Theme;
use crate::export::trait_::{ExportFormat, ExportProgress, ExportResult};

fn color_to_css(color: Color32) -> String {
    format!("rgba({}, {}, {}, {})", color.r(), color.g(), color.b(), color.a() as f32 / 255.0)
}

pub fn generate_css(theme: &Theme) -> String {
    let syntax_css = r#"
        .hljs { display: block; overflow-x: auto; padding: 0.5em; }
        .hljs-comment, .hljs-quote { color: #6a737d; }
        .hljs-keyword, .hljs-selector-tag, .hljs-meta-keyword { color: #d73a49; }
        .hljs-function, .hljs-name, .hljs-section, .hljs-selector-id { color: #6f42c1; }
        .hljs-string, .hljs-attr, .hljs-regexp, .hljs-variable, .hljs-template-variable { color: #032f62; }
        .hljs-number, .hljs-literal { color: #005cc5; }
        .hljs-title, .hljs-class .hljs-title { color: #6f42c1; }
        .hljs-tag, .hljs-attribute, .hljs-built_in { color: #22863a; }
        .hljs-emphasis { font-style: italic; }
        .hljs-strong { font-weight: bold; }
    "#;

    format!(
        r#"
        body {{
            background-color: {};
            color: {};
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: {}px;
            line-height: 1.6;
            margin: 0 auto;
            max-width: 800px;
            padding: 40px 20px;
        }}
        h1, h2, h3, h4, h5, h6 {{
            color: {};
            font-weight: 600;
            line-height: 1.3;
            margin-top: 1.5em;
            margin-bottom: 0.5em;
        }}
        h1 {{ font-size: {}px; }}
        h2 {{ font-size: {}px; }}
        h3 {{ font-size: {}px; }}
        h4 {{ font-size: {}px; }}
        h5 {{ font-size: {}px; }}
        h6 {{ font-size: {}px; }}
        p {{ margin: 1em 0; }}
        a {{
            color: {};
            text-decoration: none;
        }}
        a:hover {{ text-decoration: underline; }}
        code {{
            background-color: {};
            color: {};
            font-family: {}, monospace;
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 0.9em;
        }}
        pre {{
            background-color: {};
            color: {};
            padding: 16px;
            border-radius: 8px;
            overflow-x: auto;
        }}
        pre code {{
            background: none;
            padding: 0;
        }}
        blockquote {{
            border-left: 4px solid {};
            margin: 1em 0;
            padding-left: 1em;
            color: {};
            opacity: 0.8;
        }}
        table {{
            border-collapse: collapse;
            width: 100%;
            margin: 1em 0;
        }}
        th, td {{
            border: 1px solid {};
            padding: 8px 12px;
            text-align: left;
        }}
        th {{ background-color: {}; }}
        ul, ol {{ padding-left: 2em; margin: 1em 0; }}
        li {{ margin: 0.5em 0; }}
        hr {{
            border: none;
            border-top: 1px solid {};
            margin: 2em 0;
        }}
        img {{ max-width: 100%; height: auto; }}
        {}
        "#,
        color_to_css(theme.bg_color),
        color_to_css(theme.text_color),
        theme.text_font_size,
        color_to_css(theme.heading_color),
        theme.heading_sizes[0],
        theme.heading_sizes[1],
        theme.heading_sizes[2],
        theme.heading_sizes[3],
        theme.heading_sizes[4],
        theme.heading_sizes[5],
        color_to_css(theme.link_color),
        color_to_css(theme.code_bg),
        color_to_css(theme.code_text),
        theme.code_font_family,
        color_to_css(theme.code_bg),
        color_to_css(theme.code_text),
        color_to_css(theme.blockquote_border),
        color_to_css(theme.text_color),
        color_to_css(theme.border_color),
        color_to_css(theme.table_header_bg),
        color_to_css(theme.border_color),
        syntax_css
    )
}

pub fn markdown_to_html(content: &str) -> String {
    let parser = Parser::new(content);
    let mut html_output = String::new();
    html::push_html(&mut html_output, parser);
    html_output
}

pub struct HtmlExporter {
    theme: Theme,
}

impl HtmlExporter {
    pub fn new(theme: &Theme) -> Self {
        Self { theme: theme.clone() }
    }

    pub fn export_content(&self, content: &str, output_path: &Path) -> ExportResult<()> {
        let body_html = markdown_to_html(content);
        let css = generate_css(&self.theme);

        let full_html = format!(
            r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Exported Note</title>
    <style>
        {}
    </style>
</head>
<body>
{}
</body>
</html>"#,
            css, body_html
        );

        let mut file = File::create(output_path)?;
        file.write_all(full_html.as_bytes())?;

        Ok(())
    }
}

impl ExportFormat for HtmlExporter {
    fn name(&self) -> &'static str {
        "HTML"
    }

    fn extension(&self) -> &'static str {
        "html"
    }

    fn export(
        &self,
        content: &str,
        output_path: &Path,
        progress_callback: Option<&dyn Fn(ExportProgress)>,
    ) -> ExportResult<()> {
        if let Some(cb) = progress_callback {
            cb(0.2);
        }
        let result = self.export_content(content, output_path);
        if let Some(cb) = progress_callback {
            cb(1.0);
        }
        result
    }

    fn estimate_steps(&self, content: &str) -> usize {
        let lines = content.lines().count();
        (lines / 100).max(1)
    }
}
