## 2. Graceful Shutdown Chaos

### 2-1. 실험 목적
> Pod가 종료될 때 진행 중인 요청을 안전하게 마무리하고 종료되는지 확인한다. 
   
### 2-2. 배경 개념
   Pod가 삭제되면 Kubernetes는 컨테이너를 즉시 강제 종료하지 않는다.
   
일반적으로 Kubernetes는 먼저 컨테이너의 메인 프로세스에 `SIGTERM`을 보내고, `terminationGracePeriodSeconds` 동안 정상 종료를 기다린다.
   
만약 grace period 안에 종료되지 않으면 Kubernetes는 `SIGKILL`을 보내 컨테이너를 강제 종료한다.
   
특히, 애플리케이션이 `SIGTERM`을 제대로 처리하지 않으면 다음 문제가 발생할 수 있다.
   - 처리 중인 요청 실패
   - DB transaction 중단
   - 메시지 처리 중복
   - 로그 유실
   - 사용자 응답 실패

### 2-3. 실험 가설
```text
애플리케이션이 graceful shutdown을 구현하고,
terminationGracePeriodSeconds가 충분하다면
Pod 삭제 중에도 기존 요청을 마무리한 뒤 종료할 수 있다.

반대로 terminationGracePeriodSeconds가 요청 처리 시간보다 짧으면
graceful shutdown을 구현했더라도 요청이 중간에 실패할 수 있다.
```
---
## 2-4. 실습 과정
이번 실습에서는 Go 서버를 직접 만들어 사용한다.

이 서버는 `/slow` API를 제공하고, 요청이 오면 10초 뒤 응답한다.
```text
Client → /slow 요청
서버 → 10초 동안 처리
서버 → done 응답
```

그리고 `/slow` 요청이 처리되는 중간에 Pod를 삭제한다.

---
### Go 파일 생성

`main.go` 파일을 작성한다.

```go
package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	mux := http.NewServeMux()

	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		log.Println("slow request started")
		time.Sleep(10 * time.Second)
		log.Println("slow request finished")

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("done"))
	})

	server := &http.Server{
		Addr:    ":8080",
		Handler: mux,
	}

	go func() {
		log.Println("server started on :8080")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)

	<-quit
	log.Println("shutdown signal received")

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatalf("graceful shutdown failed: %v", err)
	}

	fmt.Println("server stopped gracefully")
}
```
핵심 코드는 다음 부분이다.
```go
signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)
```
Kubernetes가 Pod를 삭제할 때 컨테이너 프로세스에 `SIGTERM`을 보내는데, 이 코드는 Go 애플리케이션이 해당 신호를 받을 수 있게 한다.
```go
ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
server.Shutdown(ctx)
```
`server.Shutdown()`은 새로운 요청 수신을 중단하고, 이미 처리 중인 요청이 끝날 시간을 준다.

이번 실험에서는 `/slow` 요청 시간이 10초이고, Go 서버의 shutdown timeout은 15초이므로 정상 종료가 가능할 것으로 예상했다.

---
### Go 모듈 생성
```bash
go mod init graceful-demo
go mod tidy
```
Go Module은 Java의 Maven/Gradle, Node.js의 npm처럼 프로젝트 의존성을 관리하는 시스템이다.

| 명령어                         | 의미                                    |
| --------------------------- | ------------------------------------- |
| `go mod init graceful-demo` | 현재 폴더를 `graceful-demo`라는 Go 프로젝트로 초기화 |
| `go mod tidy`               | 코드에서 필요한 의존성을 정리하고 사용하지 않는 의존성 제거     |


현재 코드는 Go 내장 라이브러리만 사용하므로 별도의 외부 라이브러리는 추가되지 않는다.
> `go mod init`, `go mod tidy` 실행 화면

