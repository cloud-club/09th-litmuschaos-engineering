# Kubernetes Probe

## 1. What is a Probe?

Kubernetes Probe는 kubelet이 컨테이너 상태를 확인하기 위해 주기적으로 수행하는 진단이다. Pod가 실행되는 동안 kubelet은 컨테이너를 관리하고, 애플리케이션의 health를 추적하기 위해 probe를 실행한다. 즉, probe는 Pod lifecycle 관리의 일부이다.

Kubernetes는 probe 결과를 기반으로 비정상 컨테이너를 재시작하거나, 아직 준비되지 않은 컨테이너로 트래픽을 보내는 것을 중단할 수 있다.

Pod가 `Running` 상태라고 해서 애플리케이션이 요청을 정상적으로 처리할 수 있다는 의미는 아니다. 예를 들어 프로세스는 살아 있지만 deadlock 상태에 빠져 요청을 처리하지 못할 수 있다. 이런 상태를 구분하기 위해 probe가 필요하다.

> kubelet은 Kubernetes의 각 Node에서 Pod와 컨테이너를 관리하는 agent이다.

---

## 2. Probe Types

Kubernetes에서 주로 사용하는 probe는 다음 세 가지이다.

- Startup probe
- Liveness probe
- Readiness probe

![Kubernetes Probe Diagram with Pod Lifecycle](./images/Kubernetes%20Probe%20Diagram%20with%20Pod%20Lifecycle.png)

Probe는 컨테이너의 `PostStart` hook이 완료된 후 동작하기 시작한다. Hook은 container lifecycle 동안 특정 시점에 작업을 수행할 수 있도록 하는 메커니즘이다.

### Startup Probe

Startup probe는 컨테이너 내부의 애플리케이션이 시작되었는지 확인한다. Startup probe가 설정되어 있으면, 이 probe가 성공할 때까지 Kubernetes는 liveness probe와 readiness probe를 실행하지 않는다. 이를 통해 애플리케이션이 초기화를 완료할 시간을 확보할 수 있다.

Startup probe는 컨테이너 시작 시점에 사용된다. 실패 기준을 초과하면 kubelet은 컨테이너를 종료하고, 해당 컨테이너는 자신의 `restartPolicy`에 따라 처리된다.

### Liveness Probe

Liveness probe는 컨테이너가 계속 실행되어도 되는 상태인지 확인하고, 언제 재시작할지 결정한다. 예를 들어 프로세스는 실행 중이지만 더 이상 작업을 진행할 수 없는 deadlock 상태라면, 컨테이너를 재시작하는 것이 애플리케이션을 더 사용 가능한 상태로 유지하는 데 도움이 될 수 있다.

컨테이너가 설정된 liveness probe 실패 허용 횟수를 초과하면 kubelet은 `restartPolicy`에 따라 해당 컨테이너를 재시작한다. Liveness probe는 readiness probe가 성공할 때까지 기다리지 않는다. 실행 전 대기 시간이 필요하다면 `initialDelaySeconds`를 정의하거나 startup probe를 사용할 수 있다.

### Readiness Probe

Readiness probe는 컨테이너가 트래픽을 받을 준비가 되었는지 결정한다. 애플리케이션이 네트워크 연결 설정, 파일 로딩, 캐시 워밍업처럼 시간이 오래 걸리는 초기 작업을 수행할 때 유용하다. 또한 일시적인 오류나 과부하에서 복구하는 경우처럼 컨테이너 lifecycle 후반에도 사용할 수 있다.

Readiness probe가 실패하면 EndpointSlice controller는 해당 Pod와 매칭되는 Service의 EndpointSlice에서 그 Pod의 IP 주소를 제거한다. 이 경우 Pod는 살아 있지만 Service를 통한 트래픽 대상에서는 제외된다. Readiness probe는 컨테이너의 전체 lifecycle 동안 실행된다.

---

