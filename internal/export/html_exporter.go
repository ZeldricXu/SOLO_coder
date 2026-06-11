package export

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
)

type HTMLExporter struct {
	cfg    *config.Config
	parser *markdown.MarkdownParser
}

func (e *HTMLExporter) Export(notes []*models.Note, opts ExportOptions) error {
	return fmt.Errorf("legacy Export() not supported, use Render(ctx) instead")
}

func (e *HTMLExporter) Render(ctx *ExportContext) error {
	if len(ctx.Notes) == 1 {
		return e.ExportSingle(ctx, ctx.Notes[0], ctx.Options)
	}
	return e.ExportMultiple(ctx, ctx.Options)
}

func (e *HTMLExporter) ExportSingle(ctx *ExportContext, pn *ProcessedNote, opts ExportOptions) error {
	css := e.defaultCSS()
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css = string(customCSS)
		}
	}

	variables := ctx.MergeVariables(pn.Variables)

	var tocHTML string
	if opts.IncludeTOC {
		tocEntries := toPointerEntries(pn.TOC)
		tocHTML = e.renderTOC(tocEntries)
	}

	htmlContent := e.wrapHTML(pn.Title, pn.HTMLContent, css, tocHTML, variables)
	htmlContent = applyVariables(htmlContent, variables)

	return os.WriteFile(opts.OutputPath, []byte(htmlContent), 0644)
}

func (e *HTMLExporter) ExportMultiple(ctx *ExportContext, opts ExportOptions) error {
	if err := os.MkdirAll(opts.OutputPath, 0755); err != nil {
		return err
	}

	css := e.defaultCSS()
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css = string(customCSS)
		}
	}

	assetDir := filepath.Join(opts.OutputPath, "assets")
	if err := os.MkdirAll(assetDir, 0755); err != nil {
		return err
	}
	if err := copyAssets(e.cfg.VaultPath, assetDir); err != nil {
		return err
	}

	for _, pn := range ctx.Notes {
		variables := ctx.MergeVariables(pn.Variables)

		html := ctx.ConvertWikiLinks(pn.Slug, pn.HTMLContent, "site")
		html = ctx.ConvertAssetPaths(html, "assets")

		var tocHTML string
		if opts.IncludeTOC {
			tocEntries := toPointerEntries(pn.TOC)
			tocHTML = e.renderTOC(tocEntries)
		}

		fullHTML := e.wrapHTML(pn.Title, html, css, tocHTML, variables)
		fullHTML = applyVariables(fullHTML, variables)

		outPath := filepath.Join(opts.OutputPath, noteToHTMLPath(pn.Note.Path))
		if err := os.WriteFile(outPath, []byte(fullHTML), 0644); err != nil {
			return err
		}
	}

	return e.generateIndexHTML(ctx, opts)
}

func (e *HTMLExporter) generateIndexHTML(ctx *ExportContext, opts ExportOptions) error {
	css := e.defaultCSS()
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css = string(customCSS)
		}
	}

	notes := make([]*ProcessedNote, len(ctx.Notes))
	copy(notes, ctx.Notes)
	sort.Slice(notes, func(i, j int) bool {
		return notes[i].Title < notes[j].Title
	})

	var indexContent strings.Builder
	indexContent.WriteString(`<div class="note-list">`)
	indexContent.WriteString(`<h1>笔记索引</h1>`)
	indexContent.WriteString(`<ul class="index-list">`)

	for _, pn := range notes {
		htmlPath := noteToHTMLPath(pn.Note.Path)
		indexContent.WriteString(fmt.Sprintf(`<li><a href="%s">%s</a></li>`, htmlPath, pn.Title))
	}

	indexContent.WriteString(`</ul>`)
	indexContent.WriteString(`</div>`)

	variables := map[string]string{
		"Title": "笔记索引",
	}
	for k, v := range opts.Variables {
		variables[k] = v
	}

	html := e.wrapHTML("笔记索引", indexContent.String(), css, "", variables)
	html = applyVariables(html, variables)

	indexPath := filepath.Join(opts.OutputPath, "index.html")
	return os.WriteFile(indexPath, []byte(html), 0644)
}

func toPointerEntries(entries []TOCEntry) []*TOCEntry {
	result := make([]*TOCEntry, len(entries))
	for i := range entries {
		result[i] = &entries[i]
	}
	return result
}

