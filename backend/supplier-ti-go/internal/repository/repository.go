package repository

import (
	"time"

	"github.com/gerbitpcb/supplier-ti/internal/models"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type Repository struct {
	db *gorm.DB
}

func NewRepository(db *gorm.DB) *Repository {
	return &Repository{db: db}
}

func (r *Repository) FindAllComponents() ([]models.Component, error) {
    var components []models.Component
    result := r.db.Find(&components)
    return components, result.Error
}

func (r *Repository) FindComponentBySku(sku string) (*models.Component, error){
	var component models.Component
	result := r.db.Where("sku = ?", sku).First(&component)
	return &component, result.Error
}

func (r *Repository) SaveComponent(component *models.Component) error {
	result := r.db.Save(component)
	return result.Error
}

func (r *Repository) FindReservationById(id uuid.UUID) (*models.Reservation, error){
	var reservation models.Reservation
	result := r.db.Where("id = ?", id).First(&reservation)
	return &reservation, result.Error
}

func (r *Repository) FindReservationWithComponent(id uuid.UUID) (*models.Reservation, error) {
    var reservation models.Reservation
    result := r.db.Preload("Component").Where("id = ?", id).First(&reservation)
    return &reservation, result.Error
}

func (r *Repository) SaveReservation(reservation *models.Reservation) error {
	result := r.db.Save(reservation)
	return result.Error
}

func (r *Repository) FindStaleReservations(cutoff time.Time) ([]models.Reservation, error) {
    var reservations []models.Reservation
    result := r.db.Preload("Component").Where("status = ? AND created_at < ?", models.StatusReserved, cutoff).Find(&reservations)
    return reservations, result.Error
}
