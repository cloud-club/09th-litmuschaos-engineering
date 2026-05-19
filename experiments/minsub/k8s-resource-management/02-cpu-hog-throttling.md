## 2. CPU Hog와 CPU Limit 확인

### Go 애플리케이션

`main.go`
```go
package main

import (
	"log"
	"net/http"
	"time"
)

func main() {
	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	mux.HandleFunc("/cpu-hog", func(w http.ResponseWriter, r *http.Request) {
		log.Println("cpu-hog started")
		ctx := r.Context()
		deadline := time.After(60 * time.Second)
		for {
			select {
			case <-ctx.Done():
				return
			case <-deadline:
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte("cpu-hog done"))
				return
			default:
			}
		}
	})

	mux.HandleFunc("/memory-hog", func(w http.ResponseWriter, r *http.Request) {
		log.Println("memory-hog started")
		var allocated [][]byte
		for {
			chunk := make([]byte, 10*1024*1024)
			for i := range chunk {
				chunk[i] = byte(i)
			}
			allocated = append(allocated, chunk)
			log.Printf("allocated: %dMB total", len(allocated)*10)
			time.Sleep(200 * time.Millisecond)
		}
	})

	log.Println("server started on :8080")
	if err := http.ListenAndServe(":8080", mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
```

`Dockerfile`
```dockerfile
FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod ./
COPY main.go ./

RUN go build -o resource-demo main.go

FROM alpine:3.20

WORKDIR /app

COPY --from=builder /app/resource-demo .

EXPOSE 8080

CMD ["./resource-demo"]
```

### 이미지 빌드

```bash
go mod init resource-demo
go mod tidy
eval $(minikube docker-env --profile=k8s-practice)
docker build -t resource-demo:latest .
```

### Case 1: CPU limit 없는 경우

`cpu-no-limit.yaml`
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resource-demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: resource-demo
  template:
    metadata:
      labels:
        app: resource-demo
    spec:
      containers:
        - name: app
          image: resource-demo:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            periodSeconds: 3
---
apiVersion: v1
kind: Service
metadata:
  name: resource-demo-service
spec:
  selector:
    app: resource-demo
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

```bash
kubectl apply -f cpu-no-limit.yaml
```

터미널 1: CPU 사용량 모니터링
```bash
watch -n 2 kubectl top pod -l app=resource-demo
```

터미널 2: `/cpu-hog` 호출
```bash
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
# curl-test Pod 안에서
curl http://resource-demo-service/cpu-hog
```

### Case 2: CPU limit 있는 경우

`cpu-with-limit.yaml`
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resource-demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: resource-demo
  template:
    metadata:
      labels:
        app: resource-demo
    spec:
      containers:
        - name: app
          image: resource-demo:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            periodSeconds: 3
          resources:
            requests:
              cpu: "100m"
              memory: "128Mi"
            limits:
              cpu: "200m"
              memory: "256Mi"
---
apiVersion: v1
kind: Service
metadata:
  name: resource-demo-service
spec:
  selector:
    app: resource-demo
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

```bash
kubectl delete -f cpu-no-limit.yaml
kubectl apply -f cpu-with-limit.yaml
```

터미널 1: CPU 사용량 모니터링
```bash
watch -n 2 kubectl top pod -l app=resource-demo
```

터미널 2: `/cpu-hog` 호출
```bash
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
# curl-test Pod 안에서
curl http://resource-demo-service/cpu-hog
```

### 정리

```bash
kubectl delete -f cpu-with-limit.yaml
```
