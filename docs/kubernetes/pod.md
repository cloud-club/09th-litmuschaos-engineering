# Kubernetes Pod

> Kubernetes 공식 문서와 `Pods` 개념을 바탕으로, Pod를 단순 실행 단위로 이해하는 것을 넘어 Chaos Engineering 관점에서 Pod 장애를 어떻게 바라보고 검증할 수 있는지 정리한다.

1. Pod의 기본 개념
2. Pod가 Kubernetes에서 가지는 운영적 의미
3. Pod 장애를 Chaos Engineering으로 검증하는 이유
4. Pod Delete, Graceful Shutdown, Resource Chaos 실험 설계
5. Go 애플리케이션과 Kubernetes Manifest 예시
---

## 1. Pod란 무엇인가?
Kubernetes 공식 문서에 따르면 **Pod는 Kubernetes에서 생성하고 관리할 수 있는 가장 작은 배포 단위이다.**
> "Pods are the smallest deployable units of computing that you can create and manage in Kubernetes."

Docker를 사용할 때는 보통 컨테이너 하나를 직접 실행한다고 생각하는 반면, Kubernetes에서는 컨테이너를 직접 배포 단위로 다루기보다, 컨테이너를 **Pod라는 단위 안에 담아서 관리**한다.
```text
Docker 관점:
컨테이너 실행

Kubernetes 관점:
Pod 생성 → Pod 안에서 컨테이너 실행
```
즉, Pod는 다음처럼 이해할 수 있다.
```text
Pod = 컨테이너를 실행하기 위한 Kubernetes의 최소 실행 단위
```

그리고 Pod는 하나 이상의 컨테이너를 포함할 수 있고, Pod 안의 컨테이너들은 다음 자원을 공유할 수 있다.
- Network
- Storage Volume
- 실행 관련 설정

따라서 Pod는 단순히 컨테이너 하나를 감싸는 Wrapper가 아니라, Kubernetes가 애플리케이션 인스턴스를 실행하고 관리하기 위한 기본 단위라고 볼 수 있다.

## 2. 왜 컨테이너가 아닌 Pod가 최소 배포 단위인가?

Kubernetes는 컨테이너를 직접 스케줄링하지 않고, Pod를 스케줄링한다.
그 이유는 Pod가 하나의 **논리적 호스트(logical host)** 역할을 하기 때문이다.

즉, 같은 Pod 안에 있는 컨테이너들은 항상 같은 Node에 배치되고, 같은 생명주기 안에서 실행된다.
예를 들어, 하나의 서버에서 다음 프로세스들이 함께 실행된다고 생각해보자.
```text
하나의 서버 또는 VM
- Main Application
- Log Collector
- Config Reloader
```
Kubernetes에서는 이를 아래처럼 표현할 수 있다.
```text
하나의 Pod
├── app container
├── log-sidecar container
└── shared volume
```

## 3. Pod의 일반적인 사용 방식
### 3-1. 단일 컨테이너 Pod
가장 일반적인 형태는 하나의 Pod 안에 하나의 컨테이너를 실행하는 방식이다.
```text
Pod
└── spring-app container
```
실제로 대부분의 애플리케이션 배포는 이 구조를 사용한다.
예를 들어 Spring Boot 애플리케이션 하나를 Kubernetes에 배포한다면 보통 다음과 같은 구조가 된다.
```text
Deployment
└── ReplicaSet
    ├── Pod 1
    │   └── spring-app container
    ├── Pod 2
    │   └── spring-app container
    └── Pod 3
        └── spring-app container
```
여기서 중요한 점은 애플리케이션을 확장할 때 컨테이너를 한 Pod 안에 여러 개 넣는 것이 아니라, Pod 개수를 늘린다는 것이다.

