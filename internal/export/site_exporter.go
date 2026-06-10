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

type SiteExporter struct {
	cfg    *config.Config
	db     *db.Database
	parser *markdown.MarkdownParser
}

type SiteNote struct {
	Note     *models.Note
	HTML     string
	Slug     string
	Tags     []string
	Path     string
}

type TagInfo struct {
	Name  string
	Slug  string
	Notes []*SiteNote
	Color string
}

type FolderNode struct {
	Name     string
	Path     string
	Notes    []*SiteNote
	Children map[string]*FolderNode
}

func (e *SiteExporter) Export(notes []*models.Note, opts ExportOptions) error {
	if err := os.MkdirAll(opts.OutputPath, 0755); err != nil {
		return err
	}

	siteNotes, err := e.prepareSiteNotes(notes)
	if err != nil {
		return err
	}

	noteMap := make(map[string]*SiteNote)
	for _, sn := range siteNotes {
		noteMap[sn.Slug] = sn
	}

	tags, tagMap := e.buildTagIndex(siteNotes)

	folderTree := e.buildFolderTree(siteNotes)

	if err := e.copyAssets(opts.OutputPath); err != nil {
		return err
	}

	if err := e.generateCSS(opts.OutputPath, opts); err != nil {
		return err
	}

	if err := e.generateJS(opts.OutputPath); err != nil {
		return err
	}

	sidebarHTML := e.renderSidebar(siteNotes, tags, folderTree)

	for _, sn := range siteNotes {
		html := e.convertWikiLinksToHTML(sn.HTML, noteMap)
		html = e.convertImagePathsForSite(html, sn.Path)

		variables := e.buildSiteVariables(sn.Note, opts)

		pageHTML := e.wrapSitePage(sn.Note.Title, html, sidebarHTML, "note", variables, opts)
		pageHTML = applyVariables(pageHTML, variables)

		outPath := filepath.Join(opts.OutputPath, sn.Slug+".html")
		if err := os.WriteFile(outPath, []byte(pageHTML), 0644); err != nil {
			return err
		}
	}

	if err := e.generateHomePage(siteNotes, tags, sidebarHTML, opts); err != nil {
		return err
	}

	if err := e.generateTagPages(tags, tagMap, sidebarHTML, opts); err != nil {
		return err
	}

	return nil
}

func (e *SiteExporter) prepareSiteNotes(notes []*models.Note) ([]*SiteNote, error) {
	var siteNotes []*SiteNote

	for _, note := range notes {
		content, err := e.loadNoteContent(note)
		if err != nil {
			return nil, err
		}

		result, err := e.parser.Parse(content, note.Path)
		if err != nil {
			return nil, err
		}

		if err := e.loadNoteTags(note); err != nil {
			return nil, err
		}

		tagNames := make([]string, 0, len(note.Tags))
		for _, tag := range note.Tags {
			tagNames = append(tagNames, tag.Name)
		}

		sn := &SiteNote{
			Note: note,
			HTML: result.HTML,
			Slug: generateSlug(note.Path),
			Tags: tagNames,
			Path: note.Path,
		}
		siteNotes = append(siteNotes, sn)
	}

	sort.Slice(siteNotes, func(i, j int) bool {
		return siteNotes[i].Note.Title < siteNotes[j].Note.Title
	})

	return siteNotes, nil
}

