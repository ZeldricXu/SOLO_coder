package dailynote

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/dop251/goja"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
)

type RecentNote struct {
	Title      string `json:"title"`
	Path       string `json:"path"`
	ModifiedAt string `json:"modified_at"`
}

type TagInfo struct {
	Name  string   `json:"name"`
	Count int      `json:"count"`
	Notes []string `json:"notes"`
}

type TagCloudItem struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
	Size  int    `json:"size"`
}

type NotesContext struct {
	Total  int            `json:"total"`
	Recent []RecentNote   `json:"recent"`
	ByTag  map[string]int `json:"by_tag"`
}

type TagsContext struct {
	All   []TagInfo      `json:"all"`
	Cloud []TagCloudItem `json:"cloud"`
}

type DateContext struct {
	Now      string `json:"now"`
	Yesterday string `json:"yesterday"`
	Tomorrow  string `json:"tomorrow"`
	Weekday   string `json:"weekday"`
	Year      string `json:"year"`
	Month     string `json:"month"`
	Day       string `json:"day"`
}

type TemplateContext struct {
	Notes       NotesContext      `json:"notes"`
	Tags        TagsContext       `json:"tags"`
	RecentFiles []RecentNote      `json:"recent_files"`
	Date        DateContext       `json:"date"`
	Custom      map[string]string `json:"custom"`
	Weather     string            `json:"weather"`
}

type tagCountEntry struct {
	name  string
	count int
}

type ScriptTemplateEngine struct {
	vm       *goja.Runtime
	db       *db.Database
	cfg      *config.Config
	resolver *VariableResolver
}

func NewScriptTemplateEngine(database *db.Database, cfg *config.Config) *ScriptTemplateEngine {
	vm := goja.New()

	ste := &ScriptTemplateEngine{
		vm:       vm,
		db:       database,
		cfg:      cfg,
		resolver: NewVariableResolver(),
	}

	ste.registerHelpers()
	ste.injectEmptyContext()

	return ste
}

func setArrayItem(arr *goja.Object, index int, value interface{}) {
	arr.Set(strconv.Itoa(index), value)
}

func (ste *ScriptTemplateEngine) registerHelpers() {
	ste.vm.Set("$tags", func(call goja.FunctionCall) goja.Value {
		pattern := ""
		if len(call.Arguments) > 0 {
			pattern = call.Arguments[0].String()
		}
		result := ste.helperTags(pattern)
		v := ste.vm.NewArray()
		for i, item := range result {
			setArrayItem(v, i, ste.vm.ToValue(item))
		}
		return v
	})

	ste.vm.Set("$notesByTag", func(call goja.FunctionCall) goja.Value {
		tag := ""
		if len(call.Arguments) > 0 {
			tag = call.Arguments[0].String()
		}
		result := ste.helperNotesByTag(tag)
		v := ste.vm.NewArray()
		for i, item := range result {
			obj := ste.vm.NewObject()
			obj.Set("title", item.Title)
			obj.Set("path", item.Path)
			obj.Set("modified_at", item.ModifiedAt)
			setArrayItem(v, i, obj)
		}
		return v
	})

	ste.vm.Set("$recent", func(call goja.FunctionCall) goja.Value {
		days := 7
		if len(call.Arguments) > 0 {
			days = int(call.Arguments[0].ToInteger())
		}
		result := ste.helperRecent(days)
		v := ste.vm.NewArray()
		for i, item := range result {
			obj := ste.vm.NewObject()
			obj.Set("title", item.Title)
			obj.Set("path", item.Path)
			obj.Set("modified_at", item.ModifiedAt)
			setArrayItem(v, i, obj)
		}
		return v
	})

	ste.vm.Set("$formatDate", func(call goja.FunctionCall) goja.Value {
		dateStr := ""
		layout := "2006-01-02"
		if len(call.Arguments) > 0 {
			dateStr = call.Arguments[0].String()
		}
		if len(call.Arguments) > 1 {
			layout = call.Arguments[1].String()
		}
		result := ste.helperFormatDate(dateStr, layout)
		return ste.vm.ToValue(result)
	})

	ste.vm.Set("$weather", func(call goja.FunctionCall) goja.Value {
		result := ste.helperWeather()
		return ste.vm.ToValue(result)
	})
}

