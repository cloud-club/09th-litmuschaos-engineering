# LitmusChaos `pod-memory-hog` 실습

## 0. 실험 개요

`pod-memory-hog`는 대상 Pod의 컨테이너 안에서 메모리를 인위적으로 소비하여, 애플리케이션이 갑작스러운 메모리 압박 상황에서 어떻게 반응하는지 확인하는 LitmusChaos 실험이다.

이번 실습에서는 `podtato-head` 애플리케이션의 `hat` 서비스를 대상으로 메모리 부하를 주입하였다.  

---

## 1. 실험 설계

`pod-memory-hog` 실험의 구성은 다음과 같다.

| 항목 | 설명 |
| --- | --- |
| 정상 상태 | `podtato-head-hat` Pod가 `1/1 Running` 상태이고, `hat` 서비스의 `/healthz` endpoint가 HTTP 200을 반환한다. |
| 가설 | `podtato-head-hat` 컨테이너에 메모리 부하가 주입되어도 `/healthz` 응답은 HTTP 200을 유지하고, 애플리케이션 컨테이너는 재시작되지 않는다. |
| 장애 주입 | LitmusChaos `pod-memory-hog` fault로 `podtato-kubectl` namespace의 `app=podtato-head-hat` 라벨을 가진 Deployment Pod에 메모리 부하를 주입한다. |
| 관찰과 검증 | Continuous HTTP Probe로 `/healthz` 응답이 HTTP 200인지 검증하고, Pod 상태, `RESTARTS`, Liveness / Readiness Probe 이벤트, cgroup OOM 로그를 함께 관찰한다. |
| 중단 조건 | `podtato-head-hat` 애플리케이션 컨테이너가 반복 재시작되거나, `/healthz`가 지속적으로 실패하거나, 실험 대상이 아닌 Pod에 영향이 발생하면 실험을 중단한다. |

이 실험의 핵심 검증 기준은 다음과 같다.

```text
메모리 압박 중에도 서비스의 /healthz endpoint가 HTTP 200을 반환하는가?
```

## 2. 대상 컨테이너 설정

이번 실험 대상은 `podtato-head` 애플리케이션의 `hat` 서비스이다.

| 항목 | 값 |
| --- | --- |
| 대상 앱 | `podtato-head`의 `hat` 서비스 |
| App Kind | `deployment` |
| App Namespace | `podtato-kubectl` |
| App Label | `app=podtato-head-hat` |
| 대상 Pod | `podtato-head-hat-8699f5d6d4-mbbpj` |
| 대상 컨테이너 | `podtato-head-hat` |
| Container Image | `ghcr.io/podtato-head/podtato-server:v0.3.3` |

`kubectl describe pod -n podtato-kubectl -l app=podtato-head-hat` 결과로 확인한 설정은 다음과 같다. 리소스 설정은 다음 명령으로도 확인했다.

```bash
kubectl get pod -n podtato-kubectl \
  -l app=podtato-head-hat \
  -o jsonpath='{.items[0].spec.containers[0].resources}{"\n"}'
```

실행 결과:

```json
{"limits":{"cpu":"500m","memory":"128Mi"},"requests":{"cpu":"100m","memory":"32Mi"}}
```

| 항목 | 값 |
| --- | --- |
| CPU Request | `100m` |
| CPU Limit | `500m` |
| Memory Request | `32Mi` |
| Memory Limit | `128Mi` |
| Liveness Probe | `GET /healthz`, `timeout=1s`, `period=10s`, `failureThreshold=3` |
| Readiness Probe | `GET /readyz`, `timeout=1s`, `period=10s`, `failureThreshold=3` |
| QoS Class | `Burstable` |

이번 실험에서 중요한 설정은 **Memory Limit이 `128Mi`로 존재한다는 점**이다. 메모리 부하로 컨테이너가 이 한도를 초과하면 cgroup OOM이 발생할 수 있다.

---

## 3. Resilience Probe 설정

`pod-memory-hog`는 메모리 부하가 진행되는 동안에도 서비스가 계속 응답하는지 확인하는 것이 핵심이다. 따라서 이번 실험에서는 `Continuous` 방식의 HTTP Probe를 사용한다.

