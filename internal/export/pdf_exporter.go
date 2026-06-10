package export

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
)

type PDFExporter struct {
	cfg    *config.Config
	parser *markdown.MarkdownParser
}

type PDFPageSize struct {
	Width  float64
	Height float64
	Unit   string
}

var pageSizes = map[string]PDFPageSize{
	"A4":     {Width: 210, Height: 297, Unit: "mm"},
	"Letter": {Width: 216, Height: 279, Unit: "mm"},
	"Legal":  {Width: 216, Height: 356, Unit: "mm"},
	"A3":     {Width: 297, Height: 420, Unit: "mm"},
	"A5":     {Width: 148, Height: 210, Unit: "mm"},
}

func (e *PDFExporter) Export(notes []*models.Note, opts ExportOptions) error {
	if len(notes) == 1 {
		return e.exportSingle(notes[0], opts)
	}
	return e.exportMultiple(notes, opts)
}

func (e *PDFExporter) exportSingle(note *models.Note, opts ExportOptions) error {
	htmlExporter := &HTMLExporter{cfg: e.cfg, parser: e.parser}
	content, err := e.loadNoteContent(note)
	if err != nil {
		return err
	}

	result, err := e.parser.Parse(content, note.Path)
	if err != nil {
		return err
	}

	css := e.pdfCSS(opts)
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css += "\n" + string(customCSS)
		}
	}

	variables := e.buildVariables(note, opts)

	var tocHTML string
	if opts.IncludeTOC {
		toc := htmlExporter.extractTOC(result.HTML)
		tocHTML = htmlExporter.renderTOC(toc)
	}

	htmlContent := e.wrapPDFHTML(note.Title, result.HTML, css, tocHTML, variables, opts)
	htmlContent = applyVariables(htmlContent, variables)

	tmpDir, err := os.MkdirTemp("", "pdf-export-*")
	if err != nil {
		return fmt.Errorf("create temp dir failed: %w", err)
	}
	defer os.RemoveAll(tmpDir)

	htmlPath := filepath.Join(tmpDir, "input.html")
	if err := os.WriteFile(htmlPath, []byte(htmlContent), 0644); err != nil {
		return err
	}

	return e.convertHTMLToPDF(htmlPath, opts.OutputPath, opts)
}

func (e *PDFExporter) exportMultiple(notes []*models.Note, opts ExportOptions) error {
	htmlExporter := &HTMLExporter{cfg: e.cfg, parser: e.parser}

	tmpDir, err := os.MkdirTemp("", "pdf-export-*")
	if err != nil {
		return fmt.Errorf("create temp dir failed: %w", err)
	}
	defer os.RemoveAll(tmpDir)

	assetDir := filepath.Join(tmpDir, "assets")
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

	var allContent strings.Builder
	var allTitles []string

	for i, note := range notes {
		content, err := e.loadNoteContent(note)
		if err != nil {
			return err
		}

		result, err := e.parser.Parse(content, note.Path)
		if err != nil {
			return err
		}

		html := htmlExporter.convertWikiLinks(result.HTML, note.Path, noteMap)
		html = htmlExporter.convertImagePaths(html, note.Path, "assets")

		if i > 0 {
			allContent.WriteString(`<div class="page-break"></div>`)
		}

		allContent.WriteString(fmt.Sprintf(`<section class="note-section" data-note="%s">`, note.Title))
		allContent.WriteString(html)
		allContent.WriteString(`</section>`)

		allTitles = append(allTitles, note.Title)
	}

	css := e.pdfCSS(opts)
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			css += "\n" + string(customCSS)
		}
	}

	title := "笔记导出"
	if len(notes) == 1 {
		title = notes[0].Title
	} else if len(notes) > 1 {
		title = fmt.Sprintf("%s 等 %d 篇笔记", notes[0].Title, len(notes))
	}

	variables := map[string]string{
		"Title": title,
		"Count": fmt.Sprintf("%d", len(notes)),
	}
	for k, v := range opts.Variables {
		variables[k] = v
	}

	var tocHTML string
	if opts.IncludeTOC {
		tocHTML = e.renderNoteListTOC(allTitles)
	}

	htmlContent := e.wrapPDFHTML(title, allContent.String(), css, tocHTML, variables, opts)
	htmlContent = applyVariables(htmlContent, variables)

	htmlPath := filepath.Join(tmpDir, "input.html")
	if err := os.WriteFile(htmlPath, []byte(htmlContent), 0644); err != nil {
		return err
	}

	return e.convertHTMLToPDF(htmlPath, opts.OutputPath, opts)
}

