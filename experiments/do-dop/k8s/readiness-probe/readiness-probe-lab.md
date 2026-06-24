# Readiness Probe 실습 기록

## 실습 목적

Kubernetes에서 `readinessProbe` 설정 여부에 따라 Service가 준비되지 않은 Pod로 트래픽을 전달하는지 확인한다.

이 실습에서는 애플리케이션이 시작된 뒤 30초 동안 초기화 상태를 유지하도록 만들고, 다음 두 가지 실험을 비교한다.

- 실험 A: `readinessProbe`가 없는 Deployment
- 실험 B: `/ready` 엔드포인트를 사용하는 `readinessProbe`가 있는 Deployment

## 1. 실험 전 설정

### 1.1 필요한 도구

먼저 아래 도구가 설치되어 있어야 한다.

```bash
docker --version
minikube version
kubectl version --client
```

| 도구 | 용도 |
| --- | --- |
| Docker Desktop | 컨테이너 이미지 빌드/실행 |
| minikube | 로컬 Kubernetes 클러스터 |
| kubectl | Kubernetes 리소스 조작 |
| curl | 트래픽 테스트 |

### 1.2 minikube 클러스터 시작

Docker Desktop이 켜져 있는지 먼저 확인하고, minikube를 시작한다.

```bash
minikube start --profile=probe-lab --driver=docker --cpus=2 --memory=4096
```

정상 실행 여부를 확인한다.

```bash
kubectl get nodes
```

예상 출력:

```text
NAME        STATUS   ROLES           AGE   VERSION
probe-lab   Ready    control-plane   ...   ...
```

현재 `kubectl` context가 어떤 클러스터를 보고 있는지도 확인한다.

```bash
kubectl config current-context
```

minikube profile을 명시적으로 사용하려면 다음 명령어를 실행한다.

```bash
minikube profile probe-lab
```

### 1.3 실험용 디렉토리 확인


현재 실습 디렉토리의 파일 구조:

```text
readiness-probe/
├── main.go
├── Dockerfile
├── no-readiness.yaml
├── with-readiness.yaml
└── traffic-test.sh
```

현재 실습 디렉토리에는 위 파일들이 준비되어 있다.

| 파일 | 역할 |
| --- | --- |
| `main.go` | 30초 후에만 정상 응답하는 Go HTTP 서버 |
| `Dockerfile` | Go 애플리케이션 컨테이너 이미지 빌드 |
| `no-readiness.yaml` | readiness probe가 없는 Kubernetes Deployment와 Service |
| `with-readiness.yaml` | readiness probe가 있는 Kubernetes Deployment와 Service |
| `traffic-test.sh` | 1초 간격으로 HTTP 요청을 보내 성공/실패 횟수 확인 |

### 1.4 Go 앱 작성

`main.go` 내용:

```go
package main

import (
	"fmt"
	"log"
	"net/http"
	"time"
)

var startTime = time.Now()

func isReady() bool {
	return time.Since(startTime) >= 30*time.Second
}

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !isReady() {
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprintln(w, "app is initializing")
			return
		}

		w.WriteHeader(http.StatusOK)
		fmt.Fprintln(w, "ok")
	})

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
```

이 앱은 다음처럼 동작한다.

| 시점 | `/` | `/ready` |
| --- | --- | --- |
| 앱 시작 후 0~30초 | `500` | `503` |
| 앱 시작 후 30초 이후 | `200` | `200` |

즉, 초기화가 30초 걸리는 애플리케이션을 흉내 낸다.

### 1.5 Dockerfile 작성

`Dockerfile` 내용:

```dockerfile
FROM golang:1.22-alpine AS builder

WORKDIR /app
COPY main.go .

RUN go mod init probe-demo && go build -o server main.go

FROM alpine:3.20

WORKDIR /app
COPY --from=builder /app/server .

EXPOSE 8080

CMD ["./server"]
```

### 1.6 minikube Docker 환경 연결

minikube 안에서 로컬 이미지를 쓰려면 현재 터미널의 Docker CLI가 minikube 내부 Docker daemon을 보도록 설정해야 한다.

```bash
eval $(minikube -p probe-lab docker-env)
```

확인:

```bash
docker ps
```

이제 이 터미널에서 빌드하는 이미지는 minikube가 바로 사용할 수 있다.

### 1.7 Docker 이미지 빌드

```bash
docker build -t probe-demo:v1 .
```

이미지 확인:

```bash
docker images | grep probe-demo
```

예상 출력:

```text
probe-demo   v1   ...
```

### 1.8 Namespace 만들기

실험 리소스를 분리하기 위해 namespace를 만든다.

```bash
kubectl create namespace probe-lab
```

