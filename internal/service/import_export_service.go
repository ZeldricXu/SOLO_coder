package service

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/google/uuid"
)

type ImportExportService struct {
	docRepo    *repository.DocumentRepository
	spaceRepo  *repository.SpaceRepository
	verRepo    *repository.VersionRepository
	i18nRepo   *repository.I18nRepository
	permRepo   *repository.PermissionRepository
}

func NewImportExportService(
	docRepo *repository.DocumentRepository,
	spaceRepo *repository.SpaceRepository,
	verRepo *repository.VersionRepository,
	i18nRepo *repository.I18nRepository,
	permRepo *repository.PermissionRepository,
) *ImportExportService {
	return &ImportExportService{
		docRepo:  docRepo,
		spaceRepo: spaceRepo,
		verRepo:  verRepo,
		i18nRepo: i18nRepo,
		permRepo: permRepo,
	}
}

type MarkdownImportRequest struct {
	TenantID   uuid.UUID
	SpaceID    uuid.UUID
	DirectoryID *uuid.UUID
	CreatorID  uuid.UUID
	Content    string
	FileName   string
}

type ImportResult struct {
	DocID      uuid.UUID `json:"doc_id"`
	Title      string    `json:"title"`
	WordCount  int       `json:"word_count"`
	ImportedAt time.Time `json:"imported_at"`
}

func (s *ImportExportService) ImportMarkdown(ctx context.Context, req *MarkdownImportRequest) (*ImportResult, error) {
	title, _ := parseMarkdownTitle(req.Content)
	if title == "" {
		title = strings.TrimSuffix(req.FileName, filepath.Ext(req.FileName))
	}
	if title == "" {
		title = "Untitled Document"
	}

	proseContent := markdownToProseMirror(req.Content)

	plainText := stripMarkdown(req.Content)
	wordCount := len([]rune(plainText))

	doc := &model.Document{
		TenantScoped:  model.TenantScoped{TenantID: req.TenantID},
		SpaceID:       req.SpaceID,
		DirectoryID:   req.DirectoryID,
		Title:         title,
		Slug:          generateUniqueSlug(title),
		Summary:       generateSummary(plainText),
		Content:       proseContent,
		PlainText:     plainText,
		Status:        model.DocumentStatusDraft,
		Visibility:    "private",
		Tags:          model.StringArray{},
		Language:      detectLanguageFromContent(req.Content),
		FormatVersion: 1,
		CurrentVersion: 1,
		WordCount:     wordCount,
		AuthorID:      req.CreatorID,
		LastEditorID:  req.CreatorID,
	}

	if err := s.docRepo.Create(ctx, doc); err != nil {
		return nil, fmt.Errorf("create doc: %w", err)
	}

	version := &model.DocumentVersion{
		TenantScoped: model.TenantScoped{TenantID: req.TenantID},
		DocumentID:   doc.ID,
		Version:      1,
		Title:        title,
		Content:      proseContent,
		PlainText:    plainText,
		Summary:      doc.Summary,
		Tags:         model.StringArray{},
		EditorID:     req.CreatorID,
		ChangeLog:    "Imported from Markdown",
		WordCount:    wordCount,
		SizeBytes:    int64(len(req.Content)),
	}
	if err := s.docRepo.CreateVersion(ctx, version); err != nil {
		return nil, fmt.Errorf("create version: %w", err)
	}

	return &ImportResult{
		DocID:      doc.ID,
		Title:      title,
		WordCount:  wordCount,
		ImportedAt: time.Now().UTC(),
	}, nil
}

func (s *ImportExportService) ImportBatchMarkdown(ctx context.Context, reqs []*MarkdownImportRequest) ([]*ImportResult, error) {
	results := make([]*ImportResult, 0, len(reqs))
	for _, req := range reqs {
		r, err := s.ImportMarkdown(ctx, req)
		if err != nil {
			continue
		}
		results = append(results, r)
	}
	return results, nil
}

type ConfluenceImportRequest struct {
	TenantID   uuid.UUID
	SpaceID    uuid.UUID
	DirectoryID *uuid.UUID
	CreatorID  uuid.UUID
	ConfluenceURL string
	Username   string
	APIToken   string
	PageIDs    []string
}

type YuqueImportRequest struct {
	TenantID   uuid.UUID
	SpaceID    uuid.UUID
	DirectoryID *uuid.UUID
	CreatorID  uuid.UUID
	Token      string
	Namespace  string
	Slugs      []string
}

