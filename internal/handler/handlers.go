package handler

import (
	"context"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/pkg/utils"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type AuthHandler struct {
	authSvc *service.AuthService
}

func NewAuthHandler(authSvc *service.AuthService) *AuthHandler {
	return &AuthHandler{authSvc: authSvc}
}

type LoginReq struct {
	TenantNamespace string `json:"tenant_namespace" binding:"required"`
	Username        string `json:"username" binding:"required"`
	Password        string `json:"password" binding:"required"`
}

func (h *AuthHandler) Login(c *gin.Context) {
	var req LoginReq
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	result, err := h.authSvc.Login(c.Request.Context(), &service.LoginRequest{
		TenantNamespace: req.TenantNamespace,
		Username:        req.Username,
		Password:        req.Password,
		ClientIP:        c.ClientIP(),
	})
	if err != nil {
		response.Unauthorized(c, err.Error())
		return
	}
	response.Success(c, result)
}

type RegisterReq struct {
	TenantName      string `json:"tenant_name" binding:"required"`
	TenantDomain    string `json:"tenant_domain"`
	TenantNamespace string `json:"tenant_namespace" binding:"required"`
	Username        string `json:"username" binding:"required,min=3"`
	Email           string `json:"email" binding:"required,email"`
	Password        string `json:"password" binding:"required,min=8"`
	FullName        string `json:"full_name" binding:"required"`
}

func (h *AuthHandler) Register(c *gin.Context) {
	var req RegisterReq
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	result, err := h.authSvc.Register(c.Request.Context(), &service.RegisterRequest{
		TenantName:      req.TenantName,
		TenantDomain:    req.TenantDomain,
		TenantNamespace: req.TenantNamespace,
		Username:        req.Username,
		Email:           req.Email,
		Password:        req.Password,
		FullName:        req.FullName,
	})
	if err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	response.Success(c, result)
}

func (h *AuthHandler) RefreshToken(c *gin.Context) {
	authHeader := c.GetHeader("Authorization")
	parts := strings.SplitN(authHeader, " ", 2)
	if len(parts) != 2 {
		response.Unauthorized(c, "invalid token")
		return
	}

	token, expiresAt, err := h.authSvc.RefreshToken(c.Request.Context(), parts[1])
	if err != nil {
		response.Unauthorized(c, err.Error())
		return
	}
	response.Success(c, gin.H{
		"token":      token,
		"expires_at": expiresAt,
	})
}

func (h *AuthHandler) CreateAPIToken(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	var req service.CreateAPITokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	req.UserID = user.ID
	req.TenantID = tenant.ID

	result, err := h.authSvc.CreateAPIToken(c.Request.Context(), &req)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, result)
}

type DocumentHandler struct {
	docSvc  *service.DocumentService
	searchSvc *service.SearchService
	ieSvc   *service.ImportExportService
	userRepo interface{ GetUserGroups(context.Context, uuid.UUID) ([]model.UserGroup, error) }
}

func NewDocumentHandler(
	docSvc *service.DocumentService,
	searchSvc *service.SearchService,
	ieSvc *service.ImportExportService,
	userRepo interface{ GetUserGroups(context.Context, uuid.UUID) ([]model.UserGroup, error) },
) *DocumentHandler {
	return &DocumentHandler{
		docSvc:   docSvc,
		searchSvc: searchSvc,
		ieSvc:    ieSvc,
		userRepo: userRepo,
	}
}

type CreateDocReq struct {
	SpaceID     uuid.UUID              `json:"space_id" binding:"required"`
	DirectoryID *uuid.UUID             `json:"directory_id"`
	Title       string                 `json:"title" binding:"required"`
	Content     map[string]interface{} `json:"content"`
	Summary     string                 `json:"summary"`
	Tags        []string               `json:"tags"`
	Language    string                 `json:"language"`
	TemplateID  *uuid.UUID             `json:"template_id"`
	IsPinned    bool                   `json:"is_pinned"`
}

