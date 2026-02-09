package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

type JobMessage struct {
	JobID   string `json:"jobId"`
	Type    string `json:"type"`
	Payload string `json:"payload"`
}

type SleepPayload struct {
	Seconds int `json:"seconds"`
}

type StatusUpdate struct {
	JobID  string `json:"jobId"`
	Status string `json:"status"`
	Result string `json:"result,omitempty"`
}

func main() {
	log.Println("CloudTask Worker starting...")

	rabbitURL := envOr("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")

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
	for _, q := range []string{"jobs.created", "jobs.started", "jobs.completed"} {
		_, err := ch.QueueDeclare(q, true, false, false, false, nil)
		if err != nil {
			log.Fatalf("Failed to declare queue %s: %v", q, err)
		}
	}

	msgs, err := ch.Consume("jobs.created", "", false, false, false, false, nil)
	if err != nil {
		log.Fatalf("Failed to register consumer: %v", err)
	}

	log.Println("Waiting for jobs...")

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
		case msg, ok := <-msgs:
			if !ok {
				return
			}
			processJob(ch, msg)
		}
	}
}

func processJob(ch *amqp.Channel, msg amqp.Delivery) {
	var job JobMessage
	if err := json.Unmarshal(msg.Body, &job); err != nil {
		log.Printf("Failed to parse job message: %v", err)
		msg.Nack(false, false)
		return
	}

	log.Printf("Received job %s (type: %s)", job.JobID, job.Type)

	// Publish started status
	publish(ch, "jobs.started", StatusUpdate{
		JobID:  job.JobID,
		Status: "RUNNING",
	})

	// Execute the job
	result, err := executeJob(job)
	if err != nil {
		log.Printf("Job %s failed: %v", job.JobID, err)
		publish(ch, "jobs.completed", StatusUpdate{
			JobID:  job.JobID,
			Status: "FAILED",
			Result: err.Error(),
		})
		msg.Ack(false)
		return
	}

	log.Printf("Job %s completed: %s", job.JobID, result)
	publish(ch, "jobs.completed", StatusUpdate{
		JobID:  job.JobID,
		Status: "COMPLETED",
		Result: result,
	})
	msg.Ack(false)
}

func executeJob(job JobMessage) (string, error) {
	switch job.Type {
	case "sleep":
		var p SleepPayload
		if err := json.Unmarshal([]byte(job.Payload), &p); err != nil {
			return "", fmt.Errorf("invalid sleep payload: %v", err)
		}
		if p.Seconds <= 0 || p.Seconds > 300 {
			return "", fmt.Errorf("seconds must be between 1 and 300, got %d", p.Seconds)
		}
		log.Printf("Sleeping for %d seconds...", p.Seconds)
		time.Sleep(time.Duration(p.Seconds) * time.Second)
		return fmt.Sprintf("Slept for %d seconds", p.Seconds), nil
	default:
		return "", fmt.Errorf("unknown job type: %s", job.Type)
	}
}

func publish(ch *amqp.Channel, queue string, msg any) {
	body, err := json.Marshal(msg)
	if err != nil {
		log.Printf("Failed to marshal message: %v", err)
		return
	}
	err = ch.PublishWithContext(context.Background(), "", queue, false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	})
	if err != nil {
		log.Printf("Failed to publish to %s: %v", queue, err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