func (e *SiteExporter) loadNoteContent(note *models.Note) (string, error) {
	fullPath := note.Path
	if !filepath.IsAbs(fullPath) {
		fullPath = filepath.Join(e.cfg.VaultPath, note.Path)
	}
	content, err := os.ReadFile(fullPath)
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (e *SiteExporter) loadNoteTags(note *models.Note) error {
	noteWithTags, err := e.db.GetNoteByID(note.ID)
	if err != nil {
		return err
	}
	note.Tags = noteWithTags.Tags
	return nil
}

func (e *SiteExporter) buildTagIndex(notes []*SiteNote) ([]*TagInfo, map[string]*TagInfo) {
	tagMap := make(map[string]*TagInfo)

	for _, sn := range notes {
		for _, tagName := range sn.Tags {
			slug := utils.Slugify(tagName)
			if _, ok := tagMap[slug]; !ok {
				tagMap[slug] = &TagInfo{
					Name: tagName,
					Slug: slug,
				}
			}
			tagMap[slug].Notes = append(tagMap[slug].Notes, sn)
		}
	}

	tags := make([]*TagInfo, 0, len(tagMap))
	for _, tag := range tagMap {
		tags = append(tags, tag)
	}

	sort.Slice(tags, func(i, j int) bool {
		return tags[i].Name < tags[j].Name
	})

	return tags, tagMap
}

func (e *SiteExporter) buildFolderTree(notes []*SiteNote) *FolderNode {
	root := &FolderNode{
		Name:     "/",
		Path:     "",
		Children: make(map[string]*FolderNode),
	}

	for _, sn := range notes {
		dir := filepath.Dir(sn.Path)
		if dir == "." {
			root.Notes = append(root.Notes, sn)
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

		current.Notes = append(current.Notes, sn)
	}

	return root
}

func (e *SiteExporter) copyAssets(outputPath string) error {
	assetDir := filepath.Join(outputPath, "assets")
	if err := os.MkdirAll(assetDir, 0755); err != nil {
		return err
	}
	return copyAssets(e.cfg.VaultPath, assetDir)
}

func (e *SiteExporter) generateCSS(outputPath string, opts ExportOptions) error {
	cssDir := filepath.Join(outputPath, "css")
	if err := os.MkdirAll(cssDir, 0755); err != nil {
		return err
	}

	mainCSS := e.siteCSS()
	if opts.CSSPath != "" {
		customCSS, err := os.ReadFile(opts.CSSPath)
		if err == nil {
			mainCSS += "\n" + string(customCSS)
		}
	}

	if err := os.WriteFile(filepath.Join(cssDir, "style.css"), []byte(mainCSS), 0644); err != nil {
		return err
	}

	darkCSS := e.darkThemeCSS()
	if err := os.WriteFile(filepath.Join(cssDir, "dark.css"), []byte(darkCSS), 0644); err != nil {
		return err
	}

	lightCSS := e.lightThemeCSS()
	return os.WriteFile(filepath.Join(cssDir, "light.css"), []byte(lightCSS), 0644)
}

func (e *SiteExporter) generateJS(outputPath string) error {
	jsDir := filepath.Join(outputPath, "js")
	if err := os.MkdirAll(jsDir, 0755); err != nil {
		return err
	}

	jsContent := `
(function() {
    function initTheme() {
        const savedTheme = localStorage.getItem('theme') || 'light';
        setTheme(savedTheme);
    }

    function setTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem('theme', theme);

        const toggle = document.getElementById('theme-toggle');
        if (toggle) {
            toggle.textContent = theme === 'dark' ? '☀️ 亮色' : '🌙 暗色';
        }
    }

    function toggleTheme() {
        const current = document.documentElement.getAttribute('data-theme') || 'light';
        setTheme(current === 'dark' ? 'light' : 'dark');
    }

    function initSidebar() {
        const toggle = document.getElementById('sidebar-toggle');
        const sidebar = document.querySelector('.sidebar');
        const main = document.querySelector('.main-content');

        if (toggle && sidebar) {
            toggle.addEventListener('click', function() {
                sidebar.classList.toggle('collapsed');
                main.classList.toggle('sidebar-collapsed');
            });
        }
    }

    function initFolderToggle() {
        const folderHeaders = document.querySelectorAll('.folder-header');
        folderHeaders.forEach(function(header) {
            header.addEventListener('click', function() {
                const folder = this.closest('.folder');
                folder.classList.toggle('collapsed');
            });
        });
    }

    function initSearch() {
        const searchInput = document.getElementById('search-input');
        if (!searchInput) return;

        const noteItems = document.querySelectorAll('.note-item');

        searchInput.addEventListener('input', function() {
            const query = this.value.toLowerCase();

            noteItems.forEach(function(item) {
                const title = item.textContent.toLowerCase();
                if (title.includes(query)) {
                    item.style.display = '';
                } else {
                    item.style.display = 'none';
                }
            });
        });
    }

    document.addEventListener('DOMContentLoaded', function() {
        initTheme();
        initSidebar();
        initFolderToggle();
        initSearch();

        const toggleBtn = document.getElementById('theme-toggle');
        if (toggleBtn) {
            toggleBtn.addEventListener('click', toggleTheme);
        }
    });
})();
`

	return os.WriteFile(filepath.Join(jsDir, "site.js"), []byte(jsContent), 0644)
}

func (e *SiteExporter) renderSidebar(notes []*SiteNote, tags []*TagInfo, folderTree *FolderNode) string {
	var sb strings.Builder

	sb.WriteString(`<aside class="sidebar">`)
	sb.WriteString(`<div class="sidebar-header">`)
	sb.WriteString(`<h1 class="site-title">知识库</h1>`)
	sb.WriteString(`<button id="sidebar-toggle" class="sidebar-toggle" title="折叠侧边栏">«</button>`)
	sb.WriteString(`</div>`)

	sb.WriteString(`<div class="sidebar-search">`)
	sb.WriteString(`<input type="text" id="search-input" placeholder="搜索笔记...">`)
	sb.WriteString(`</div>`)

	sb.WriteString(`<div class="sidebar-nav">`)

	sb.WriteString(`<div class="nav-section">`)
	sb.WriteString(`<a href="index.html" class="nav-home">🏠 首页</a>`)
	sb.WriteString(`</div>`)

	sb.WriteString(`<div class="nav-section">`)
	sb.WriteString(`<h3 class="nav-title">📁 文件夹</h3>`)
	sb.WriteString(e.renderFolderTree(folderTree))
	sb.WriteString(`</div>`)

	sb.WriteString(`<div class="nav-section">`)
	sb.WriteString(`<h3 class="nav-title">🏷️ 标签</h3>`)
	sb.WriteString(`<ul class="tag-list">`)
	for _, tag := range tags {
		sb.WriteString(fmt.Sprintf(
			`<li class="tag-item"><a href="tag-%s.html"><span class="tag-dot" style="background-color: %s;"></span>%s <span class="tag-count">%d</span></a></li>`,
			tag.Slug, "#6366f1", tag.Name, len(tag.Notes),
		))
	}
	sb.WriteString(`</ul>`)
	sb.WriteString(`</div>`)

	sb.WriteString(`</div>`)

	sb.WriteString(`<div class="sidebar-footer">`)
	sb.WriteString(`<button id="theme-toggle" class="theme-toggle">🌙 暗色</button>`)
	sb.WriteString(`</div>`)

	sb.WriteString(`</aside>`)

	return sb.String()
}

func (e *SiteExporter) renderFolderTree(node *FolderNode) string {
	var sb strings.Builder

	sb.WriteString(`<ul class="folder-list">`)

	sortedChildren := make([]*FolderNode, 0, len(node.Children))
	for _, child := range node.Children {
		sortedChildren = append(sortedChildren, child)
	}
	sort.Slice(sortedChildren, func(i, j int) bool {
		return sortedChildren[i].Name < sortedChildren[j].Name
	})

	if len(node.Notes) > 0 && node.Path != "" {
		sb.WriteString(`<li class="folder">`)
		sb.WriteString(`<div class="folder-header">`)
		sb.WriteString(fmt.Sprintf(`<span class="folder-icon">📂</span><span class="folder-name">%s</span>`, node.Name))
		sb.WriteString(`</div>`)
		sb.WriteString(`<ul class="folder-content">`)

		for _, sn := range node.Notes {
			sb.WriteString(fmt.Sprintf(
				`<li class="note-item"><a href="%s.html">📄 %s</a></li>`,
				sn.Slug, sn.Note.Title,
			))
		}

		for _, child := range sortedChildren {
			sb.WriteString(e.renderSubFolder(child))
		}

		sb.WriteString(`</ul>`)
		sb.WriteString(`</li>`)
	} else if node.Path == "" {
		for _, sn := range node.Notes {
			sb.WriteString(fmt.Sprintf(
				`<li class="note-item"><a href="%s.html">📄 %s</a></li>`,
				sn.Slug, sn.Note.Title,
			))
		}

		for _, child := range sortedChildren {
			sb.WriteString(e.renderSubFolder(child))
		}
	}

	sb.WriteString(`</ul>`)

	return sb.String()
}

func (e *SiteExporter) renderSubFolder(node *FolderNode) string {
	var sb strings.Builder

	sb.WriteString(`<li class="folder">`)
	sb.WriteString(`<div class="folder-header">`)
	sb.WriteString(fmt.Sprintf(`<span class="folder-icon">📂</span><span class="folder-name">%s</span>`, node.Name))
	sb.WriteString(`</div>`)
	sb.WriteString(`<ul class="folder-content">`)

	for _, sn := range node.Notes {
		sb.WriteString(fmt.Sprintf(
			`<li class="note-item"><a href="%s.html">📄 %s</a></li>`,
			sn.Slug, sn.Note.Title,
		))
	}

	sortedChildren := make([]*FolderNode, 0, len(node.Children))
	for _, child := range node.Children {
		sortedChildren = append(sortedChildren, child)
	}
	sort.Slice(sortedChildren, func(i, j int) bool {
		return sortedChildren[i].Name < sortedChildren[j].Name
	})

	for _, child := range sortedChildren {
		sb.WriteString(e.renderSubFolder(child))
	}

	sb.WriteString(`</ul>`)
	sb.WriteString(`</li>`)

	return sb.String()
}

func (e *SiteExporter) convertWikiLinksToHTML(html string, noteMap map[string]*SiteNote) string {
	re := regexpWikiLink()

	return re.ReplaceAllStringFunc(html, func(match string) string {
		matches := re.FindStringSubmatch(match)
		if len(matches) < 3 {
			return match
		}

		target := matches[1]
		display := matches[2]

		targetSlug := utils.Slugify(target)

		if sn, ok := noteMap[targetSlug]; ok {
			return fmt.Sprintf(`<a href="%s.html" class="wiki-link">%s</a>`, sn.Slug, display)
		}

		return fmt.Sprintf(`<span class="wiki-link broken" title="笔记不存在">%s</span>`, display)
	})
}

func regexpWikiLink() *regexp.Regexp {
	return regexpWikiLinkCached
}

var regexpWikiLinkCached = func() *regexp.Regexp {
	re, _ := regexp.Compile(`<a[^>]*class="wiki-link"[^>]*data-target="([^"]*)"[^>]*>([^<]*)</a>`)
	return re
}()

func (e *SiteExporter) convertImagePathsForSite(html, notePath string) string {
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

		noteDir := filepath.Dir(notePath)
		imgPath := filepath.Join(noteDir, src)
		imgPath = filepath.Clean(imgPath)

		newSrc := "assets/" + filepath.ToSlash(imgPath)

		return strings.Replace(match, src, newSrc, 1)
	})
}

