package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/dailynote"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/editor"
	"github.com/solocoder/knowledgebase/internal/export"
	"github.com/solocoder/knowledgebase/internal/fsnotify"
	"github.com/solocoder/knowledgebase/internal/graph"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/internal/plugin"
	"github.com/solocoder/knowledgebase/internal/search"
	"github.com/solocoder/knowledgebase/internal/tags"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type Server struct {
	cfg          *config.Config
	db           *db.Database
	markdown     *markdown.MarkdownParser
	search       *search.SearchEngine
	hybridEngine *search.HybridSearchEngine
	watcher      *fsnotify.Watcher
	tagManager   *tags.TagManager
	folderMgr    *tags.FolderManager
	filterMgr    *tags.FilterManager
	exporter     *export.ExportManager
	dailyNote    *dailynote.DailyNoteManager
	pluginMgr    *plugin.PluginManager
	graph        *graph.Graph
	graphInter   *graph.GraphInteraction
	editor       *editor.Editor
	webDir       string

	upgrader websocket.Upgrader
	clients  map[*websocket.Conn]bool
}

func New(cfg *config.Config) (*Server, error) {
	database, err := db.New(cfg)
	if err != nil {
		return nil, fmt.Errorf("failed to init database: %w", err)
	}

	mdParser := markdown.NewParser(cfg)
	searchEngine := search.NewSearchEngine(database, cfg)
	watcher := fsnotify.NewWatcher(cfg, database)
	tagMgr := tags.NewTagManager(database, cfg)
	folderMgr := tags.NewFolderManager(database, cfg)
	filterMgr := tags.NewFilterManager(database, cfg)
	exporter := export.NewManager(cfg, database)
	dailyNoteMgr := dailynote.NewDailyNoteManager(cfg, database)
	pluginMgr := plugin.NewPluginManager(cfg, database, searchEngine, tagMgr)
	graphEngine := graph.New(cfg)
	graphInteraction := graph.NewGraphInteraction(graphEngine, database, cfg)
	editorEngine := editor.New(database, mdParser, cfg)

	webDir := resolveWebDir()

	var hybridEngine *search.HybridSearchEngine
	if cfg.Search.EnableSemantic {
		embeddingClient := search.NewEmbeddingClient(cfg.Search.OllamaBaseURL, cfg.Search.EmbeddingModel)
		vectorIndex := search.NewVectorIndex(cfg.Search.VectorIndexPath)
		hybridEngine = search.NewHybridSearchEngine(searchEngine, embeddingClient, vectorIndex)
		hybridEngine.SetWeights(cfg.Search.BM25Weight, cfg.Search.VectorWeight)
	}

	srv := &Server{
		cfg:          cfg,
		db:           database,
		markdown:     mdParser,
		search:       searchEngine,
		hybridEngine: hybridEngine,
		watcher:      watcher,
		tagManager:   tagMgr,
		folderMgr:    folderMgr,
		filterMgr:    filterMgr,
		exporter:     exporter,
		dailyNote:    dailyNoteMgr,
		pluginMgr:    pluginMgr,
		graph:        graphEngine,
		graphInter:   graphInteraction,
		editor:       editorEngine,
		webDir:       webDir,
		clients:      make(map[*websocket.Conn]bool),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin: func(r *http.Request) bool {
				return true
			},
		},
	}

	watcher.SetOnEvent(srv.onFileEvents)
	return srv, nil
}

func (s *Server) Start() error {
	if err := os.MkdirAll(s.cfg.VaultPath, 0755); err != nil {
		return err
	}

	added, updated, err := s.watcher.InitialScan()
	if err != nil {
		log.Printf("Initial scan error: %v", err)
	}
	log.Printf("Initial scan complete: added=%d, updated=%d", added, updated)

	if err := s.watcher.Start(); err != nil {
		return err
	}

	if err := s.pluginMgr.Init(); err != nil {
		log.Printf("Plugin init error: %v", err)
	}

	if err := s.graph.BuildFromDB(s.db); err != nil {
		log.Printf("Graph build error: %v", err)
	}

	mux := http.NewServeMux()
	s.registerRoutes(mux)

	addr := fmt.Sprintf("%s:%d", s.cfg.Server.Host, s.cfg.Server.Port)
	log.Printf("Server starting on http://%s", addr)
	return http.ListenAndServe(addr, mux)
}

