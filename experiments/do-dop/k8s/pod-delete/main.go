package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"time"
)

// 프로그램 시작 시간 저장
var startTime = time.Now()

// 서버 시작 후 30초 지났는지 확인
func isReady() bool {
	return time.Since(startTime) >= 30*time.Second
}

func main() {
	podName := os.Getenv("POD_NAME")
	if podName == "" {
		podName = "unknown"
	}

	// 기본 경로 요청
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !isReady() {
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprintf(w, "app is initializing, pod=%s\n", podName)
			return
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, "ok, pod=%s\n", podName)
	})

	// 준비 상태 확인용 endpoint
	http.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		if !isReady() {
			w.WriteHeader(http.StatusServiceUnavailable)
			fmt.Fprintf(w, "not ready, pod=%s\n", podName)
			return
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, "ready, pod=%s\n", podName)
	})

	log.Println("server started on :8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}