### 3-2. 멀티 컨테이너 Pod
Pod는 하나 이상의 컨테이너를 포함할 수 있다.
하지만, 여러 컨테이너를 하나의 Pod 안에 넣는 것은 일반적인 확장 방식이 아니라, 컨테이너들이 서로 강하게 결합되어 있을 때 사용하는 방식이다.
대표적인 예시는 **Sidecar Pattern**이다.
```text
Pod
├── app container
└── sidecar container
```
예를 들어 다음과 같은 구조를 생각할 수 있다.
```text
Pod
├── web-app container
└── log-agent container
```
`web-app container`는 로그 파일을 shared volume에 기록하고, `log-agent container`는 같은 volume에서 로그를 읽어 외부 로그 시스템으로 전송한다.
이때 두 컨테이너는 다음 특징을 가진다.
- 같은 Node에서 함께 실행되어야 한다.
- 같은 Pod 생명주기를 공유한다.
- 같은 네트워크 네임스페이스를 공유한다.
- 같은 volume을 공유할 수 있다.

따라서 멀티 컨테이너 Pod는 독립적으로 확장 가능한 서비스들을 하나로 묶는 방식이 아니라, 함께 동작해야 하는 보조 컨테이너를 붙이는 방식으로 이해해야 한다.

## 4. Pod와 Scale-out
Pod 하나는 보통 애플리케이션 인스턴스 하나를 의미한다.
따라서 트래픽이 증가해 애플리케이션을 확장해야 한다면, 다음처럼 같은 애플리케이션을 실행하는 Pod의 개수를 늘린다.
```text
Before:
Pod 1

After:
Pod 1
Pod 2
Pod 3
```
Kubernetes에서는 이를 직접 Pod를 여러 개 생성해서 관리하기보다, 보통 Deployment를 통해 관리한다.
```text
Deployment = 원하는 상태를 선언
ReplicaSet = 지정된 수의 Pod replica 유지
Pod = 실제 애플리케이션 인스턴스 실행
```
예를 들어 다음과 같이 Deployment에 `replicas: 3`을 설정하면 Kubernetes는 항상 Pod가 3개가 실행되도록 유지하려고 한다.
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pod-chaos-demo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: pod-chaos-demo
  template:
    metadata:
      labels:
        app: pod-chaos-demo
    spec:
      containers:
        - name: app
          image: nginx:1.25
          ports:
            - containerPort: 80
```

이때 Pod 하나가 삭제되면 ReplicaSet은 다시 새로운 Pod를 생성해서 desired state를 맞추려고 한다.

---

## 5. Pod는 직접 만들기보다 Controller로 관리한다.
Kubernetes에서는 Pod를 직접 생성할 수도 있다.
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: nginx
spec:
  containers:
    - name: nginx
      image: nginx:1.25
      ports:
        - containerPort: 80
```
하지만 운영 환경에서는 보통 Pod를 직접 생성하진 않는다.
대신 다음과 같은 Workload Resource를 사용한다.
- Deployment
- StatefulSet
- DaemonSet
- Job
- CronJob

그 이유는 Pod가 기본적으로 일시적이고 disposable한 존재이기 때문이다.
> Usually you don't need to create Pods directly.

그래서 Pod는 죽으면 기존 Pod가 다른 Node로 이동하는 것이 아니라, 컨트롤러가 새로운 Pod를 생성하는 것이다.

즉, Kubernetes에서 중요한 것은 개별 Pod를 오래 살리는 것이 아니라, Pod가 사라져도 원하는 상태가 유지되도록 Controller가 복구하는 구조이다.

## 6. Pod의 생명주기
Pod는 생성된 뒤 다음과 같은 상태를 거친다.

```text
Pending → Running → Succeeded / Failed
```
대표적인 Pod 상태는 다음과 같다.

| 상태 | 의미 |
| --- | --- |
| Pending | Pod가 생성되었지만 아직 모든 컨테이너가 실행되지는 않은 상태 |
| Running | Pod가 Node에 바인딩되었고 하나 이상의 컨테이너가 실행 중인 상태 |
| Succeeded | Pod 안의 모든 컨테이너가 정상 종료된 상태 |
| Failed | Pod 안의 컨테이너 중 하나 이상이 실패로 종료된 상태 |
| Unknown | Node와 통신할 수 없어 Pod 상태를 알 수 없는 상태 |

여기서 중요한 건 Pod 상태가 Running이라고 해서 애플리케이션이 정상적으로 요청을 처리할 수 있다는 뜻은 아니다.
예를 들어 다음과 같은 상황이 있을 수 있다.
```text
Pod 상태: Running
Container 상태: Running
Application 상태: Deadlock
Service 상태: 요청 처리 불가
```
이런 문제를 감지하기 위해 Probe가 필요하다.  