## 3. When to Use Each Probe

### Startup Probe

Startup probe는 서비스 가능한 상태가 되기까지 시간이 오래 걸리는 컨테이너에 사용한다.

예시는 다음과 같다.

- AI 모델 로딩
- 대용량 설정 파일 로딩
- DB migration
- 초기 캐시 생성

긴 liveness interval을 설정하는 대신, 컨테이너가 시작될 때만 검사하는 별도의 startup probe를 구성할 수 있다. 이를 통해 liveness probe가 허용하는 시간보다 더 긴 초기화 시간을 줄 수 있다.

즉, startup probe는 초기 구동이 긴 애플리케이션을 liveness probe가 너무 빨리 재시작하지 않도록 보호하는 역할을 한다.

### Liveness Probe

Probe가 실패했을 때 컨테이너가 종료되고 재시작되기를 원한다면 liveness probe를 지정하고 `restartPolicy`를 `Always` 또는 `OnFailure`로 지정한다.

Liveness probe가 유용한 상황은 다음과 같다.

- 애플리케이션 deadlock
- 무한 대기 상태
- 프로세스는 살아 있지만 요청을 처리하지 못하는 상태

문제가 생기면 스스로 crash되는 프로세스에는 liveness probe가 불필요할 수 있다. 또한 너무 민감하게 설정하면 일시적인 지연이나 부하 상황에서 컨테이너가 반복적으로 재시작되어 장애를 키울 수 있다.

### Readiness Probe

Probe가 성공했을 때만 Pod로 트래픽을 보내고 싶다면 readiness probe를 지정한다.

Readiness probe가 유용한 상황은 다음과 같다.

- 애플리케이션 초기화 중
- DB 연결 대기
- 캐시 로딩
- 일시적 과부하
- 유지보수를 위해 애플리케이션이 스스로 트래픽 대상에서 빠져야 하는 경우

애플리케이션이 백엔드 서비스에 강하게 의존한다면 liveness probe와 readiness probe를 함께 구현할 수 있다. 다만 readiness probe는 트래픽 수신 가능 여부를 판단하고, liveness probe는 컨테이너 재시작 여부를 판단한다는 차이를 유지해야 한다.

---

## 4. Check Mechanisms

Probe를 사용하여 컨테이너를 검사하는 방법은 네 가지가 있으며, 하나의 probe에는 반드시 하나의 check mechanism만 정의해야 한다.

### exec

컨테이너 내부에서 지정된 명령어를 실행한다. 명령어가 상태 코드 `0`으로 종료되면 진단이 성공한 것으로 간주한다.

`exec` 방식은 실행될 때마다 새로운 프로세스를 만들기 때문에, 너무 자주 실행하면 CPU와 프로세스 생성 비용이 부담될 수 있다.

```yaml
livenessProbe:
  exec:
    command:
      - cat
      - /tmp/healthy
```

위 설정은 컨테이너 내부에서 다음 명령어를 실행한 것과 같다.

```bash
cat /tmp/healthy
```

### grpc

gRPC를 사용하여 원격 프로시저 호출을 수행한다. 대상 애플리케이션은 gRPC health checking protocol을 구현해야 하며, 응답 상태가 `SERVING`이면 진단이 성공한 것으로 간주한다.

> RPC(Remote Procedure Call)는 다른 서버에 있는 함수를 내 코드 안의 함수처럼 호출하는 방식이다. 즉, 서버 간 통신을 함수 호출처럼 보이게 만든다.

### httpGet

지정된 포트와 경로에 대해 Pod의 IP 주소로 HTTP GET 요청을 수행한다. 응답 상태 코드가 `200` 이상 `400` 미만이면 진단이 성공한 것으로 간주한다. 가장 흔하게 사용되는 방식이다.

```yaml
readinessProbe:
  httpGet:
    path: /ready
    port: 8080
```

위 설정은 다음 요청과 같다.