앞으로 실험 리소스 조회 명령어에는 `-n probe-lab`을 붙인다.

### 1.9 Readiness Probe 없는 Manifest 작성

`no-readiness.yaml` 내용:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: probe-demo-no-readiness
  namespace: probe-lab
spec:
  replicas: 1
  selector:
    matchLabels:
      app: probe-demo-no-readiness
  template:
    metadata:
      labels:
        app: probe-demo-no-readiness
    spec:
      containers:
        - name: app
          image: probe-demo:v1
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: probe-demo-no-readiness
  namespace: probe-lab
spec:
  selector:
    app: probe-demo-no-readiness
  ports:
    - port: 80
      targetPort: 8080
```

핵심 설정:

```yaml
imagePullPolicy: Never
```

이 설정이 있어야 minikube 내부에 빌드한 로컬 이미지를 사용한다.

### 1.10 Readiness Probe 있는 Manifest 작성

`with-readiness.yaml` 내용:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: probe-demo-with-readiness
  namespace: probe-lab
spec:
  replicas: 1
  selector:
    matchLabels:
      app: probe-demo-with-readiness
  template:
    metadata:
      labels:
        app: probe-demo-with-readiness
    spec:
      containers:
        - name: app
          image: probe-demo:v1
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /ready
              port: 8080
            initialDelaySeconds: 5  # 컨테이너 시작 5초 후부터 /ready 검사
            periodSeconds: 5        # 5초마다 검사
            failureThreshold: 10    # 초기화 중 NotReady 상태를 충분히 허용
---
apiVersion: v1
kind: Service
metadata:
  name: probe-demo-with-readiness
  namespace: probe-lab
spec:
  selector:
    app: probe-demo-with-readiness
  ports:
    - port: 80
      targetPort: 8080
```

의미:

- 컨테이너 시작 5초 후부터 `/ready` 검사
- 5초마다 검사
- `/ready`가 `200`이 되기 전까지 Pod는 `Ready=False`
- `Ready=False` 동안 Service 트래픽 대상에서 제외

### 1.11 트래픽 테스트 스크립트 작성

`traffic-test.sh`에 실행 권한을 부여한다.

```bash
chmod +x traffic-test.sh
```

`traffic-test.sh` 내용:

```bash
#!/bin/bash

URL=$1
COUNT=${2:-45}

if [ -z "$URL" ]; then
  echo "Usage: ./traffic-test.sh <url> [count]"
  exit 1
fi

SUCCESS=0
FAIL=0

for i in $(seq 1 $COUNT); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$URL")

  NOW=$(date +"%H:%M:%S")
  echo "[$NOW] status=$CODE"

  if [ "$CODE" = "200" ]; then
    SUCCESS=$((SUCCESS + 1))
  else
    FAIL=$((FAIL + 1))
  fi

  sleep 1
done

echo "----------------------"
echo "success=$SUCCESS"
echo "fail=$FAIL"
echo "total=$COUNT"
```

### 1.12 실험 전 클러스터 상태 확인

```bash
kubectl get all -n probe-lab
```

처음에는 실험 리소스가 없어야 정상에 가깝다.

## 2. 실험

### 2.1 실험 A: Readiness Probe 없음

#### 2.1.1 배포

```bash
kubectl apply -f no-readiness.yaml
```

Pod 상태를 확인한다.

```bash
kubectl get pods -n probe-lab -w
```

다른 터미널에서 Service port-forward를 실행한다.

```bash
kubectl port-forward -n probe-lab svc/probe-demo-no-readiness 8080:80
```

#### 2.1.2 트래픽 전송

또 다른 터미널에서 테스트 스크립트를 실행한다.

```bash
./traffic-test.sh http://localhost:8080/ 45
```


이 실험에서 봐야 하는 것:

- Readiness Probe가 없으므로 Pod가 `Running`이 되면 초기화 중이어도 Service 트래픽을 받는다.
- 애플리케이션은 시작 후 30초 동안 `/` 요청에 `500`을 반환한다.
- 그 결과 초기 30초 동안 실패 응답이 발생한다.

#### 2.1.3 관찰 결과

실제 테스트에서는 처음에는 `500` 응답이 발생하다가, 애플리케이션 초기화가 끝난 뒤 `200` 응답으로 바뀌었다.

```text
[02:28:56] status=500
[02:28:57] status=500
[02:28:58] status=500
...
[02:29:14] status=500
[02:29:16] status=200
[02:29:17] status=200
[02:29:18] status=200
...
[02:29:43] status=200
----------------------
success=27
fail=18
total=45
```