다만 이 문서에서는 Probe 자체보다는, Pod가 죽거나 종료되거나 재생성되는 상황에서 Kubernetes가 어떻게 동작하는지에 초점을 둔다.

## 7. Pod의 네트워크와 스토리지 공유
### 7-1. 네트워크 공유
각 Pod는 고유한 IP 주소를 가진다.
같은 Pod 안의 컨테이너들은 네트워크 네임스페이스를 공유하므로 `localhost`로 서로 통신할 수 있다.
```text
Pod IP: 10.1.2.3

Pod
├── app container        localhost:8080
└── sidecar container    localhost:9000
```
> 같은 Pod 내부에서는 다음과 같이 접근할 수 있다.

```text
app container → sidecar container
http://localhost:9000
```
반면, 서로 다른 Pod는 각자 다른 IP를 가지므로, 보통 Service를 통해 통신한다.
```text
client Pod → Service → app Pod들
```
---

### 7-2. 스토리지 공유
Pod는 Volume을 통해 여러 컨테이너가 같은 파일 시스템 영역을 공유할 수 있다.
```text
Pod
├── app container
├── log-sidecar container
└── shared volume
```

예를 들어 app container가 `/logs/app.log`에 로그를 쓰고, sidecar container가 같은 파일을 읽어 외부 시스템으로 전송할 수 있다.

## 8. Chaos Engineering 관점에서 Pod를 보는 이유
Chaos Engineering은 시스템에 의도적으로 장애를 주입하여, 실제 장애 상황에서도 시스템이 기대한 방식으로 동작하는지 검증하는 방법이다.
Pod 관점에서 Chaos Engineering의 핵심 질문은 하기와 같다고 생각했다.

```text
Pod가 죽지 않는가?
```
가 아니라,
```text
Pod가 죽어도 서비스는 기대한 방식으로 복구되는가?
```
Kubernetes 환경에서 Pod는 언제든 다음의 이유로 종료될 수 있다.
- 애플리케이션 crash
- 메모리 초과로 인한 OOMKilled
- Node 장애
- Rolling Update
- 사용자의 Pod 삭제
- autoscaling 또는 drain에 의한 재스케줄링
- 네트워크 장애
- storage 장애

따라서 안정적인 Kubernetes 시스템을 만들려면 Pod가 절대 죽지 않게 만드는 것보다, Pod가 죽어도 서비스가 유지되도록 설계하는 것이 중요하다.

## 9. 이번 Pod Chaos Engineering의 핵심 실험 주제
Pod를 주제로 할 수 있는 Chaos Engineering 실험을 아래처럼 뽑아봤다.

| 실험 주제                   | 핵심 질문                              | 기대 검증 내용                                  |
| ----------------------- | ---------------------------------- | ----------------------------------------- |
| Pod Delete Chaos        | Pod가 삭제되어도 서비스가 유지되는가?             | ReplicaSet 복구, Service 트래픽 유지             |
| Graceful Shutdown Chaos | Pod 종료 중 요청이 안전하게 마무리되는가?          | SIGTERM 처리, terminationGracePeriodSeconds |
| Resource Chaos          | CPU/Memory 압박에 어떻게 반응하는가?          | OOMKilled, CPU throttling, restart count  |
| Network Chaos           | Pod 간 네트워크 지연이 장애로 전파되는가?          | timeout, retry, circuit breaker 필요성       |
| Sidecar Chaos           | sidecar 장애가 전체 Pod 기능에 어떤 영향을 주는가? | multi-container Pod의 관찰 가능성               |

본 문서에서는 그 중 가장 Pod 개념과 직접적으로 연결되는 세 가지 실험을 중심으로 정리하고자 한다.
1. Pod Delete Chaos
2. Graceful Shutdown Chaos
3. Resource Chaos
---

## 10. 실험 1: Pod Delete Chaos
### 10-1. 실험 목적
Pod 하나가 강제로 삭제되었을 때 Kubernetes가 새로운 Pod를 생성하여 우너하는 replicat 수를 회복하는지 확인한다.
### 10-2. 실험 가설
```text
Deployment로 관리되는 Pod 하나가 삭제되더라도,
ReplicaSet이 새로운 Pod를 생성하여 원하는 replica 수를 회복할 것이다.

replica가 2개 이상이면 일부 Pod 장애가 발생해도
Service는 나머지 정상 Pod로 트래픽을 전달할 수 있다.
```

