package handlers

import (
	"github.com/gerbitpcb/supplier-ti/internal/service"
	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *service.Service
}

func NewHandler(service *service.Service) *Handler {
	return &Handler{service: service}
}

var req struct {
    Sku      string `json:"sku"`
    Quantity int    `json:"quantity"`
}

func (h *Handler) GetComponents(c *gin.Context) {
    components, err := h.service.GetAllComponents()
    if err != nil {
        c.JSON(500, gin.H{"error": err.Error()})
        return
    }
    c.JSON(200, components)
}

