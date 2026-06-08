package service

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
)

type stockUpdateRequest struct {
	Sku string `json:"sku"`
	Supplier string `json:"supplier"`
	AvailableStock int `json:"availableStock"`
}

func notifyBroker(webhookUrl string, sku string, availableStock int) {
	body, _ := json.Marshal(stockUpdateRequest{
		Sku: sku,
		Supplier: "TI",
		AvailableStock: availableStock,
	})
	resp, err := http.Post(webhookUrl+"/api/components/stock-update", "application/json", bytes.NewBuffer(body))
	if err != nil {
		log.Println("failed to notify broker:", err)
		return
	}
	defer resp.Body.Close()
}
