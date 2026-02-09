package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/jackc/pgx/v5/pgxpool"
	amqp "github.com/rabbitmq/amqp091-go"
)

type StatusUpdate struct {
	JobID  string `json:"jobId"`
	Status string `json:"status"`
	Result string `json:"result,omitempty"`
}

func main() {
	log.Println("CloudTask Results Processor starting...")

	rabbitURL := envOr("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")
	dbURL := envOr("DATABASE_URL", "postgres://cloudtask:cloudtask@localhost:5432/cloudtask")

	// Connect to PostgreSQL
	dbPool, err := pgxpool.New(context.Background(), dbURL)
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	defer dbPool.Close()

	if err := dbPool.Ping(context.Background()); err != nil {
		log.Fatalf("Failed to ping database: %v", err)
	}
	log.Println("Connected to PostgreSQL")

	// Connect to RabbitMQ
	conn, err := amqp.Dial(rabbitURL)
	if err != nil {
		log.Fatalf("Failed to connect to RabbitMQ: %v", err)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("Failed to open channel: %v", err)
	}
	defer ch.Close()

	// Declare queues
	for _, q := range []string{"jobs.started", "jobs.completed"} {
		_, err := ch.QueueDeclare(q, true, false, false, false, nil)
		if err != nil {
			log.Fatalf("Failed to declare queue %s: %v", q, err)
		}
	}

	startedMsgs, err := ch.Consume("jobs.started", "results-started", false, false, false, false, nil)
	if err != nil {
		log.Fatalf("Failed to consume jobs.started: %v", err)
	}

	completedMsgs, err := ch.Consume("jobs.completed", "results-completed", false, false, false, false, nil)
	if err != nil {
		log.Fatalf("Failed to consume jobs.completed: %v", err)
	}

	log.Println("Waiting for status updates...")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		log.Println("Shutting down...")
		cancel()
	}()

	for {
		select {
		case <-ctx.Done():
			return
		case msg, ok := <-startedMsgs:
			if !ok {
				return
			}
			handleStatusUpdate(ctx, dbPool, msg)
		case msg, ok := <-completedMsgs:
			if !ok {
				return
			}
			handleStatusUpdate(ctx, dbPool, msg)
		}
	}
}

func handleStatusUpdate(ctx context.Context, db *pgxpool.Pool, msg amqp.Delivery) {
	var update StatusUpdate
	if err := json.Unmarshal(msg.Body, &update); err != nil {
		log.Printf("Failed to parse message: %v", err)
		msg.Nack(false, false)
		return
	}

	log.Printf("Updating job %s to %s", update.JobID, update.Status)

	_, err := db.Exec(ctx,
		"UPDATE jobs SET status = $1, updated_at = NOW() WHERE id = $2",
		update.Status, update.JobID,
	)
	if err != nil {
		log.Printf("Failed to update job %s: %v", update.JobID, err)
		msg.Nack(false, true)
		return
	}

	msg.Ack(false)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
