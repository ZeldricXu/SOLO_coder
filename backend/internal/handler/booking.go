package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"meeting-system/pkg/notification"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type BookingHandler struct{}

func NewBookingHandler() *BookingHandler {
	return &BookingHandler{}
}

type CreateBookingRequest struct {
	RoomID        string   `json:"room_id" binding:"required"`
	Title         string   `json:"title" binding:"required"`
	Description   string   `json:"description"`
	StartTime     string   `json:"start_time" binding:"required"`
	EndTime       string   `json:"end_time" binding:"required"`
	RecurringRule string   `json:"recurring_rule"`
	Attendees     []string `json:"attendees"`
}

type UpdateBookingRequest struct {
	Title       string `json:"title"`
	Description string `json:"description"`
	StartTime   string `json:"start_time"`
	EndTime     string `json:"end_time"`
}

func (h *BookingHandler) List(c *gin.Context) {
	var bookings []model.Booking
	query := database.DB.Preload("Room").Preload("User")

	roomID := c.Query("room_id")
	if roomID != "" {
		query = query.Where("room_id = ?", roomID)
	}

	status := c.Query("status")
	if status != "" {
		query = query.Where("status = ?", status)
	}

	date := c.Query("date")
	if date != "" {
		d, _ := time.ParseInLocation("2006-01-02", date, time.Local)
		startOfDay := time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, time.Local)
		endOfDay := startOfDay.AddDate(0, 0, 1)
		query = query.Where("start_time < ? AND end_time > ?", endOfDay, startOfDay)
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	offset := (page - 1) * pageSize

	var total int64
	query.Model(&model.Booking{}).Count(&total)

	if err := query.Order("start_time desc").Offset(offset).Limit(pageSize).Find(&bookings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"data":      bookings,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

func (h *BookingHandler) MyBookings(c *gin.Context) {
	userID := getUserID(c)

	status := c.Query("status")

	var bookings []model.Booking
	query := database.DB.Where("user_id = ?", userID).Preload("Room").Preload("User")

	if status != "" {
		query = query.Where("status = ?", status)
	} else {
		query = query.Where("status IN ?", []string{"confirmed", "pending"})
	}

	if err := query.Order("start_time desc").Find(&bookings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, bookings)
}

func (h *BookingHandler) Get(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var booking model.Booking
	if err := database.DB.Preload("Room").Preload("User").First(&booking, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
		return
	}

	c.JSON(http.StatusOK, booking)
}

func checkTimeConflict(db *gorm.DB, roomID uuid.UUID, startTime, endTime time.Time, excludeID ...uuid.UUID) (bool, error) {
	var count int64
	query := db.Model(&model.Booking{}).Where(
		"room_id = ? AND status = ? AND start_time < ? AND end_time > ? AND approval_status != ?",
		roomID, "confirmed", endTime, startTime, "rejected",
	)

	if len(excludeID) > 0 {
		query = query.Where("id != ?", excludeID[0])
	}

	err := query.Count(&count).Error
	return count > 0, err
}

func (h *BookingHandler) Create(c *gin.Context) {
	userID := getUserID(c)

	var req CreateBookingRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	roomUUID, err := uuid.Parse(req.RoomID)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	startTime, err := time.ParseInLocation("2006-01-02 15:04:05", req.StartTime, time.Local)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid start time format"})
		return
	}

	endTime, err := time.ParseInLocation("2006-01-02 15:04:05", req.EndTime, time.Local)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid end time format"})
		return
	}

	if !endTime.After(startTime) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "End time must be after start time"})
		return
	}

	conflict, err := checkTimeConflict(database.DB, roomUUID, startTime, endTime)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if conflict {
		c.JSON(http.StatusConflict, gin.H{"error": "Time slot conflicts with existing booking"})
		return
	}

	var room model.Room
	database.DB.First(&room, roomUUID)

	approvalStatus := "approved"
	if room.NeedApproval {
		approvalStatus = "pending"
	}

	recurringID := uuid.New()
	var createdBookings []model.Booking

	if req.RecurringRule != "" {
		dates := generateRecurringDates(startTime, endTime, req.RecurringRule)
		for _, d := range dates {
			s := time.Date(d.Year(), d.Month(), d.Day(), startTime.Hour(), startTime.Minute(), 0, 0, time.Local)
			e := s.Add(endTime.Sub(startTime))

			conflict, _ := checkTimeConflict(database.DB, roomUUID, s, e)
			if conflict {
				continue
			}

			booking := model.Booking{
				ID:             uuid.New(),
				RoomID:         roomUUID,
				UserID:         userID,
				Title:          req.Title,
				Description:    req.Description,
				StartTime:      s,
				EndTime:        e,
				Status:         "confirmed",
				RecurringRule:  req.RecurringRule,
				RecurringID:    &recurringID,
				ApprovalStatus: approvalStatus,
			}
			database.DB.Create(&booking)
			createdBookings = append(createdBookings, booking)
		}
	} else {
		booking := model.Booking{
			ID:             uuid.New(),
			RoomID:         roomUUID,
			UserID:         userID,
			Title:          req.Title,
			Description:    req.Description,
			StartTime:      startTime,
			EndTime:        endTime,
			Status:         "confirmed",
			RecurringRule:  req.RecurringRule,
			ApprovalStatus: approvalStatus,
		}
		database.DB.Create(&booking)
		createdBookings = append(createdBookings, booking)
	}

	go notification.SendBookingConfirmation(userID, "预订确认", req.Title+" 已成功预订", createdBookings[0].ID)

	c.JSON(http.StatusCreated, gin.H{
		"message":         "Bookings created successfully",
		"bookings":        createdBookings,
		"approval_status": approvalStatus,
	})
}