func (ste *ScriptTemplateEngine) injectEmptyContext() {
	ctx := &TemplateContext{
		Notes: NotesContext{
			Total:  0,
			Recent: []RecentNote{},
			ByTag:  map[string]int{},
		},
		Tags: TagsContext{
			All:   []TagInfo{},
			Cloud: []TagCloudItem{},
		},
		RecentFiles: []RecentNote{},
		Date:        ste.buildDateContext(),
		Custom:      map[string]string{},
		Weather:     "晴",
	}
	ste.InjectContext(ctx)
}

func (ste *ScriptTemplateEngine) buildDateContext() DateContext {
	now := time.Now()
	weekdays := []string{"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"}
	return DateContext{
		Now:       now.Format("2006-01-02"),
		Yesterday: now.AddDate(0, 0, -1).Format("2006-01-02"),
		Tomorrow:  now.AddDate(0, 0, 1).Format("2006-01-02"),
		Weekday:   weekdays[now.Weekday()],
		Year:      now.Format("2006"),
		Month:     now.Format("01"),
		Day:       now.Format("02"),
	}
}

func (ste *ScriptTemplateEngine) Render(content string, customVars map[string]string) (string, error) {
	resolver := NewVariableResolver()
	if customVars != nil {
		resolver.SetCustom(customVars)
	}

	result, err := resolver.Resolve(content)
	if err != nil {
		return "", err
	}

	if !strings.Contains(result, "<%") {
		return result, nil
	}

	return ste.renderScript(result)
}

var (
	reScriptTag = regexp.MustCompile(`<%=?([\s\S]*?)%>`)
)