func (h *DocumentHandler) Create(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	var req CreateDocReq
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	pmDoc := model.ProseMirrorDoc{Type: "doc"}
	if req.Content != nil {
		if typ, ok := req.Content["type"].(string); ok {
			pmDoc.Type = typ
		}
		if content, ok := req.Content["content"].([]interface{}); ok {
			pmDoc.Content = content
		}
		if attrs, ok := req.Content["attrs"].(map[string]interface{}); ok {
			pmDoc.Attrs = attrs
		}
	}
	if pmDoc.Content == nil {
		pmDoc.Content = []interface{}{map[string]interface{}{
			"type": "paragraph",
			"content": []interface{}{map[string]interface{}{
				"type": "text", "text": "",
			}},
		}}
	}

	language := req.Language
	if language == "" {
		language = "zh-CN"
	}

	doc, err := h.docSvc.Create(c.Request.Context(), &service.CreateDocumentRequest{
		TenantID:    tenant.ID,
		SpaceID:     req.SpaceID,
		DirectoryID: req.DirectoryID,
		Title:       req.Title,
		Content:     pmDoc,
		Summary:     req.Summary,
		Tags:        req.Tags,
		Language:    language,
		AuthorID:    user.ID,
		TemplateID:  req.TemplateID,
		IsPinned:    req.IsPinned,
	})
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, doc)
}

func (h *DocumentHandler) Get(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	if user == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	groups, _ := h.userRepo.GetUserGroups(c.Request.Context(), user.ID)
	groupIDs := make([]uuid.UUID, 0, len(groups))
	for _, g := range groups {
		groupIDs = append(groupIDs, g.ID)
	}
	var deptIDs []uuid.UUID
	if user.DepartmentID != nil {
		deptIDs = append(deptIDs, *user.DepartmentID)
	}

	doc, err := h.docSvc.GetByID(c.Request.Context(), user.ID, groupIDs, deptIDs, docID)
	if err != nil {
		response.NotFound(c, "document not found")
		return
	}
	response.Success(c, doc)
}

type UpdateDocReq struct {
	Title       *string                `json:"title"`
	Content     *map[string]interface{} `json:"content"`
	Summary     *string                `json:"summary"`
	Tags        *[]string              `json:"tags"`
	DirectoryID *uuid.UUID             `json:"directory_id"`
	Status      *string                `json:"status"`
	IsPinned    *bool                  `json:"is_pinned"`
	ChangeLog   string                 `json:"change_log"`
}

func (h *DocumentHandler) Update(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	if user == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	var req UpdateDocReq
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	updateReq := &service.UpdateDocumentRequest{
		DocID:     docID,
		EditorID:  user.ID,
		ChangeLog: req.ChangeLog,
	}

	if req.Title != nil {
		updateReq.Title = req.Title
	}
	if req.Summary != nil {
		updateReq.Summary = req.Summary
	}
	if req.Tags != nil {
		updateReq.Tags = req.Tags
	}
	if req.DirectoryID != nil {
		updateReq.DirectoryID = req.DirectoryID
	}
	if req.Status != nil {
		status := model.DocumentStatus(*req.Status)
		updateReq.Status = &status
	}
	if req.IsPinned != nil {
		updateReq.IsPinned = req.IsPinned
	}
	if req.Content != nil {
		pmDoc := model.ProseMirrorDoc{Type: "doc"}
		if typ, ok := (*req.Content)["type"].(string); ok {
			pmDoc.Type = typ
		}
		if content, ok := (*req.Content)["content"].([]interface{}); ok {
			pmDoc.Content = content
		}
		if attrs, ok := (*req.Content)["attrs"].(map[string]interface{}); ok {
			pmDoc.Attrs = attrs
		}
		updateReq.Content = &pmDoc
	}

	doc, err := h.docSvc.Update(c.Request.Context(), updateReq)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, doc)
}

