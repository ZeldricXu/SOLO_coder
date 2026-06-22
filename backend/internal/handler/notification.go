package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type NotificationHandler struct{}

func NewNotificationHandler() *NotificationHandler {
	return &NotificationHandler{}
}

type UpdatePreferencesRequest struct {
	BookingConfirm bool   `json:"booking_confirm"`
	UpcomingRemind bool   `json:"upcoming_remind"`
	MinutesRelease bool   `json:"minutes_release"`
	TodoAssign     bool   `json:"todo_assign"`
	Channels       string `json:"channels"`
}

func (h *NotificationHandler) List(c *gin.Context) {
	userID := getUserID(c)

	status := c.Query("status")
	notifType := c.Query("type")

	var notifications []model.Notification
	query := database.DB.Where("user_id = ?", userID)

	if status != "" {
		query = query.Where("status = ?", status)
	}
	if notifType != "" {
		query = query.Where("type = ?", notifType)
	}

	pageSize := 20
	query = query.Order("created_at desc").Limit(pageSize)

	if err := query.Find(&notifications).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, notifications)
}

func (h *NotificationHandler) MarkRead(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid notification ID"})
		return
	}

	userID := getUserID(c)

	var notification model.Notification
	if err := database.DB.Where("id = ? AND user_id = ?", id, userID).First(&notification).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Notification not found"})
		return
	}

	now := time.Now()
	notification.Status = "read"
	notification.ReadAt = &now
	database.DB.Save(&notification)

	c.JSON(http.StatusOK, notification)
}

func (h *NotificationHandler) MarkAllRead(c *gin.Context) {
	userID := getUserID(c)

	now := time.Now()
	database.DB.Model(&model.Notification{}).
		Where("user_id = ? AND status = ?", userID, "unread").
		Updates(map[string]interface{}{
			"status":  "read",
			"read_at": now,
		})

	c.JSON(http.StatusOK, gin.H{"message": "All notifications marked as read"})
}

func (h *NotificationHandler) GetPreferences(c *gin.Context) {
	userID := getUserID(c)

	var prefs model.NotificationPreference
	if err := database.DB.Where("user_id = ?", userID).First(&prefs).Error; err != nil {
		prefs = model.NotificationPreference{
			ID:             uuid.New(),
			UserID:         userID,
			BookingConfirm: true,
			UpcomingRemind: true,
			MinutesRelease: true,
			TodoAssign:     true,
			Channels:       "wechat,email",
		}
		database.DB.Create(&prefs)
	}

	c.JSON(http.StatusOK, prefs)
}

func (h *NotificationHandler) UpdatePreferences(c *gin.Context) {
	userID := getUserID(c)

	var req UpdatePreferencesRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var prefs model.NotificationPreference
	if err := database.DB.Where("user_id = ?", userID).First(&prefs).Error; err != nil {
		prefs = model.NotificationPreference{
			ID:     uuid.New(),
			UserID: userID,
		}
	}

	prefs.BookingConfirm = req.BookingConfirm
	prefs.UpcomingRemind = req.UpcomingRemind
	prefs.MinutesRelease = req.MinutesRelease
	prefs.TodoAssign = req.TodoAssign
	if req.Channels != "" {
		prefs.Channels = req.Channels
	}

	if err := database.DB.Save(&prefs).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, prefs)
}
