package export

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type TOCEntry struct {
	Level    int
	Title    string
	ID       string
	Children []*TOCEntry
}

type LinkRef struct {
	SourcePath  string
	SourceSlug  string
	TargetTitle string
	TargetPath  string
	TargetSlug  string
	Found       bool
	Line        int
}

type AssetRef struct {
	SourcePath string
	AbsPath    string
	TargetPath string
	Type       string
	RefCount   int
}

type ProcessedNote struct {
	Note        *models.Note
	RawContent  string
	HTMLContent string
	Title       string
	FrontMatter map[string]interface{}
	Tags        []string
	Slug        string
	OutLinks    []LinkRef
	InLinks     []LinkRef
	Assets      []AssetRef
	Variables   map[string]string
	TOC         []TOCEntry
}

type TagSummary struct {
	Name  string
	Slug  string
	Count int
	Notes []*ProcessedNote
	Color string
}

type FolderNode struct {
	Name     string
	Path     string
	Notes    []*ProcessedNote
	Children map[string]*FolderNode
}

type ExportContext struct {
	Notes     []*ProcessedNote
	NoteMap   map[string]*ProcessedNote
	SlugMap   map[string]*ProcessedNote
	Tags      []TagSummary
	TagMap    map[string]*TagSummary
	Folders   []*FolderNode
	Assets    []AssetRef
	LinkGraph map[string][]string
	Backlinks map[string][]string
	Variables map[string]string
	Config    *config.Config
	Options   ExportOptions
}

type ExportPipeline struct {
	cfg    *config.Config
	parser *markdown.MarkdownParser
	db     *db.Database
}

func NewExportPipeline(cfg *config.Config, database *db.Database, parser *markdown.MarkdownParser) *ExportPipeline {
	return &ExportPipeline{
		cfg:    cfg,
		parser: parser,
		db:     database,
	}
}

func (p *ExportPipeline) BuildContext(notes []*models.Note, opts ExportOptions) (*ExportContext, error) {
	ctx := &ExportContext{
		Notes:     make([]*ProcessedNote, 0, len(notes)),
		NoteMap:   make(map[string]*ProcessedNote),
		SlugMap:   make(map[string]*ProcessedNote),
		TagMap:    make(map[string]*TagSummary),
		LinkGraph: make(map[string][]string),
		Backlinks: make(map[string][]string),
		Variables: make(map[string]string),
		Config:    p.cfg,
		Options:   opts,
	}

	for k, v := range opts.Variables {
		ctx.Variables[k] = v
	}

	assetRefCount := make(map[string]int)
	assetMap := make(map[string]*AssetRef)

	for _, note := range notes {
		pn, err := p.processNote(note, assetRefCount, assetMap)
		if err != nil {
			return nil, fmt.Errorf("process note %s failed: %w", note.Path, err)
		}
		ctx.Notes = append(ctx.Notes, pn)
		ctx.NoteMap[pn.Note.Path] = pn
		ctx.SlugMap[pn.Slug] = pn
	}

	for _, pn := range ctx.Notes {
		for i := range pn.OutLinks {
			link := &pn.OutLinks[i]
			targetSlug := utils.Slugify(link.TargetTitle)
			link.TargetSlug = targetSlug

			if targetPN, ok := ctx.SlugMap[targetSlug]; ok {
				link.TargetPath = targetPN.Note.Path
				link.TargetSlug = targetPN.Slug
				link.Found = true
			} else {
				for path, candidate := range ctx.NoteMap {
					base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
					if strings.EqualFold(base, link.TargetTitle) ||
						strings.EqualFold(strings.TrimSuffix(link.TargetTitle, filepath.Ext(link.TargetTitle)), base) {
						link.TargetPath = candidate.Note.Path
						link.TargetSlug = candidate.Slug
						link.Found = true
						break
					}
				}
			}

			if link.Found {
				ctx.LinkGraph[pn.Note.Path] = append(ctx.LinkGraph[pn.Note.Path], link.TargetPath)
				ctx.Backlinks[link.TargetPath] = append(ctx.Backlinks[link.TargetPath], pn.Note.Path)
			}
		}
	}

	for _, pn := range ctx.Notes {
		if incoming, ok := ctx.Backlinks[pn.Note.Path]; ok {
			for _, srcPath := range incoming {
				srcPN := ctx.NoteMap[srcPath]
				if srcPN == nil {
					continue
				}
				for _, outLink := range srcPN.OutLinks {
					if outLink.TargetPath == pn.Note.Path {
						pn.InLinks = append(pn.InLinks, LinkRef{
							SourcePath:  srcPath,
							SourceSlug:  srcPN.Slug,
							TargetTitle: pn.Title,
							TargetPath:  pn.Note.Path,
							TargetSlug:  pn.Slug,
							Found:       true,
							Line:        outLink.Line,
						})
						break
					}
				}
			}
		}
	}

	assets := make([]AssetRef, 0, len(assetMap))
	for _, ar := range assetMap {
		assets = append(assets, *ar)
	}
	sort.Slice(assets, func(i, j int) bool {
		return assets[i].RefCount > assets[j].RefCount
	})
	ctx.Assets = assets

	for _, pn := range ctx.Notes {
		for _, tagName := range pn.Tags {
			slug := utils.Slugify(tagName)
			if _, ok := ctx.TagMap[slug]; !ok {
				ctx.TagMap[slug] = &TagSummary{
					Name:  tagName,
					Slug:  slug,
					Color: "#6366f1",
				}
			}
			ts := ctx.TagMap[slug]
			ts.Count++
			ts.Notes = append(ts.Notes, pn)
		}
	}

	tags := make([]TagSummary, 0, len(ctx.TagMap))
	for _, ts := range ctx.TagMap {
		tags = append(tags, *ts)
	}
	sort.Slice(tags, func(i, j int) bool {
		return tags[i].Name < tags[j].Name
	})
	ctx.Tags = tags

	folderRoot := p.buildFolderTree(ctx.Notes)
	ctx.Folders = flattenFolders(folderRoot)

	return ctx, nil
}