### HTTP Probe 설정

| 항목 | 값 |
| --- | --- |
| Probe Name | `check-podtato-head-hat-http` |
| Probe Type | HTTP Probe |
| Mode | `Continuous` |
| URL | `http://podtato-head-hat.podtato-kubectl.svc.cluster.local:8080/healthz` |
| Method | `GET` |
| Criteria | `==` |
| Expected Response Code | `200` |
| Timeout | `10s` |
| Interval | `2s` |
| Attempt | `3` |
| Polling Interval | `2s` |

### Probe 검증 범위

HTTP Probe는 메모리 압박이 진행되는 동안 `hat` 서비스의 `/healthz` endpoint가 계속 HTTP 200을 반환하는지 검증한다. 다만 이 Probe만으로는 컨테이너 내부 메모리 사용량 증가, cgroup OOM 발생 여부, OOM으로 종료된 프로세스, Kubernetes Readiness / Liveness Probe의 일시적 실패 여부까지 확인할 수 없다.

따라서 실험 중에는 HTTP Probe 결과와 함께 Pod 상태, 메모리 사용량, Pod 이벤트, OOM 로그를 별도 터미널에서 함께 관찰한다.

---

## 4. `pod-memory-hog` fault 설정

ChaosCenter에서 새로운 실험을 생성하고, `pod-memory-hog` fault를 추가한다.

### Experiment 기본 정보

| 항목 | 값 |
| --- | --- |
| Experiment Name | `podtato-head-memory-hog` |
| Infrastructure | `local` |
| 생성 방식 | `Blank Canvas` |
| Fault | `pod-memory-hog` |

### Target Application

| 항목 | 값 |
| --- | --- |
| App Kind | `deployment` |
| App Namespace | `podtato-kubectl` |
| App Label | `app=podtato-head-hat` |

### 주요 Fault Parameter

| 파라미터 | 의미 | 설정 / 확인값 |
| --- | --- | --- |
| `TOTAL_CHAOS_DURATION` | 메모리 부하 지속 시간 | 기본값 `60`초 사용 |
| `MEMORY_CONSUMPTION` | 소비시킬 메모리 양 | 기본값 `500MB` 사용 |
| `PODS_AFFECTED_PERC` | 대상 Pod 비율 | 대상 hat Pod 1개 |
| `NUMBER_OF_WORKERS` | stress worker 수 | 기본값 `1` |
| `SEQUENCE` | 다중 Pod 대상 실행 순서 | 기본값 사용 |
| `CONTAINER_RUNTIME` | 클러스터 런타임 | `docker` |
| `SOCKET_PATH` | Docker socket 경로 | `/var/run/docker.sock` |

### 런타임 설정을 바꾸는 이유

이번 minikube 클러스터는 다음처럼 Docker driver로 시작했다.

```bash
minikube start --profile=litmus-experiments --cpus=4 --memory=7168 --driver=docker
```

따라서 이 실습 환경에서는 컨테이너 런타임을 `docker`로 맞춰야 한다. `pod-memory-hog`는 런타임 socket을 통해 대상 컨테이너에 stress 프로세스를 실행하므로, runtime과 socket 경로가 실제 환경과 일치해야 한다.

이 값을 잘못 지정하면 helper가 대상 컨테이너에 메모리 부하를 주입하지 못해 실험이 실패할 수 있다.

---

## 5. 실험 관찰

### 5.1 메모리 사용량 관찰

`kubectl top`을 사용하려면 metrics-server가 필요하다.

```bash
minikube addons enable metrics-server -p litmus-experiments
```

metrics-server가 준비된 후 다음 명령으로 대상 Pod의 메모리 사용량을 확인한다.

```bash
kubectl top pod -n podtato-kubectl -l app=podtato-head-hat
```

실험 중 지속적으로 보려면:

```bash
watch -n 1 'kubectl top pod -n podtato-kubectl -l app=podtato-head-hat'
```

### 관찰 목표

```text
실험 전 MEMORY 값
→ memory-hog 실행 중 MEMORY 값 증가
→ 실험 종료 후 MEMORY 값 감소
```

