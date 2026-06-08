package main

import (
	"fmt"
	"log"
	"time"

	"github.com/gerbitpcb/supplier-ti/internal/config"
	"github.com/gerbitpcb/supplier-ti/internal/handlers"
	"github.com/gerbitpcb/supplier-ti/internal/middleware"
	"github.com/gerbitpcb/supplier-ti/internal/repository"
	"github.com/gerbitpcb/supplier-ti/internal/service"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func main() {
	cfg := config.LoadConfig()

	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=disable",
		cfg.DbHost, cfg.DbPort, cfg.DbUser, cfg.DbPassword, cfg.DbName)
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		log.Fatal("failed to connect to database: ", err)
	}

	repo := repository.NewRepository(db)
	serv := service.NewService(repo, cfg.BrokerWebhookUrl)
	handler := handlers.NewHandler(serv)

	router := gin.Default()
	router.GET("/api/components", handler.GetComponents)
	auth := router.Group("/api/transaction")
	auth.Use(middleware.AuthMiddleware(cfg.Auth0Domain, cfg.Auth0Audience))
	auth.POST("/reserve", handler.MakeReservation)
	auth.POST("/commit", handler.MakeCommit)
	auth.POST("/rollback", handler.MakeRollback)

	go func() {
		ticker := time.NewTicker(5 * time.Minute)
		for range ticker.C {
			if err := serv.CleanupStaleReservations(); err != nil {
				log.Println("cleanup error: ", err)
			}
		}
	}()
	router.Run(cfg.Port)
}
