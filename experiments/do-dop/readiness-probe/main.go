package main

import (
	"fmt"
	"log"
	"net/http"
	"time"
)

// 프로그램 시작 시간 저장
var startTime = time.Now()

// 서버 시작 후 30초 지났는 지 확인
func isReady() bool {
	return time.Since(startTime) >= 30*time.Second
}

func main() {
	// 기본 경로 요청 
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !isReady() {
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprintln(w, "app is initializing")
			return
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprintln(w, "ok")
	})

	// 준비 상태 확인용 endpoint
	http.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
		if !isReady() {
			w.WriteHeader(http.StatusServiceUnavailable)
			fmt.Fprintln(w, "not ready")
			return
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprintln(w, "ready")
	})

	log.Println("server started on :8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}