func (p *ExportPipeline) processNote(note *models.Note, assetRefCount map[string]int, assetMap map[string]*AssetRef) (*ProcessedNote, error) {
	rawContent, err := p.loadNoteContent(note)
	if err != nil {
		return nil, err
	}

	result, err := p.parser.Parse(rawContent, note.Path)
	if err != nil {
		return nil, err
	}

	slug := generateSlug(note.Path)

	fullNote, err := p.db.GetNoteByID(note.ID)
	if err == nil && fullNote != nil {
		note.Tags = fullNote.Tags
	}

	dbTags := make([]string, 0, len(note.Tags))
	for _, t := range note.Tags {
		dbTags = append(dbTags, t.Name)
	}
	allTags := utils.UniqueStrings(append(dbTags, result.Tags...))

	pn := &ProcessedNote{
		Note:        note,
		RawContent:  rawContent,
		HTMLContent: result.HTML,
		Title:       note.Title,
		FrontMatter: result.Metadata,
		Tags:        allTags,
		Slug:        slug,
		Variables:   p.buildNoteVariables(note),
	}

	outLinks := make([]LinkRef, 0, len(result.Links))
	for _, wl := range result.Links {
		targetTitle := wl.Target
		if wl.Alias != "" {
			targetTitle = wl.Target
		}
		outLinks = append(outLinks, LinkRef{
			SourcePath:  note.Path,
			SourceSlug:  slug,
			TargetTitle: targetTitle,
			Line:        wl.LineNum,
		})
	}
	pn.OutLinks = outLinks

	pn.Assets = p.collectAssets(rawContent, note.Path, assetRefCount, assetMap)

	pn.TOC = p.extractTOCFromHTML(result.HTML)

	return pn, nil
}

