package storage

import (
	"io"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *StorageService
}

func NewHandler(service *StorageService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	storage := r.Group("/storage")
	{
		storage.POST("/upload", h.Upload)
		storage.GET("/download/*key", h.Download)
		storage.DELETE("/objects/*key", h.Delete)
		storage.GET("/objects", h.List)
		storage.GET("/objects/*key/metadata", h.GetMetadata)
		storage.GET("/objects/*key/presigned-url", h.GetPresignedURL)
		storage.POST("/copy", h.Copy)
		storage.PUT("/objects/*key/tags", h.UpdateTags)
		storage.GET("/search", h.SearchByTags)
		storage.GET("/objects/*key/versions", h.ListVersions)
		storage.POST("/batch-delete", h.BatchDelete)
		storage.GET("/stats", h.GetStats)
	}
}

func (h *Handler) Upload(c *gin.Context) {
	key := c.PostForm("key")
	bucket := c.PostForm("bucket")
	contentType := c.PostForm("content_type")

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	file, _, err := c.Request.FormFile("file")
	if err != nil {
		utils.Error(c, errors.InvalidParams("文件上传失败: "+err.Error()))
		return
	}
	defer file.Close()

	userID := c.GetString("user_id")

	metadata := make(map[string]string)
	tags := make(map[string]string)

	req := &UploadRequest{
		Key:         key,
		Bucket:      bucket,
		ContentType: contentType,
		Metadata:    metadata,
		Tags:        tags,
	}

	result, err := h.service.Upload(c.Request.Context(), req, file, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, result)
}

func (h *Handler) Download(c *gin.Context) {
	key := c.Param("key")
	bucket := c.Query("bucket")

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	key = key[1:]

	reader, info, metadata, err := h.service.Download(c.Request.Context(), bucket, key)
	if err != nil {
		utils.Error(c, err)
		return
	}
	defer reader.Close()

	c.Header("Content-Disposition", "attachment; filename="+info.Key)
	c.Header("Content-Type", info.ContentType)
	c.Header("Content-Length", strconv.FormatInt(info.SizeBytes, 10))

	io.Copy(c.Writer, reader)
}

func (h *Handler) Delete(c *gin.Context) {
	key := c.Param("key")
	bucket := c.Query("bucket")

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	key = key[1:]

	if err := h.service.Delete(c.Request.Context(), bucket, key); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "删除成功"})
}

func (h *Handler) List(c *gin.Context) {
	bucket := c.Query("bucket")
	prefix := c.Query("prefix")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	objects, total, err := h.service.List(c.Request.Context(), bucket, prefix, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, objects, total, page, pageSize)
}

func (h *Handler) GetMetadata(c *gin.Context) {
	key := c.Param("key")
	bucket := c.Query("bucket")

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	key = key[1:]

	metadata, err := h.service.GetMetadata(c.Request.Context(), bucket, key)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, metadata)
}

func (h *Handler) GetPresignedURL(c *gin.Context) {
	key := c.Param("key")
	bucket := c.Query("bucket")
	expiresStr := c.DefaultQuery("expires", "3600")

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	key = key[1:]

	expiresSec, _ := strconv.Atoi(expiresStr)
	expires := time.Duration(expiresSec) * time.Second

	url, err := h.service.GetPresignedURL(c.Request.Context(), bucket, key, expires)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"url": url, "expires_at": time.Now().Add(expires)})
}

func (h *Handler) Copy(c *gin.Context) {
	var req struct {
		SrcBucket string `json:"src_bucket"`
		SrcKey    string `json:"src_key" binding:"required"`
		DstBucket string `json:"dst_bucket"`
		DstKey    string `json:"dst_key" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")

	result, err := h.service.Copy(c.Request.Context(), req.SrcBucket, req.SrcKey, req.DstBucket, req.DstKey, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, result)
}

func (h *Handler) UpdateTags(c *gin.Context) {
	key := c.Param("key")
	bucket := c.Query("bucket")

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	key = key[1:]

	var req struct {
		Tags map[string]string `json:"tags" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	if err := h.service.UpdateTags(c.Request.Context(), bucket, key, req.Tags); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "标签更新成功"})
}

func (h *Handler) SearchByTags(c *gin.Context) {
	bucket := c.Query("bucket")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	tags := make(map[string]string)
	for k, v := range c.Request.URL.Query() {
		if len(v) > 0 && k != "bucket" && k != "page" && k != "page_size" {
			tags[k] = v[0]
		}
	}

	objects, total, err := h.service.SearchByTags(c.Request.Context(), bucket, tags, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, objects, total, page, pageSize)
}

func (h *Handler) ListVersions(c *gin.Context) {
	key := c.Param("key")
	bucket := c.Query("bucket")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if key == "" {
		utils.Error(c, errors.InvalidParams("key 是必填参数"))
		return
	}

	key = key[1:]

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	versions, total, err := h.service.ListVersions(c.Request.Context(), bucket, key, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, versions, total, page, pageSize)
}

func (h *Handler) BatchDelete(c *gin.Context) {
	var req struct {
		Bucket string   `json:"bucket"`
		Keys   []string `json:"keys" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	if err := h.service.BatchDelete(c.Request.Context(), req.Bucket, req.Keys); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "批量删除成功", "deleted_count": len(req.Keys)})
}

func (h *Handler) GetStats(c *gin.Context) {
	bucket := c.Query("bucket")

	stats, err := h.service.GetStorageStats(c.Request.Context(), bucket)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, stats)
}