이 결과는 Readiness Probe가 없을 때 Service가 초기화 중인 Pod에도 트래픽을 전달한다는 것을 보여준다. Pod는 실행 중이지만 애플리케이션은 아직 준비되지 않았기 때문에 초기 요청은 `500`으로 실패했고, 시간이 지난 뒤 준비가 완료되면서 `200` 응답으로 전환되었다.

#### 2.1.4 확인

이벤트를 확인한다.

```bash
kubectl describe pod -n probe-lab -l app=probe-demo-no-readiness
```

Endpoint를 확인한다.

```bash
kubectl get endpoints -n probe-lab probe-demo-no-readiness
```

Readiness Probe가 없으면 일반적으로 Pod가 `Running`인 시점부터 Endpoint에 포함될 수 있다. 즉, 애플리케이션이 실제 요청을 처리할 준비가 되지 않았더라도 Service 트래픽이 전달될 수 있다.

#### 2.1.5 리소스 삭제

다음 실험과 섞이지 않도록 삭제한다.

```bash
kubectl delete -f no-readiness.yaml
```

정리 상태를 확인한다.

```bash
kubectl get all -n probe-lab
```

### 2.2 실험 B: Readiness Probe 있음

#### 2.2.1 배포

```bash
kubectl apply -f with-readiness.yaml
```

Pod 상태를 확인한다.

```bash
kubectl get pods -n probe-lab -l app=probe-demo-with-readiness -w
```

EndpointSlice도 함께 확인한다.

```bash
kubectl get endpointslices -n probe-lab \
  -l kubernetes.io/service-name=probe-demo-with-readiness -w
```

초기에는 Pod가 `Running`이어도 `READY`가 `0/1`로 표시될 수 있다. 약 30초가 지나 `/ready`가 `200`을 반환하면 `READY`가 `1/1`로 변경된다.



#### 2.2.2 클러스터 내부에서 트래픽 전송

처음에는 로컬 Mac에서 `port-forward`를 사용해 Service에 요청을 보냈다.

```bash
kubectl port-forward -n probe-lab svc/probe-demo-with-readiness 8081:80
./traffic-test.sh http://localhost:8081/ 45
```

하지만 이 방식으로 테스트했을 때, Readiness Probe가 설정되어 있음에도 초기화 중인 애플리케이션으로 요청이 전달되어 `500` 응답이 관찰되었다.

```text
[16:25:53] status=500
[16:25:54] status=500
...
[16:26:14] status=500
[16:26:15] status=200
```

이 결과는 실험 B의 기대 결과와 맞지 않는다. Readiness Probe가 정상적으로 동작한다면 `/ready`가 실패하는 동안 Pod는 `Ready=False` 상태가 되고, Service 트래픽 대상에서 제외되어야 한다. 따라서 초기화 중인 Pod까지 요청이 도달해 `/`에서 `500`을 반환하는 흐름은 기대한 관찰이 아니다.

이 문제가 발생한 이유는 `kubectl port-forward svc/...` 방식이 실제 클러스터 내부 Service 라우팅 경로를 그대로 재현하지 않을 수 있기 때문이다. `port-forward`는 로컬 포트와 Kubernetes 리소스를 임시로 연결해주는 디버깅용 기능이며, Service의 일반적인 클러스터 내부 DNS 접근 및 EndpointSlice 기반 트래픽 흐름과 관찰 결과가 다르게 나타날 수 있다.

그래서 Readiness Probe가 Service 트래픽 대상에서 Pod를 제외하는지 정확히 확인하기 위해, 클러스터 내부에 임시 curl Pod를 띄우고 Service DNS로 직접 요청을 보냈다.

임시 curl Pod를 생성한다.

```bash
kubectl run curl-test -n probe-lab --rm -it --image=curlimages/curl -- sh
```

이 명령어를 실행하면 `curl-test`라는 임시 Pod가 `probe-lab` namespace 안에 생성되고, 그 Pod 내부 shell로 진입한다.

그 안에서 Service DNS로 요청을 보낸다.

```bash
for i in $(seq 1 45); do
  date +"%H:%M:%S"
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://probe-demo-with-readiness.probe-lab.svc.cluster.local/
  sleep 1
done
```

요청 경로는 다음과 같다.

```text
curl-test Pod
-> probe-demo-with-readiness.probe-lab.svc.cluster.local
-> Kubernetes Service
-> Ready 상태인 EndpointSlice 대상 Pod
```

이 방식은 실제 클러스터 내부에서 다른 Pod가 Service를 호출하는 것과 같은 흐름이므로, Readiness Probe에 의한 트래픽 차단 효과를 더 정확히 관찰할 수 있다.

#### 2.2.3 관찰 결과