<p align="center">
<img width="45%" height="412" alt="image" src="https://github.com/user-attachments/assets/fe0bd241-5b64-4f31-9955-cbd53620e108" />
<img width="45%" height="148" alt="image" src="https://github.com/user-attachments/assets/1d78d7e5-f3ed-4877-86a4-7f23144ff0fd" />
</p>

---

### Dockerfile 작성 및 이미지 빌드

`Dockerfile`을 작성한다.

```dockerfile
FROM golang:1.22-alpine AS builder

WORKDIR /app

COPY go.mod ./
COPY main.go ./

RUN go build -o graceful-demo main.go

FROM alpine:3.20

WORKDIR /app

COPY --from=builder /app/graceful-demo .

EXPOSE 8080

CMD ["./graceful-demo"]
```
Dockerfile 구성은 다음과 같다.

단계	설명

| 단계 | 설명 |
| --- | --- |
| `FROM golang:1.22-alpine AS builder` | Go 코드를 컴파일하기 위한 빌드 환경 |
| `WORKDIR /app` | 컨테이너 내부 작업 디렉터리 설정 |
| `COPY go.mod ./`, `COPY main.go ./` | 로컬 파일을 컨테이너 내부로 복사 |
| `RUN go build -o graceful-demo main.go` | Go 코드를 `graceful-demo` 실행 파일로 빌드 |
| `FROM alpine:3.20` | 실행에 필요한 최소 환경만 포함한 가벼운 이미지 사용 |
| `COPY --from=builder /app/graceful-demo .` | 빌드 단계에서 생성한 실행 파일만 최종 이미지로 복사 |
| `EXPOSE 8080` | 컨테이너가 8080 포트를 사용함을 명시 |
| `CMD ["./graceful-demo"]` | 컨테이너 시작 시 Go 서버 실행 |

**이미지 빌드**
```bash
docker build -t graceful-demo:latest .
```
이미지 생성 여부를 확인한다.
```bash
docker images | findstr graceful-demo
```
Git Bash를 사용하는 경우 다음 명령어를 사용할 수 있다.
```bash
docker images | grep graceful-demo
```
---
### kind 클러스터에 이미지 로드

kind는 로컬 Docker 이미지를 자동으로 가져오지 못할 수 있으므로, 직접 kind 클러스터에 이미지를 로드한다.
```bash
kind load docker-image graceful-demo:latest --name pod-chaos
```
---
### Kubernetes YAML 작성

`graceful-demo.yaml` 파일을 작성한다.
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: graceful-demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: graceful-demo
  template:
    metadata:
      labels:
        app: graceful-demo
    spec:
      terminationGracePeriodSeconds: 20
      containers:
        - name: app
          image: graceful-demo:latest
          imagePullPolicy: IfNotPresent
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
  name: graceful-demo-service
spec:
  selector:
    app: graceful-demo
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

여기서

```yaml
terminationGracePeriodSeconds: 20
```

이 설정은 Kubernetes가 컨테이너를 강제 종료하기 전에 최대 20초까지 기다린다는 의미이다.

그래서 이번 실험의 시간 관계는 하기와 같다.
```text
/slow 요청 시간: 10초
Go Shutdown timeout: 15초
Kubernetes grace period: 20초
```

따라서 정상적인 경우 `/slow` 요청이 완료된 뒤 Pod가 종료될 수 있다.

---
### 배포

```bash
kubectl apply -f graceful-demo.yaml
```

### 배포 상태 확인

```bash
kubectl get pods -l app=graceful-demo
kubectl get svc graceful-demo-service
```
---

### Service 호출 테스트

테스트용 `curl-test` Pod에서 Service를 호출한다.

```bash
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
```
`curl-test` Pod 안에서 health check를 수행한다.
```bash
curl http://graceful-demo-service/healthz
```
`ok`가 출력되면 정상이다.
`/slow` API도 테스트한다.
```bash
curl http://graceful-demo-service/slow
```
10초 뒤 `done`이 출력되면 정상이다.
> `/healthz`, `/slow` 호출 성공 화면