대상 Pod의 메모리 사용량은 다음과 같이 확인되었다.

```text
NAME                                CPU(cores)   MEMORY(bytes)
podtato-head-hat-8699f5d6d4-mbbpj   1m           11Mi
```

이 값은 실험 이후 안정 상태에서 확인한 값이다. 다음 재실행에서는 실험 시작 전부터 `kubectl top` 또는 `watch`를 켜두고, memory-hog 실행 중의 순간적인 증가 값을 함께 기록하는 것이 좋다.

---

### 5.2 Pod 상태와 재시작 여부 관찰

```bash
kubectl get pod -n podtato-kubectl -l app=podtato-head-hat -w
```

### 실제 관찰 결과

```text
NAME                                  READY   STATUS    RESTARTS   AGE
podtato-head-hat-8699f5d6d4-mbbpj   1/1     Running   0          154m
```

실험 중에도 Pod는 `1/1 Running` 상태를 유지하였고, `RESTARTS` 값은 `0`으로 유지되었다.

### 1차 해석

```text
hat 애플리케이션 컨테이너 전체가 종료되거나 재시작되지는 않았다.
```

다만 `RESTARTS=0`만으로 메모리 압박이나 OOM이 없었다고 판단할 수는 없다. 컨테이너 내부에서 부하 프로세스만 종료되고 메인 애플리케이션 프로세스가 살아남았다면, 컨테이너 재시작은 발생하지 않기 때문이다.

---

### 5.3 Pod 상세 상태와 Probe Event 관찰

다음 명령으로 상세 정보를 확인하였다.

```bash
kubectl describe pod -n podtato-kubectl -l app=podtato-head-hat
```

### 실제 확인 결과

```text
State:          Running
Ready:          True
Restart Count:  0

Limits:
  cpu:     500m
  memory:  128Mi

Requests:
  cpu:      100m
  memory:   32Mi

Liveness:   http-get http://:http/healthz delay=0s timeout=1s period=10s #success=1 #failure=3
Readiness:  http-get http://:http/readyz delay=0s timeout=1s period=10s #success=1 #failure=3
```

Events에는 다음과 같은 Probe timeout 기록이 존재하였다.

```text
Warning  Unhealthy  kubelet  Liveness probe failed:
Get "http://10.244.0.34:8080/healthz":
context deadline exceeded (Client.Timeout exceeded while awaiting headers)

Warning  Unhealthy  kubelet  Readiness probe failed:
Get "http://10.244.0.34:8080/readyz":
context deadline exceeded (Client.Timeout exceeded while awaiting headers)
```

### 해석

Pod는 최종적으로 `Ready=True`, `Restart Count=0`을 유지하였다. 그러나 이벤트에 Liveness / Readiness Probe timeout이 기록된 점으로 보아, 메모리 압박이 진행되는 동안 애플리케이션 응답이 일시적으로 느려졌을 가능성이 있다.

---

### 5.4 cgroup OOM 발생 여부 확인

> 참고: `cgroup`은 컨테이너가 사용할 수 있는 CPU, 메모리 같은 리소스를 제한하고 격리하는 Linux 기능이다. Kubernetes의 container memory limit도 cgroup을 통해 적용된다.
>
> `cgroup OOM`은 컨테이너가 자신에게 할당된 메모리 한도를 초과했을 때 발생하는 out of memory 상황이다. 이때 커널은 해당 cgroup 안의 프로세스 중 일부를 종료해 메모리를 확보할 수 있다.

Pod의 `RESTARTS`가 증가하지 않았더라도, 컨테이너 내부에서 메모리 제한 초과가 발생했는지 확인하기 위해 minikube 노드의 커널 로그를 조회하였다.

```bash
minikube ssh -p litmus-experiments -- \
  "sudo dmesg | grep -i 'memory cgroup out of memory'"
```

### 실제 관찰 결과 일부

```text
Memory cgroup out of memory: Killed process 355864 (stress-ng) total-vm:531768kB, anon-rss:115724kB, ...
Memory cgroup out of memory: Killed process 355885 (stress-ng) total-vm:531768kB, anon-rss:115852kB, ...
Memory cgroup out of memory: Killed process 355923 (stress-ng) total-vm:531768kB, anon-rss:115724kB, ...
Memory cgroup out of memory: Killed process 356169 (stress-ng) total-vm:531768kB, anon-rss:115724kB, ...
```