```http
GET http://<Pod-IP>:8080/ready
```

### tcpSocket

지정된 포트에 대해 Pod의 IP 주소로 TCP 검사를 수행한다. 포트가 열려 있고 TCP 연결을 만들 수 있으면 진단이 성공한 것으로 간주한다.

```yaml
livenessProbe:
  tcpSocket:
    port: 3306
```

위 설정은 kubelet이 Pod IP의 `3306` 포트에 TCP 연결을 시도하는 것과 같다.

---

## 5. Probe Results

각 probe는 세 가지 결과 중 하나를 가진다.

### Success

컨테이너가 진단을 통과한 상태이다.

### Failure

컨테이너가 진단에 실패한 상태이다.

Liveness probe와 startup probe가 실패 기준을 초과하면 kubelet은 컨테이너를 종료하고, 컨테이너는 자신의 `restartPolicy`에 따라 처리된다.

Readiness probe가 실패하면 컨테이너는 not ready 상태로 표시되고, 해당 Pod는 매칭되는 Service로부터 트래픽을 받지 않는다.

### Unknown

진단 자체를 정상적으로 수행하지 못한 상태이다. 이 경우 kubelet은 별도의 조치를 취하지 않고 이후 probe를 계속 수행한다.

### What if there is no Probe?

Startup probe나 liveness probe가 없으면 해당 검사는 성공한 것으로 간주된다.

Readiness probe가 정의되지 않은 컨테이너는 기본적으로 ready 상태로 간주된다. 단, readiness probe가 정의되어 있는 경우에는 첫 성공 전까지 ready 상태가 아니다.

---

## 6. Configuration Fields

Probe에는 검사의 동작을 더 정밀하게 제어할 수 있는 여러 필드가 있다.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: probe-example
spec:
  containers:
    - name: app
      image: registry.k8s.io/e2e-test-images/agnhost:2.40
      ports:
        - containerPort: 8080
      startupProbe:
        httpGet:
          path: /healthz
          port: 8080
        failureThreshold: 30
        periodSeconds: 10
      livenessProbe:
        httpGet:
          path: /healthz
          port: 8080
        initialDelaySeconds: 10
        periodSeconds: 5
        timeoutSeconds: 3
        failureThreshold: 3
      readinessProbe:
        httpGet:
          path: /ready
          port: 8080
        periodSeconds: 5
```

### initialDelaySeconds

컨테이너가 시작된 후 startup, liveness 또는 readiness probe가 시작되기 전까지의 대기 시간이다.

- 기본값: `0`
- 최솟값: `0`

### periodSeconds

Probe를 얼마나 자주 수행할지 지정하는 초 단위 시간이다.

- 기본값: `10`
- 최솟값: `1`

### timeoutSeconds

Probe가 timeout되는 초 단위 시간이다.

- 기본값: `1`
- 최솟값: `1`

### successThreshold

Probe가 실패한 후 다시 성공한 것으로 간주되기 위해 필요한 최소 연속 성공 횟수이다.

- 기본값: `1`
- 최솟값: `1`

Liveness probe와 startup probe에서는 반드시 `1`이어야 한다.

### failureThreshold

Probe가 연속으로 `failureThreshold`번 실패하면 Kubernetes는 전체 검사가 실패했다고 간주한다.

- 기본값: `3`
- 최솟값: `1`

### terminationGracePeriodSeconds

Probe 실패로 컨테이너 종료가 트리거된 시점부터 컨테이너 런타임이 해당 컨테이너를 강제로 중지할 때까지 kubelet이 기다리는 grace period를 설정한다.

---

## 7. Defining Probes

### Liveness Command

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    test: liveness
  name: liveness-exec
spec:
  containers:
    - name: liveness
      image: registry.k8s.io/busybox:1.27.2
      args:
        - /bin/sh
        - -c
        - touch /tmp/healthy; sleep 30; rm -f /tmp/healthy; sleep 600
      livenessProbe:
        exec:
          command:
            - cat
            - /tmp/healthy
        initialDelaySeconds: 5
        periodSeconds: 5
```

