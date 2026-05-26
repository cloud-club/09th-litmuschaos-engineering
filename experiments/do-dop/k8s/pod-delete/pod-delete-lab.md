# Pod Delete 실습 기록

## 실습 목적

Kubernetes에서 Pod가 삭제되어 새 Pod가 생성되는 동안 `readinessProbe`가 Service 트래픽을 준비된 Pod로만 전달하는지 확인한다.

이 실습에서는 애플리케이션이 시작된 뒤 30초 동안 초기화 상태를 유지하도록 만들고, 3개 replica 중 일부 Pod를 삭제한 뒤 다음 흐름을 관찰한다.

- 삭제된 Pod IP가 EndpointSlice에서 제외되는지
- 새 Pod IP가 EndpointSlice에 등록되더라도 Ready 전에는 `ready: false`인지
- Ready 전 새 Pod가 Service 트래픽을 받지 않는지
- Ready 후 새 Pod가 Service 트래픽 대상에 포함되는지

기본 minikube, Docker, kubectl 세팅은 `readiness-probe/readiness-probe-lab.md`와 동일하므로 생략한다.

## 1. 실험 파일 준비

### 1.1 Go 앱 작성

`main.go` 내용:

```go
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
```

원래 시나리오의 Go 앱은 `/ready`만 실패하고 `/`는 항상 성공할 수 있다. 그러면 readinessProbe가 없는 경우에도 사용자 요청에서 `500` 에러가 잘 보이지 않는다.

그래서 비교 실험을 명확히 하기 위해 `/`도 초기화 중에는 `500`을 반환하도록 구성했다.

| 시점 | `/` | `/ready` |
| --- | --- | --- |
| 앱 시작 후 0~30초 | `500 app is initializing` | `503 not ready` |
| 앱 시작 후 30초 이후 | `200 ok` | `200 ready` |

### 1.2 Dockerfile 작성

`Dockerfile` 내용:

```dockerfile
FROM golang:1.22-alpine AS builder

WORKDIR /app
COPY main.go .

RUN go mod init probe-test
RUN go build -o server main.go

FROM alpine:3.20

WORKDIR /app
COPY --from=builder /app/server .

EXPOSE 8080

CMD ["./server"]
```

### 1.3 이미지 빌드

먼저 minikube 프로필을 확인한다.

```bash
minikube profile list
```

실습에서는 프로필 이름으로 `probe-lab`을 사용했다.

```bash
eval $(minikube -p probe-lab docker-env)
docker build -t probe-test:v1 .
```

이미지를 확인한다.

```bash
docker images | grep probe-test
```

예상 출력:

```text
probe-test   v1
```

### 1.4 Namespace 준비

```bash
kubectl create namespace probe-lab
```

이미 존재한다면 다음 메시지는 무시해도 된다.

```text
AlreadyExists
```

### 1.5 Readiness Probe 있는 버전 배포

`with-readiness.yaml` 내용:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: probe-test-with-readiness
  namespace: probe-lab
spec:
  replicas: 3
  selector:
    matchLabels:
      app: probe-test
      version: with-readiness
  template:
    metadata:
      labels:
        app: probe-test
        version: with-readiness
    spec:
      containers:
        - name: app
          image: probe-test:v1
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          env:
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
          readinessProbe:
            httpGet:
              path: /ready
              port: 8080
            initialDelaySeconds: 3
            periodSeconds: 3
            failureThreshold: 10
---
apiVersion: v1
kind: Service
metadata:
  name: probe-test-svc
  namespace: probe-lab
spec:
  selector:
    app: probe-test
    version: with-readiness
  ports:
    - port: 80
      targetPort: 8080