func generateRecurringDates(start, end time.Time, rule string) []time.Time {
	var dates []time.Time
	current := start

	switch rule {
	case "daily":
		for i := 0; i < 30; i++ {
			dates = append(dates, current)
			current = current.AddDate(0, 0, 1)
		}
	case "weekly":
		for i := 0; i < 12; i++ {
			dates = append(dates, current)
			current = current.AddDate(0, 0, 7)
		}
	case "biweekly":
		for i := 0; i < 6; i++ {
			dates = append(dates, current)
			current = current.AddDate(0, 0, 14)
		}
	case "monthly":
		for i := 0; i < 6; i++ {
			dates = append(dates, current)
			current = current.AddDate(0, 1, 0)
		}
	default:
		dates = append(dates, start)
	}

	return dates
}

func (h *BookingHandler) Update(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var req UpdateBookingRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var booking model.Booking
	if err := database.DB.First(&booking, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
		return
	}

	userID := getUserID(c)
	if booking.UserID != userID {
		role, _ := c.Get("userRole")
		if role != "admin" {
			c.JSON(http.StatusForbidden, gin.H{"error": "No permission to update this booking"})
			return
		}
	}

	if req.Title != "" {
		booking.Title = req.Title
	}
	if req.Description != "" {
		booking.Description = req.Description
	}

	if req.StartTime != "" {
		newStartTime, err := time.ParseInLocation("2006-01-02 15:04:05", req.StartTime, time.Local)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid start time format"})
			return
		}
		booking.StartTime = newStartTime
	}
	if req.EndTime != "" {
		newEndTime, err := time.ParseInLocation("2006-01-02 15:04:05", req.EndTime, time.Local)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid end time format"})
			return
		}
		booking.EndTime = newEndTime
	}

	if req.StartTime != "" || req.EndTime != "" {
		conflict, _ := checkTimeConflict(database.DB, booking.RoomID, booking.StartTime, booking.EndTime, booking.ID)
		if conflict {
			c.JSON(http.StatusConflict, gin.H{"error": "Time slot conflicts with existing booking"})
			return
		}
	}

	if err := database.DB.Save(&booking).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, booking)
}

func (h *BookingHandler) Cancel(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var booking model.Booking
	if err := database.DB.First(&booking, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
		return
	}

	userID := getUserID(c)
	if booking.UserID != userID {
		role, _ := c.Get("userRole")
		if role != "admin" {
			c.JSON(http.StatusForbidden, gin.H{"error": "No permission to cancel this booking"})
			return
		}
	}

	booking.Status = "cancelled"
	database.DB.Save(&booking)

	c.JSON(http.StatusOK, gin.H{"message": "Booking cancelled"})
}

func (h *BookingHandler) CheckConflict(c *gin.Context) {
	var req struct {
		RoomID    string `json:"room_id" binding:"required"`
		StartTime string `json:"start_time" binding:"required"`
		EndTime   string `json:"end_time" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	roomUUID, _ := uuid.Parse(req.RoomID)
	startTime, _ := time.ParseInLocation("2006-01-02 15:04:05", req.StartTime, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02 15:04:05", req.EndTime, time.Local)

	conflict, err := checkTimeConflict(database.DB, roomUUID, startTime, endTime)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"conflict": conflict})
}

func (h *BookingHandler) Approve(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var booking model.Booking
	if err := database.DB.First(&booking, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
		return
	}

	userID := getUserID(c)
	approverID := userID
	now := time.Now()

	booking.ApprovalStatus = "approved"
	booking.ApproverID = &approverID
	booking.ApprovedAt = &now

	if err := database.DB.Save(&booking).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, booking)
}

func (h *BookingHandler) Reject(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var req struct {
		Reason string `json:"reason"`
	}
	c.ShouldBindJSON(&req)

	var booking model.Booking
	if err := database.DB.First(&booking, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
		return
	}

	userID := getUserID(c)
	approverID := userID

	booking.ApprovalStatus = "rejected"
	booking.ApproverID = &approverID
	booking.RejectReason = req.Reason
	booking.Status = "cancelled"

	if err := database.DB.Save(&booking).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, booking)
}