func (ste *ScriptTemplateEngine) renderScript(content string) (string, error) {
	var jsBuilder strings.Builder
	jsBuilder.WriteString("var __output = '';\n")

	lastEnd := 0
	matches := reScriptTag.FindAllStringSubmatchIndex(content, -1)

	for _, loc := range matches {
		fullStart := loc[0]
		fullEnd := loc[1]
		groupStart := loc[2]
		groupEnd := loc[3]

		if fullStart > lastEnd {
			text := content[lastEnd:fullStart]
			escaped := strings.ReplaceAll(text, `\`, `\\`)
			escaped = strings.ReplaceAll(escaped, `'`, `\'`)
			escaped = strings.ReplaceAll(escaped, "\n", `\n`)
			escaped = strings.ReplaceAll(escaped, "\r", `\r`)
			jsBuilder.WriteString("__output += '" + escaped + "';\n")
		}

		tagContent := content[groupStart:groupEnd]
		fullTag := content[fullStart:fullEnd]

		if strings.HasPrefix(fullTag, "<%=") {
			jsBuilder.WriteString("__output += String(" + strings.TrimSpace(tagContent) + ");\n")
		} else {
			jsBuilder.WriteString(tagContent + "\n")
		}

		lastEnd = fullEnd
	}

	if lastEnd < len(content) {
		text := content[lastEnd:]
		escaped := strings.ReplaceAll(text, `\`, `\\`)
		escaped = strings.ReplaceAll(escaped, `'`, `\'`)
		escaped = strings.ReplaceAll(escaped, "\n", `\n`)
		escaped = strings.ReplaceAll(escaped, "\r", `\r`)
		jsBuilder.WriteString("__output += '" + escaped + "';\n")
	}

	jsProgram := jsBuilder.String()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	done := make(chan struct{})
	var result string
	var execErr error

	go func() {
		defer func() {
			if r := recover(); r != nil {
				execErr = fmt.Errorf("%v", r)
			}
			close(done)
		}()

		_, err := ste.vm.RunString(jsProgram)
		if err != nil {
			execErr = err
			return
		}

		val := ste.vm.Get("__output")
		if val != goja.Undefined() {
			result = val.String()
		}
	}()

	select {
	case <-done:
		if execErr != nil {
			return fmt.Sprintf("<!-- script error: %s -->", execErr.Error()), nil
		}
		return result, nil
	case <-ctx.Done():
		ste.vm.Interrupt("execution timeout")
		return "", fmt.Errorf("script execution timeout (5s)")
	}
}

func (ste *ScriptTemplateEngine) InjectContext(ctx *TemplateContext) {
	obj := ste.vm.NewObject()

	notesObj := ste.vm.NewObject()
	notesObj.Set("total", ctx.Notes.Total)

	recentArr := ste.vm.NewArray()
	for i, r := range ctx.Notes.Recent {
		item := ste.vm.NewObject()
		item.Set("title", r.Title)
		item.Set("path", r.Path)
		item.Set("modified_at", r.ModifiedAt)
		setArrayItem(recentArr, i, item)
	}
	notesObj.Set("recent", recentArr)

	byTagObj := ste.vm.NewObject()
	for k, v := range ctx.Notes.ByTag {
		byTagObj.Set(k, v)
	}
	notesObj.Set("by_tag", byTagObj)
	obj.Set("notes", notesObj)

	tagsObj := ste.vm.NewObject()
	allArr := ste.vm.NewArray()
	for i, t := range ctx.Tags.All {
		tagObj := ste.vm.NewObject()
		tagObj.Set("name", t.Name)
		tagObj.Set("count", t.Count)
		notesArr := ste.vm.NewArray()
		for j, n := range t.Notes {
			setArrayItem(notesArr, j, n)
		}
		tagObj.Set("notes", notesArr)
		setArrayItem(allArr, i, tagObj)
	}
	tagsObj.Set("all", allArr)

	cloudArr := ste.vm.NewArray()
	for i, c := range ctx.Tags.Cloud {
		cloudObj := ste.vm.NewObject()
		cloudObj.Set("name", c.Name)
		cloudObj.Set("count", c.Count)
		cloudObj.Set("size", c.Size)
		setArrayItem(cloudArr, i, cloudObj)
	}
	tagsObj.Set("cloud", cloudArr)
	obj.Set("tags", tagsObj)

	recentFilesArr := ste.vm.NewArray()
	for i, r := range ctx.RecentFiles {
		item := ste.vm.NewObject()
		item.Set("title", r.Title)
		item.Set("path", r.Path)
		item.Set("modified_at", r.ModifiedAt)
		setArrayItem(recentFilesArr, i, item)
	}
	obj.Set("recent_files", recentFilesArr)

	dateObj := ste.vm.NewObject()
	dateObj.Set("now", ctx.Date.Now)
	dateObj.Set("yesterday", ctx.Date.Yesterday)
	dateObj.Set("tomorrow", ctx.Date.Tomorrow)
	dateObj.Set("weekday", ctx.Date.Weekday)
	dateObj.Set("year", ctx.Date.Year)
	dateObj.Set("month", ctx.Date.Month)
	dateObj.Set("day", ctx.Date.Day)
	obj.Set("date", dateObj)

	customObj := ste.vm.NewObject()
	for k, v := range ctx.Custom {
		customObj.Set(k, v)
	}
	obj.Set("custom", customObj)

	obj.Set("weather", ctx.Weather)

	ste.vm.Set("context", obj)
}

func (ste *ScriptTemplateEngine) BuildContext() *TemplateContext {
	ctx := &TemplateContext{
		Notes: NotesContext{
			Recent: []RecentNote{},
			ByTag:  map[string]int{},
		},
		Tags: TagsContext{
			All:   []TagInfo{},
			Cloud: []TagCloudItem{},
		},
		RecentFiles: []RecentNote{},
		Date:        ste.buildDateContext(),
		Custom:      map[string]string{},
		Weather:     "晴",
	}

	if ste.db != nil {
		ste.buildNotesContext(ctx)
		ste.buildTagsContext(ctx)
		ste.buildRecentFiles(ctx)
	}

	return ctx
}

func (ste *ScriptTemplateEngine) buildNotesContext(ctx *TemplateContext) {
	var total int
	err := ste.db.QueryRow("SELECT COUNT(*) FROM notes").Scan(&total)
	if err != nil {
		total = 0
	}
	ctx.Notes.Total = total

	rows, err := ste.db.Query("SELECT title, path, updated_at FROM notes ORDER BY updated_at DESC LIMIT 20")
	if err != nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var title, path string
		var updatedAt time.Time
		if err := rows.Scan(&title, &path, &updatedAt); err != nil {
			continue
		}
		ctx.Notes.Recent = append(ctx.Notes.Recent, RecentNote{
			Title:      title,
			Path:       path,
			ModifiedAt: updatedAt.Format("2006-01-02 15:04:05"),
		})
	}

	tagRows, err := ste.db.Query(`
		SELECT t.name, COUNT(nt.note_id) as cnt
		FROM tags t
		LEFT JOIN note_tags nt ON t.id = nt.tag_id
		GROUP BY t.id, t.name
		ORDER BY cnt DESC
	`)
	if err != nil {
		return
	}
	defer tagRows.Close()

	for tagRows.Next() {
		var name string
		var count int
		if err := tagRows.Scan(&name, &count); err != nil {
			continue
		}
		ctx.Notes.ByTag[name] = count
	}
}

func (ste *ScriptTemplateEngine) buildTagsContext(ctx *TemplateContext) {
	tagRows, err := ste.db.Query(`
		SELECT t.name, COUNT(nt.note_id) as cnt
		FROM tags t
		LEFT JOIN note_tags nt ON t.id = nt.tag_id
		GROUP BY t.id, t.name
		ORDER BY cnt DESC
	`)
	if err != nil {
		return
	}
	defer tagRows.Close()

	var tagCounts []tagCountEntry

	for tagRows.Next() {
		var name string
		var count int
		if err := tagRows.Scan(&name, &count); err != nil {
			continue
		}
		tagCounts = append(tagCounts, tagCountEntry{name: name, count: count})

		noteRows, err := ste.db.Query(`
			SELECT n.title
			FROM notes n
			INNER JOIN note_tags nt ON n.id = nt.note_id
			INNER JOIN tags t ON nt.tag_id = t.id
			WHERE t.name = ?
			ORDER BY n.updated_at DESC
			LIMIT 20
		`, name)
		if err != nil {
			ctx.Tags.All = append(ctx.Tags.All, TagInfo{Name: name, Count: count, Notes: []string{}})
			continue
		}
		var noteTitles []string
		for noteRows.Next() {
			var title string
			if err := noteRows.Scan(&title); err != nil {
				continue
			}
			noteTitles = append(noteTitles, title)
		}
		noteRows.Close()
		if noteTitles == nil {
			noteTitles = []string{}
		}
		ctx.Tags.All = append(ctx.Tags.All, TagInfo{Name: name, Count: count, Notes: noteTitles})
	}

	ctx.Tags.Cloud = buildTagCloud(tagCounts)
}

func buildTagCloud(items []tagCountEntry) []TagCloudItem {
	if len(items) == 0 {
		return []TagCloudItem{}
	}

	result := make([]TagCloudItem, len(items))
	for i, item := range items {
		result[i] = TagCloudItem{
			Name:  item.name,
			Count: item.count,
		}
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i].Count > result[j].Count
	})

	n := len(result)
	for i := range result {
		pct := 0
		if n > 1 {
			pct = i * 100 / (n - 1)
		}
		switch {
		case pct <= 20:
			result[i].Size = 5
		case pct <= 40:
			result[i].Size = 4
		case pct <= 60:
			result[i].Size = 3
		case pct <= 80:
			result[i].Size = 2
		default:
			result[i].Size = 1
		}
	}

	return result
}