```

배포한다.

```bash
kubectl apply -f with-readiness.yaml
```

## 2. 실험

### 2.1 Pod 3개 Running & Ready 확인

```bash
kubectl get pods -n probe-lab -l app=probe-test -o wide
```

처음에는 Pod가 `Running`이어도 `READY`가 `0/1`로 보일 수 있다.

```text
0/1   Running
0/1   Running
0/1   Running
```

약 30초 뒤 `/ready`가 `200`을 반환하면 `READY`가 `1/1`로 바뀐다.

```text
1/1   Running
1/1   Running
1/1   Running
```

### 2.2 관찰 터미널 준비

#### 터미널 1: Pod 상태 감시

```bash
watch -n 1 "kubectl get pods -n probe-lab -l app=probe-test -o wide"
```

#### 터미널 2: EndpointSlice 감시

```bash
watch -n 1 "kubectl get endpointslices -n probe-lab -l kubernetes.io/service-name=probe-test-svc -o yaml | grep -E 'addresses:|ready:|hostname:'"
```

여기서 확인할 항목:

| 항목 | 의미 |
| --- | --- |
| 삭제된 Pod IP 제거 | Service 대상에서 삭제된 Pod가 빠졌는지 확인 |
| 새 Pod IP의 `ready: false` | 새 Pod가 생성됐지만 아직 트래픽 대상이 아닌 상태 |
| 새 Pod IP의 `ready: true` | readinessProbe 통과 후 트래픽 대상에 포함된 상태 |

#### 터미널 3: 트래픽 연속 전송

로컬 Mac에서 ClusterIP로 바로 요청하지 않고, 클러스터 내부에 curl Pod를 띄워 Service DNS로 요청한다.

```bash
kubectl delete pod -n probe-lab traffic-client --ignore-not-found
```

```bash
kubectl run traffic-client -n probe-lab \
  --image=curlimages/curl \
  --restart=Never \
  --command -- sh -c '
while true; do
  date "+%H:%M:%S"
  curl -s -w "HTTP_STATUS=%{http_code}\n" http://probe-test-svc/
  sleep 0.3
done
'
```

로그를 확인한다.

```bash
kubectl logs -n probe-lab traffic-client -f
```

정상 예시:

```text
ok, pod=probe-test-with-readiness-xxxxx
HTTP_STATUS=200
```

### 2.3 Pod 1개 강제 삭제

터미널 4에서 Pod 목록을 확인한다.

```bash
kubectl get pods -n probe-lab -l app=probe-test
```

Pod 하나를 골라 삭제한다.

```bash
kubectl delete pod -n probe-lab <pod-name>
```

예시:

```bash
kubectl delete pod -n probe-lab probe-test-with-readiness-6c68c88f55-abcde
```

### 2.4 새 Pod가 뜨는 동안 트래픽 확인

Pod 상태 감시 터미널에서 다음 흐름을 관찰한다.

```text
기존 Pod 3개 1/1 Running
-> 삭제한 Pod Terminating
-> 새 Pod 생성
-> 새 Pod 0/1 Running
-> 약 30초 후 새 Pod 1/1 Running
```

`traffic-client` 로그에서는 새 Pod가 `0/1`일 때 등장하면 안 된다.

정상 예시:

```text
ok, pod=probe-test-with-readiness-기존Pod1
HTTP_STATUS=200

ok, pod=probe-test-with-readiness-기존Pod2
HTTP_STATUS=200
```

나오면 안 되는 예시:

```text
app is initializing, pod=probe-test-with-readiness-새Pod
HTTP_STATUS=500
```

`readinessProbe`가 있는 버전에서는 새 Pod가 준비되기 전 Service 트래픽 대상에서 제외되므로 `HTTP_STATUS=500`이 없어야 한다.

### 2.5 새 Pod Ready 후 트래픽 수신 확인

새 Pod가 다음처럼 바뀌면 readinessProbe를 통과한 상태다.

```text
probe-test-with-readiness-새Pod   1/1   Running
```

이후 `traffic-client` 로그에 새 Pod 이름이 등장하는지 확인한다.

```text
ok, pod=probe-test-with-readiness-새Pod
HTTP_STATUS=200
```

EndpointSlice 감시 터미널에서도 새 Pod IP가 `ready: true`로 바뀌는지 확인한다.

### 2.6 50% 동시 삭제 실험

replica가 3개라 절반이면 1개만 삭제될 수 있다. 변화를 명확히 보려면 2개를 삭제해도 된다.

```bash
PODS=($(kubectl get pods -n probe-lab -l app=probe-test -o name | head -n 2))

for pod in "${PODS[@]}"; do
  kubectl delete -n probe-lab $pod &
done