func (s *Server) Stop() {
	if s.hybridEngine != nil {
		s.hybridEngine.StopBackgroundIndexing()
	}
	s.watcher.Stop()
	s.pluginMgr.Shutdown()
	s.db.Close()
}

func (s *Server) registerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/health", s.handleHealth)
	mux.HandleFunc("/api/config", s.handleConfig)

	mux.HandleFunc("/api/notes", s.handleNotes)
	mux.HandleFunc("/api/notes/", s.handleNote)
	mux.HandleFunc("/api/notes/search", s.handleSearch)
	mux.HandleFunc("/api/notes/content", s.handleNoteContent)
	mux.HandleFunc("/api/notes/backlinks", s.handleBacklinks)

	mux.HandleFunc("/api/search/semantic/status", s.handleSemanticStatus)
	mux.HandleFunc("/api/search/semantic/reindex", s.handleReindexVectors)

	mux.HandleFunc("/api/tags", s.handleTags)
	mux.HandleFunc("/api/tags/", s.handleTag)
	mux.HandleFunc("/api/tags/autocomplete", s.handleTagAutocomplete)

	mux.HandleFunc("/api/folders", s.handleFolders)

	mux.HandleFunc("/api/graph", s.handleGraph)
	mux.HandleFunc("/api/graph/layout", s.handleGraphLayout)
	mux.HandleFunc("/api/graph/node/", s.handleGraphNode)
	mux.HandleFunc("/api/graph/action", s.handleGraphAction)

	mux.HandleFunc("/api/export", s.handleExport)

	mux.HandleFunc("/api/daily/today", s.handleDailyToday)
	mux.HandleFunc("/api/daily/", s.handleDailyNote)
	mux.HandleFunc("/api/templates", s.handleTemplates)
	mux.HandleFunc("/api/templates/context", s.handleTemplateContext)
	mux.HandleFunc("/api/templates/render", s.handleRenderTemplate)
	mux.HandleFunc("/api/templates/preview", s.handleTemplatePreview)
	mux.HandleFunc("/api/todos", s.handleTodos)

	mux.HandleFunc("/api/plugins", s.handlePlugins)
	mux.HandleFunc("/api/plugins/", s.handlePlugin)
	mux.HandleFunc("/api/plugins/marketplace", s.handlePluginMarketplace)

	mux.HandleFunc("/api/editor/render", s.handleEditorRender)
	mux.HandleFunc("/api/editor/autocomplete", s.handleEditorAutocomplete)
	mux.HandleFunc("/api/editor/command", s.handleEditorCommand)

	mux.HandleFunc("/ws", s.handleWebSocket)

	mux.HandleFunc("/", s.handleFrontend)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	s.jsonResponse(w, map[string]interface{}{
		"status": "ok",
		"vault":  s.cfg.VaultPath,
	})
}

func (s *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "GET":
		s.jsonResponse(w, s.cfg)
	case "PUT":
		var newCfg config.Config
		if err := json.NewDecoder(r.Body).Decode(&newCfg); err != nil {
			s.errorResponse(w, err)
			return
		}
		config.Save(&newCfg, "")
		s.cfg = &newCfg
		s.jsonResponse(w, s.cfg)
	}
}

func (s *Server) handleNotes(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "GET":
		notes, err := s.db.GetAllNotes()
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, notes)
	}
}

