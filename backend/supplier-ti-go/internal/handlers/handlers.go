package handlers

import (
	"github.com/gerbitpcb/supplier-ti/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type Handler struct {
	service *service.Service
}

func NewHandler(service *service.Service) *Handler {
	return &Handler{service: service}
}



func (h *Handler) GetComponents(c *gin.Context) {
    components, err := h.service.GetAllComponents()
    if err != nil {
        c.JSON(500, gin.H{"error": err.Error()})
        return
    }
    c.JSON(200, components)
}

func (h *Handler) MakeReservation(c *gin.Context) {
	var req struct {
		Sku      string `json:"sku"`
		Quantity int    `json:"quantity"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	reservationId, err := h.service.Reserve(req.Sku, req.Quantity)
	if err != nil {
		c.JSON(409, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"reservationId": reservationId})
}

func (h *Handler) MakeCommit(c *gin.Context) {
	var req struct {
		Id      uuid.UUID `json:"reservationId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	err := h.service.Commit(req.Id)
	if err != nil {
		c.JSON(409, gin.H{"error": err.Error()})
		return
	}
	c.JSON(204, nil)
}

func (h *Handler) MakeRollback(c *gin.Context) {
	var req struct {
		Id      uuid.UUID `json:"reservationId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	err := h.service.Rollback(req.Id)
	if err != nil {
		c.JSON(409, gin.H{"error": err.Error()})
		return
	}
	c.JSON(204, nil)
}