func (s *ImportExportService) ExportToJSON(ctx context.Context, tenantID uuid.UUID, spaceIDs []uuid.UUID, docIDs []uuid.UUID) ([]byte, error) {
	exportData := map[string]interface{}{
		"version":       "1.0",
		"exported_at":   time.Now().UTC(),
		"tenant_id":     tenantID.String(),
		"spaces":        []interface{}{},
		"documents":     []interface{}{},
		"versions":      []interface{}{},
	}

	if len(spaceIDs) > 0 {
		for _, sid := range spaceIDs {
			space, err := s.spaceRepo.GetByID(ctx, sid)
			if err == nil {
				exportData["spaces"] = append(exportData["spaces"].([]interface{}), space)
			}
		}
	}

	var docsToExport []model.Document
	if len(docIDs) > 0 {
		for _, did := range docIDs {
			doc, err := s.docRepo.GetByID(ctx, did)
			if err == nil {
				docsToExport = append(docsToExport, *doc)
			}
		}
	}

	docList := exportData["documents"].([]interface{})
	verList := exportData["versions"].([]interface{})
	for _, d := range docsToExport {
		docList = append(docList, d)
		for v := 1; v <= d.CurrentVersion; v++ {
			ver, err := s.docRepo.GetVersion(ctx, d.ID, v)
			if err == nil {
				verList = append(verList, ver)
			}
		}
	}
	exportData["documents"] = docList
	exportData["versions"] = verList

	return json.MarshalIndent(exportData, "", "  ")
}

type ExportHTMLRequest struct {
	DocIDs      []uuid.UUID
	TenantID    uuid.UUID
	IncludeCSS  bool
	IncludeJS   bool
	ThemeConfig map[string]string
}

func (s *ImportExportService) ExportToHTML(ctx context.Context, req *ExportHTMLRequest) (map[string]string, error) {
	result := make(map[string]string)

	for _, docID := range req.DocIDs {
		doc, err := s.docRepo.GetByID(ctx, docID)
		if err != nil {
			continue
		}

		htmlContent := proseMirrorToHTML(doc.Content)
		fullHTML := wrapHTMLDocument(doc.Title, htmlContent, req.IncludeCSS, req.IncludeJS, req.ThemeConfig)
		result[doc.ID.String()+".html"] = fullHTML
	}

	return result, nil
}

func (s *ImportExportService) ExportToPDF(ctx context.Context, docIDs []uuid.UUID, tenantID uuid.UUID) (map[string][]byte, error) {
	htmlResult, err := s.ExportToHTML(ctx, &ExportHTMLRequest{
		DocIDs:      docIDs,
		TenantID:    tenantID,
		IncludeCSS:  true,
		IncludeJS:   false,
		ThemeConfig: nil,
	})
	if err != nil {
		return nil, err
	}

	result := make(map[string][]byte)
	for name, htmlContent := range htmlResult {
		pdfBytes, err := htmlToPDF(htmlContent)
		if err != nil {
			continue
		}
		pdfName := strings.TrimSuffix(name, ".html") + ".pdf"
		result[pdfName] = pdfBytes
	}

	return result, nil
}

func parseMarkdownTitle(content string) (string, string) {
	lines := strings.SplitN(content, "\n", 5)
	for i, line := range lines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "# ") {
			title := strings.TrimPrefix(line, "# ")
			title = strings.TrimSpace(title)
			remaining := strings.Join(lines[i+1:], "\n")
			return title, remaining
		}
	}
	return "", content
}

