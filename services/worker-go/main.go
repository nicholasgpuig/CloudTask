package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
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

var (
	jobsProcessed = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "worker_jobs_processed_total",
		Help: "Total jobs processed by the worker",
	}, []string{"type", "status"})

	jobDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "worker_job_duration_seconds",
		Help:    "Job execution duration in seconds",
		Buckets: prometheus.DefBuckets,
	}, []string{"type"})
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	slog.Info("CloudTask Worker starting")

	go func() {
		mux := http.NewServeMux()
		mux.Handle("/metrics", promhttp.Handler())
		slog.Info("metrics server listening", "port", 9091)
		if err := http.ListenAndServe(":9091", mux); err != nil {
			slog.Error("metrics server error", "error", err)
		}
	}()

	rabbitURL := envOr("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")

	conn, err := amqp.Dial(rabbitURL)
	if err != nil {
		slog.Error("failed to connect to RabbitMQ", "error", err)
		os.Exit(1)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		slog.Error("failed to open channel", "error", err)
		os.Exit(1)
	}
	defer ch.Close()

	for _, q := range []string{"jobs.created", "jobs.started", "jobs.completed"} {
		_, err := ch.QueueDeclare(q, true, false, false, false, nil)
		if err != nil {
			slog.Error("failed to declare queue", "queue", q, "error", err)
			os.Exit(1)
		}
	}

	msgs, err := ch.Consume("jobs.created", "", false, false, false, false, nil)
	if err != nil {
		slog.Error("failed to register consumer", "error", err)
		os.Exit(1)
	}

	slog.Info("waiting for jobs")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		slog.Info("shutting down")
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
		slog.Error("failed to parse job message", "error", err)
		msg.Nack(false, false)
		return
	}

	slog.Info("received job", "jobId", job.JobID, "type", job.Type)

	publish(ch, "jobs.started", StatusUpdate{
		JobID:  job.JobID,
		Status: "RUNNING",
	})

	start := time.Now()
	result, err := executeJob(job)
	elapsed := time.Since(start)

	jobDuration.WithLabelValues(job.Type).Observe(elapsed.Seconds())

	if err != nil {
		slog.Error("job failed", "jobId", job.JobID, "type", job.Type, "duration_ms", elapsed.Milliseconds(), "error", err)
		jobsProcessed.WithLabelValues(job.Type, "FAILED").Inc()
		publish(ch, "jobs.completed", StatusUpdate{
			JobID:  job.JobID,
			Status: "FAILED",
			Result: err.Error(),
		})
		msg.Ack(false)
		return
	}

	slog.Info("job completed", "jobId", job.JobID, "type", job.Type, "duration_ms", elapsed.Milliseconds(), "result", result)
	jobsProcessed.WithLabelValues(job.Type, "COMPLETED").Inc()
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
		slog.Info("sleeping", "seconds", p.Seconds)
		time.Sleep(time.Duration(p.Seconds) * time.Second)
		return fmt.Sprintf("Slept for %d seconds", p.Seconds), nil
	default:
		return "", fmt.Errorf("unknown job type: %s", job.Type)
	}
}

func publish(ch *amqp.Channel, queue string, msg any) {
	body, err := json.Marshal(msg)
	if err != nil {
		slog.Error("failed to marshal message", "error", err)
		return
	}
	err = ch.PublishWithContext(context.Background(), "", queue, false, false, amqp.Publishing{
		ContentType: "application/json",
		Body:        body,
	})
	if err != nil {
		slog.Error("failed to publish", "queue", queue, "error", err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