func (s *Server) handleNote(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path[len("/api/notes/"):]

	switch r.Method {
	case "GET":
		note, err := s.db.GetNoteByPath(path)
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		content, _ := os.ReadFile(filepath.Join(s.cfg.VaultPath, path))
		note.Content = string(content)
		s.jsonResponse(w, note)

	case "POST", "PUT":
		var req struct {
			Content string `json:"content"`
		}
		json.NewDecoder(r.Body).Decode(&req)

		fullPath := filepath.Join(s.cfg.VaultPath, path)
		os.MkdirAll(filepath.Dir(fullPath), 0755)

		if err := os.WriteFile(fullPath, []byte(req.Content), 0644); err != nil {
			s.errorResponse(w, err)
			return
		}

		result, _ := s.markdown.Parse(req.Content, path)
		note := &models.Note{
			Path:      path,
			Title:     result.Title,
			Hash:      utils.Hash(req.Content),
			WordCount: utils.CountWords(req.Content),
		}
		for _, t := range result.Tags {
			note.Tags = append(note.Tags, models.Tag{Name: t})
		}
		s.db.SaveNote(note)

		if s.hybridEngine != nil {
			s.hybridEngine.IndexNoteWithVector(note.ID, note.Title, req.Content)
		} else {
			s.search.IndexNote(note.ID, note.Title, req.Content)
		}

		links := []models.Link{}
		for _, l := range result.Links {
			links = append(links, models.Link{
				SourcePath: path,
				TargetPath: l.Target + ".md",
				AnchorText: l.Display,
				LineNum:    l.LineNum,
			})
		}
		s.db.SaveLinks(note.ID, links)

		s.jsonResponse(w, note)

	case "DELETE":
		fullPath := filepath.Join(s.cfg.VaultPath, path)
		if err := os.Remove(fullPath); err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, map[string]string{"status": "deleted"})
	}
}

func (s *Server) handleNoteContent(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Query().Get("path")
	fullPath := filepath.Join(s.cfg.VaultPath, path)

	content, err := os.ReadFile(fullPath)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	result, err := s.markdown.Parse(string(content), path)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	s.jsonResponse(w, map[string]interface{}{
		"content":   string(content),
		"html":      result.HTML,
		"title":     result.Title,
		"tags":      result.Tags,
		"links":     result.Links,
		"wordCount": utils.CountWords(string(content)),
	})
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("pageSize"))

	if page == 0 {
		page = 1
	}
	if pageSize == 0 {
		pageSize = 20
	}

	sq := search.SearchQuery{
		Query:       query,
		Page:        page,
		PageSize:    pageSize,
		EnableFuzzy: true,
	}

	var results []models.SearchResult
	var total int
	var err error

	if s.hybridEngine != nil {
		results, total, err = s.hybridEngine.Search(sq)
	} else {
		results, total, err = s.search.Search(sq)
	}

	if err != nil {
		s.errorResponse(w, err)
		return
	}

	s.jsonResponse(w, map[string]interface{}{
		"results": results,
		"total":   total,
		"page":    page,
		"pages":   (total + pageSize - 1) / pageSize,
	})
}

func (s *Server) handleSemanticStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	enabled := s.hybridEngine != nil
	ollamaAvailable := false
	var total, completed int
	var running bool

	if enabled {
		ollamaAvailable = s.hybridEngine.IsOllamaAvailable()
		total, completed, running = s.hybridEngine.GetIndexingProgress()
	}

	s.jsonResponse(w, map[string]interface{}{
		"enabled":          enabled,
		"ollama_available": ollamaAvailable,
		"indexing_progress": map[string]interface{}{
			"total":     total,
			"completed": completed,
			"running":   running,
		},
	})
}

func (s *Server) handleReindexVectors(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	if s.hybridEngine == nil {
		s.errorResponse(w, fmt.Errorf("semantic search is not enabled"))
		return
	}

	s.hybridEngine.StopBackgroundIndexing()
	s.hybridEngine.StartBackgroundIndexing(s.db)

	s.jsonResponse(w, map[string]interface{}{
		"status":  "reindexing_started",
		"message": "Background vector reindexing has been initiated",
	})
}

func (s *Server) handleBacklinks(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Query().Get("path")
	note, err := s.db.GetNoteByPath(path)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	backlinks, err := s.db.GetBacklinks(note.ID)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	s.jsonResponse(w, backlinks)
}