위 예시는 컨테이너 안에서 `cat /tmp/healthy` 명령어를 실행한다.

### Liveness HTTP Request

```yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    test: liveness
  name: liveness-http
spec:
  containers:
    - name: liveness
      image: registry.k8s.io/e2e-test-images/agnhost:2.40
      args:
        - liveness
      livenessProbe:
        httpGet:
          path: /healthz
          port: 8080
          httpHeaders:
            - name: Custom-Header
              value: Awesome
        initialDelaySeconds: 3
        periodSeconds: 3
```

위 예시는 kubelet이 Pod의 `8080` 포트에 있는 `/healthz` 경로로 HTTP GET 요청을 보낸다.

### TCP Liveness Probe

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: goproxy
  labels:
    app: goproxy
spec:
  containers:
    - name: goproxy
      image: registry.k8s.io/goproxy:0.1
      ports:
        - containerPort: 8080
      readinessProbe:
        tcpSocket:
          port: 8080
        initialDelaySeconds: 15
        periodSeconds: 10
      livenessProbe:
        tcpSocket:
          port: 8080
        initialDelaySeconds: 15
        periodSeconds: 10
```

위 예시는 Pod의 `8080` 포트에 TCP 연결을 시도한다.

### Protect Slow-starting Containers with Startup Probes

```yaml
ports:
  - name: liveness-port
    containerPort: 8080

livenessProbe:
  httpGet:
    path: /healthz
    port: liveness-port
  failureThreshold: 1
  periodSeconds: 10

startupProbe:
  httpGet:
    path: /healthz
    port: liveness-port
  failureThreshold: 30
  periodSeconds: 10
```

위 예시는 startup probe가 10초마다 검사하고 최대 30번 실패를 허용한다. 따라서 최대 300초, 즉 5분 동안 컨테이너 시작을 기다릴 수 있다.

### Readiness Probes

애플리케이션이 일시적으로 트래픽을 처리할 수 없을 때, 컨테이너를 죽이고 싶지는 않지만 요청도 보내고 싶지 않다면 readiness probe를 사용한다.

```yaml
readinessProbe:
  exec:
    command:
      - cat
      - /tmp/healthy
  initialDelaySeconds: 5
  periodSeconds: 5
```

---

## 8. Probes in Chaos Engineering

### What is Chaos Engineering?

Chaos Engineering은 시스템이 실제 장애 조건에서도 정상적으로 동작하는지 검증하기 위해 **의도적으로 장애를 주입하는 방법론**이다. Netflix의 "Chaos Monkey"에서 시작된 개념으로, 운영 환경의 복잡성과 불확실성을 미리 탐색한다.

> **Key Link with Probes**
>
> Probe의 본질 = 시스템의 **self-healing** 메커니즘
>
> Chaos Engineering = 그 메커니즘이 **실제로 작동하는지** 검증하는 experiment
>
> 즉, "Probe를 올바르게 설정했는가?"를 chaos experiment로 증명한다.

### Five Principles of Chaos Engineering

1. Steady state를 정의한다.
2. Steady state가 실험군에서도 지속될 것이라 가설을 세운다.
3. 실제 장애 조건을 반영한 변수를 도입한다.
4. 실험군과 대조군의 steady state 차이를 관찰한다.
5. 최소한의 blast radius로 실험을 시작한다.

### Probe and Chaos Mapping

| Probe Type | Chaos Experiment Goal | Experiment Method | Expected Result |
| --- | --- | --- | --- |
| Liveness | deadlock 감지 및 자동 복구 | 앱을 강제 deadlock 상태로 진입 | `failureThreshold` 초과 후 자동 재시작 |
| Readiness | 불완전한 Pod로의 트래픽 차단 | DB 연결을 강제로 끊음 | Service에서 제외, 정상 Pod만 트래픽 수신 |
| Startup | 느린 초기화 앱 보호 | 초기화 시간을 인위적으로 늘림 | Startup 성공 전까지 Liveness 미실행 |

---

## 9. Chaos Experiment Scenarios by Probe

### Scenario 1: Readiness Probe 없이 배포하면?
**가설**
```
Readiness Probe가 없으면 초기화 중인 Pod에 트래픽이 인입되어
에러가 발생할 것이다.
```


| 단계 | 행동 | 관찰 포인트 |
| --- | --- | --- |
| 1 | 느린 초기화(`sleep 30초`) 앱 배포, Readiness Probe 없음 | Pod는 `Running`이지만 초기화 중 |
| 2 | 배포 직후 트래픽 전송 | 500 에러 발생 확인 |
| 3 | Readiness Probe 추가 후 동일 chaos experiment 반복 | 트래픽이 Ready 이후에만 인입되는지 확인 |
| 4 | 결과 비교 | Probe 유무에 따른 에러율 차이 측정 |

#### Go 앱

```go
// Go 서버: 30초 후에만 /ready 성공
var startTime = time.Now()