### 해석

커널 로그에 `Memory cgroup out of memory`가 반복적으로 기록되었으며, 종료된 프로세스는 모두 `stress-ng`였다. `stress-ng`는 LitmusChaos가 메모리 압박을 발생시키기 위해 실행한 부하 프로세스이다.

대상 컨테이너의 memory limit은 `128Mi`이고, `stress-ng` 하나의 `anon-rss`는 약 `115MB` 수준이었다. 여기에 기존 `hat` 애플리케이션이 사용하던 메모리까지 더해지면서 컨테이너 cgroup의 메모리 한도를 초과한 것으로 해석할 수 있다.

```text
hat 애플리케이션 메모리
+ stress-ng 사용량 약 115MB
> memory limit 128Mi
→ cgroup OOM 발생
→ stress-ng 프로세스 종료
```

---

## 6. cgroup OOM과 컨테이너 `OOMKilled`의 차이

이번 실험 결과를 해석할 때 가장 중요한 부분이다.

실험 중 대상 컨테이너 내부에는 다음과 같은 프로세스가 존재할 수 있다.

```text
hat 컨테이너
├── podtato-head 애플리케이션 프로세스   # 실제 서비스 프로세스
└── stress-ng 프로세스                  # LitmusChaos가 실행한 메모리 부하
```

컨테이너가 memory limit을 초과하면 Linux 커널은 cgroup 내부의 프로세스 중 하나를 종료하여 메모리를 확보할 수 있다.

LitmusChaos 공식 `pod-memory-hog` 문서의 Uses 설명도 같은 전제를 가진다. `pod-memory-hog`는 대상 컨테이너 내부에서 stress process를 실행해 메모리 압박을 만들며, 컨테이너에 memory limit이 설정되어 있는 경우 한도 초과로 primary process, 보통 PID 1이 OOMKill될 수 있다고 설명한다. 이 경우 컨테이너가 종료되고, kubelet이 재시작 정책에 따라 컨테이너를 다시 시작할 수 있다.

| 상황 | 종료되는 프로세스 | 컨테이너 재시작 | `RESTARTS` | 이번 실험 |
| --- | --- | --- | --- | --- |
| cgroup OOM 후 부하 프로세스 종료 | `stress-ng` | 발생하지 않음 | 유지 | 확인됨 |
| 컨테이너 자체 `OOMKilled` | 앱의 주 프로세스(PID 1) | 발생 가능 | 증가 | 확인되지 않음 |

이번 실험 로그에서는 `stress-ng`만 종료되었고, 실제 `hat` 애플리케이션 컨테이너는 계속 `Running` 상태를 유지하였다. 커널 로그에서 `Killed process ... (stress-ng)`와 `oom_score_adj:1000`이 확인되었기 때문에, 이번 환경에서는 `stress-ng`가 OOM 대상으로 반복 종료되었다고 해석할 수 있다.

`oom_score_adj`는 Linux OOM killer가 어떤 프로세스를 종료할지 판단할 때 사용하는 보정값이다. 값이 높을수록 OOM victim으로 선택될 가능성이 커지고, `1000`은 가장 높은 보정값이다. 따라서 커널 로그의 `oom_score_adj:1000`은 `stress-ng` 프로세스가 OOM 상황에서 종료 대상으로 선택되기 쉬운 상태였음을 의미한다.

```text
oom_score_adj = -1000  → 거의 죽이지 않음
oom_score_adj = 0      → 기본값
oom_score_adj = 1000   → 가장 죽이기 쉬운 대상
```

만약 커널이 `stress-ng`가 아니라 `hat` 서비스의 주 프로세스를 종료했다면 흐름은 다음과 달라진다.

```text
stress-ng로 메모리 압박 발생
→ hat 컨테이너의 128Mi limit 초과
→ cgroup OOM 발생
→ 커널이 hat 서비스 주 프로세스 종료
→ 컨테이너 종료
→ kubelet이 컨테이너 재시작
→ RESTARTS 증가
→ kubectl describe pod의 Last State에 OOMKilled 기록 가능
```