func (s *Server) handleTags(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "GET":
		tags, err := s.tagManager.GetAllTags()
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, tags)
	case "POST":
		var tag models.Tag
		json.NewDecoder(r.Body).Decode(&tag)
		created, err := s.tagManager.CreateTag(tag.Name, tag.ParentID, tag.Color)
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, created)
	}
}

func (s *Server) handleTag(w http.ResponseWriter, r *http.Request) {
	idStr := r.URL.Path[len("/api/tags/"):]
	id, _ := strconv.Atoi(idStr)

	switch r.Method {
	case "GET":
		tag, err := s.tagManager.GetTag(uint(id))
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, tag)
	case "PUT":
		var tag models.Tag
		json.NewDecoder(r.Body).Decode(&tag)
		updated, err := s.tagManager.UpdateTag(uint(id), tag.Name, tag.ParentID, tag.Color)
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, updated)
	case "DELETE":
		s.tagManager.DeleteTag(uint(id))
		s.jsonResponse(w, map[string]string{"status": "deleted"})
	}
}

func (s *Server) handleTagAutocomplete(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit == 0 {
		limit = 10
	}
	tags, err := s.tagManager.Autocomplete(query, limit)
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, tags)
}

func (s *Server) handleFolders(w http.ResponseWriter, r *http.Request) {
	folders, err := s.folderMgr.GetFolderTree()
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, folders)
}

func (s *Server) handleGraph(w http.ResponseWriter, r *http.Request) {
	if err := s.graph.BuildFromDB(s.db); err != nil {
		s.errorResponse(w, err)
		return
	}
	s.graph.UpdateNodeSizes()
	s.graph.InitRandomPositions(800, 600)
	s.graph.Layout(nil)
	data := s.graph.ToGraphData()
	s.jsonResponse(w, data)
}