http.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
    if time.Since(startTime) < 30*time.Second {
        w.WriteHeader(http.StatusServiceUnavailable) // 503
        return
    }
    w.WriteHeader(http.StatusOK) // 200
})
```

#### Kubernetes Manifest

```yaml
readinessProbe:
  httpGet:
    path: /ready
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
  failureThreshold: 10 # 50초 대기 허용
```

---

### Scenario 2: Liveness Probe - Deadlock 감지

프로세스는 살아 있지만 요청을 처리할 수 없는 상태(deadlock)를 Liveness Probe가 감지하고 자동 복구하는지 검증한다.  
**가설**
```
애플리케이션이 deadlock 상태가 되면 livenessProbe가 실패하고,
kubelet이 컨테이너를 재시작하여 복구할 것이다.
```
| 단계 | 행동 | 관찰 포인트 |
| --- | --- | --- |
| 1 | 정상 앱 배포 후 `/healthz` Liveness Probe 설정 | 정상적으로 `Running` 상태 유지 |
| 2 | 특정 시간 후 `/healthz`가 500을 반환하도록 앱 조작 | kubelet이 실패 감지 시작 |
| 3 | `failureThreshold` 초과 대기 | Pod가 자동으로 재시작되는지 확인 |
| 4 | 재시작 후 `/healthz` 복구 확인 | `CrashLoopBackOff` 없이 정상 복구 |

#### Go 앱

```go
// 60초 후 deadlock 시뮬레이션
var healthy = true

func init() {
    go func() {
        time.Sleep(60 * time.Second)
        healthy = false // deadlock 시뮬레이션
    }()
}

http.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
    if !healthy {
        w.WriteHeader(http.StatusInternalServerError) // 500
        return
    }
    w.WriteHeader(http.StatusOK)
})
```

---

### Scenario 3: Startup Probe - 느린 초기화 앱 보호

초기화 시간이 긴 앱에서 Startup Probe 유무에 따른 차이를 비교한다.  

**가설**
```
Startup Probe 없이 초기화가 긴 앱을 배포하면 CrashLoopBackOff가 발생하고,
Startup Probe를 추가하면 정상 기동할 것이다.
```


| Experiment Condition | Result |
| --- | --- |
| Startup Probe 없이 Liveness Probe만 설정(`failureThreshold: 3`) | 초기화 중 Liveness가 먼저 실패하여 무한 재시작(`CrashLoopBackOff`) |
| Startup Probe 추가(`failureThreshold: 30`, `periodSeconds: 10`) | 초기화 완료까지 최대 300초 허용, 정상 기동 |

```yaml
# Startup Probe 없이 사용하는 위험한 설정
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 10
  failureThreshold: 3 # 30초 내 성공 못하면 재시작