func (p *ExportPipeline) loadNoteContent(note *models.Note) (string, error) {
	fullPath := note.Path
	if !filepath.IsAbs(fullPath) {
		fullPath = filepath.Join(p.cfg.VaultPath, note.Path)
	}
	content, err := os.ReadFile(fullPath)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (p *ExportPipeline) buildNoteVariables(note *models.Note) map[string]string {
	return map[string]string{
		"Title":     note.Title,
		"NotePath":  note.Path,
		"CreatedAt": note.CreatedAt.Format("2006-01-02"),
		"UpdatedAt": note.UpdatedAt.Format("2006-01-02"),
		"WordCount": fmt.Sprintf("%d", note.WordCount),
	}
}

func (p *ExportPipeline) collectAssets(content, notePath string, assetRefCount map[string]int, assetMap map[string]*AssetRef) []AssetRef {
	assets := []AssetRef{}
	seen := make(map[string]bool)

	mdImgRe := regexp.MustCompile(`!\[[^\]]*\]\(([^)]+)\)`)
	htmlImgRe := regexp.MustCompile(`<img[^>]+src="([^"]+)"`)
	htmlLinkRe := regexp.MustCompile(`<a[^>]+href="([^"]+)"`)

	allMatches := [][]string{}
	allMatches = append(allMatches, mdImgRe.FindAllStringSubmatch(content, -1)...)
	allMatches = append(allMatches, htmlImgRe.FindAllStringSubmatch(content, -1)...)
	for _, m := range htmlLinkRe.FindAllStringSubmatch(content, -1) {
		if len(m) >= 2 {
			href := m[1]
			ext := strings.ToLower(filepath.Ext(href))
			if isAttachmentExt(ext) && !strings.HasPrefix(href, "http://") && !strings.HasPrefix(href, "https://") {
				allMatches = append(allMatches, m)
			}
		}
	}

	for _, match := range allMatches {
		if len(match) < 2 {
			continue
		}
		src := match[1]
		if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") || strings.HasPrefix(src, "data:") || strings.HasPrefix(src, "#") || strings.HasPrefix(src, "mailto:") {
			continue
		}
		if seen[src] {
			continue
		}
		seen[src] = true

		noteDir := filepath.Dir(notePath)
		sourcePath := filepath.Clean(filepath.Join(noteDir, src))

		absPath := sourcePath
		if !filepath.IsAbs(absPath) {
			absPath = filepath.Join(p.cfg.VaultPath, sourcePath)
		}
		absPath = filepath.Clean(absPath)

		assetKey := sourcePath
		if _, ok := assetRefCount[assetKey]; !ok {
			ext := strings.ToLower(filepath.Ext(src))
			assetType := "attachment"
			if isImageExt(ext) {
				assetType = "image"
			}
			assetMap[assetKey] = &AssetRef{
				SourcePath: sourcePath,
				AbsPath:    absPath,
				TargetPath: sourcePath,
				Type:       assetType,
			}
		}
		assetRefCount[assetKey]++
		assetMap[assetKey].RefCount = assetRefCount[assetKey]

		assets = append(assets, *assetMap[assetKey])
	}

	return assets
}

func (p *ExportPipeline) extractTOCFromHTML(html string) []TOCEntry {
	re := regexp.MustCompile(`<h([1-6])\s+id="([^"]*)"[^>]*>([^<]+)</h[1-6]>`)
	matches := re.FindAllStringSubmatch(html, -1)

	var entries []TOCEntry
	for _, match := range matches {
		level := int(match[1][0] - '0')
		id := match[2]
		title := match[3]

		entries = append(entries, TOCEntry{
			Level: level,
			Title: title,
			ID:    id,
		})
	}

	return entries
}

func (p *ExportPipeline) buildFolderTree(notes []*ProcessedNote) *FolderNode {
	root := &FolderNode{
		Name:     "/",
		Path:     "",
		Children: make(map[string]*FolderNode),
	}

	for _, pn := range notes {
		dir := filepath.Dir(pn.Note.Path)
		if dir == "." {
			root.Notes = append(root.Notes, pn)
			continue
		}

		parts := strings.Split(dir, string(filepath.Separator))
		current := root

		for _, part := range parts {
			if _, ok := current.Children[part]; !ok {
				current.Children[part] = &FolderNode{
					Name:     part,
					Path:     filepath.Join(current.Path, part),
					Children: make(map[string]*FolderNode),
				}
			}
			current = current.Children[part]
		}

		current.Notes = append(current.Notes, pn)
	}

	return root
}