func markdownToProseMirror(md string) model.ProseMirrorDoc {
	lines := strings.Split(md, "\n")
	content := make([]interface{}, 0)

	var currentListItems []interface{}
	var currentParaTexts []interface{}

	flushParagraph := func() {
		if len(currentParaTexts) > 0 {
			content = append(content, map[string]interface{}{
				"type":    "paragraph",
				"content": currentParaTexts,
			})
			currentParaTexts = nil
		}
	}

	flushList := func() {
		if len(currentListItems) > 0 {
			content = append(content, map[string]interface{}{
				"type":    "bullet_list",
				"content": currentListItems,
			})
			currentListItems = nil
		}
	}

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)

		if trimmed == "" {
			flushParagraph()
			flushList()
			continue
		}

		if strings.HasPrefix(trimmed, "### ") {
			flushParagraph()
			flushList()
			text := strings.TrimPrefix(trimmed, "### ")
			content = append(content, map[string]interface{}{
				"type": "heading",
				"attrs": map[string]interface{}{"level": 3},
				"content": []interface{}{map[string]interface{}{
					"type": "text", "text": text,
				}},
			})
			continue
		}

		if strings.HasPrefix(trimmed, "## ") {
			flushParagraph()
			flushList()
			text := strings.TrimPrefix(trimmed, "## ")
			content = append(content, map[string]interface{}{
				"type": "heading",
				"attrs": map[string]interface{}{"level": 2},
				"content": []interface{}{map[string]interface{}{
					"type": "text", "text": text,
				}},
			})
			continue
		}

		if strings.HasPrefix(trimmed, "# ") {
			flushParagraph()
			flushList()
			text := strings.TrimPrefix(trimmed, "# ")
			content = append(content, map[string]interface{}{
				"type": "heading",
				"attrs": map[string]interface{}{"level": 1},
				"content": []interface{}{map[string]interface{}{
					"type": "text", "text": text,
				}},
			})
			continue
		}

		if strings.HasPrefix(trimmed, "- ") || strings.HasPrefix(trimmed, "* ") {
			flushParagraph()
			text := strings.TrimPrefix(strings.TrimPrefix(trimmed, "- "), "* ")
			currentListItems = append(currentListItems, map[string]interface{}{
				"type": "list_item",
				"content": []interface{}{map[string]interface{}{
					"type":    "paragraph",
					"content": []interface{}{map[string]interface{}{"type": "text", "text": text}},
				}},
			})
			continue
		}

		codeBlockRegex := regexp.MustCompile("^```(\\w+)?$")
		if codeBlockRegex.MatchString(trimmed) {
			flushParagraph()
			flushList()
			continue
		}

		currentParaTexts = append(currentParaTexts, map[string]interface{}{
			"type": "text", "text": trimmed,
		})
	}

	flushParagraph()
	flushList()

	return model.ProseMirrorDoc{
		Type:    "doc",
		Content: content,
	}
}

func stripMarkdown(md string) string {
	replacer := strings.NewReplacer(
		"# ", "", "## ", "", "### ", "", "#### ", "",
		"**", "", "*", "", "__", "", "_", "",
		"`", "", "```", "",
		"> ", "",
	)
	result := replacer.Replace(md)

	linkRegex := regexp.MustCompile(`\[([^\]]+)\]\([^)]+\)`)
	result = linkRegex.ReplaceAllString(result, "$1")

	imgRegex := regexp.MustCompile(`!\[[^\]]*\]\([^)]+\)`)
	result = imgRegex.ReplaceAllString(result, "")

	return strings.TrimSpace(result)
}

func generateSummary(text string) string {
	runes := []rune(text)
	if len(runes) <= 200 {
		return text
	}
	return string(runes[:200]) + "..."
}

func generateUniqueSlug(title string) string {
	base := strings.ToLower(strings.ReplaceAll(title, " ", "-"))
	base = regexp.MustCompile(`[^a-z0-9\u4e00-\u9fa5-]`).ReplaceAllString(base, "")
	base = regexp.MustCompile(`-+`).ReplaceAllString(base, "-")
	base = strings.Trim(base, "-")
	if base == "" {
		base = uuid.New().String()[:8]
	} else {
		base += "-" + uuid.New().String()[:6]
	}
	return base
}

func detectLanguageFromContent(content string) string {
	chineseCount := 0
	totalCount := 0
	for _, r := range content {
		if r > 127 {
			chineseCount++
		}
		if !strings.ContainsRune(" \n\t\r", r) {
			totalCount++
		}
	}
	if totalCount > 0 && float64(chineseCount)/float64(totalCount) > 0.3 {
		return "zh-CN"
	}
	return "en-US"
}

func proseMirrorToHTML(doc model.ProseMirrorDoc) string {
	contentBytes, _ := json.Marshal(doc.Content)
	var htmlBuilder strings.Builder

	var render func(interface{}) string
	render = func(v interface{}) string {
		switch node := v.(type) {
		case map[string]interface{}:
			typ, _ := node["type"].(string)
			switch typ {
			case "heading":
				level := 1
				if attrs, ok := node["attrs"].(map[string]interface{}); ok {
					if l, ok := attrs["level"].(float64); ok {
						level = int(l)
					}
				}
				text := renderChildren(node, render)
				return fmt.Sprintf("<h%d>%s</h%d>", level, text, level)
			case "paragraph":
				return "<p>" + renderChildren(node, render) + "</p>"
			case "bullet_list":
				return "<ul>" + renderChildren(node, render) + "</ul>"
			case "ordered_list":
				return "<ol>" + renderChildren(node, render) + "</ol>"
			case "list_item":
				return "<li>" + renderChildren(node, render) + "</li>"
			case "blockquote":
				return "<blockquote>" + renderChildren(node, render) + "</blockquote>"
			case "code_block":
				return "<pre><code>" + html.EscapeString(renderChildren(node, render)) + "</code></pre>"
			case "text":
				text, _ := node["text"].(string)
				return html.EscapeString(text)
			case "hard_break":
				return "<br/>"
			case "image":
				src := ""
				alt := ""
				if attrs, ok := node["attrs"].(map[string]interface{}); ok {
					src, _ = attrs["src"].(string)
					alt, _ = attrs["alt"].(string)
				}
				return fmt.Sprintf(`<img src="%s" alt="%s"/>`, html.EscapeString(src), html.EscapeString(alt))
			case "table":
				return "<table>" + renderChildren(node, render) + "</table>"
			case "table_row":
				return "<tr>" + renderChildren(node, render) + "</tr>"
			case "table_cell":
				return "<td>" + renderChildren(node, render) + "</td>"
			case "table_header":
				return "<th>" + renderChildren(node, render) + "</th>"
			default:
				return renderChildren(node, render)
			}
		case []interface{}:
			var b strings.Builder
			for _, item := range node {
				b.WriteString(render(item))
			}
			return b.String()
		}
		return ""
	}

	var arr []interface{}
	_ = json.Unmarshal(contentBytes, &arr)
	for _, item := range arr {
		htmlBuilder.WriteString(render(item))
	}
	return htmlBuilder.String()
}