### 10-3. 실험 구성
```text
Client
  ↓
Service
  ↓
Deployment(replicas: 3)
  ├── Pod 1
  ├── Pod 2
  └── Pod 3
```
### 10-4. Kubernetes Manifest
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pod-chaos-demo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: pod-chaos-demo
  template:
    metadata:
      labels:
        app: pod-chaos-demo
    spec:
      containers:
        - name: app
          image: nginx:1.25
          ports:
            - containerPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: pod-chaos-demo-service
spec:
  selector:
    app: pod-chaos-demo
  ports:
    - port: 80
      targetPort: 80
```
### 10-5. 실험 명령어
```bash
# 리소스 생성
kubectl apply -f pod-chaos-demo.yaml

# Pod 목록 확인
kubectl get pods -l app=pod-chaos-demo

# Service 확인
kubectl get svc pod-chaos-demo-service

# Pod 하나 삭제
kubectl delete pod <pod-name>

# Pod 복구 과정 관찰
kubectl get pods -l app=pod-chaos-demo -w
```
### 10-6. 관찰 포인트
```text
1. 삭제된 Pod가 Terminating 상태가 되는가?
2. 새로운 Pod가 생성되는가?
3. 새로운 Pod가 Pending → ContainerCreating → Running 상태로 전환되는가?
4. 전체 replica 수가 다시 3개로 회복되는가?
5. Service 요청은 계속 성공하는가?
```

### 10-7. 추가 비교 실험
### Case 1. replicas: 1
```yaml
spec:
  replicas: 1
```
예상 결과:
```text
Pod 하나가 삭제되면 새 Pod가 뜰 때까지 서비스 중단 가능성이 크다.
```

### Case 2. replicas: 3
```yaml
spec:
  replicas: 3
```
예상 결과:
```text
Pod 하나가 삭제되어도 나머지 Pod가 트래픽을 처리할 수 있다.
```

### 10-8. 실험에서 얻을 수 있는 결론
```text
Pod는 영구적인 서버가 아니라 언제든 사라질 수 있는 실행 단위이다.
Kubernetes는 Pod 자체를 살리는 것이 아니라,
Controller를 통해 원하는 수의 Pod가 유지되도록 복구한다.

따라서 고가용성을 위해서는 단일 Pod가 아니라
Deployment, ReplicaSet, Service를 함께 고려해야 한다.
```
---

## 11. 실험 2: Graceful Shutdown Chaos
### 11-1. 실험 목적
Pod가 종료될 때 진행 중인 요청을 안전하게 마무리하고 종료되는지 확인한다.

### 11-2. 배경 개념
Pod가 삭제되면 Kubernetes는 컨테이너를 즉시 강제 종료하지 않는다.
일반적으로 먼저 컨테이너의 메인 프로세스에 `SIGTERM`을 보내고, `terminationGracePeriodSeconds` 동안 정상 종료를 기다린다.
만약 grace period 안에 종료되지 않으면 Kubernetes는 `SIGKILL`을 보내 강제 종료한다.
즉, 애플리케이션이 `SIGTERM`을 제대로 처리하지 않으면 다음 문제가 발생할 수 있다.
- 처리 중인 요청 실패
- DB transcation 중단
- 메세지 처리 중복
- 로그 유실
- 사용자 응답 실패

### 11-3. 실험 가설
```text
애플리케이션이 graceful shutdown을 구현하지 않으면,
Pod 삭제 시 처리 중이던 요청이 실패할 수 있다.

반대로 graceful shutdown을 구현하면,
Pod 종료 요청을 받아도 기존 요청을 마무리한 뒤 안전하게 종료할 수 있다.
```
---

### 11-4. Go 애플리케이션 예시
아래 코드는 `/slow` 요청을 받으면 10초 뒤 응답하는 간단한 서버이다.  
`SIGTERM`을 받으면 새로운 요청 수신을 중단하고, 기존 요청이 끝날 시간을 기다린다.
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
---
### 11-5. Kubernetes Manifest
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
          image: your-dockerhub-id/graceful-demo:latest
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            periodSeconds: 3
```

