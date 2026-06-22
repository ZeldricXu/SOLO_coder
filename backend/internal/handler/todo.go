package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type TodoHandler struct{}

func NewTodoHandler() *TodoHandler {
	return &TodoHandler{}
}

type CreateTodoRequest struct {
	Content    string `json:"content" binding:"required"`
	AssigneeID string `json:"assignee_id" binding:"required"`
	DueDate    string `json:"due_date"`
	Priority   int    `json:"priority"`
}

type UpdateTodoRequest struct {
	Content    string `json:"content"`
	Status     string `json:"status"`
	AssigneeID string `json:"assignee_id"`
	DueDate    string `json:"due_date"`
	Priority   int    `json:"priority"`
}

func (h *TodoHandler) ListByDoc(c *gin.Context) {
	docID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid doc ID"})
		return
	}

	var todos []model.Todo
	if err := database.DB.Where("doc_id = ?", docID).Preload("Assignee").Order("created_at").Find(&todos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, todos)
}

func (h *TodoHandler) MyTodos(c *gin.Context) {
	userID := getUserID(c)

	status := c.Query("status")

	var todos []model.Todo
	query := database.DB.Where("assignee_id = ?", userID).Preload("Assignee")

	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Order("priority desc, created_at desc").Find(&todos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, todos)
}

func (h *TodoHandler) Create(c *gin.Context) {
	docID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid doc ID"})
		return
	}

	var req CreateTodoRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	assigneeUUID, err := uuid.Parse(req.AssigneeID)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid assignee ID"})
		return
	}

	var doc model.MeetingDoc
	if err := database.DB.First(&doc, docID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Meeting doc not found"})
		return
	}

	todo := model.Todo{
		ID:         uuid.New(),
		DocID:      docID,
		BookingID:  doc.BookingID,
		Content:    req.Content,
		AssigneeID: assigneeUUID,
		Status:     "pending",
		Priority:   req.Priority,
	}

	if req.DueDate != "" {
		dueDate, err := time.ParseInLocation("2006-01-02", req.DueDate, time.Local)
		if err == nil {
			todo.DueDate = &dueDate
		}
	}

	if err := database.DB.Create(&todo).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, todo)
}

func (h *TodoHandler) Update(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid todo ID"})
		return
	}

	var req UpdateTodoRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var todo model.Todo
	if err := database.DB.First(&todo, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Todo not found"})
		return
	}

	if req.Content != "" {
		todo.Content = req.Content
	}
	if req.Status != "" {
		todo.Status = req.Status
	}
	if req.AssigneeID != "" {
		assigneeUUID, err := uuid.Parse(req.AssigneeID)
		if err == nil {
			todo.AssigneeID = assigneeUUID
		}
	}
	if req.Priority > 0 {
		todo.Priority = req.Priority
	}
	if req.DueDate != "" {
		dueDate, err := time.ParseInLocation("2006-01-02", req.DueDate, time.Local)
		if err == nil {
			todo.DueDate = &dueDate
		}
	}

	if err := database.DB.Save(&todo).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, todo)
}

func (h *TodoHandler) Delete(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid todo ID"})
		return
	}

	if err := database.DB.Delete(&model.Todo{}, id).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Todo deleted"})
}