---

## 7. 실험 결과 확인

Continuous HTTP Probe가 실제로 실험 중 통과했는지 확인했다.

```bash
kubectl describe chaosresult pod-memory-hog-73r7gnld-pod-memory-hog -n litmus
```

주요 결과:

```text
Name:         pod-memory-hog-73r7gnld-pod-memory-hog
Namespace:    litmus
Spec:
  Engine:      pod-memory-hog-73r7gnld
  Experiment:  pod-memory-hog
Status:
  Experiment Status:
    Phase:                     Completed
    Probe Success Percentage:  100
    Verdict:                   Pass
  History:
    Failed Runs:   0
    Passed Runs:   1
    Stopped Runs:  0
    Targets:
      Chaos Status:  reverted
      Kind:          pod
      Name:          podtato-head-hat-8699f5d6d4-mbbpj
  Probe Statuses:
    Mode:  Continuous
    Name:  check-podtato-head-hat-http
    Status:
      Description:  The URL http://podtato-head-hat.podtato-kubectl.svc.cluster.local:8080/healthz did respond with correct status code. Actual code: '200'. Expected code: '200'
      Verdict:      Passed
    Type:           httpProbe
Events:
  Normal  Awaited  6m47s  pod-memory-hog-mczr86-z7nlq  experiment: pod-memory-hog, Result: Awaited
  Normal  Pass     5m1s   pod-memory-hog-mczr86-z7nlq  experiment: pod-memory-hog, Result: Pass
```

ChaosResult 기준으로 실험은 정상 완료되었고, Continuous HTTP Probe도 `Passed`로 기록되었다. 대상 Pod의 `Chaos Status`가 `reverted`인 것은 메모리 부하 주입이 끝난 뒤 fault가 정리되었음을 의미한다.

---

## 8. 최종 결론

이번 `pod-memory-hog` 실험에서는 `podtato-head-hat` 컨테이너에 메모리 압박이 실제로 주입되었으며, 커널 로그를 통해 cgroup OOM이 반복적으로 발생했음을 확인하였다. LitmusChaos `ChaosResult`는 `Phase: Completed`, `Verdict: Pass`, `Probe Success Percentage: 100`으로 기록되었다.

대상 컨테이너의 memory limit은 `128Mi`였고, LitmusChaos가 실행한 `stress-ng` 프로세스가 약 `115MB` 수준의 메모리를 사용하면서 기존 애플리케이션 메모리와 합쳐 한도를 넘은 것으로 볼 수 있다. 이에 따라 커널은 `stress-ng` 프로세스를 반복적으로 종료하였다.

그러나 실제 `hat` 애플리케이션 프로세스는 종료되지 않았고, Pod는 `Running`, `Ready=True`, `Restart Count=0` 상태를 유지하였다. 즉, **메모리 부족 상황은 발생했지만, 애플리케이션 컨테이너 전체의 OOMKilled 및 재시작으로 이어지지는 않았다.**

한편 Pod 이벤트에는 Liveness 및 Readiness Probe timeout이 기록되었다. 이는 서비스가 완전히 종료되지는 않았더라도, 메모리 압박 중 응답 지연이 발생하여 Kubernetes health check가 일시적으로 실패할 수 있음을 보여준다. 다만 Continuous HTTP Probe는 `/healthz`에 대해 HTTP 200을 확인했으므로, 실험 가설은 LitmusChaos 판정 기준에서 통과했다.

```text
결론:
메모리 압박 → cgroup OOM 발생 → stress-ng 종료
하지만 hat 앱 컨테이너는 생존 → RESTARTS=0
다만 Probe timeout이 기록되어 성능 영향 가능성은 확인됨
```

---

## 9. 참고 자료

- LitmusChaos Experiments: Pod Memory Hog  
  https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-memory-hog/

- LitmusChaos Docs: Resilience Probes  
  https://docs.litmuschaos.io/docs/concepts/probes

- Kubernetes Documentation: Resource Management for Pods and Containers  
  https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