func (e *PDFExporter) renderNoteListTOC(titles []string) string {
	var sb strings.Builder
	sb.WriteString(`<nav class="toc">`)
	sb.WriteString(`<h1 class="toc-main-title">目录</h1>`)
	sb.WriteString(`<ul class="toc-list">`)

	for i, title := range titles {
		sb.WriteString(fmt.Sprintf(
			`<li class="toc-item"><span class="toc-number">%d.</span><a href="#note-%d">%s</a></li>`,
			i+1, i+1, title,
		))
	}

	sb.WriteString(`</ul>`)
	sb.WriteString(`</nav>`)
	sb.WriteString(`<div class="page-break"></div>`)

	return sb.String()
}

func (e *PDFExporter) loadNoteContent(note *models.Note) (string, error) {
	fullPath := filepath.Join(e.cfg.VaultPath, note.Path)
	content, err := os.ReadFile(fullPath)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (e *PDFExporter) buildVariables(note *models.Note, opts ExportOptions) map[string]string {
	variables := map[string]string{
		"Title":     note.Title,
		"NotePath":  note.Path,
		"CreatedAt": note.CreatedAt.Format("2006-01-02"),
		"UpdatedAt": note.UpdatedAt.Format("2006-01-02"),
		"WordCount": fmt.Sprintf("%d", note.WordCount),
	}
	for k, v := range opts.Variables {
		variables[k] = v
	}
	return variables
}

func (e *PDFExporter) wrapPDFHTML(title, content, css, toc string, variables map[string]string, opts ExportOptions) string {
	pageSize := e.getPageSize(opts.PDFPageSize)
	margin := e.getMargin(opts.PDFMargin)

	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>%s</title>
    <style>
        @page {
            size: %s;
            margin: %s;
            @top-center {
                content: "%s";
                font-size: 10px;
                color: #666;
            }
            @bottom-center {
                content: counter(page) " / " counter(pages);
                font-size: 10px;
                color: #666;
            }
        }
%s
    </style>
</head>
<body>
    <div class="pdf-container">
        %s
        <div class="pdf-content">
%s
        </div>
    </div>
</body>
</html>`, title, pageSize, margin, title, css, toc, content)
}

func (e *PDFExporter) getPageSize(size string) string {
	if size == "" {
		return "A4"
	}
	if _, ok := pageSizes[size]; ok {
		return size
	}
	return "A4"
}

func (e *PDFExporter) getMargin(margin string) string {
	if margin == "" {
		return "20mm"
	}
	return margin
}

func (e *PDFExporter) convertHTMLToPDF(htmlPath, pdfPath string, opts ExportOptions) error {
	tools := []string{
		"wkhtmltopdf",
		"weasyprint",
		"chromium",
		"google-chrome",
		"google-chrome-stable",
	}

	var cmd *exec.Cmd
	var toolFound bool

	for _, tool := range tools {
		if _, err := exec.LookPath(tool); err == nil {
			toolFound = true
			switch tool {
			case "wkhtmltopdf":
				cmd = exec.Command(tool, "--page-size", e.getPageSize(opts.PDFPageSize),
					"--margin-top", "20mm", "--margin-bottom", "20mm",
					"--margin-left", "20mm", "--margin-right", "20mm",
					"--encoding", "UTF-8", htmlPath, pdfPath)
			case "weasyprint":
				cmd = exec.Command(tool, "-e", "utf-8", htmlPath, pdfPath)
			case "chromium", "google-chrome", "google-chrome-stable":
				cmd = exec.Command(tool, "--headless", "--disable-gpu",
					"--no-pdf-header-footer",
					fmt.Sprintf("--print-to-pdf=%s", pdfPath),
					htmlPath)
			}
			break
		}
	}

	if !toolFound {
		return e.fallbackExport(htmlPath, pdfPath, opts)
	}

	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func (e *PDFExporter) fallbackExport(htmlPath, pdfPath string, opts ExportOptions) error {
	htmlContent, err := os.ReadFile(htmlPath)
	if err != nil {
		return err
	}

	if runtime.GOOS == "darwin" {
		if _, err := exec.LookPath("textutil"); err == nil {
			tmpRtf := filepath.Join(filepath.Dir(htmlPath), "temp.rtf")
			cmd := exec.Command("textutil", "-convert", "rtf", "-input", "html",
				"-output", tmpRtf, htmlPath)
			if err := cmd.Run(); err == nil {
				return fmt.Errorf("PDF conversion requires wkhtmltopdf, weasyprint, or Chrome/Chromium. HTML file saved at: %s", htmlPath)
			}
		}
	}

	outputHTML := strings.TrimSuffix(pdfPath, filepath.Ext(pdfPath)) + ".html"
	if err := os.WriteFile(outputHTML, htmlContent, 0644); err != nil {
		return err
	}

	return fmt.Errorf("no PDF converter found (wkhtmltopdf, weasyprint, or Chrome/Chromium). HTML output saved to: %s", outputHTML)
}

func (e *PDFExporter) pdfCSS(opts ExportOptions) string {
	_ = e.getPageSize(opts.PDFPageSize)

	return `
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    font-size: 12pt;
    line-height: 1.6;
    color: #333;
    background: #fff;
}

.pdf-container {
    max-width: 100%;
    margin: 0 auto;
}

.pdf-content {
    padding: 0;
}

.note-section {
    page-break-inside: avoid;
}

h1, h2, h3, h4, h5, h6 {
    margin-top: 24pt;
    margin-bottom: 12pt;
    font-weight: 600;
    line-height: 1.3;
    page-break-after: avoid;
}

h1 {
    font-size: 24pt;
    border-bottom: 2pt solid #333;
    padding-bottom: 6pt;
}

h2 {
    font-size: 18pt;
    border-bottom: 1pt solid #ccc;
    padding-bottom: 4pt;
}

h3 {
    font-size: 14pt;
}

h4 {
    font-size: 12pt;
}

p {
    margin-bottom: 12pt;
    orphans: 3;
    widows: 3;
}

ul, ol {
    margin-bottom: 12pt;
    padding-left: 2em;
}

li {
    margin-bottom: 4pt;
}

blockquote {
    padding: 8pt 12pt;
    color: #666;
    border-left: 3pt solid #ccc;
    margin-bottom: 12pt;
    font-style: italic;
}

code {
    padding: 2pt 6pt;
    font-size: 90%;
    background-color: #f5f5f5;
    border-radius: 3pt;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace;
}

pre {
    padding: 12pt;
    overflow: auto;
    font-size: 10pt;
    line-height: 1.4;
    background-color: #f5f5f5;
    border-radius: 4pt;
    margin-bottom: 12pt;
    page-break-inside: avoid;
    white-space: pre-wrap;
    word-wrap: break-word;
}

pre code {
    padding: 0;
    background-color: transparent;
}

table {
    border-collapse: collapse;
    width: 100%;
    margin-bottom: 12pt;
    page-break-inside: avoid;
}

table th,
table td {
    padding: 6pt 10pt;
    border: 1pt solid #ddd;
}

table th {
    font-weight: 600;
    background-color: #f5f5f5;
}

table tr:nth-child(even) {
    background-color: #fafafa;
}

img {
    max-width: 100%;
    height: auto;
    page-break-inside: avoid;
}

a {
    color: #0366d6;
    text-decoration: none;
}

.wiki-link {
    color: #0366d6;
}

.wiki-link.broken {
    color: #d73a49;
    text-decoration: underline;
    text-decoration-style: dotted;
}

.page-break {
    page-break-before: always;
}

.toc {
    background-color: #f9f9f9;
    padding: 20pt;
    border-radius: 6pt;
    margin-bottom: 20pt;
    border: 1pt solid #e0e0e0;
    page-break-after: always;
}

.toc-main-title {
    font-size: 18pt;
    text-align: center;
    margin-bottom: 16pt;
    color: #333;
}

.toc-title {
    font-size: 14pt;
    font-weight: 600;
    margin-bottom: 10pt;
    color: #333;
}

.toc-list {
    list-style: none;
    padding: 0;
    margin: 0;
}

.toc-item {
    margin-bottom: 6pt;
}

.toc-item a {
    color: #333;
    text-decoration: none;
}

.toc-number {
    display: inline-block;
    width: 24pt;
    color: #666;
}

.toc-level-1 {
    font-weight: 500;
    padding-left: 0;
}

.toc-level-2 {
    padding-left: 20pt;
}

.toc-level-3 {
    padding-left: 40pt;
}

.toc-level-4 {
    padding-left: 60pt;
}

@page :first {
    @top-center {
        content: "";
    }
}

@media print {
    .page-break {
        page-break-before: always;
    }

    .note-section {
        page-break-inside: auto;
    }

    h1, h2, h3, h4, h5, h6 {
        page-break-after: avoid;
    }

    pre, table, img {
        page-break-inside: avoid;
    }
}
`
}
