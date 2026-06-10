package export

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
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

type TOCEntry struct {
	Level   int
	Title   string
	ID      string
	Children []*TOCEntry
}

func (e *HTMLExporter) Export(notes []*models.Note, opts ExportOptions) error {
	if len(notes) == 1 {
		return e.exportSingle(notes[0], opts)
	}
	return e.exportMultiple(notes, opts)
}

func (e *HTMLExporter) exportSingle(note *models.Note, opts ExportOptions) error {
	content, err := e.loadNoteContent(note)
	if err != nil {
		return err
	}

	result, err := e.parser.Parse(content, note.Path)
	if err != nil {
		return err
	}

	css := e.defaultCSS()
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css = string(customCSS)
		}
	}

	variables := e.buildVariables(note, opts)

	var tocHTML string
	if opts.IncludeTOC {
		toc := e.extractTOC(result.HTML)
		tocHTML = e.renderTOC(toc)
	}

	htmlContent := e.wrapHTML(note.Title, result.HTML, css, tocHTML, variables)
	htmlContent = applyVariables(htmlContent, variables)

	return os.WriteFile(opts.OutputPath, []byte(htmlContent), 0644)
}

func (e *HTMLExporter) exportMultiple(notes []*models.Note, opts ExportOptions) error {
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

	noteMap := make(map[string]*models.Note)
	for _, note := range notes {
		noteMap[note.Path] = note
	}

	for _, note := range notes {
		variables := e.buildVariables(note, opts)

		content, err := e.loadNoteContent(note)
		if err != nil {
			return err
		}

		result, err := e.parser.Parse(content, note.Path)
		if err != nil {
			return err
		}

		html := e.convertWikiLinks(result.HTML, note.Path, noteMap)
		html = e.convertImagePaths(html, note.Path, "assets")

		var tocHTML string
		if opts.IncludeTOC {
			toc := e.extractTOC(html)
			tocHTML = e.renderTOC(toc)
		}

		fullHTML := e.wrapHTML(note.Title, html, css, tocHTML, variables)
		fullHTML = applyVariables(fullHTML, variables)

		outPath := filepath.Join(opts.OutputPath, noteToHTMLPath(note.Path))
		if err := os.WriteFile(outPath, []byte(fullHTML), 0644); err != nil {
			return err
		}
	}

	return e.generateIndexHTML(notes, opts)
}

func (e *HTMLExporter) generateIndexHTML(notes []*models.Note, opts ExportOptions) error {
	css := e.defaultCSS()
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css = string(customCSS)
		}
	}

	sort.Slice(notes, func(i, j int) bool {
		return notes[i].Title < notes[j].Title
	})

	var indexContent strings.Builder
	indexContent.WriteString(`<div class="note-list">`)
	indexContent.WriteString(`<h1>笔记索引</h1>`)
	indexContent.WriteString(`<ul class="index-list">`)

	for _, note := range notes {
		htmlPath := noteToHTMLPath(note.Path)
		indexContent.WriteString(fmt.Sprintf(`<li><a href="%s">%s</a></li>`, htmlPath, note.Title))
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

func (e *HTMLExporter) loadNoteContent(note *models.Note) (string, error) {
	fullPath := filepath.Join(e.cfg.VaultPath, note.Path)
	content, err := os.ReadFile(fullPath)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (e *HTMLExporter) buildVariables(note *models.Note, opts ExportOptions) map[string]string {
	variables := map[string]string{
		"Title":      note.Title,
		"NotePath":   note.Path,
		"CreatedAt":  note.CreatedAt.Format("2006-01-02"),
		"UpdatedAt":  note.UpdatedAt.Format("2006-01-02"),
		"WordCount":  fmt.Sprintf("%d", note.WordCount),
	}
	for k, v := range opts.Variables {
		variables[k] = v
	}
	return variables
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

func (e *HTMLExporter) extractTOC(html string) []*TOCEntry {
	re := regexp.MustCompile(`<h([1-6])\s+id="([^"]*)"[^>]*>([^<]+)</h[1-6]>`)
	matches := re.FindAllStringSubmatch(html, -1)

	var entries []*TOCEntry
	for _, match := range matches {
		level := int(match[1][0] - '0')
		id := match[2]
		title := match[3]

		entries = append(entries, &TOCEntry{
			Level: level,
			Title: title,
			ID:    id,
		})
	}

	return entries
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

func (e *HTMLExporter) convertWikiLinks(html string, sourcePath string, noteMap map[string]*models.Note) string {
	re := regexp.MustCompile(`<a[^>]*class="wiki-link"[^>]*data-target="([^"]*)"[^>]*>([^<]*)</a>`)

	return re.ReplaceAllStringFunc(html, func(match string) string {
		matches := re.FindStringSubmatch(match)
		if len(matches) < 3 {
			return match
		}

		target := matches[1]
		display := matches[2]

		var targetPath string
		for path := range noteMap {
			base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
			if strings.EqualFold(base, target) || strings.EqualFold(strings.TrimSuffix(target, filepath.Ext(target)), base) {
				targetPath = path
				break
			}
		}

		if targetPath != "" {
			href := noteToHTMLPath(targetPath)
			return fmt.Sprintf(`<a href="%s" class="wiki-link">%s</a>`, href, display)
		}

		return fmt.Sprintf(`<span class="wiki-link broken">%s</span>`, display)
	})
}

func (e *HTMLExporter) convertImagePaths(html, notePath, assetDir string) string {
	re := regexp.MustCompile(`<img[^>]*src="([^"]*)"[^>]*>`)

	return re.ReplaceAllStringFunc(html, func(match string) string {
		matches := re.FindStringSubmatch(match)
		if len(matches) < 2 {
			return match
		}

		src := matches[1]
		if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") || strings.HasPrefix(src, "data:") {
			return match
		}

		noteDir := filepath.Dir(notePath)
		imgPath := filepath.Join(noteDir, src)
		imgPath = filepath.Clean(imgPath)

		newSrc := filepath.Join(assetDir, imgPath)
		newSrc = filepath.ToSlash(newSrc)

		return strings.Replace(match, src, newSrc, 1)
	})
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