func (s *Server) handleGraphLayout(w http.ResponseWriter, r *http.Request) {
	if r.Method == "POST" {
		var req struct {
			Type   string             `json:"type"`
			Config *graph.LayoutConfig `json:"config"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			s.errorResponse(w, err)
			return
		}

		cfg := req.Config
		if cfg == nil {
			cfg = graph.DefaultLayoutConfig()
		}

		switch models.LayoutType(req.Type) {
		case models.LayoutCircular:
			s.graph.CircularLayout(cfg)
		case models.LayoutHierarchical:
			s.graph.HierarchicalLayout(cfg)
		default:
			s.graph.Layout(cfg)
		}

		data := s.graph.ToGraphData()
		s.jsonResponse(w, data)
		return
	}

	s.graph.Layout(nil)
	data := s.graph.ToGraphData()
	s.jsonResponse(w, data)
}

func (s *Server) handleGraphNode(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path[len("/api/graph/node/"):]
	parts := strings.SplitN(path, "/", 3)

	if len(parts) < 2 || parts[1] != "preview" {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	id, err := strconv.Atoi(parts[0])
	if err != nil {
		s.errorResponse(w, fmt.Errorf("invalid node id"))
		return
	}

	preview, err := s.graphInter.GetNodePreview(uint(id))
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, preview)
}

func (s *Server) handleGraphAction(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var action models.GraphAction
	if err := json.NewDecoder(r.Body).Decode(&action); err != nil {
		s.errorResponse(w, err)
		return
	}

	switch action.Type {
	case "addLink":
		targetID, ok := action.Data["target_id"].(float64)
		if !ok {
			s.errorResponse(w, fmt.Errorf("target_id is required"))
			return
		}
		if err := s.graphInter.AddLink(action.NodeID, uint(targetID)); err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, map[string]string{"status": "ok"})

	case "removeLink":
		targetID, ok := action.Data["target_id"].(float64)
		if !ok {
			s.errorResponse(w, fmt.Errorf("target_id is required"))
			return
		}
		if err := s.graphInter.RemoveLink(action.NodeID, uint(targetID)); err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, map[string]string{"status": "ok"})

	case "rename":
		newTitle, ok := action.Data["new_title"].(string)
		if !ok {
			s.errorResponse(w, fmt.Errorf("new_title is required"))
			return
		}
		if err := s.graphInter.RenameNode(action.NodeID, newTitle); err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, map[string]string{"status": "ok"})

	case "delete":
		if err := s.graphInter.DeleteNode(action.NodeID); err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, map[string]string{"status": "ok"})

	case "createSummary":
		rawIDs, ok := action.Data["node_ids"].([]interface{})
		if !ok {
			s.errorResponse(w, fmt.Errorf("node_ids is required"))
			return
		}
		var nodeIDs []uint
		for _, raw := range rawIDs {
			if id, ok := raw.(float64); ok {
				nodeIDs = append(nodeIDs, uint(id))
			}
		}
		note, err := s.graphInter.CreateSummaryFromNodes(nodeIDs)
		if err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, note)

	default:
		s.errorResponse(w, fmt.Errorf("unknown action type: %s", action.Type))
	}
}

func (s *Server) handleExport(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var opts export.ExportOptions
	json.NewDecoder(r.Body).Decode(&opts)

	if err := s.exporter.Export(opts); err != nil {
		s.errorResponse(w, err)
		return
	}

	s.jsonResponse(w, map[string]string{
		"status": "success",
		"path":   opts.OutputPath,
	})
}

func (s *Server) handleDailyToday(w http.ResponseWriter, r *http.Request) {
	info, err := s.dailyNote.Today()
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	var content string
	if !info.Exists {
		info, content, err = s.dailyNote.CreateToday()
		if err != nil {
			s.errorResponse(w, err)
			return
		}
	} else {
		data, err := os.ReadFile(info.Path)
		if err == nil {
			content = string(data)
		}
	}

	s.jsonResponse(w, map[string]interface{}{
		"info":    info,
		"content": content,
	})
}

func (s *Server) handleDailyNote(w http.ResponseWriter, r *http.Request) {
	dateStr := r.URL.Path[len("/api/daily/"):]
	date, err := time.Parse("2006-01-02", dateStr)
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	info, err := s.dailyNote.GetNote(date)
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, info)
}

func (s *Server) handleTemplates(w http.ResponseWriter, r *http.Request) {
	templates, err := s.dailyNote.GetTemplateManager().ListTemplates()
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, templates)
}

func (s *Server) handleTemplateContext(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	ctx := s.dailyNote.GetTemplateManager().GetContext()
	s.jsonResponse(w, ctx)
}

func (s *Server) handleRenderTemplate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		Content   string            `json:"content"`
		Variables map[string]string `json:"variables"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.errorResponse(w, err)
		return
	}

	result, err := s.dailyNote.GetTemplateManager().RenderContent(req.Content, req.Variables)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	s.jsonResponse(w, map[string]string{"result": result})
}

func (s *Server) handleTemplatePreview(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		TemplateID string `json:"template_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.errorResponse(w, err)
		return
	}

	tpl, err := s.dailyNote.GetTemplateManager().GetTemplate(req.TemplateID)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	ctx := s.dailyNote.GetTemplateManager().GetContext()
	result, err := s.dailyNote.GetTemplateManager().RenderScriptTemplate(tpl.Content, ctx)
	if err != nil {
		result, err = s.dailyNote.GetTemplateManager().RenderContent(tpl.Content, nil)
		if err != nil {
			s.errorResponse(w, err)
			return
		}
	}

	s.jsonResponse(w, map[string]interface{}{
		"template_id": req.TemplateID,
		"result":      result,
	})
}

func (s *Server) handleTodos(w http.ResponseWriter, r *http.Request) {
	todos, err := s.dailyNote.GetTodoExtractor().ExtractAll(true)
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, todos)
}

func (s *Server) handlePlugins(w http.ResponseWriter, r *http.Request) {
	plugins := s.pluginMgr.GetInstalledPlugins()
	s.jsonResponse(w, plugins)
}

func (s *Server) handlePlugin(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Path[len("/api/plugins/"):]

	switch r.Method {
	case "GET":
		plugin, ok := s.pluginMgr.GetInstalledPlugin(id)
		if !ok {
			http.Error(w, "Plugin not found", http.StatusNotFound)
			return
		}
		s.jsonResponse(w, plugin)
	case "POST":
		if id == "install" {
			var req struct {
				ID string `json:"id"`
			}
			json.NewDecoder(r.Body).Decode(&req)
			p, err := s.pluginMgr.InstallPlugin(req.ID)
			if err != nil {
				s.errorResponse(w, err)
				return
			}
			s.jsonResponse(w, p)
		}
	case "DELETE":
		if err := s.pluginMgr.UninstallPlugin(id); err != nil {
			s.errorResponse(w, err)
			return
		}
		s.jsonResponse(w, map[string]string{"status": "uninstalled"})
	}
}

func (s *Server) handlePluginMarketplace(w http.ResponseWriter, r *http.Request) {
	category := r.URL.Query().Get("category")
	keyword := r.URL.Query().Get("keyword")
	plugins := s.pluginMgr.GetMarketplacePlugins(category, keyword)
	s.jsonResponse(w, plugins)
}

func (s *Server) handleEditorRender(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Content string `json:"content"`
		Path    string `json:"path"`
	}
	json.NewDecoder(r.Body).Decode(&req)

	result, err := s.markdown.Parse(req.Content, req.Path)
	if err != nil {
		s.errorResponse(w, err)
		return
	}

	s.jsonResponse(w, map[string]interface{}{
		"html":  result.HTML,
		"title": result.Title,
		"tags":  result.Tags,
		"links": result.Links,
	})
}

func (s *Server) handleEditorAutocomplete(w http.ResponseWriter, r *http.Request) {
	content := r.URL.Query().Get("content")
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))

	result, err := s.editor.GetAutocomplete().GetCompletions(content, offset)
	if err != nil {
		s.errorResponse(w, err)
		return
	}
	s.jsonResponse(w, result)
}

func (s *Server) handleEditorCommand(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Command string                 `json:"command"`
		Content string                 `json:"content"`
		Args    map[string]interface{} `json:"args"`
	}
	json.NewDecoder(r.Body).Decode(&req)

	s.jsonResponse(w, map[string]interface{}{
		"content": req.Content,
		"command": req.Command,
	})
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("WebSocket upgrade error:", err)
		return
	}
	defer conn.Close()

	s.clients[conn] = true
	defer delete(s.clients, conn)

	for {
		_, _, err := conn.ReadMessage()
		if err != nil {
			break
		}
	}
}

func (s *Server) onFileEvents(events []*models.FileEvent) {
	data, _ := json.Marshal(map[string]interface{}{
		"type":   "file_events",
		"events": events,
	})

	for client := range s.clients {
		client.WriteMessage(websocket.TextMessage, data)
	}
}

func (s *Server) handleFrontend(w http.ResponseWriter, r *http.Request) {
	indexPath := filepath.Join(s.webDir, "index.html")
	content, err := os.ReadFile(indexPath)
	if err != nil {
		http.Error(w, "Frontend not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write(content)
}

func resolveWebDir() string {
	ex, err := os.Executable()
	if err == nil {
		exDir := filepath.Dir(ex)
		webDir := filepath.Join(exDir, "web")
		if _, err := os.Stat(webDir); err == nil {
			return webDir
		}
		webDir = filepath.Join(filepath.Dir(exDir), "web")
		if _, err := os.Stat(webDir); err == nil {
			return webDir
		}
	}

	cwd, err := os.Getwd()
	if err == nil {
		webDir := filepath.Join(cwd, "web")
		if _, err := os.Stat(webDir); err == nil {
			return webDir
		}
	}

	return "web"
}

func (s *Server) jsonResponse(w http.ResponseWriter, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(data)
}

func (s *Server) errorResponse(w http.ResponseWriter, err error) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusInternalServerError)
	json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}