func (e *HTMLExporter) wrapHTML(title, content, css, toc string, variables map[string]string) string {
	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>%s</title>
    <style>
%s
    </style>
</head>
<body>
    <div class="container">
        %s
        <article class="markdown-body">
%s
        </article>
    </div>
</body>
</html>`, title, css, toc, content)
}

func (e *HTMLExporter) renderTOC(entries []*TOCEntry) string {
	if len(entries) == 0 {
		return ""
	}

	var sb strings.Builder
	sb.WriteString(`<nav class="toc">`)
	sb.WriteString(`<h2 class="toc-title">目录</h2>`)
	sb.WriteString(`<ul class="toc-list">`)

	for _, entry := range entries {
		indent := (entry.Level - 1) * 20
		sb.WriteString(fmt.Sprintf(
			`<li class="toc-item toc-level-%d" style="padding-left: %dpx;">
				<a href="#%s">%s</a>
			</li>`,
			entry.Level, indent, entry.ID, entry.Title,
		))
	}

	sb.WriteString(`</ul>`)
	sb.WriteString(`</nav>`)

	return sb.String()
}

func (e *HTMLExporter) defaultCSS() string {
	return `
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    line-height: 1.6;
    color: #333;
    background-color: #fafafa;
}

.container {
    max-width: 800px;
    margin: 0 auto;
    padding: 40px 20px;
    background-color: #fff;
    min-height: 100vh;
}

.markdown-body {
    font-size: 16px;
    line-height: 1.8;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4,
.markdown-body h5,
.markdown-body h6 {
    margin-top: 24px;
    margin-bottom: 16px;
    font-weight: 600;
    line-height: 1.25;
}

.markdown-body h1 {
    font-size: 2em;
    border-bottom: 1px solid #eaecef;
    padding-bottom: 10px;
}

.markdown-body h2 {
    font-size: 1.5em;
    border-bottom: 1px solid #eaecef;
    padding-bottom: 8px;
}

.markdown-body h3 {
    font-size: 1.25em;
}

.markdown-body h4 {
    font-size: 1em;
}

.markdown-body p {
    margin-bottom: 16px;
}

.markdown-body ul,
.markdown-body ol {
    margin-bottom: 16px;
    padding-left: 2em;
}

.markdown-body li {
    margin-bottom: 4px;
}

.markdown-body blockquote {
    padding: 0 1em;
    color: #6a737d;
    border-left: 4px solid #dfe2e5;
    margin-bottom: 16px;
}

.markdown-body code {
    padding: 2px 6px;
    font-size: 90%;
    background-color: #f6f8fa;
    border-radius: 3px;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
}

.markdown-body pre {
    padding: 16px;
    overflow: auto;
    font-size: 85%;
    line-height: 1.45;
    background-color: #f6f8fa;
    border-radius: 6px;
    margin-bottom: 16px;
}

.markdown-body pre code {
    padding: 0;
    background-color: transparent;
}

.markdown-body table {
    border-collapse: collapse;
    width: 100%;
    margin-bottom: 16px;
}

.markdown-body table th,
.markdown-body table td {
    padding: 6px 13px;
    border: 1px solid #dfe2e5;
}

.markdown-body table th {
    font-weight: 600;
    background-color: #f6f8fa;
}

.markdown-body table tr:nth-child(2n) {
    background-color: #f6f8fa;
}

.markdown-body img {
    max-width: 100%;
    height: auto;
    border-radius: 4px;
}

.markdown-body a {
    color: #0366d6;
    text-decoration: none;
}

.markdown-body a:hover {
    text-decoration: underline;
}

.markdown-body .wiki-link {
    color: #0366d6;
}

.markdown-body .wiki-link.broken {
    color: #d73a49;
    text-decoration: underline;
    text-decoration-style: dotted;
}

.toc {
    background-color: #f6f8fa;
    border-radius: 6px;
    padding: 16px 20px;
    margin-bottom: 32px;
    border-left: 4px solid #0366d6;
}

.toc-title {
    font-size: 1.1em;
    font-weight: 600;
    margin-bottom: 12px;
    color: #24292e;
}

.toc-list {
    list-style: none;
    padding: 0;
    margin: 0;
}

.toc-item {
    margin-bottom: 4px;
}

.toc-item a {
    color: #586069;
    text-decoration: none;
    font-size: 14px;
}

.toc-item a:hover {
    color: #0366d6;
    text-decoration: underline;
}

.toc-level-1 {
    font-weight: 500;
}

.note-list .index-list {
    list-style: none;
    padding: 0;
}

.note-list .index-list li {
    padding: 8px 0;
    border-bottom: 1px solid #eaecef;
}

.note-list .index-list li:last-child {
    border-bottom: none;
}

.note-list .index-list a {
    color: #0366d6;
    text-decoration: none;
    font-size: 16px;
}

.note-list .index-list a:hover {
    text-decoration: underline;
}

@media print {
    body {
        background-color: #fff;
    }
    .container {
        max-width: 100%;
        padding: 0;
    }
    .toc {
        page-break-after: always;
    }
}
`
}
