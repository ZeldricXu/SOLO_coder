package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"meeting-system/pkg/notification"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type MeetingDocHandler struct{}

func NewMeetingDocHandler() *MeetingDocHandler {
	return &MeetingDocHandler{}
}

type UpdateDocRequest struct {
	Agenda  string `json:"agenda"`
	Content string `json:"content"`
	Summary string `json:"summary"`
}

func defaultAgenda() string {
	return `## 会议议程

### 1. 上次会议待办跟进
- 

### 2. 本期主要议题
- 

### 3. 讨论与决策
- 

### 4. 下次会议安排
- 
`
}

func (h *MeetingDocHandler) GetByBooking(c *gin.Context) {
	bookingID, err := uuid.Parse(c.Param("bookingId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var doc model.MeetingDoc
	if err := database.DB.Where("booking_id = ?", bookingID).First(&doc).Error; err != nil {
		var booking model.Booking
		if err := database.DB.First(&booking, bookingID).Error; err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
			return
		}

		doc = model.MeetingDoc{
			ID:        uuid.New(),
			BookingID: bookingID,
			Agenda:    defaultAgenda(),
			Content:   "",
		}
		database.DB.Create(&doc)
	}

	c.JSON(http.StatusOK, doc)
}

func (h *MeetingDocHandler) Update(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid doc ID"})
		return
	}

	var req UpdateDocRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var doc model.MeetingDoc
	if err := database.DB.First(&doc, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Meeting doc not found"})
		return
	}

	if doc.IsArchived {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Document is archived and cannot be edited"})
		return
	}

	if req.Agenda != "" {
		doc.Agenda = req.Agenda
	}
	if req.Content != "" {
		doc.Content = req.Content
	}
	if req.Summary != "" {
		doc.Summary = req.Summary
	}

	if err := database.DB.Save(&doc).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, doc)
}

func extractTodos(content string) []string {
	var todos []string
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "- [ ]") || strings.HasPrefix(trimmed, "TODO:") || strings.HasPrefix(trimmed, "待办:") {
			todo := strings.TrimPrefix(trimmed, "- [ ]")
			todo = strings.TrimPrefix(todo, "TODO:")
			todo = strings.TrimPrefix(todo, "待办:")
			todo = strings.TrimSpace(todo)
			if todo != "" {
				todos = append(todos, todo)
			}
		}
	}
	return todos
}

func (h *MeetingDocHandler) Archive(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid doc ID"})
		return
	}

	var doc model.MeetingDoc
	if err := database.DB.First(&doc, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Meeting doc not found"})
		return
	}

	if doc.IsArchived {
		c.JSON(http.StatusOK, doc)
		return
	}

	now := time.Now()
	doc.IsArchived = true
	doc.ArchivedAt = &now

	todoTexts := extractTodos(doc.Content)
	userID := getUserID(c)
	for _, todoText := range todoTexts {
		todo := model.Todo{
			ID:         uuid.New(),
			DocID:      doc.ID,
			BookingID:  doc.BookingID,
			Content:    todoText,
			AssigneeID: userID,
			Status:     "pending",
		}
		database.DB.Create(&todo)

		go notification.SendTodoAssign(userID, "新待办分配", todoText)
	}

	if doc.Summary == "" {
		if len(todoTexts) > 0 {
			doc.Summary = "本次会议共产生 " + strconv.Itoa(len(todoTexts)) + " 项待办事项。"
		} else {
			doc.Summary = "会议纪要已归档。"
		}
	}

	if err := database.DB.Save(&doc).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, doc)
}
