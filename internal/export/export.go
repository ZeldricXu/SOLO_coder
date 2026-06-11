package export

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type ExportFormat string

const (
	FormatHTML ExportFormat = "html"
	FormatPDF  ExportFormat = "pdf"
	FormatSite ExportFormat = "site"
)

type ExportOptions struct {
	Format       ExportFormat
	OutputPath   string
	CSSPath      string
	IncludeTOC   bool
	Variables    map[string]string
	PDFPageSize  string
	PDFMargin    string
	Tags         []string
	Folders      []string
	Theme        string
}

type Exporter interface {
	Export(notes []*models.Note, opts ExportOptions) error
}

type ContextExporter interface {
	Render(ctx *ExportContext) error
}

type ExportManager struct {
	cfg       *config.Config
	db        *db.Database
	parser    *markdown.MarkdownParser
	pipeline  *ExportPipeline
	exporters map[ExportFormat]Exporter
}

func NewManager(cfg *config.Config, database *db.Database) *ExportManager {
	parser := markdown.NewParser(cfg)
	pipeline := NewExportPipeline(cfg, database, parser)
	mgr := &ExportManager{
		cfg:       cfg,
		db:        database,
		parser:    parser,
		pipeline:  pipeline,
		exporters: make(map[ExportFormat]Exporter),
	}

	mgr.exporters[FormatHTML] = &HTMLExporter{cfg: cfg, parser: parser}
	mgr.exporters[FormatPDF] = &PDFExporter{cfg: cfg, parser: parser}
	mgr.exporters[FormatSite] = &SiteExporter{cfg: cfg, parser: parser, db: database}

	return mgr
}

func (m *ExportManager) Export(opts ExportOptions) error {
	notes, err := m.selectNotes(opts)
	if err != nil {
		return fmt.Errorf("select notes failed: %w", err)
	}

	if len(notes) == 0 {
		return fmt.Errorf("no notes selected for export")
	}

	exporter, ok := m.exporters[opts.Format]
	if !ok {
		return fmt.Errorf("unsupported export format: %s", opts.Format)
	}

	if err := os.MkdirAll(filepath.Dir(opts.OutputPath), 0755); err != nil {
		return fmt.Errorf("create output directory failed: %w", err)
	}

	ctx, err := m.pipeline.BuildContext(notes, opts)
	if err != nil {
		return fmt.Errorf("build export context failed: %w", err)
	}

	if ctxExp, ok := exporter.(ContextExporter); ok {
		if err := ctxExp.Render(ctx); err != nil {
			return fmt.Errorf("export failed: %w", err)
		}
		return nil
	}

	if err := exporter.Export(notes, opts); err != nil {
		return fmt.Errorf("export failed: %w", err)
	}

	return nil
}

func (m *ExportManager) selectNotes(opts ExportOptions) ([]*models.Note, error) {
	allNotes, err := m.db.GetAllNotes()
	if err != nil {
		return nil, err
	}

	if len(opts.Tags) == 0 && len(opts.Folders) == 0 {
		return allNotes, nil
	}

	var filtered []*models.Note
	tagSet := make(map[string]bool)
	for _, t := range opts.Tags {
		tagSet[strings.ToLower(t)] = true
	}

	for _, note := range allNotes {
		if len(opts.Folders) > 0 && !m.matchFolders(note.Path, opts.Folders) {
			continue
		}

		if len(opts.Tags) > 0 {
			fullNote, err := m.db.GetNoteByPath(note.Path)
			if err != nil {
				continue
			}
			hasTag := false
			for _, tag := range fullNote.Tags {
				if tagSet[strings.ToLower(tag.Name)] {
					hasTag = true
					break
				}
			}
			if !hasTag {
				continue
			}
		}

		filtered = append(filtered, note)
	}

	return filtered, nil
}

func (m *ExportManager) matchFolders(notePath string, folders []string) bool {
	relPath, err := filepath.Rel(m.cfg.VaultPath, notePath)
	if err != nil {
		return false
	}

	for _, folder := range folders {
		folder = filepath.Clean(folder)
		if strings.HasPrefix(relPath, folder) {
			return true
		}
	}
	return false
}

func applyVariables(content string, variables map[string]string) string {
	result := content
	for key, value := range variables {
		placeholder := fmt.Sprintf("{{%s}}", key)
		result = strings.ReplaceAll(result, placeholder, value)
	}
	return result
}

func copyAssets(srcDir, dstDir string) error {
	return filepath.Walk(srcDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		ext := strings.ToLower(filepath.Ext(path))
		if isImageExt(ext) || isAttachmentExt(ext) {
			relPath, err := filepath.Rel(srcDir, path)
			if err != nil {
				return err
			}

			dstPath := filepath.Join(dstDir, relPath)
			if err := os.MkdirAll(filepath.Dir(dstPath), 0755); err != nil {
				return err
			}

			data, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			return os.WriteFile(dstPath, data, 0644)
		}
		return nil
	})
}

func isImageExt(ext string) bool {
	imageExts := map[string]bool{
		".png":  true,
		".jpg":  true,
		".jpeg": true,
		".gif":  true,
		".svg":  true,
		".webp": true,
		".bmp":  true,
		".ico":  true,
	}
	return imageExts[ext]
}

func isAttachmentExt(ext string) bool {
	attachExts := map[string]bool{
		".pdf":  true,
		".doc":  true,
		".docx": true,
		".xls":  true,
		".xlsx": true,
		".ppt":  true,
		".pptx": true,
		".txt":  true,
		".zip":  true,
		".rar":  true,
		".7z":   true,
	}
	return attachExts[ext]
}

func convertImagePaths(html string, notePath, basePath string) string {
	return html
}

func generateSlug(path string) string {
	base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	return utils.Slugify(base)
}

func noteToHTMLPath(notePath string) string {
	slug := generateSlug(notePath)
	return slug + ".html"
}

var regexpWikiLinkCached = func() *regexp.Regexp {
	re, _ := regexp.Compile(`<a[^>]*class="wiki-link"[^>]*data-target="([^"]*)"[^>]*>([^<]*)</a>`)
	return re
}()

func regexpWikiLink() *regexp.Regexp {
	return regexpWikiLinkCached
}

var regexpImgCached = func() *regexp.Regexp {
	re, _ := regexp.Compile(`<img[^>]*src="([^"]+)"[^>]*>`)
	return re
}()

func regexpImg() *regexp.Regexp {
	return regexpImgCached
}