func regexpImg() *regexp.Regexp {
	return regexpImgCached
}

var regexpImgCached = func() *regexp.Regexp {
	re, _ := regexp.Compile(`<img[^>]*src="([^"]*)"[^>]*>`)
	return re
}()

func (e *SiteExporter) buildSiteVariables(note *models.Note, opts ExportOptions) map[string]string {
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

func (e *SiteExporter) wrapSitePage(title, content, sidebar, pageType string, variables map[string]string, opts ExportOptions) string {
	return fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN" data-theme="light">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>%s - 知识库</title>
    <link rel="stylesheet" href="css/style.css">
    <link rel="stylesheet" href="css/light.css" id="theme-light">
    <link rel="stylesheet" href="css/dark.css" id="theme-dark" disabled>
</head>
<body class="page-%s">
    <div class="layout">
        %s
        <main class="main-content">
            <article class="content markdown-body">
%s
            </article>
        </main>
    </div>
    <script src="js/site.js"></script>
</body>
</html>`, title, pageType, sidebar, content)
}

func (e *SiteExporter) generateHomePage(notes []*SiteNote, tags []*TagInfo, sidebar string, opts ExportOptions) error {
	var content strings.Builder

	content.WriteString(`<div class="home-page">`)
	content.WriteString(`<header class="home-header">`)
	content.WriteString(`<h1>📚 知识文库</h1>`)
	content.WriteString(fmt.Sprintf(`<p class="home-stats">共 <strong>%d</strong> 篇笔记 · <strong>%d</strong> 个标签</p>`,
		len(notes), len(tags)))
	content.WriteString(`</header>`)

	content.WriteString(`<section class="home-section">`)
	content.WriteString(`<h2>📝 最新笔记</h2>`)
	content.WriteString(`<ul class="note-grid">`)

	sortedNotes := make([]*SiteNote, len(notes))
	copy(sortedNotes, notes)
	sort.Slice(sortedNotes, func(i, j int) bool {
		return sortedNotes[i].Note.UpdatedAt.After(sortedNotes[j].Note.UpdatedAt)
	})

	recentCount := 10
	if recentCount > len(sortedNotes) {
		recentCount = len(sortedNotes)
	}

	for i := 0; i < recentCount; i++ {
		sn := sortedNotes[i]
		content.WriteString(fmt.Sprintf(`
			<li class="note-card">
				<a href="%s.html">
					<h3>%s</h3>
					<div class="note-meta">
						<span class="note-date">%s</span>
						<span class="note-words">%d 字</span>
					</div>
				</a>
			</li>`,
			sn.Slug, sn.Note.Title,
			sn.Note.UpdatedAt.Format("2006-01-02"),
			sn.Note.WordCount,
		))
	}

	content.WriteString(`</ul>`)
	content.WriteString(`</section>`)

	content.WriteString(`<section class="home-section">`)
	content.WriteString(`<h2>🏷️ 标签云</h2>`)
	content.WriteString(`<div class="tag-cloud">`)

	for _, tag := range tags {
		size := 100 + len(tag.Notes)*10
		if size > 200 {
			size = 200
		}
		content.WriteString(fmt.Sprintf(
			`<a href="tag-%s.html" class="tag-cloud-item" style="font-size: %d%%;">%s <span class="tag-count">(%d)</span></a>`,
			tag.Slug, size, tag.Name, len(tag.Notes),
		))
	}

	content.WriteString(`</div>`)
	content.WriteString(`</section>`)

	content.WriteString(`</div>`)

	variables := map[string]string{
		"Title": "首页",
	}
	for k, v := range opts.Variables {
		variables[k] = v
	}

	html := e.wrapSitePage("首页", content.String(), sidebar, "home", variables, opts)
	html = applyVariables(html, variables)

	indexPath := filepath.Join(opts.OutputPath, "index.html")
	return os.WriteFile(indexPath, []byte(html), 0644)
}

func (e *SiteExporter) generateTagPages(tags []*TagInfo, tagMap map[string]*TagInfo, sidebar string, opts ExportOptions) error {
	for _, tag := range tags {
		var content strings.Builder

		content.WriteString(`<div class="tag-page">`)
		content.WriteString(fmt.Sprintf(`<header class="tag-header">`))
		content.WriteString(fmt.Sprintf(`<h1><span class="tag-dot-large"></span> #%s</h1>`, tag.Name))
		content.WriteString(fmt.Sprintf(`<p>共 %d 篇笔记</p>`, len(tag.Notes)))
		content.WriteString(`</header>`)

		content.WriteString(`<ul class="note-list">`)
		for _, sn := range tag.Notes {
			content.WriteString(fmt.Sprintf(`
				<li class="note-list-item">
					<a href="%s.html">
						<h3>%s</h3>
						<div class="note-meta">
							<span class="note-date">%s</span>
							<span class="note-words">%d 字</span>
						</div>
					</a>
				</li>`,
				sn.Slug, sn.Note.Title,
				sn.Note.UpdatedAt.Format("2006-01-02"),
				sn.Note.WordCount,
			))
		}
		content.WriteString(`</ul>`)
		content.WriteString(`</div>`)

		variables := map[string]string{
			"Title": tag.Name,
		}
		for k, v := range opts.Variables {
			variables[k] = v
		}

		html := e.wrapSitePage(tag.Name, content.String(), sidebar, "tag", variables, opts)
		html = applyVariables(html, variables)

		tagPath := filepath.Join(opts.OutputPath, "tag-"+tag.Slug+".html")
		if err := os.WriteFile(tagPath, []byte(html), 0644); err != nil {
			return err
		}
	}

	return nil
}

