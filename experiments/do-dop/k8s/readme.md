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

### Scenario 4: Pod 강제 삭제

**가설**
```
Pod 하나가 삭제되어도 Deployment가 새 Pod를 생성하고,
readinessProbe를 통과한 이후에만 트래픽을 수신할 것이다.
```

| 단계 | 행동 | 관찰 포인트 |
| --- | --- | --- |
| 1 | 3개 replica Deployment 배포, Readiness Probe 있음 | Pod 3개 `Running & Ready` 확인 |
| 2 | Pod 1개 강제 삭제(`kubectl delete pod`) | 삭제된 Pod IP가 EndpointSlice에서 즉시 제거되는지 확인 |
| 3 | 새 Pod가 뜨는 동안 트래픽 연속 전송 | 정상 Pod 2개에만 라우팅되는지, 에러 없는지 확인 |
| 4 | 새 Pod가 Readiness 통과 후 트래픽 수신 | EndpointSlice에 IP 재추가 타이밍 확인 |
| 5 | Probe 없는 버전과 비교 반복 | Probe 유무에 따른 에러율 차이 측정 |

#### Go 앱

```go
// 앱 시작 후 30초 동안은 /ready 실패 -> 초기화 중임을 시뮬레이션
var startTime = time.Now()

func isReady() bool {
    return time.Since(startTime) >= 30*time.Second
}

http.HandleFunc("/ready", func(w http.ResponseWriter, r *http.Request) {
    if !isReady() {
        w.WriteHeader(http.StatusServiceUnavailable) // 503: 아직 준비 안 됨
        fmt.Fprintf(w, "not ready, pod=%s\n", os.Getenv("POD_NAME"))
        return
    }
    w.WriteHeader(http.StatusOK) // 200: 준비 완료
    fmt.Fprintf(w, "ready, pod=%s\n", os.Getenv("POD_NAME"))
})

http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
    if !isReady() {
        w.WriteHeader(http.StatusInternalServerError) // 500: 초기화 중 요청 수신
        fmt.Fprintf(w, "app is initializing, pod=%s\n", os.Getenv("POD_NAME"))
        return
    }

    fmt.Fprintf(w, "ok, pod=%s\n", os.Getenv("POD_NAME")) // 어느 Pod가 응답했는지 확인용
})
```

#### Kubernetes Manifest

```yaml
readinessProbe:
  httpGet:
    path: /ready
    port: 8080
  initialDelaySeconds: 3
  periodSeconds: 3
  failureThreshold: 10 # 최대 30초 대기 후 트래픽 수신
```

#### 실험 스크립트

**터미널 1 - Pod 상태 감시**

```bash
watch -n 1 kubectl get pods -l app=probe-test -o wide
```

**터미널 2 - EndpointSlice 감시**

```bash
# IP가 제거/추가되는 타이밍을 직접 눈으로 확인
watch -n 1 kubectl get endpointslices \
  -l kubernetes.io/service-name=probe-test-svc -o yaml \
  | grep -E "addresses|ready"
```

**터미널 3 - 트래픽 연속 발사**

```bash
# 에러율 + 어느 Pod가 응답하는지 동시에 확인
while true; do
  curl -s http://<CLUSTER-IP>/ && sleep 0.3
done
```

**터미널 4 - 혼돈 주입**

```bash
# 실험 1: Pod 1개 삭제(graceful)
kubectl delete pod <pod-name>

# 실험 2: 전체의 50% 동시 삭제
PODS=($(kubectl get pods -l app=probe-test -o name))
for pod in "${PODS[@]:0:$((${#PODS[@]}/2))}"; do
  kubectl delete $pod &
done
wait
```

#### 결과 비교

| 조건 | Pod 삭제 직후 에러 | 새 Pod Ready 전 에러 |
| --- | --- | --- |
| Readiness Probe **없음** | 없음(Pod가 바로 EndpointSlice 등록) | **에러 발생**(초기화 중에 트래픽 수신) |
| Readiness Probe **있음** | 없음(삭제 즉시 제외) | **에러 없음**(Ready 통과 후에만 등록) |