### 11-6. 실험 방법
터미널 1에서 요청을 보낸다.
```bash
curl http://<service-url>/slow
```
요청이 끝나기 전에 터미널 2에서 Pod를 삭제한다.
```bash
kubectl delete pod <pod-name>
```
Pod 로그를 확인한다.
```bash
kubectl logs <pod-name>
```

### 11-7. 관찰 포인트
```text
1. SIGTERM 수신 로그가 출력되는가?
2. /slow 요청이 중간에 끊기지 않고 완료되는가?
3. terminationGracePeriodSeconds 안에 정상 종료되는가?
4. grace period가 너무 짧으면 요청이 실패하는가?
```

### 11-8. 비교 실험
| 조건 | 예상 결과 |
| --- | --- |
| graceful shutdown 없음 | 요청 중간에 connection reset 가능 |
| graceful shutdown 있음 | 기존 요청 마무리 후 종료 |
| `terminationGracePeriodSeconds: 5` | 10초 요청을 처리하기 전에 강제 종료될 수 있음 |
| `terminationGracePeriodSeconds: 20` | 10초 요청 완료 후 정상 종료 가능 |

### 11-9. 실험에서 얻을 수 있는 결론
```text
Pod 종료는 단순히 컨테이너가 죽는 이벤트가 아니라,
트래픽 제거, SIGTERM 처리, grace period, 강제 종료가 연결된 과정이다.

Kubernetes 환경에서 안정적인 서비스를 만들려면
애플리케이션 코드도 Kubernetes의 종료 모델에 맞게 설계해야 한다.
```
---

## 12. 실험 3: Resource Chaos
### 12-1. 실험 목적
Pod가 CPU 또는 Memory 압박을 받을 때 어떤 상태 변화가 발생하는지 확인

### 12-2. 배경 개념
Kubernetes에서는 컨테이너별로 resource requests와 limits를 설정할 수 있다.
```yaml
resources:
  requests:
    cpu: "100m"
    memory: "128Mi"
  limits:
    cpu: "500m"
    memory: "256Mi"
```
- `requests`: 스케줄링 시 필요한 최소 리소스 기준
- `limits`: 컨테이너가 사용할 수 있는 최대 리소스 제한

Memory limit을 초과하면 컨테이너가 `OOMKilled` 될 수 있다.  
CPU limit이 낮으면 컨테이너가 죽지는 않지만 CPU throttling으로 응답 시간이 증가할 수 있다.

### 12-3. 실험 가설
```text
Memory limit이 설정된 Pod가 제한을 초과하면 OOMKilled 되고,
컨테이너 restart count가 증가할 것이다.

CPU limit이 낮은 Pod에 부하를 주면 응답 시간이 증가하고,
심하면 readiness/liveness probe 실패로 이어질 수 있다.
```
---

### 12-4. Memory Chaos 예시 Pod
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: memory-chaos-demo
spec:
  containers:
    - name: memory-demo
      image: polinux/stress
      command: ["stress"]
      args: ["--vm", "1", "--vm-bytes", "300M", "--vm-hang", "1"]
      resources:
        limits:
          memory: "128Mi"
        requests:
          memory: "64Mi"
```

### 12-5. 실험 명령어
```bash
kubectl apply -f memory-chaos-demo.yaml

kubectl get pod memory-chaos-demo -w

kubectl describe pod memory-chaos-demo

kubectl logs memory-chaos-demo --previous
```

### 12-6. 관찰 포인트
```text
1. Pod 상태가 어떻게 변하는가?
2. describe 결과에 OOMKilled가 표시되는가?
3. restart count가 증가하는가?
4. logs --previous로 이전 컨테이너 로그를 볼 수 있는가?
```

### 12-7. 실험에서 얻을 수 있는 결론
```text
Pod 장애는 항상 코드 오류로만 발생하지 않는다.
메모리 제한, CPU 제한, 노드 리소스 압박 같은 운영 환경 요인으로도 Pod는 실패할 수 있다.