func flattenFolders(root *FolderNode) []*FolderNode {
	result := []*FolderNode{}
	var walk func(node *FolderNode)
	walk = func(node *FolderNode) {
		result = append(result, node)
		keys := make([]string, 0, len(node.Children))
		for k := range node.Children {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			walk(node.Children[k])
		}
	}
	walk(root)
	return result
}

func (ctx *ExportContext) ConvertWikiLinks(sourceSlug string, html string, linkFormat string) string {
	re := regexpWikiLink()

	return re.ReplaceAllStringFunc(html, func(match string) string {
		matches := re.FindStringSubmatch(match)
		if len(matches) < 3 {
			return match
		}

		target := matches[1]
		display := matches[2]

		targetSlug := utils.Slugify(target)
		targetPN := ctx.SlugMap[targetSlug]
		if targetPN == nil {
			for path, candidate := range ctx.NoteMap {
				base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
				if strings.EqualFold(base, target) ||
					strings.EqualFold(strings.TrimSuffix(target, filepath.Ext(target)), base) {
					targetPN = candidate
					break
				}
			}
		}

		if targetPN == nil {
			return fmt.Sprintf(`<span class="wiki-link broken" title="笔记不存在">%s</span>`, display)
		}

		var href string
		switch linkFormat {
		case "site":
			href = targetPN.Slug + ".html"
		case "pdf", "html-single":
			href = "#" + targetPN.Slug
		default:
			href = targetPN.Slug + ".html"
		}

		return fmt.Sprintf(`<a href="%s" class="wiki-link" data-target="%s">%s</a>`, href, target, display)
	})
}

func (ctx *ExportContext) ConvertAssetPaths(html string, basePath string) string {
	re := regexpImg()

	return re.ReplaceAllStringFunc(html, func(match string) string {
		matches := re.FindStringSubmatch(match)
		if len(matches) < 2 {
			return match
		}

		src := matches[1]
		if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") || strings.HasPrefix(src, "data:") {
			return match
		}

		var sourcePN *ProcessedNote
		for _, pn := range ctx.Notes {
			for _, a := range pn.Assets {
				if strings.HasSuffix(a.SourcePath, src) || strings.HasSuffix(src, filepath.Base(a.SourcePath)) {
					sourcePN = pn
					break
				}
			}
			if sourcePN != nil {
				break
			}
		}

		var targetRelPath string
		found := false
		for _, ar := range ctx.Assets {
			if strings.HasSuffix(ar.SourcePath, src) {
				targetRelPath = ar.TargetPath
				found = true
				break
			}
		}

		if !found {
			if sourcePN != nil {
				noteDir := filepath.Dir(sourcePN.Note.Path)
				imgPath := filepath.Clean(filepath.Join(noteDir, src))
				targetRelPath = imgPath
			} else {
				targetRelPath = src
			}
		}

		newSrc := filepath.ToSlash(filepath.Join(basePath, targetRelPath))
		return strings.Replace(match, src, newSrc, 1)
	})
}

func (ctx *ExportContext) GetNoteByPath(path string) *ProcessedNote {
	return ctx.NoteMap[path]
}

func (ctx *ExportContext) GetNoteBySlug(slug string) *ProcessedNote {
	return ctx.SlugMap[slug]
}

func (ctx *ExportContext) RootFolder() *FolderNode {
	if len(ctx.Folders) == 0 {
		return &FolderNode{
			Name:     "/",
			Path:     "",
			Children: make(map[string]*FolderNode),
		}
	}
	return ctx.Folders[0]
}

func (ctx *ExportContext) MergeVariables(noteVars map[string]string) map[string]string {
	merged := make(map[string]string, len(ctx.Variables)+len(noteVars))
	for k, v := range ctx.Variables {
		merged[k] = v
	}
	for k, v := range noteVars {
		merged[k] = v
	}
	return merged
}