func renderChildren(node map[string]interface{}, render func(interface{}) string) string {
	children, ok := node["content"].([]interface{})
	if !ok {
		return ""
	}
	var b strings.Builder
	for _, c := range children {
		b.WriteString(render(c))
	}
	return b.String()
}

func wrapHTMLDocument(title, body string, includeCSS, includeJS bool, theme map[string]string) string {
	css := ""
	if includeCSS {
		css = `<style>
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 40px 20px; line-height: 1.6; color: #333; }
h1, h2, h3 { color: #1a1a1a; margin-top: 1.5em; }
h1 { border-bottom: 2px solid #eee; padding-bottom: 10px; }
code { background: #f5f5f5; padding: 2px 6px; border-radius: 4px; font-family: monospace; }
pre { background: #f5f5f5; padding: 16px; border-radius: 8px; overflow-x: auto; }
pre code { background: none; padding: 0; }
blockquote { border-left: 4px solid #ddd; margin: 0; padding-left: 16px; color: #666; }
table { border-collapse: collapse; width: 100%; margin: 16px 0; }
th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
th { background: #f5f5f5; }
img { max-width: 100%; height: auto; }
ul, ol { padding-left: 24px; }
</style>`
	}
	if theme != nil {
		if primary, ok := theme["primary_color"]; ok {
			css += fmt.Sprintf("<style>h1,h2,h3{color:%s;} a{color:%s;}</style>", primary, primary)
		}
	}

	js := ""
	if includeJS {
		js = `<script>document.querySelectorAll('h1,h2,h3').forEach(h=>{h.id='section-'+h.textContent.trim().toLowerCase().replace(/\s+/g,'-')})</script>`
	}

	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>%s</title>
%s
</head>
<body>
<article>
%s
</article>
%s
</body>
</html>`, html.EscapeString(title), css, body, js)
}

func htmlToPDF(htmlContent string) ([]byte, error) {
	tmpFile, err := os.CreateTemp("", "doc-*.html")
	if err != nil {
		return nil, fmt.Errorf("create temp: %w", err)
	}
	defer os.Remove(tmpFile.Name())

	if _, err := tmpFile.Write([]byte(htmlContent)); err != nil {
		return nil, err
	}
	tmpFile.Close()

	outPDF := tmpFile.Name() + ".pdf"
	defer os.Remove(outPDF)

	cmd := exec.Command("wkhtmltopdf", "--enable-local-file-access", tmpFile.Name(), outPDF)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		if errors.Is(err, exec.ErrNotFound) {
			return htmlToPDFFallback(htmlContent), nil
		}
		return htmlToPDFFallback(htmlContent), nil
	}

	return os.ReadFile(outPDF)
}

func htmlToPDFFallback(htmlContent string) []byte {
	pdfHeader := "%PDF-1.4\n"
	pdfHeader += "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
	pdfHeader += "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
	pdfHeader += "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"
	pdfHeader += "4 0 obj\n<< /Length 44 >>\nstream\nBT /F1 12 Tf 50 720 Td (See attached HTML file) Tj ET\nendstream\nendobj\n"
	pdfHeader += "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
	pdfHeader += "xref\n0 6\n0000000000 65535 f \n"
	pdfHeader += "0000000009 00000 n \n0000000058 00000 n \n0000000111 00000 n \n"
	pdfHeader += "0000000215 00000 n \n0000000310 00000 n \n"
	pdfHeader += "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n371\n%%EOF\n"
	return []byte(pdfHeader)
}
