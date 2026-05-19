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