wait
```

관찰할 항목:

| 관찰 항목 | 기대 결과 |
| --- | --- |
| 새 Pod 2개 생성 | `0/1 Running`으로 먼저 생성 |
| Ready 전 트래픽 | 기존 Ready Pod로만 전달 |
| HTTP 에러 | `HTTP_STATUS=500`이 나오지 않음 |
| Ready 후 트래픽 | 약 30초 후 새 Pod들이 `1/1`이 되고 응답 로그에 등장 |

## 3. 관찰 결과

### 3.1 Pod 1개 삭제 관찰

Pod 삭제 후 새 Pod가 생성되는 동안 EndpointSlice에서 다음 상태를 확인했다.

```text
10.244.0.25 ready=true
10.244.0.26 ready=true
10.244.0.27 ready=false
```

의미:

| IP | 상태 | 의미 |
| --- | --- | --- |
| `10.244.0.25` | `ready=true` | 기존 Ready Pod |
| `10.244.0.26` | `ready=true` | 기존 Ready Pod |
| `10.244.0.27` | `ready=false` | 새로 생성된 Pod지만 readinessProbe 통과 전 |

이때 traffic 로그에서는 계속 `HTTP_STATUS=200`만 나왔고, 응답한 Pod도 기존 Ready Pod들이었다.

```text
ok, pod=probe-test-with-readiness-6c68c88f55-6g5q8
HTTP_STATUS=200

ok, pod=probe-test-with-readiness-6c68c88f55-2cqrq
HTTP_STATUS=200
```

중요한 점은 `ready=false`인 `10.244.0.27` Pod가 트래픽을 받지 않았다는 것이다. 그래서 다음과 같은 초기화 중 응답은 나오지 않았다.

```text
app is initializing, pod=probe-test-with-readiness-...
HTTP_STATUS=500
```

### 3.2 새 Pod Ready 후 관찰

이후 EndpointSlice 상태가 다음처럼 바뀌었다.

```text
10.244.0.25 ready=true
10.244.0.26 ready=true
10.244.0.27 ready=true
```

의미:

- 새 Pod가 readinessProbe를 통과했다.
- EndpointSlice에서도 `ready=true`로 바뀌었다.
- 이제 Service 트래픽 대상에 포함될 수 있다.

이후 traffic 로그에서 새 Pod 이름이 등장하면, Ready 이후에만 트래픽을 수신했다는 것을 확인할 수 있다.

### 3.3 50% 동시 삭제 관찰

전체 Pod 중 2개를 동시에 삭제하자 Deployment가 새 Pod 2개를 생성했다.

Pod 상태:

```text
probe-test-with-readiness-6c68c88f55-559ng   0/1   Running   10.244.0.28
probe-test-with-readiness-6c68c88f55-8pgmz   0/1   Running   10.244.0.29
probe-test-with-readiness-6c68c88f55-gxrgx   1/1   Running   10.244.0.27
```

의미:

| Pod | IP | 상태 | 의미 |
| --- | --- | --- | --- |
| `probe-test-with-readiness-6c68c88f55-559ng` | `10.244.0.28` | `0/1 Running` | 새로 생성됐지만 readinessProbe 통과 전 |
| `probe-test-with-readiness-6c68c88f55-8pgmz` | `10.244.0.29` | `0/1 Running` | 새로 생성됐지만 readinessProbe 통과 전 |
| `probe-test-with-readiness-6c68c88f55-gxrgx` | `10.244.0.27` | `1/1 Running` | 기존 Ready Pod |

EndpointSlice 상태:

```text
10.244.0.27 ready=true
10.244.0.28 ready=false
10.244.0.29 ready=false
```

의미:

| IP | EndpointSlice 상태 | 트래픽 수신 여부 |
| --- | --- | --- |
| `10.244.0.27` | `ready=true` | 수신 가능 |
| `10.244.0.28` | `ready=false` | 수신 불가 |
| `10.244.0.29` | `ready=false` | 수신 불가 |

이 상태가 readinessProbe의 핵심 동작이다. Pod는 `Running` 상태지만 readinessProbe를 통과하기 전에는 EndpointSlice에서 `ready=false`로 표시되고, Service 트래픽 대상에서 제외된다.

같은 시간대의 `traffic-client` 로그에서는 기존 Ready Pod인 `gxrgx`만 계속 응답했다.

```text
ok, pod=probe-test-with-readiness-6c68c88f55-gxrgx
HTTP_STATUS=200
```

새 Pod인 `559ng`, `8pgmz`는 `Running` 상태였지만 `ready=false`였기 때문에 트래픽을 받지 않았다. Service는 `ready=true`인 `gxrgx`에게만 요청을 전달했고, 이 동안 `HTTP_STATUS=500` 없이 `200`이 유지되었다.

정리하면 다음과 같다.

| 관찰 항목 | 결과 |
| --- | --- |
| 2개 Pod 동시 삭제 | 새 Pod 2개 생성 확인 |
| 새 Pod 상태 | `0/1 Running` |
| 새 Pod EndpointSlice 상태 | `10.244.0.28 ready=false`, `10.244.0.29 ready=false` |
| 기존 Pod EndpointSlice 상태 | `10.244.0.27 ready=true` |
| Ready 전 트래픽 수신 Pod | `probe-test-with-readiness-6c68c88f55-gxrgx`만 응답 |
| 트래픽 결과 | `HTTP_STATUS=200` 유지, `500` 없음 |

약 30초 후 새 Pod들이 readinessProbe를 통과하면 EndpointSlice 상태가 다음처럼 바뀐다.

```text
10.244.0.27 ready=true
10.244.0.28 ready=true
10.244.0.29 ready=true
```

Pod 상태도 모두 `1/1 Running`이 된다.

```text
probe-test-with-readiness-6c68c88f55-559ng   1/1   Running
probe-test-with-readiness-6c68c88f55-8pgmz   1/1   Running
probe-test-with-readiness-6c68c88f55-gxrgx   1/1   Running
```

이후 `traffic-client` 로그에는 새 Pod들도 등장할 수 있다.

```text
ok, pod=probe-test-with-readiness-6c68c88f55-559ng
HTTP_STATUS=200

