package service

import (
	"errors"
	"time"
	"github.com/gerbitpcb/supplier-ti/internal/models"
	"github.com/gerbitpcb/supplier-ti/internal/repository"
	"github.com/google/uuid"
)

type Service struct {
	repository *repository.Repository
}

func NewService(repository *repository.Repository) *Service {
	return &Service{repository: repository}
}

func (s *Service) GetAllComponents() ([]models.Component, error) {
	var components []models.Component
	components, err := s.repository.FindAllComponents()
	return components, err
}

func (s *Service) Reserve(sku string, quantity int) (uuid.UUID, error) {
	component, err := s.repository.FindComponentBySku(sku)
	if err != nil {
		return uuid.Nil, err
	}
	if component.AvailableStock < quantity {
		return uuid.Nil, errors.New("Insufficient stock")
	}
	component.AvailableStock -= quantity
	err = s.repository.SaveComponent(component)
	if err != nil {
		return uuid.Nil, err
	}

	reservation := models.Reservation{
		Id:          uuid.New(),
		ComponentId: component.Id,
		Quantity:    quantity,
		Status:      models.StatusReserved,
		CreatedAt:   time.Now(),
	}
	err = s.repository.SaveReservation(&reservation)
	if err != nil {
		return uuid.Nil, err
	}

	return reservation.Id, err
}

func (s *Service) Commit (reservationId uuid.UUID) error {
	reservation, err := s.repository.FindReservationWithComponent(reservationId)
	if err != nil {
		return err
	}
	if reservation.Status == models.StatusCommitted || reservation.Status == models.StatusRolledBack {
		return nil
	}
	component := reservation.Component
	quantity := reservation.Quantity

	if component.ReservedStock < quantity {
		return errors.New("reserved stock is lower than reservation quantity")
	}

	component.ReservedStock = component.ReservedStock - quantity
	err = s.repository.SaveComponent(&component)
	if err != nil {
		return err
	}
	reservation.Status = models.StatusCommitted
		err = s.repository.SaveReservation(reservation)
	if err != nil {
		return err
	}
	return nil
}

func (s *Service) Rollback (reservationId uuid.UUID) error {
	reservation, err := s.repository.FindReservationWithComponent(reservationId)
	if err != nil {
		return err
	}

	// if already rolled back do nothing:
	if reservation.Status == models.StatusRolledBack {
		return nil
	}

	// normal rollback
	component := reservation.Component
	quantity := reservation.Quantity
	
	if reservation.Status == models.StatusReserved {
		if component.ReservedStock < quantity {
			return errors.New("reserved stock is lower than reservation quantity")
		}
		component.ReservedStock = component.ReservedStock - quantity
		component.AvailableStock = component.AvailableStock + quantity
	} else if reservation.Status == models.StatusCommitted {
		component.AvailableStock = component.AvailableStock + quantity
	} else {
		return errors.New("unknown reservation state: " + string(reservation.Status))
	}
	reservation.Status = models.StatusRolledBack

	err = s.repository.SaveComponent(&component)
	if err != nil {
		return err
	}
	return nil

	//TODO brokerNotificationSevice.notifySTockUpdate
}

func (s *Service) CleanupStaleReservations() error {
	cutoff := time.Now().Add(-5 * time.Minute)
	reservations, err := s.repository.FindStaleReservations(cutoff)
	if err != nil {
		return err
	}

	for _, reservation := range reservations {
		component := reservation.Component
		quantity := reservation.Quantity

		if component.ReservedStock >= quantity {
			component.ReservedStock = component.ReservedStock - quantity
			component.AvailableStock = component.AvailableStock + quantity
		}
		reservation.Status = models.StatusRolledBack
		s.repository.SaveReservation(&reservation)
		s.repository.SaveComponent(&component)
		//TODO broker notifictaion service
	}
	return nil
}
