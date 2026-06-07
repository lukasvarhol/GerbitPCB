package models

import (
	"time"
	"github.com/google/uuid"
)


type Component struct {
	Id uuid.UUID `gorm:"type:uuid;primaryKey;"`
	Sku string `gorm:"column:sku;not null; unique"`
	Price float64 `gorm:"column:price; not null"`
	Name string `gorm:"column:name; not null"`
	AvailableStock int `gorm:"column:available_stock; not null"`
	ReservedStock int `gorm:"column:reserved_stock; not null"`
	Version uint64 
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