ok, pod=probe-test-with-readiness-6c68c88f55-8pgmz
HTTP_STATUS=200
```

이 흐름까지 확인하면 50% 동시 삭제 상황에서도 readinessProbe가 준비되지 않은 Pod를 트래픽 대상에서 제외하고, 준비된 Pod에게만 요청을 전달한다는 것을 확인할 수 있다.

## 4. 결과 정리

| 관찰 항목 | 결과 |
| --- | --- |
| Pod 삭제 후 새 Pod 생성 | 확인 |
| 새 Pod IP EndpointSlice 등록 | `10.244.0.27` 확인 |
| 새 Pod Ready 전 상태 | `ready=false` 확인 |
| Ready 전 트래픽 수신 | 수신하지 않음 |
| 트래픽 에러 | `HTTP_STATUS=500` 없음 |
| Ready 후 EndpointSlice 상태 | `ready=true`로 변경 확인 |
| 2개 Pod 동시 삭제 | 새 Pod 2개는 `ready=false`, 기존 Ready Pod만 응답 |

## 5. 결론

Pod가 삭제되면 Deployment는 replica 수를 맞추기 위해 새 Pod를 생성한다. 새 Pod는 생성 직후 EndpointSlice에 나타날 수 있지만, readinessProbe를 통과하기 전에는 `ready=false` 상태로 남는다.

이 실험에서는 새 Pod가 `ready=false`인 동안 Service 트래픽을 받지 않았고, 기존 Ready Pod들만 요청을 처리했다. 따라서 초기화 중인 새 Pod의 `/` 응답인 `500`은 발생하지 않았다.

한 줄로 정리하면 다음과 같다.

```text
EndpointSlice ready=false -> traffic 대상 제외 -> HTTP_STATUS=500 없음
EndpointSlice ready=true  -> traffic 대상 포함 -> HTTP_STATUS=200
```

이번 실습에서 확인한 핵심은 다음과 같다.

| 핵심 | 확인 내용 |
| --- | --- |
| Deployment의 자가 복구 | Pod를 직접 삭제해도 `replicas: 3`을 맞추기 위해 새 Pod가 자동 생성됨 |
| Running과 Ready의 차이 | 새 Pod는 `Running`이어도 앱 초기화 전에는 `0/1` 상태로 남음 |
| readinessProbe의 트래픽 보호 | EndpointSlice에서 `ready=true`인 Pod만 Service 트래픽을 받고, `ready=false`인 Pod는 제외됨 |

따라서 readinessProbe는 단순한 상태 확인 기능이 아니라, Service가 어떤 Pod에게 트래픽을 전달할지 결정하는 중요한 기준이다. 이를 통해 Pod 교체, 장애 복구, 롤링 업데이트 상황에서 초기화 중인 Pod로 인한 요청 실패를 줄일 수 있다.

```text
이번 실습은 Deployment의 자가 복구 동작과 readinessProbe의 트래픽 보호 역할을 확인한 실험이다.
```

특히 중요한 점은 Pod가 다시 생성되었다는 사실보다, 다시 생성된 Pod가 준비되기 전까지 트래픽을 받지 않았다는 것이다.