Readiness Probe가 설정된 상태에서 Pod를 재생성하자, Pod는 먼저 `Running` 상태가 되었지만 `READY` 값은 `0/1`로 표시되었다.

```text
probe-demo-with-readiness-7bf8c75697-rdq62   0/1   Running   0   3s
probe-demo-with-readiness-7bf8c75697-rdq62   1/1   Running   0   33s
```

이 결과는 컨테이너 프로세스는 실행 중이지만, 애플리케이션은 아직 트래픽을 받을 준비가 되지 않았다는 의미다. 약 30초가 지나 `/ready` 엔드포인트가 `200`을 반환하면 Readiness Probe가 통과하고 Pod의 `READY` 값이 `1/1`로 변경된다.

같은 구간에서 클러스터 내부 Service DNS로 요청을 보내면 처음에는 `000`이 반환되다가, Pod가 Ready 상태가 된 뒤 `200`으로 바뀐다.

```text
07:33:43
000
07:33:44
000
07:33:45
000
07:33:46
200
07:33:47
200
07:33:48
200
```

여기서 `000`은 curl이 HTTP 응답 코드를 받지 못했다는 뜻이다. 이 실험에서는 Service가 아직 Ready 상태의 Endpoint를 갖지 못해 요청을 정상 전달하지 못한 상태로 해석할 수 있다. 이후 `200`이 반환된 것은 Readiness Probe 통과 후 Pod가 Service 트래픽 대상에 포함되었음을 의미한다.

EndpointSlice도 Pod 교체 과정에서 새 Endpoint가 반영되는 것을 확인할 수 있었다.

```text
probe-demo-with-readiness-vmg7l   IPv4   <unset>   <unset>       0s
probe-demo-with-readiness-vmg7l   IPv4   8080      10.244.0.15   1s
probe-demo-with-readiness-vmg7l   IPv4   8080      10.244.0.15   32s
```

기본 `kubectl get endpointslices` 출력은 Endpoint의 ready 조건을 직접 보여주지는 않는다. 하지만 Pod 상태 변화와 트래픽 테스트 결과를 함께 보면 Readiness Probe가 의도대로 동작했음을 확인할 수 있다.

핵심 패턴은 다음과 같다.

| Pod 상태 | 트래픽 결과 | 의미 |
| --- | --- | --- |
| `0/1 Running` | `000` | 컨테이너는 실행 중이지만 아직 Ready가 아니므로 Service가 정상 트래픽을 전달하지 못함 |
| `1/1 Running` | `200` | Readiness Probe 통과 후 Ready Endpoint로 등록되어 정상 응답 |

중요한 점은 실험 B에서는 초기화 중인 애플리케이션에서 발생하는 `500` 응답이 보이지 않았다는 것이다. Readiness Probe가 준비되지 않은 Pod를 Service 트래픽 대상에서 제외했기 때문이다.

#### 2.2.4 리소스 삭제

실험이 끝난 뒤 리소스를 삭제한다.

```bash
kubectl delete -f with-readiness.yaml
kubectl delete namespace probe-lab
```

## 3. 결과 비교

### 3.1 실험 결과 비교

| 항목 | 실험 A: readinessProbe 없음 | 실험 B: readinessProbe 있음 |
| --- | --- | --- |
| Pod 실행 직후 Service 트래픽 전달 | 전달됨 | 전달되지 않음 |
| 애플리케이션 준비 전 사용자 요청 | `500` 발생 가능 | Ready Endpoint가 없어 `000` 또는 연결 실패 |
| Pod Ready 상태 판단 기준 | 컨테이너 실행 여부 중심 | `/ready` 응답 결과 |
| 장애 영향 | 초기화 중인 Pod에도 요청 전달 | 준비 완료 후에만 요청 전달 |

### 3.2 결론

`readinessProbe`는 Pod가 실행 중인지가 아니라 실제로 요청을 처리할 준비가 되었는지를 Kubernetes에 알려주는 장치다.

실험 A에서는 Readiness Probe가 없기 때문에 Pod가 `Running`이 되면 초기화 중이어도 Service 트래픽을 받았다. 그 결과 애플리케이션이 준비되기 전까지 `500` 응답이 발생할 수 있었다.

실험 B에서는 `/ready` 엔드포인트가 `200`을 반환하기 전까지 Pod가 `0/1 Running` 상태로 남아 있었고, Service 트래픽 대상에서 제외되었다. 이후 Readiness Probe가 통과하자 Pod가 `1/1 Running`으로 바뀌고 트래픽 결과도 `200`으로 변경되었다.

한 줄로 정리하면 다음과 같다.

```text
Pod: 0/1 Running -> traffic: 000
Pod: 1/1 Running -> traffic: 200
```