따라서 Kubernetes 운영에서는 requests/limits 설정과 모니터링이 중요하다.
```
---

## 13. LimusChaos를 활용한 Pod Delete 실험
직접 `kubectl delete pod`를 사용하는 것도 좋은 실험이지만, Chaos Engineering 도구를 사용하면 더 체계적으로 장애를 주입하고 결과를 기록할 수 있다.
대표적인 도구로 LitmusChaos가 있다.  
LitmusChaos의 `pod-delete` 실험은 애플리케이션의 특정 replica 또는 랜덤 replica에 대해 Pod 실패를 재현하고, replica availability와 recovery workflow를 검증하는 데 사용할 수 있다.

### 13-1. ChaosEngine 예시
```yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: pod-delete-chaos
  namespace: default
spec:
  engineState: "active"
  annotationCheck: "false"
  appinfo:
    appns: "default"
    applabel: "app=pod-chaos-demo"
    appkind: "deployment"
  chaosServiceAccount: litmus-admin
  experiments:
    - name: pod-delete
      spec:
        components:
          env:
            - name: TOTAL_CHAOS_DURATION
              value: "30"
            - name: CHAOS_INTERVAL
              value: "10"
            - name: FORCE
              value: "false"
            - name: PODS_AFFECTED_PERC
              value: "50"
```

### 13-2. 관찰 포인트
```text
1. 전체 Pod 중 몇 %가 삭제되는가?
2. Deployment가 replica 수를 복구하는가?
3. Service 응답은 유지되는가?
4. ChaosResult가 Pass/Fail 중 어떤 결과를 보여주는가?
5. replica 수가 적을수록 장애 영향이 커지는가?
```
---

## 14. Pod Chaos Engineering 정리
Pod를 Chaos Engineering 관점에서 봄녀 핵심은 다음과 같다.
```text
Pod는 죽지 않아야 하는 서버가 아니다.
Pod는 언제든 죽고 다시 만들어질 수 있는 애플리케이션 인스턴스이다.
```
따라서 Kubernetes 환경에서 안정적인 서비스를 만들기 위해서는 다음 질문에 답할 수 있어야 한다.
```text
1. Pod가 삭제되어도 서비스가 유지되는가?
2. Pod가 종료될 때 요청을 안전하게 마무리하는가?
3. Pod가 리소스 압박을 받을 때 어떤 상태로 실패하는가?
4. 장애 발생 시 Kubernetes가 원하는 상태로 복구하는가?
5. 애플리케이션 코드는 Kubernetes의 생명주기 모델에 맞게 작성되어 있는가?
```
최종적으로 Pod Chaos Engineering의 목적은 다음과 같이 정리할 수 있다.
> Pod Chaos Engineering은 “Pod가 죽지 않는가?”를 확인하는 것이 아니라,  
> “Pod가 죽어도 시스템이 기대한 방식으로 복구되는가?”를 검증하는 과정이다.
---

## 15. 참고 자료
### Kubernetes 공식 문서
- [Kubernetes Docs - Pods](https://kubernetes.io/docs/concepts/workloads/pods/)

- [Kubernetes Docs - Pod Lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)

- [Kubernetes Docs - Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)

- [Kubernetes Docs - ReplicaSet](https://kubernetes.io/docs/concepts/workloads/controllers/replicaset/)

- [Kubernetes Docs - Kubernetes Self-Healing](https://kubernetes.io/docs/concepts/architecture/self-healing/)

- [Kubernetes Docs - Resource Management for Pods and Containers](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/)

- [Kubernetes Docs - Container Lifecycle Hooks](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/)

### Chaos Engineering / LitmusChaos
- [LitmusChaos Docs - What is LitmusChaos?](https://docs.litmuschaos.io/docs/introduction/what-is-litmus)

- [LitmusChaos Experiments - Pod Delete](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-delete/)

- [LitmusChaos Docs - Chaos Experiment Concepts](https://docs.litmuschaos.io/docs/concepts/chaos-workflow)

### 추가 참고

- [CNCF Blog - Decoding the Pod Termination Lifecycle in Kubernetes](https://www.cncf.io/blog/2024/12/19/decoding-the-pod-termination-lifecycle-in-kubernetes-a-comprehensive-guide/)

- [Google Cloud Blog - Kubernetes Best Practices: Terminating with Grace](https://cloud.google.com/blog/products/containers-kubernetes/kubernetes-best-practices-terminating-with-grace)