func (e *SiteExporter) siteCSS() string {
	return `
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
}

html, body {
    height: 100%;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    font-size: 16px;
    line-height: 1.6;
    transition: background-color 0.3s, color 0.3s;
}

.layout {
    display: flex;
    height: 100vh;
    overflow: hidden;
}

.sidebar {
    width: 280px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    transition: transform 0.3s, width 0.3s;
    border-right: 1px solid var(--border-color);
    background-color: var(--sidebar-bg);
}

.sidebar.collapsed {
    transform: translateX(-100%);
    width: 0;
}

.main-content.sidebar-collapsed {
    margin-left: 0;
}

.sidebar-header {
    padding: 20px;
    border-bottom: 1px solid var(--border-color);
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.site-title {
    font-size: 20px;
    font-weight: 600;
    color: var(--text-primary);
}

.sidebar-toggle {
    background: none;
    border: none;
    font-size: 18px;
    cursor: pointer;
    color: var(--text-secondary);
    padding: 4px 8px;
    border-radius: 4px;
}

.sidebar-toggle:hover {
    background-color: var(--hover-bg);
}

.sidebar-search {
    padding: 12px 16px;
    border-bottom: 1px solid var(--border-color);
}

.sidebar-search input {
    width: 100%;
    padding: 8px 12px;
    border: 1px solid var(--border-color);
    border-radius: 6px;
    font-size: 14px;
    background-color: var(--input-bg);
    color: var(--text-primary);
    outline: none;
    transition: border-color 0.2s;
}

.sidebar-search input:focus {
    border-color: var(--accent-color);
}

.sidebar-nav {
    flex: 1;
    overflow-y: auto;
    padding: 12px 0;
}

.nav-section {
    margin-bottom: 16px;
}

.nav-home {
    display: block;
    padding: 8px 20px;
    color: var(--text-primary);
    text-decoration: none;
    font-weight: 500;
}

.nav-home:hover {
    background-color: var(--hover-bg);
}

.nav-title {
    padding: 8px 20px;
    font-size: 12px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: var(--text-secondary);
}

.folder-list,
.tag-list {
    list-style: none;
}

.folder {
    margin: 2px 0;
}

.folder-header {
    display: flex;
    align-items: center;
    padding: 4px 20px;
    cursor: pointer;
    color: var(--text-primary);
    font-size: 14px;
}

.folder-header:hover {
    background-color: var(--hover-bg);
}

.folder-icon {
    margin-right: 6px;
    font-size: 14px;
}

.folder-name {
    flex: 1;
}

.folder-content {
    list-style: none;
    padding-left: 0;
}

.folder.collapsed .folder-content {
    display: none;
}

.note-item {
    padding: 4px 20px 4px 44px;
}

.note-item a {
    display: block;
    padding: 4px 8px;
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 14px;
    border-radius: 4px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}

.note-item a:hover {
    background-color: var(--hover-bg);
    color: var(--text-primary);
}

.tag-list {
    padding: 0 12px;
}

.tag-item {
    margin: 2px 0;
}

.tag-item a {
    display: flex;
    align-items: center;
    padding: 4px 8px;
    color: var(--text-secondary);
    text-decoration: none;
    font-size: 14px;
    border-radius: 4px;
}

.tag-item a:hover {
    background-color: var(--hover-bg);
    color: var(--text-primary);
}

.tag-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    margin-right: 8px;
    flex-shrink: 0;
}

.tag-count {
    margin-left: auto;
    font-size: 12px;
    opacity: 0.6;
}

.sidebar-footer {
    padding: 12px 20px;
    border-top: 1px solid var(--border-color);
}

.theme-toggle {
    width: 100%;
    padding: 8px 12px;
    border: 1px solid var(--border-color);
    border-radius: 6px;
    background-color: transparent;
    color: var(--text-primary);
    cursor: pointer;
    font-size: 14px;
    transition: background-color 0.2s;
}

.theme-toggle:hover {
    background-color: var(--hover-bg);
}

.main-content {
    flex: 1;
    overflow-y: auto;
    padding: 40px 60px;
    background-color: var(--bg-primary);
    transition: margin-left 0.3s;
}

.content {
    max-width: 800px;
    margin: 0 auto;
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
    color: var(--text-primary);
}

.markdown-body h1 {
    font-size: 2em;
    border-bottom: 1px solid var(--border-color);
    padding-bottom: 10px;
}

.markdown-body h2 {
    font-size: 1.5em;
    border-bottom: 1px solid var(--border-color);
    padding-bottom: 8px;
}

.markdown-body h3 {
    font-size: 1.25em;
}

.markdown-body p {
    margin-bottom: 16px;
    color: var(--text-primary);
}

.markdown-body ul,
.markdown-body ol {
    margin-bottom: 16px;
    padding-left: 2em;
    color: var(--text-primary);
}

.markdown-body li {
    margin-bottom: 4px;
}

.markdown-body blockquote {
    padding: 0 1em;
    color: var(--text-secondary);
    border-left: 4px solid var(--border-color);
    margin-bottom: 16px;
}

.markdown-body code {
    padding: 2px 6px;
    font-size: 90%;
    background-color: var(--code-bg);
    border-radius: 3px;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
    color: var(--text-primary);
}

.markdown-body pre {
    padding: 16px;
    overflow: auto;
    font-size: 85%;
    line-height: 1.45;
    background-color: var(--code-bg);
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
    border: 1px solid var(--border-color);
}

.markdown-body table th {
    font-weight: 600;
    background-color: var(--table-header-bg);
}

.markdown-body img {
    max-width: 100%;
    height: auto;
    border-radius: 4px;
}

.markdown-body a {
    color: var(--accent-color);
    text-decoration: none;
}

.markdown-body a:hover {
    text-decoration: underline;
}

.markdown-body .wiki-link {
    color: var(--accent-color);
}

.markdown-body .wiki-link.broken {
    color: var(--error-color);
    text-decoration: underline;
    text-decoration-style: dotted;
}

.home-page .home-header {
    text-align: center;
    margin-bottom: 40px;
    padding-bottom: 30px;
    border-bottom: 1px solid var(--border-color);
}

.home-page h1 {
    font-size: 2.5em;
    margin-bottom: 12px;
    color: var(--text-primary);
}

.home-stats {
    color: var(--text-secondary);
    font-size: 16px;
}

.home-section {
    margin-bottom: 40px;
}

.home-section h2 {
    font-size: 1.5em;
    margin-bottom: 20px;
    color: var(--text-primary);
}

.note-grid {
    list-style: none;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: 16px;
}

.note-card {
    border: 1px solid var(--border-color);
    border-radius: 8px;
    overflow: hidden;
    transition: transform 0.2s, box-shadow 0.2s;
}

.note-card:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.note-card a {
    display: block;
    padding: 16px;
    text-decoration: none;
    color: inherit;
}

.note-card h3 {
    font-size: 16px;
    margin-bottom: 8px;
    color: var(--text-primary);
}

.note-meta {
    font-size: 12px;
    color: var(--text-secondary);
    display: flex;
    gap: 12px;
}

.tag-cloud {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

.tag-cloud-item {
    display: inline-block;
    padding: 6px 12px;
    background-color: var(--tag-bg);
    color: var(--text-primary);
    text-decoration: none;
    border-radius: 16px;
    transition: background-color 0.2s;
}

.tag-cloud-item:hover {
    background-color: var(--accent-color);
    color: #fff;
}

.tag-page .tag-header {
    margin-bottom: 30px;
    padding-bottom: 20px;
    border-bottom: 1px solid var(--border-color);
}

.tag-page h1 {
    display: flex;
    align-items: center;
    gap: 12px;
    color: var(--text-primary);
}

.tag-dot-large {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    background-color: var(--accent-color);
}

.tag-page .tag-header p {
    color: var(--text-secondary);
    margin-top: 8px;
}

.note-list {
    list-style: none;
}

.note-list-item {
    border-bottom: 1px solid var(--border-color);
}

.note-list-item a {
    display: block;
    padding: 16px 0;
    text-decoration: none;
    color: inherit;
}

.note-list-item a:hover h3 {
    color: var(--accent-color);
}

.note-list-item h3 {
    font-size: 16px;
    margin-bottom: 6px;
    color: var(--text-primary);
    transition: color 0.2s;
}

@media (max-width: 768px) {
    .sidebar {
        position: fixed;
        left: 0;
        top: 0;
        height: 100vh;
        z-index: 100;
        transform: translateX(-100%);
    }

    .sidebar.open {
        transform: translateX(0);
    }

    .main-content {
        padding: 20px;
    }

    .note-grid {
        grid-template-columns: 1fr;
    }
}
`
}