func (h *DocumentHandler) Delete(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	if user == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}

	if err := h.docSvc.Delete(c.Request.Context(), user.ID, docID); err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *DocumentHandler) List(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	q := &model.DocumentQuery{
		TenantID:  tenant.ID,
		Page:      parseInt(c.Query("page"), 1),
		PageSize:  parseInt(c.Query("page_size"), 20),
		SortBy:    c.DefaultQuery("sort_by", "created_at"),
		SortOrder: c.DefaultQuery("sort_order", "desc"),
	}

	if sid := c.Query("space_id"); sid != "" {
		q.SpaceID, _ = uuid.Parse(sid)
	}
	if did := c.Query("directory_id"); did != "" {
		id, _ := uuid.Parse(did)
		q.DirectoryID = &id
	}
	if status := c.Query("status"); status != "" {
		q.Status = model.DocumentStatus(status)
	}
	if tag := c.Query("tag"); tag != "" {
		q.Tag = tag
	}
	if kw := c.Query("keyword"); kw != "" {
		q.Keyword = kw
	}
	if lang := c.Query("language"); lang != "" {
		q.Language = lang
	}
	if aid := c.Query("author_id"); aid != "" {
		q.AuthorID, _ = uuid.Parse(aid)
	}
	if p := c.Query("is_pinned"); p != "" {
		pinned := p == "true" || p == "1"
		q.IsPinned = &pinned
	}

	groups, _ := h.userRepo.GetUserGroups(c.Request.Context(), user.ID)
	groupIDs := make([]uuid.UUID, 0, len(groups))
	for _, g := range groups {
		groupIDs = append(groupIDs, g.ID)
	}
	var deptIDs []uuid.UUID
	if user.DepartmentID != nil {
		deptIDs = append(deptIDs, *user.DepartmentID)
	}

	result, err := h.docSvc.List(c.Request.Context(), user.ID, groupIDs, deptIDs, q)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.SuccessWithPagination(c, result.Data, result.Page, result.PageSize, result.Total)
}

func (h *DocumentHandler) UploadAttachment(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	spaceID, err := uuid.Parse(c.PostForm("space_id"))
	if err != nil {
		response.BadRequest(c, "invalid space_id")
		return
	}

	var docID *uuid.UUID
	if did := c.PostForm("document_id"); did != "" {
		id, err := uuid.Parse(did)
		if err == nil {
			docID = &id
		}
	}

	file, header, err := c.Request.FormFile("file")
	if err != nil {
		response.BadRequest(c, "file required: "+err.Error())
		return
	}
	defer file.Close()

	contentType := header.Header.Get("Content-Type")
	att, err := h.docSvc.UploadAttachment(c.Request.Context(),
		tenant.ID, spaceID, docID, user.ID,
		header.Filename, header.Size, contentType, file,
	)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, att)
}

func (h *DocumentHandler) GetDiff(c *gin.Context) {
	docID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		response.BadRequest(c, "invalid document id")
		return
	}
	v1 := parseInt(c.Query("v1"), 0)
	v2 := parseInt(c.Query("v2"), 0)
	if v1 == 0 || v2 == 0 {
		response.BadRequest(c, "v1 and v2 required")
		return
	}

	diff, err := h.docSvc.GetDiff(c.Request.Context(), docID, v1, v2)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"diff": diff})
}

func (h *DocumentHandler) Search(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	groups, _ := h.userRepo.GetUserGroups(c.Request.Context(), user.ID)
	groupIDs := make([]uuid.UUID, 0, len(groups))
	for _, g := range groups {
		groupIDs = append(groupIDs, g.ID)
	}
	var deptIDs []uuid.UUID
	if user.DepartmentID != nil {
		deptIDs = append(deptIDs, *user.DepartmentID)
	}

	var spaceID uuid.UUID
	if sid := c.Query("space_id"); sid != "" {
		spaceID, _ = uuid.Parse(sid)
	}

	result, err := h.searchSvc.Search(c.Request.Context(), &service.SearchRequest{
		Query:              c.Query("q"),
		TenantID:           tenant.ID,
		UserID:             user.ID,
		GroupIDs:           groupIDs,
		DeptIDs:            deptIDs,
		SpaceID:            spaceID,
		Tags:               strings.Split(c.Query("tags"), ","),
		LangCode:           c.Query("lang"),
		Page:               parseInt(c.Query("page"), 1),
		PageSize:           parseInt(c.Query("page_size"), 20),
		SortBy:             c.DefaultQuery("sort_by", "_score"),
		SortOrder:          c.DefaultQuery("sort_order", "desc"),
		TimeDecay:          c.Query("time_decay") != "false",
	})
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, result)
}

