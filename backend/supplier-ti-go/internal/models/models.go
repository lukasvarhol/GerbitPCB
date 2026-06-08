package models

import (
	"time"
	"github.com/google/uuid"
)


type Component struct {
    Id             uuid.UUID `gorm:"type:uuid;primaryKey" json:"id"`
    Sku            string    `gorm:"column:sku;not null;unique" json:"sku"`
    Price          float64   `gorm:"column:price;not null" json:"price"`
    Name           string    `gorm:"column:name;not null" json:"name"`
    AvailableStock int       `gorm:"column:available_stock;not null" json:"availableStock"`
    ReservedStock  int       `gorm:"column:reserved_stock;not null" json:"reservedStock"`
    Version        uint64    `json:"version"`
}

type ReservationStatus string
const (
	StatusReserved ReservationStatus = "RESERVED"
	StatusCommitted ReservationStatus = "COMMITTED"
	StatusRolledBack ReservationStatus = "ROLLED_BACK"
)

type Reservation struct {
	Id uuid.UUID `gorm:"type:uuid;primaryKey;"`
	ComponentId uuid.UUID
	Component Component 
	Quantity int `gorm:"column:quantity; not null"`
	Status ReservationStatus `gorm:"column:status; not null"`
	CreatedAt time.Time `gorm:"column:created_at; not null"`
}