func (e *SiteExporter) lightThemeCSS() string {
	return `
:root[data-theme="light"] {
    --bg-primary: #ffffff;
    --bg-secondary: #f6f8fa;
    --sidebar-bg: #f6f8fa;
    --text-primary: #24292e;
    --text-secondary: #586069;
    --border-color: #e1e4e8;
    --accent-color: #0366d6;
    --hover-bg: #eef2f7;
    --code-bg: #f6f8fa;
    --table-header-bg: #f6f8fa;
    --tag-bg: #e8eaed;
    --error-color: #d73a49;
    --input-bg: #ffffff;
}
`
}

func (e *SiteExporter) darkThemeCSS() string {
	return `
:root[data-theme="dark"] {
    --bg-primary: #0d1117;
    --bg-secondary: #161b22;
    --sidebar-bg: #161b22;
    --text-primary: #c9d1d9;
    --text-secondary: #8b949e;
    --border-color: #30363d;
    --accent-color: #58a6ff;
    --hover-bg: #21262d;
    --code-bg: #161b22;
    --table-header-bg: #21262d;
    --tag-bg: #21262d;
    --error-color: #f85149;
    --input-bg: #0d1117;
}

:root[data-theme="dark"] .sidebar {
    background-color: #161b22;
}

:root[data-theme="dark"] .main-content {
    background-color: #0d1117;
}

:root[data-theme="dark"] .markdown-body h1,
:root[data-theme="dark"] .markdown-body h2 {
    border-bottom-color: #30363d;
}

:root[data-theme="dark"] .note-card {
    background-color: #161b22;
}

:root[data-theme="dark"] .tag-cloud-item {
    background-color: #21262d;
}

:root[data-theme="dark"] .tag-cloud-item:hover {
    background-color: #58a6ff;
    color: #fff;
}
`
}