func (h *DocumentHandler) ImportMarkdown(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	spaceID, err := uuid.Parse(c.PostForm("space_id"))
	if err != nil {
		response.BadRequest(c, "invalid space_id")
		return
	}
	var dirID *uuid.UUID
	if did := c.PostForm("directory_id"); did != "" {
		id, _ := uuid.Parse(did)
		dirID = &id
	}

	file, header, err := c.Request.FormFile("file")
	if err != nil {
		content := c.PostForm("content")
		if content == "" {
			response.BadRequest(c, "file or content required")
			return
		}
		result, err := h.ieSvc.ImportMarkdown(c.Request.Context(), &service.MarkdownImportRequest{
			TenantID:    tenant.ID,
			SpaceID:     spaceID,
			DirectoryID: dirID,
			CreatorID:   user.ID,
			Content:     content,
			FileName:    c.DefaultPostForm("filename", "document.md"),
		})
		if err != nil {
			response.InternalError(c, err.Error())
			return
		}
		response.Success(c, result)
		return
	}
	defer file.Close()

	content, err := io.ReadAll(file)
	if err != nil {
		response.InternalError(c, "read file: "+err.Error())
		return
	}

	result, err := h.ieSvc.ImportMarkdown(c.Request.Context(), &service.MarkdownImportRequest{
		TenantID:    tenant.ID,
		SpaceID:     spaceID,
		DirectoryID: dirID,
		CreatorID:   user.ID,
		Content:     string(content),
		FileName:    header.Filename,
	})
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, result)
}

func (h *DocumentHandler) ExportJSON(c *gin.Context) {
	tenant := middleware.GetCurrentTenant(c)
	if tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	var spaceIDs []uuid.UUID
	if s := c.Query("space_ids"); s != "" {
		for _, idStr := range strings.Split(s, ",") {
			if id, err := uuid.Parse(strings.TrimSpace(idStr)); err == nil {
				spaceIDs = append(spaceIDs, id)
			}
		}
	}
	var docIDs []uuid.UUID
	if d := c.Query("doc_ids"); d != "" {
		for _, idStr := range strings.Split(d, ",") {
			if id, err := uuid.Parse(strings.TrimSpace(idStr)); err == nil {
				docIDs = append(docIDs, id)
			}
		}
	}

	data, err := h.ieSvc.ExportToJSON(c.Request.Context(), tenant.ID, spaceIDs, docIDs)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	c.Header("Content-Type", "application/json")
	c.Header("Content-Disposition", "attachment; filename=knowledgebase-export-"+time.Now().Format("20060102")+".json")
	c.Writer.Write(data)
}

func (h *DocumentHandler) ExportHTML(c *gin.Context) {
	tenant := middleware.GetCurrentTenant(c)
	if tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	var docIDs []uuid.UUID
	if d := c.Query("doc_ids"); d != "" {
		for _, idStr := range strings.Split(d, ",") {
			if id, err := uuid.Parse(strings.TrimSpace(idStr)); err == nil {
				docIDs = append(docIDs, id)
			}
		}
	}

	themeConfig := make(map[string]string)
	if primary := c.Query("primary_color"); primary != "" {
		themeConfig["primary_color"] = primary
	}

	result, err := h.ieSvc.ExportToHTML(c.Request.Context(), &service.ExportHTMLRequest{
		DocIDs:      docIDs,
		TenantID:    tenant.ID,
		IncludeCSS:  c.Query("no_css") != "true",
		IncludeJS:   c.Query("with_js") == "true",
		ThemeConfig: themeConfig,
	})
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	if len(result) == 1 {
		for _, html := range result {
			c.Header("Content-Type", "text/html; charset=utf-8")
			c.Writer.Write([]byte(html))
			return
		}
	}
	response.Success(c, result)
}

func (h *DocumentHandler) Suggest(c *gin.Context) {
	tenant := middleware.GetCurrentTenant(c)
	if tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}
	query := c.Query("q")
	limit := parseInt(c.Query("limit"), 10)

	suggestions, err := h.searchSvc.Suggest(c.Request.Context(), tenant.ID, query, limit)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"suggestions": suggestions})
}

type OTHandler struct {
	otSvc *service.OTService
}

func NewOTHandler(otSvc *service.OTService) *OTHandler {
	return &OTHandler{otSvc: otSvc}
}

type SubmitOpsReq struct {
	ClientID    string                   `json:"client_id" binding:"required"`
	BaseVersion int                      `json:"base_version" binding:"required"`
	Operations  []map[string]interface{} `json:"operations"`
	Timestamp   int64                    `json:"timestamp"`
}