func (ste *ScriptTemplateEngine) buildRecentFiles(ctx *TemplateContext) {
	rows, err := ste.db.Query("SELECT title, path, updated_at FROM notes ORDER BY updated_at DESC LIMIT 20")
	if err != nil {
		return
	}
	defer rows.Close()

	for rows.Next() {
		var title, path string
		var updatedAt time.Time
		if err := rows.Scan(&title, &path, &updatedAt); err != nil {
			continue
		}
		ctx.RecentFiles = append(ctx.RecentFiles, RecentNote{
			Title:      title,
			Path:       path,
			ModifiedAt: updatedAt.Format("2006-01-02 15:04:05"),
		})
	}
}

func (ste *ScriptTemplateEngine) helperTags(pattern string) []string {
	if ste.db == nil {
		return []string{}
	}

	tags, err := ste.db.GetAllTags()
	if err != nil {
		return []string{}
	}

	var result []string
	for _, tag := range tags {
		if pattern == "" || strings.Contains(strings.ToLower(tag.Name), strings.ToLower(pattern)) {
			result = append(result, tag.Name)
		}
	}
	return result
}

func (ste *ScriptTemplateEngine) helperNotesByTag(tag string) []RecentNote {
	if ste.db == nil || tag == "" {
		return []RecentNote{}
	}

	tagObj, err := ste.db.GetTagByName(tag)
	if err != nil {
		return []RecentNote{}
	}

	rows, err := ste.db.Query(`
		SELECT n.title, n.path, n.updated_at
		FROM notes n
		INNER JOIN note_tags nt ON n.id = nt.note_id
		WHERE nt.tag_id = ?
		ORDER BY n.updated_at DESC
		LIMIT 20
	`, tagObj.ID)
	if err != nil {
		return []RecentNote{}
	}
	defer rows.Close()

	var result []RecentNote
	for rows.Next() {
		var title, path string
		var updatedAt time.Time
		if err := rows.Scan(&title, &path, &updatedAt); err != nil {
			continue
		}
		result = append(result, RecentNote{
			Title:      title,
			Path:       path,
			ModifiedAt: updatedAt.Format("2006-01-02 15:04:05"),
		})
	}
	return result
}

