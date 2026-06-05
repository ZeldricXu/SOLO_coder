package renderer

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *RenderService
}

func NewHandler(service *RenderService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	renderer := r.Group("/renderer")
	{
		renderer.POST("/states", h.CreateState)
		renderer.GET("/states/:id", h.GetState)
		renderer.PUT("/states/:id/config", h.UpdateConfig)
		renderer.PUT("/states/:id/camera", h.UpdateCamera)
		renderer.PUT("/states/:id/viewport", h.UpdateViewport)
		renderer.PUT("/states/:id/tiles", h.SetActiveTiles)
		renderer.DELETE("/states/:id", h.DeleteState)

		renderer.GET("/shaders", h.ListShaders)
		renderer.GET("/shaders/:type", h.GetShader)
		renderer.POST("/shaders", h.RegisterShader)

		renderer.GET("/colormaps", h.ListColorMaps)
		renderer.GET("/colormaps/:name", h.GetColorMap)
		renderer.POST("/colormaps", h.RegisterColorMap)

		renderer.POST("/states/:id/frustum", h.GetFrustum)
		renderer.GET("/config/default", h.GetDefaultConfig)
	}
}

func (h *Handler) CreateState(c *gin.Context) {
	var req struct {
		ID string `json:"id"`
	}

	if err := c.ShouldBindJSON(&req); err != nil || req.ID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid state id"})
		return
	}

	state := h.service.CreateState(req.ID)
	c.JSON(http.StatusOK, state)
}

func (h *Handler) GetState(c *gin.Context) {
	id := c.Param("id")

	state, exists := h.service.GetState(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "state not found"})
		return
	}

	c.JSON(http.StatusOK, state)
}

func (h *Handler) UpdateConfig(c *gin.Context) {
	id := c.Param("id")

	var config RenderConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.UpdateConfig(id, config); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	state, _ := h.service.GetState(id)
	c.JSON(http.StatusOK, state)
}

func (h *Handler) UpdateCamera(c *gin.Context) {
	id := c.Param("id")

	var camera CameraState
	if err := c.ShouldBindJSON(&camera); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.UpdateCamera(id, camera); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	state, _ := h.service.GetState(id)
	c.JSON(http.StatusOK, state)
}

func (h *Handler) UpdateViewport(c *gin.Context) {
	id := c.Param("id")

	var viewport Viewport
	if err := c.ShouldBindJSON(&viewport); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.UpdateViewport(id, viewport); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	state, _ := h.service.GetState(id)
	c.JSON(http.StatusOK, state)
}

func (h *Handler) SetActiveTiles(c *gin.Context) {
	id := c.Param("id")

	var req struct {
		Tiles []string `json:"tiles"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.SetActiveTiles(id, req.Tiles); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	state, _ := h.service.GetState(id)
	c.JSON(http.StatusOK, state)
}

func (h *Handler) DeleteState(c *gin.Context) {
	id := c.Param("id")
	h.service.DeleteState(id)
	c.JSON(http.StatusOK, gin.H{"status": "deleted"})
}

func (h *Handler) ListShaders(c *gin.Context) {
	types := []ShaderType{ShaderPoint, ShaderCircle, ShaderSplat, ShaderHeatmap}
	shaders := make([]ShaderConfig, 0, len(types))

	for _, t := range types {
		if shader, exists := h.service.GetShader(t); exists {
			shaders = append(shaders, shader)
		}
	}

	c.JSON(http.StatusOK, shaders)
}

func (h *Handler) GetShader(c *gin.Context) {
	shaderType := ShaderType(c.Param("type"))

	shader, exists := h.service.GetShader(shaderType)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "shader not found"})
		return
	}

	c.JSON(http.StatusOK, shader)
}

func (h *Handler) RegisterShader(c *gin.Context) {
	var config ShaderConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if config.Type == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "shader type is required"})
		return
	}

	h.service.RegisterShader(config)
	c.JSON(http.StatusOK, config)
}

func (h *Handler) ListColorMaps(c *gin.Context) {
	names := h.service.ListColorMaps()
	colorMaps := make([]*ColorMap, 0, len(names))

	for _, name := range names {
		if cm, exists := h.service.GetColorMap(name); exists {
			colorMaps = append(colorMaps, cm)
		}
	}

	c.JSON(http.StatusOK, colorMaps)
}

func (h *Handler) GetColorMap(c *gin.Context) {
	name := c.Param("name")

	cm, exists := h.service.GetColorMap(name)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "colormap not found"})
		return
	}

	c.JSON(http.StatusOK, cm)
}

func (h *Handler) RegisterColorMap(c *gin.Context) {
	var cm ColorMap
	if err := c.ShouldBindJSON(&cm); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if cm.Name == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "colormap name is required"})
		return
	}

	h.service.RegisterColorMap(&cm)
	c.JSON(http.StatusOK, cm)
}

func (h *Handler) GetFrustum(c *gin.Context) {
	id := c.Param("id")

	frustum, err := h.service.GetViewFrustum(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	planes := make([][4]float64, 6)
	for i, plane := range frustum.Planes {
		planes[i] = [4]float64{plane.X, plane.Y, plane.Z, plane.W}
	}

	c.JSON(http.StatusOK, gin.H{
		"planes": planes,
	})
}

func (h *Handler) GetDefaultConfig(c *gin.Context) {
	config := h.service.DefaultConfig()
	c.JSON(http.StatusOK, config)
}