```

```yaml
# Startup Probe를 추가한 설정
startupProbe:
  httpGet:
    path: /healthz
    port: 8080
  failureThreshold: 30
  periodSeconds: 10 # 최대 300초 대기

livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  failureThreshold: 1
  periodSeconds: 10 # Startup 성공 후부터 엄격하게
```

---

### Scenario 4: 네트워크 지연 주입 (LitmusChaos)

LitmusChaos의 `pod-network-latency` experiment로 network latency를 주입하고, Probe의 `timeoutSeconds` 설정이 적절한지 검증한다.  

**가설**
```
네트워크 지연이 발생하면 readinessProbe가 실패하여
해당 Pod는 트래픽 대상에서 제외되고,
서비스 전체는 정상 응답을 유지할 것이다.
```
#### LitmusChaos Core Resources

| CRD | 역할 |
| --- | --- |
| ChaosExperiment | Experiment template. 실행할 chaos 종류와 파라미터 기본값 정의 |
| ChaosEngine | 대상 앱과 experiment를 연결하는 리소스. Chaos Operator가 이를 감지해 experiment 실행 |
| ChaosResult | Experiment result 저장. `probeSuccessPercentage` 포함. Prometheus metric 내보내기 가능 |

#### Installation

```bash
# Litmus Helm으로 설치
helm repo add litmuschaos https://litmuschaos.github.io/litmus-helm/
helm repo update
kubectl create namespace litmus
helm install litmus litmuschaos/litmus \
  --namespace litmus \
  --set portal.frontend.service.type=ClusterIP

# Experiment CRD 설치(앱 namespace에)
kubectl apply -f https://hub.litmuschaos.io/api/chaos/3.0.0?file=charts/generic/experiments.yaml \
  -n default

# 대상 앱에 chaos 허용 annotation 추가
kubectl annotate deploy your-app litmuschaos.io/chaos="true" -n default
```

#### Step 1: ServiceAccount와 RBAC 생성

```yaml
# rbac.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: pod-network-latency-sa
  namespace: default
  labels:
    name: pod-network-latency-sa
    app.kubernetes.io/part-of: litmus
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-network-latency-sa
  namespace: default
rules:
  - apiGroups: [""]
    resources: ["pods", "events", "configmaps", "pods/log"]
    verbs: ["create", "delete", "get", "list", "patch", "update", "deletecollection"]
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["create", "list", "get", "delete", "deletecollection"]
  - apiGroups: ["litmuschaos.io"]
    resources: ["chaosengines", "chaosexperiments", "chaosresults"]
    verbs: ["create", "list", "get", "patch", "update", "delete"]
```

#### Step 2:ChaosEngine - Network Latency 주입

```yaml
# chaosengine-network-latency.yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: probe-network-latency-test
  namespace: default
spec:
  engineState: "active"
  annotationCheck: "false"
  appinfo:
    appns: "default"
    applabel: "app=your-app" # 대상 앱 레이블
    appkind: "deployment"
  chaosServiceAccount: pod-network-latency-sa
  jobCleanUpPolicy: "delete"
  experiments:
    - name: pod-network-latency
      spec:
        components:
          env:
            - name: TARGET_CONTAINER
              value: "your-app"
            - name: NETWORK_INTERFACE
              value: "eth0"
            - name: NETWORK_LATENCY
              value: "5000" # 5000ms = 5초 지연 주입
            - name: TOTAL_CHAOS_DURATION
              value: "60" # 60초 동안 experiment
            - name: LIB_IMAGE
              value: "litmuschaos/go-runner:latest"