func (ste *ScriptTemplateEngine) helperRecent(days int) []RecentNote {
	if ste.db == nil {
		return []RecentNote{}
	}

	cutoff := time.Now().AddDate(0, 0, -days)
	rows, err := ste.db.Query(`
		SELECT title, path, updated_at
		FROM notes
		WHERE updated_at >= ?
		ORDER BY updated_at DESC
		LIMIT 50
	`, cutoff)
	if err != nil {
		return []RecentNote{}
	}
	defer rows.Close()

	var result []RecentNote
	for rows.Next() {
		var title, path string
		var updatedAt time.Time
		if err := rows.Scan(&title, &path, &updatedAt); err != nil {
			continue
		}
		result = append(result, RecentNote{
			Title:      title,
			Path:       path,
			ModifiedAt: updatedAt.Format("2006-01-02 15:04:05"),
		})
	}
	return result
}

func (ste *ScriptTemplateEngine) helperFormatDate(dateStr, layout string) string {
	t, err := time.Parse("2006-01-02", dateStr)
	if err != nil {
		t, err = time.Parse("2006-01-02 15:04:05", dateStr)
		if err != nil {
			return dateStr
		}
	}
	return t.Format(layout)
}

func (ste *ScriptTemplateEngine) helperWeather() string {
	if ste.db == nil {
		return "晴"
	}
	return "晴"
}