<img width="60%" height="256" alt="image" src="https://github.com/user-attachments/assets/b10c51b3-edaf-4305-8702-9cc74e59254d" />


---

### 2-5. 실험: 요청 중 Pod 삭제

**터미널 1: 로그 모니터링**
```bash
kubectl logs -l app=graceful-demo -f
```
**터미널 2: curl-test 안에서 slow 요청 실행**
```bash
curl -v http://graceful-demo-service/slow
```
**터미널 3: 요청 처리 중 Pod 삭제**
```bash
kubectl get pods -l app=graceful-demo
kubectl delete pod <pod-name>
```
**기대 로그**
```text
server started on :8080
slow request started
shutdown signal received
slow request finished
server stopped gracefully
```
### 실험 결과
예상한 대로 `/slow` 요청이 처리되는 도중 Pod를 삭제했지만, Go 서버가 `SIGTERM`을 수신한 뒤 기존 요청을 마무리하고 종료했다.

클라이언트는 `done` 응답을 정상적으로 받았고, 로그에서도 다음 흐름을 확인할 수 있었다.

```text
slow request started
shutdown signal received
slow request finished
server stopped gracefully
```
> 정상 graceful shutdown 로그 및 curl 결과 화면

<img width="70%" height="863" alt="image" src="https://github.com/user-attachments/assets/08c0bba3-544e-43b8-b704-c9b2a32fc6b7" />

---

### 2-6. 비교 실험: Grace Period를 짧게 설정

이번에는 `terminationGracePeriodSeconds`를 5초로 줄여서, 10초가 걸리는 `/slow` 요청이 끝나기 전에 컨테이너가 강제 종료되는지 확인했다.

```yaml
terminationGracePeriodSeconds: 5
```
변경 후 적용한다.
```bash
kubectl apply -f graceful-demo.yaml
kubectl rollout restart deployment graceful-demo
```

이후 동일하게 `/slow` 요청 중 Pod를 삭제한다.

### 실험 결과

> `terminationGracePeriodSeconds: 5`에서 `Empty reply from server` 발생 화면

<img width="70%" height="968" alt="image" src="https://github.com/user-attachments/assets/59714ea1-8fc8-4c6e-8858-915c3409b3b4" />

애플리케이션 로그에서는 다음 흐름을 확인했다.

```text
slow request started
shutdown signal received
```

하지만 이후 `slow request finished`, `server stopped gracefully` 로그는 출력되지 않았다.
클라이언트 측에서는 다음과 같은 결과가 발생했다.

```text
Empty reply from server
curl: (52) Empty reply from server
```
이는 애플리케이션이 기존 요청을 마무리하기 전에 컨테이너가 강제로 종료되었음을 의미한다.

### 조건	결과	해석

- `terminationGracePeriodSeconds: 20`	`/slow` 요청이 `done` 응답으로 정상 완료	Kubernetes가 충분히 기다려주어 기존 요청을 마무리함
- `terminationGracePeriodSeconds: 5`	`Empty reply from server` 발생	요청 처리 시간보다 grace period가 짧아 강제 종료됨



### 2-7. 결론
> Graceful shutdown은 코드 구현만으로 끝나는 것이 아니라, Kubernetes의 종료 유예 시간 설정과 함께 맞춰야 한다.

Pod 삭제 시 Kubernetes는 먼저 `SIGTERM`을 전달하고, 애플리케이션은 이를 받아 기존 요청을 마무리할 수 있다.
하지만 `terminationGracePeriodSeconds`가 실제 요청 처리 시간보다 짧으면, graceful shutdown을 구현했더라도 컨테이너가 `SIGKILL`에 의해 강제 종료될 수 있다.

따라서 Kubernetes 환경에서는 애플리케이션의 최대 요청 처리 시간과 종료 작업 시간을 고려해 적절한 `terminationGracePeriodSeconds`를 설정해야 한다.

---