```

#### Step 3: LitmusChaos httpProbe로 Hypothesis 검증

ChaosEngine에 probe를 추가하면 experiment 중/후 steady state를 자동으로 검증할 수 있다. 이것이 Chaos Engineering의 가설 검증 단계에 해당한다.

```yaml
# ChaosEngine에 probe 섹션 추가
experiments:
  - name: pod-network-latency
    spec:
      probe:
        - name: check-readiness-during-chaos
          type: httpProbe
          mode: Continuous # experiment 중 계속 체크
          httpProbe/inputs:
            url: http://your-app.default.svc:8080/ready
            method:
              get:
                criteria: ==
                responseCode: "200"
          runProperties:
            probeTimeout: 3s
            interval: 2s
            retry: 3
```

#### Step 4: Experiment 결과 확인

```bash
# Experiment 적용
kubectl apply -f chaosengine-network-latency.yaml

# Experiment 상태 모니터링
kubectl get chaosengine probe-network-latency-test -n default -w

# 결과 확인(ChaosResult)
kubectl describe chaosresult probe-network-latency-test-pod-network-latency -n default

# Experiment 즉시 중단
kubectl patch chaosengine probe-network-latency-test -n default \
  --type merge --patch '{"spec":{"engineState":"stop"}}'
```

#### Result Comparison by timeoutSeconds

| `timeoutSeconds` 설정 | 5초 지연 주입 시 결과 | ChaosResult |
| --- | --- | --- |
| 1초(기본값) | Probe timeout, Failure, 불필요한 재시작 발생 | Fail(`probeSuccessPercentage` 낮음) |
| 6초 | 지연은 감지하되 timeout 발생 안 함 | Pass |
| 10초 | 여유롭게 통과하지만 실제 장애 감지가 느려짐 | Pass. 단, 감지 지연 주의 |

---

### Scenario 5: Pod 강제 삭제 (LitmusChaos)

`pod-delete` experiment로 Pod를 강제 종료했을 때 Readiness Probe가 트래픽을 정상 격리하는지 검증한다.  

**가설**
```
Pod 하나가 삭제되어도 Deployment가 새 Pod를 생성하고,
readinessProbe를 통과한 이후에만 트래픽을 수신할 것이다.
```

**ChaosEngine**
```yaml
# chaosengine-pod-delete.yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: pod-delete-probe-test
  namespace: default
spec:
  engineState: "active"
  annotationCheck: "false"
  appinfo:
    appns: "default"
    applabel: "app=your-app"
    appkind: "deployment"
  chaosServiceAccount: litmus-admin
  experiments:
    - name: pod-delete
      spec:
        components:
          env:
            - name: TOTAL_CHAOS_DURATION
              value: "30" # 30초 동안 experiment
            - name: CHAOS_INTERVAL
              value: "10" # 10초마다 Pod 삭제
            - name: FORCE
              value: "false" # graceful termination
            - name: PODS_AFFECTED_PERC
              value: "50" # 전체 Pod의 50% 대상
```

> **Observation Points**
>
> - Pod 삭제 직후 Readiness Probe 실패로 Service EndpointSlice에서 제외되는지 확인
> - 새 Pod가 뜨는 동안 트래픽이 정상 Pod에만 라우팅되는지 확인
> - ChaosResult의 `probeSuccessPercentage`로 서비스 가용성 측정

---
## References

- [Liveness, Readiness, and Startup Probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/)
- [Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Pod Lifecycle: Container probes](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#container-probes)
- [Kubernetes에서 Pod에 대한 헬스체크 Probe](https://www.openmaru.io/kubernetes-%EC%97%90%EC%84%9C-pod-%EC%97%90-%EB%8C%80%ED%95%9C-%ED%97%AC%EC%8A%A4%EC%B2%B4%ED%81%AC-probe/)
- [LitmusChaos 공식 문서](https://docs.litmuschaos.io)
- [Litmus pod-network-latency experiment](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-network-latency/)
- [Litmus pod-delete experiment](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-delete/)
- [Litmus ChaosHub](https://hub.litmuschaos.io)
- [Principles of Chaos Engineering](https://principlesofchaos.org)
