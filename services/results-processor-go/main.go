package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	amqp "github.com/rabbitmq/amqp091-go"
)

type StatusUpdate struct {
	JobID  string `json:"jobId"`
	Status string `json:"status"`
	Result string `json:"result,omitempty"`
}

var (
	statusUpdates = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "processor_status_updates_total",
		Help: "Total status updates processed by the results processor",
	}, []string{"status"})

	dbUpdateDuration = promauto.NewHistogram(prometheus.HistogramOpts{
		Name:    "processor_db_update_duration_seconds",
		Help:    "Duration of database update operations",
		Buckets: prometheus.DefBuckets,
	})

	nackTotal = promauto.NewCounter(prometheus.CounterOpts{
		Name: "processor_nack_total",
		Help: "Total messages nacked by the results processor",
	})
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

	slog.Info("CloudTask Results Processor starting")

	go func() {
		mux := http.NewServeMux()
		mux.Handle("/metrics", promhttp.Handler())
		slog.Info("metrics server listening", "port", 9092)
		if err := http.ListenAndServe(":9092", mux); err != nil {
			slog.Error("metrics server error", "error", err)
		}
	}()

	rabbitURL := envOr("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/")
	dbURL := envOr("DATABASE_URL", "postgres://cloudtask:cloudtask@localhost:5432/cloudtask")

	dbPool, err := pgxpool.New(context.Background(), dbURL)
	if err != nil {
		slog.Error("failed to connect to database", "error", err)
		os.Exit(1)
	}
	defer dbPool.Close()

	if err := dbPool.Ping(context.Background()); err != nil {
		slog.Error("failed to ping database", "error", err)
		os.Exit(1)
	}
	slog.Info("connected to PostgreSQL")

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

	for _, q := range []string{"jobs.started", "jobs.completed"} {
		_, err := ch.QueueDeclare(q, true, false, false, false, nil)
		if err != nil {
			slog.Error("failed to declare queue", "queue", q, "error", err)
			os.Exit(1)
		}
	}

	startedMsgs, err := ch.Consume("jobs.started", "results-started", false, false, false, false, nil)
	if err != nil {
		slog.Error("failed to consume jobs.started", "error", err)
		os.Exit(1)
	}

	completedMsgs, err := ch.Consume("jobs.completed", "results-completed", false, false, false, false, nil)
	if err != nil {
		slog.Error("failed to consume jobs.completed", "error", err)
		os.Exit(1)
	}

	slog.Info("waiting for status updates")

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
		slog.Error("failed to parse message", "error", err)
		nackTotal.Inc()
		msg.Nack(false, false)
		return
	}

	slog.Info("updating job status", "jobId", update.JobID, "status", update.Status)

	start := time.Now()
	_, err := db.Exec(ctx,
		"UPDATE jobs SET status = $1, updated_at = NOW() WHERE id = $2",
		update.Status, update.JobID,
	)
	elapsed := time.Since(start)
	dbUpdateDuration.Observe(elapsed.Seconds())

	if err != nil {
		slog.Error("failed to update job", "jobId", update.JobID, "status", update.Status, "error", err)
		nackTotal.Inc()
		msg.Nack(false, true)
		return
	}

	statusUpdates.WithLabelValues(update.Status).Inc()
	msg.Ack(false)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
