package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	DbHost string
	DbPort string
	DbName string
	DbUser string
	DbPassword string
	Auth0Domain string
	Auth0Audience string
	Port string
	BrokerWebhookUrl string
	ReservationTTL time.Duration
}

func LoadConfig() Config {
	var config Config

	config.DbHost = os.Getenv("DB_HOST")
	config.DbPort = os.Getenv("DB_PORT")
	config.DbName = os.Getenv("DB_NAME")
	config.DbUser = os.Getenv("DB_USER")
	config.DbPassword = os.Getenv("DB_PASSWORD")
	config.Auth0Domain = os.Getenv("AUTH0_DOMAIN")
	config.Auth0Audience = os.Getenv("AUTH0_AUDIENCE")
	port := os.Getenv("PORT")
	if port == "" {
		port = "8083"
	}
	config.Port = ":" + port;
	config.BrokerWebhookUrl = os.Getenv("BROKER_WEBHOOK_URL")

	ttl := os.Getenv("RESERVATION_TTL_MINUTES")
	if ttl == "" {
		ttl = "20"
	}
	mins, _ := strconv.Atoi(ttl)
	config.ReservationTTL = time.Duration(mins) * time.Minute

	return config
}