func (h *OTHandler) SubmitOps(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("doc_id"))
	if err != nil {
		response.BadRequest(c, "invalid doc_id")
		return
	}

	var req SubmitOpsReq
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	result, err := h.otSvc.SubmitOps(c.Request.Context(), &service.SubmitOpsRequest{
		TenantID:    tenant.ID,
		DocID:       docID,
		UserID:      user.ID,
		ClientID:    req.ClientID,
		BaseVersion: req.BaseVersion,
		Operations:  req.Operations,
		Timestamp:   req.Timestamp,
	})
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, result)
}

func (h *OTHandler) Connect(c *gin.Context) {
	user := middleware.GetCurrentUser(c)
	tenant := middleware.GetCurrentTenant(c)
	if user == nil || tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("doc_id"))
	if err != nil {
		response.BadRequest(c, "invalid doc_id")
		return
	}
	clientID := c.Query("client_id")
	if clientID == "" {
		clientID = utils.GenerateSlug(uuid.New().String())
	}

	version, err := h.otSvc.ConnectUser(c.Request.Context(), tenant.ID, docID, user.ID, clientID)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}

	ver, ops, _ := h.otSvc.GetDocumentState(c.Request.Context(), tenant.ID, docID)
	_ = ver

	response.Success(c, gin.H{
		"client_id":     clientID,
		"current_ver":   version,
		"pending_ops":   ops,
		"document_ver":  ver,
	})
}

func (h *OTHandler) Disconnect(c *gin.Context) {
	tenant := middleware.GetCurrentTenant(c)
	if tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("doc_id"))
	if err != nil {
		response.BadRequest(c, "invalid doc_id")
		return
	}
	clientID := c.Query("client_id")
	h.otSvc.DisconnectUser(c.Request.Context(), tenant.ID, docID, clientID)
	response.Success(c, nil)
}

func (h *OTHandler) Presence(c *gin.Context) {
	tenant := middleware.GetCurrentTenant(c)
	if tenant == nil {
		response.Unauthorized(c, "unauthorized")
		return
	}

	docID, err := uuid.Parse(c.Param("doc_id"))
	if err != nil {
		response.BadRequest(c, "invalid doc_id")
		return
	}

	users, err := h.otSvc.GetPresence(c.Request.Context(), tenant.ID, docID)
	if err != nil {
		response.InternalError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"users": users})
}

type SpaceHandler struct {
	spaceRepo interface {
		Create(ctx context.Context, space *model.Space) error
		GetByID(ctx context.Context, id uuid.UUID) (*model.Space, error)
		List(ctx context.Context, tenantID uuid.UUID, ids []uuid.UUID, status model.SpaceStatus, keyword string, page, pageSize int) (*gin.H, error)
		Update(ctx context.Context, space *model.Space) error
		Delete(ctx context.Context, id uuid.UUID) error
	}
	permRepo interface {
		GrantRole(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID, subjectType model.SubjectType, subjectID uuid.UUID, role model.Role, grantedBy uuid.UUID) error
	}
}

func NewSpaceHandler(
	spaceRepo interface {
		Create(ctx context.Context, space *model.Space) error
		GetByID(ctx context.Context, id uuid.UUID) (*model.Space, error)
		List(ctx context.Context, tenantID uuid.UUID, ids []uuid.UUID, status model.SpaceStatus, keyword string, page, pageSize int) (*gin.H, error)
		Update(ctx context.Context, space *model.Space) error
		Delete(ctx context.Context, id uuid.UUID) error
	},
	permRepo interface {
		GrantRole(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID, subjectType model.SubjectType, subjectID uuid.UUID, role model.Role, grantedBy uuid.UUID) error
	},
) *SpaceHandler {
	return &SpaceHandler{spaceRepo: spaceRepo, permRepo: permRepo}
}

type CreateSpaceReq struct {
	Name        string `json:"name" binding:"required"`
	Namespace   string `json:"namespace" binding:"required"`
	Description string `json:"description"`
	Icon        string `json:"icon"`
	Color       string `json:"color"`
	Visibility  string `json:"visibility"`
}

func parseInt(s string, def int) int {
	if s == "" {
		return def
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return v
}
