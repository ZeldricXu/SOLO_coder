package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type RoomHandler struct{}

func NewRoomHandler() *RoomHandler {
	return &RoomHandler{}
}

type CreateRoomRequest struct {
	Name         string `json:"name" binding:"required"`
	Floor        int    `json:"floor" binding:"required"`
	Capacity     int    `json:"capacity" binding:"required"`
	Equipment    string `json:"equipment"`
	Description  string `json:"description"`
	NeedApproval bool   `json:"need_approval"`
	ApproverID   string `json:"approver_id"`
	Location     string `json:"location"`
}

type UpdateRoomRequest struct {
	Name         string `json:"name"`
	Floor        int    `json:"floor"`
	Capacity     int    `json:"capacity"`
	Equipment    string `json:"equipment"`
	Description  string `json:"description"`
	Status       string `json:"status"`
	NeedApproval bool   `json:"need_approval"`
	ApproverID   string `json:"approver_id"`
	Location     string `json:"location"`
}

func (h *RoomHandler) List(c *gin.Context) {
	var rooms []model.Room
	query := database.DB

	floor := c.Query("floor")
	if floor != "" {
		query = query.Where("floor = ?", floor)
	}

	status := c.Query("status")
	if status != "" {
		query = query.Where("status = ?", status)
	} else {
		query = query.Where("status = ?", "active")
	}

	search := c.Query("search")
	if search != "" {
		query = query.Where("name ILIKE ?", "%"+search+"%")
	}

	if err := query.Order("floor, name").Find(&rooms).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, rooms)
}

func (h *RoomHandler) Get(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	var room model.Room
	if err := database.DB.First(&room, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Room not found"})
		return
	}

	c.JSON(http.StatusOK, room)
}

func (h *RoomHandler) Create(c *gin.Context) {
	var req CreateRoomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	room := model.Room{
		ID:           uuid.New(),
		Name:         req.Name,
		Floor:        req.Floor,
		Capacity:     req.Capacity,
		Equipment:    req.Equipment,
		Description:  req.Description,
		Status:       "active",
		NeedApproval: req.NeedApproval,
		Location:     req.Location,
	}

	if req.ApproverID != "" {
		approverID, err := uuid.Parse(req.ApproverID)
		if err == nil {
			room.ApproverID = &approverID
		}
	}

	if err := database.DB.Create(&room).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, room)
}

func (h *RoomHandler) Update(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	var req UpdateRoomRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var room model.Room
	if err := database.DB.First(&room, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Room not found"})
		return
	}

	if req.Name != "" {
		room.Name = req.Name
	}
	if req.Floor != 0 {
		room.Floor = req.Floor
	}
	if req.Capacity != 0 {
		room.Capacity = req.Capacity
	}
	if req.Equipment != "" {
		room.Equipment = req.Equipment
	}
	if req.Description != "" {
		room.Description = req.Description
	}
	if req.Status != "" {
		room.Status = req.Status
	}
	room.NeedApproval = req.NeedApproval
	if req.Location != "" {
		room.Location = req.Location
	}

	if req.ApproverID != "" {
		approverID, err := uuid.Parse(req.ApproverID)
		if err == nil {
			room.ApproverID = &approverID
		}
	}

	if err := database.DB.Save(&room).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, room)
}

func (h *RoomHandler) Delete(c *gin.Context) {
	id, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	if err := database.DB.Delete(&model.Room{}, id).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Room deleted"})
}

func (h *RoomHandler) GetBookings(c *gin.Context) {
	roomID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	date := c.Query("date")
	var startTime, endTime time.Time

	if date != "" {
		d, err := time.ParseInLocation("2006-01-02", date, time.Local)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid date format"})
			return
		}
		startTime = time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, time.Local)
		endTime = startTime.AddDate(0, 0, 1)
	} else {
		startTime = time.Now()
		endTime = startTime.AddDate(0, 0, 7)
	}

	var bookings []model.Booking
	if err := database.DB.Where("room_id = ? AND start_time < ? AND end_time > ? AND status = ? AND approval_status != ?",
		roomID, endTime, startTime, "confirmed", "rejected").
		Preload("User").
		Order("start_time").
		Find(&bookings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, bookings)
}

func (h *RoomHandler) Calendar(c *gin.Context) {
	roomID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	start := c.Query("start")
	end := c.Query("end")

	startTime, _ := time.ParseInLocation("2006-01-02", start, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02", end, time.Local)

	if startTime.IsZero() {
		startTime = time.Now().AddDate(0, 0, -7)
	}
	if endTime.IsZero() {
		endTime = time.Now().AddDate(0, 0, 30)
	}

	var bookings []model.Booking
	if err := database.DB.Where("room_id = ? AND start_time < ? AND end_time > ? AND status = ?",
		roomID, endTime, startTime, "confirmed").
		Preload("User").
		Order("start_time").
		Find(&bookings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, bookings)
}

func (h *RoomHandler) DisplayInfo(c *gin.Context) {
	roomID, err := uuid.Parse(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid room ID"})
		return
	}

	var room model.Room
	if err := database.DB.First(&room, roomID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Room not found"})
		return
	}

	now := time.Now()
	var currentEnd := now.Add(24 * time.Hour)

	var bookings []model.Booking
	database.DB.Where("room_id = ? AND start_time < ? AND end_time > ? AND status = ?",
		roomID, currentEnd, now, "confirmed").
		Preload("User").
		Order("start_time").
		Find(&bookings)

	var currentBooking *model.Booking
	var nextBooking *model.Booking

	for i := range bookings {
		if bookings[i].StartTime.Before(now) && bookings[i].EndTime.After(now) {
			currentBooking = &bookings[i]
		}
		if bookings[i].StartTime.After(now) && nextBooking == nil {
			nextBooking = &bookings[i]
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"room":           room,
		"current_time":   now,
		"current_booking": currentBooking,
		"next_booking":  nextBooking,
		"today_bookings": bookings,
	})
}
