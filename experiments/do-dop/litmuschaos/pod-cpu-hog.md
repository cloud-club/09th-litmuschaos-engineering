# LitmusChaos `pod-cpu-hog` 실습

## 0. 실험 개요

`pod-cpu-hog`는 대상 애플리케이션 컨테이너의 CPU 자원을 인위적으로 소비하여 CPU spike 상황을 만드는 LitmusChaos 실험이다.

Litmus 공식 문서는 이 실험을 애플리케이션 컨테이너의 CPU resources를 소비하고, CPU spike 상황에서 전체 애플리케이션 stack이 어떻게 동작하는지 확인하는 실험으로 설명한다.

이번 실습에서는 기존 `pod-memory-hog` 실습 환경을 재사용하고, `podtato-head` 애플리케이션의 `hat` 서비스를 대상으로 CPU 부하를 주입한다.

---

## 1. 실험 목표

`pod-memory-hog` 실험에서는 메모리 압박 때문에 cgroup OOM이 발생했고, `stress-ng` 프로세스가 종료되었지만 `hat` 애플리케이션 컨테이너의 `RESTARTS` 값은 `0`으로 유지되었다.

이번 `pod-cpu-hog` 실험의 질문은 다르다.

```text
CPU를 강하게 사용하면 컨테이너가 죽는가?
아니면 컨테이너는 죽지 않지만 응답이 느려지는가?
Readiness / Liveness Probe timeout이 발생하는가?
```

CPU는 memory처럼 limit을 넘었다고 바로 `OOMKilled`되는 구조가 아니다. 일반적으로 CPU limit에 의해 사용량이 제한되거나 throttling이 발생하고, 그 결과 애플리케이션 응답 지연 또는 Probe timeout으로 나타난다.

따라서 이번 실험에서는 `RESTARTS` 증가 여부보다 다음 항목을 더 중요하게 본다.

| 관찰 항목 | 의미 |
| --- | --- |
| CPU 사용량 | CPU 부하가 실제로 대상 컨테이너에 주입되었는지 확인한다. |
| 응답 시간 | CPU spike 중 `/healthz`, `/readyz` 응답이 느려지는지 확인한다. |
| Readiness / Liveness Probe 이벤트 | Kubernetes Probe timeout 또는 실패 이벤트가 발생하는지 확인한다. |
| Litmus HTTP Probe 결과 | 실험 중 서비스 응답 조건을 계속 만족했는지 확인한다. |
| `RESTARTS` | CPU 부하로 애플리케이션 컨테이너가 재시작되었는지 확인한다. 보통은 `0` 유지가 예상된다. |

---

## 2. 실험 설계

`pod-cpu-hog` 실험의 구성은 다음과 같다.

| 항목 | 설명 |
| --- | --- |
| 정상 상태 | `podtato-head-hat` Pod가 `1/1 Running` 상태이고, `hat` 서비스의 `/healthz` endpoint가 HTTP 200을 반환한다. |
| 가설 | `podtato-head-hat` 컨테이너에 CPU 부하가 주입되면 CPU 사용량이 증가하고 응답 시간이 느려질 수 있지만, 애플리케이션 컨테이너는 재시작되지 않는다. |
| 장애 주입 | LitmusChaos `pod-cpu-hog` fault로 `podtato-kubectl` namespace의 `app=podtato-head-hat` 라벨을 가진 Deployment Pod에 CPU 부하를 주입한다. |
| 관찰과 검증 | Continuous HTTP Probe로 `/healthz` 응답이 HTTP 200인지 검증하고, Pod 상태, CPU 사용량, `RESTARTS`, Readiness / Liveness Probe 이벤트를 함께 관찰한다. |
| 중단 조건 | `/healthz`가 지속적으로 실패하거나, 애플리케이션 컨테이너가 반복 재시작되거나, 실험 대상이 아닌 Pod에 영향이 발생하면 실험을 중단한다. |

이 실험의 핵심 검증 기준은 다음과 같다.

```text
CPU spike 중에도 서비스가 응답 가능한 상태를 유지하는가?
```

---

## 3. 클러스터 상태 확인

먼저 기존 minikube profile이 정상인지 확인한다.

```bash
minikube status -p litmus-experiments
```

정상 기대 결과:

```text
host: Running
kubelet: Running
apiserver: Running
kubeconfig: Configured
```

클러스터가 멈춰 있으면 다시 시작한다.

```bash
minikube start -p litmus-experiments --cpus=4 --memory=7168 --driver=docker
```

현재 `kubectl` context도 확인한다.

```bash
kubectl config current-context
```

`litmus-experiments`가 아니면 context를 변경한다.

```bash
kubectl config use-context litmus-experiments
```

---

## 4. LitmusChaos 상태 확인

`litmus` namespace의 Pod 상태를 확인한다.

```bash
kubectl get pods -n litmus
```

정상 상태라면 대략 다음 컴포넌트들이 `Running`이어야 한다.

```text
chaos-litmus-frontend-...
chaos-litmus-server-...
chaos-litmus-auth-server-...
chaos-mongodb-...
subscriber-...
chaos-operator-...
chaos-exporter-...
workflow-controller-...
event-tracker-...
```

`subscriber`가 보이지 않거나 `Running`이 아니면 ChaosCenter에서 Infrastructure 연결이 정상 상태가 아닐 수 있다.

### 4.1 현재 확인된 LitmusChaos 상태


재시작 직후에는 기존 Pod와 새 Pod가 함께 보이며, 새 Pod는 `ContainerCreating` 또는 `Init:0/1` 상태일 수 있다.

```text
NAME                                        READY   STATUS              RESTARTS      AGE
chaos-exporter-54fbc884c8-psb5g             1/1     Running             2 (89s ago)   6d20h
chaos-exporter-5999fb4bd6-5s77r             0/1     ContainerCreating   0             4s
chaos-litmus-auth-server-6576749b77-gtbnn   1/1     Running             2 (89s ago)   6d22h
chaos-litmus-auth-server-9876dfbff-tvdbc    0/1     Init:0/1            0             4s
chaos-litmus-frontend-77db4999d8-rxcvm      0/1     ContainerCreating   0             4s
chaos-litmus-frontend-cc4c59b46-qmnvt       1/1     Running             5 (58s ago)   6d22h
chaos-litmus-server-5b67c485bb-swps6        0/1     Init:0/1            0             4s
chaos-litmus-server-7c88f5bc5d-7tlws        1/1     Running             2 (89s ago)   6d22h
chaos-mongodb-0                             1/1     Running             7 (89s ago)   6d22h
chaos-mongodb-1                             1/1     Running             7 (57m ago)   6d22h
chaos-mongodb-2                             1/1     Running             8 (89s ago)   6d22h
chaos-mongodb-arbiter-0                     1/1     Running             2 (89s ago)   6d22h
chaos-operator-ce-5f4744dd4d-w5v7g          0/1     ContainerCreating   0             4s
chaos-operator-ce-7d9777db84-wj22n          1/1     Running             4 (74m ago)   6d20h
event-tracker-5bc7ff94ff-l4zzj              1/1     Running             2 (89s ago)   6d20h
event-tracker-6685c768c7-mfnkm              0/1     ContainerCreating   0             4s
subscriber-799c9df8c6-sdpn8                 0/1     ContainerCreating   0             4s
subscriber-856888ddd4-8m2lq                 1/1     Running             3 (89s ago)   6d20h
workflow-controller-5487d457f9-zg65b        1/1     Running             0             4s
```

StatefulSet은 다음처럼 정상 개수로 준비되어 있었다.

```bash
kubectl get statefulset -n litmus
```

```text
NAME                    READY   AGE
chaos-mongodb           3/3     6d22h
chaos-mongodb-arbiter   1/1     6d22h
```

### 4.2 ChaosCenter UI 접속

ChaosCenter UI 접속이 필요하면 port-forward를 사용한다.

```bash
kubectl port-forward -n litmus svc/chaos-litmus-frontend-service 9091:9091
```

실행 결과:

```text
Forwarding from 127.0.0.1:9091 -> 8185
Forwarding from [::1]:9091 -> 8185
Handling connection for 9091
```

브라우저에서 접속한다.

```text
http://127.0.0.1:9091
```

또는 NodePort 방식으로 접속할 수 있다.

```bash
minikube service chaos-litmus-frontend-service \
  -n litmus \
  -p litmus-experiments
```

---

## 5. `podtato-head` 앱 확인

실험 대상 애플리케이션 Pod를 확인한다.

```bash
kubectl get pods -n podtato-kubectl
```

다음 6개 Pod가 보여야 한다.

```text
podtato-head-frontend-...
podtato-head-hat-...
podtato-head-left-arm-...
podtato-head-left-leg-...
podtato-head-right-arm-...
podtato-head-right-leg-...
```

Pod가 없으면 다시 배포한다.

```bash
kubectl apply -f https://github.com/podtato-head/podtato-head-app/releases/download/v0.3.3/manifest.yaml
```

---

## 6. `hat` Deployment label 확인

Litmus 실험 대상은 Kubernetes 리소스 종류와 label selector를 조합해서 지정한다. 이번 실험에서는 Target Application을 다음처럼 설정할 예정이다.

```text
App Kind: deployment
App Namespace: podtato-kubectl
App Label: app=podtato-head-hat
```

이 설정은 LitmusChaos에게 다음처럼 지시하는 것과 같다.

```text
podtato-kubectl namespace 안에서
app=podtato-head-hat 라벨이 붙은
Deployment를 찾아서
그 Deployment가 관리하는 Pod에 CPU 부하를 넣어라.
```

즉 대상은 단순히 `podtato-head-hat`이라는 이름 하나가 아니라, `kind=deployment`, `namespace=podtato-kubectl`, `label=app=podtato-head-hat` 조건으로 정해진다. 따라서 `podtato-head-hat` Deployment 객체 자체에 이 라벨이 있어야 한다.

여기서 주의할 점은 **Pod label과 Deployment label은 다를 수 있다**는 것이다. Pod에 `app=podtato-head-hat` 라벨이 있다고 해서 Deployment 객체에도 같은 라벨이 있다는 뜻은 아니다.

Deployment YAML에서 다음 위치의 라벨은 Deployment가 만들어낼 Pod에 붙는 라벨이다.

```yaml
spec:
  template:
    metadata:
      labels:
        app: podtato-head-hat
```

반면 LitmusChaos가 `App Kind: deployment`와 `App Label: app=podtato-head-hat` 조건으로 대상을 찾을 때는 Deployment 객체의 label을 기준으로 찾을 수 있다. 그래서 Pod label만 있고 Deployment label이 없으면 실험 대상 Deployment를 찾지 못해 `Target application not found`처럼 실패하거나 fault가 주입되지 않을 수 있다.

따라서 먼저 `podtato-head-hat` Deployment의 라벨을 확인한다.

```bash
kubectl get deployment podtato-head-hat \
  -n podtato-kubectl \
  --show-labels
```

`LABELS`에 다음 값이 있어야 한다.

```text
app=podtato-head-hat
```

없으면 라벨을 추가한다.

```bash
kubectl label deployment podtato-head-hat \
  app=podtato-head-hat \
  -n podtato-kubectl
```

이미 라벨이 있으면 `not labeled`가 출력될 수 있다. 이 경우는 정상이다.

정리하면, Litmus가 `hat` Pod에 장애를 넣으려면 먼저 `hat` Deployment가 어떤 리소스인지 label로 찾을 수 있어야 한다. 그래서 이번 실험에서는 Pod label뿐 아니라 Deployment 자체의 `app=podtato-head-hat` 라벨 확인이 필요하다.

---

## 7. 대상 컨테이너 리소스 설정 확인

대상 Pod의 리소스 설정을 확인한다.

```bash
kubectl describe pod -n podtato-kubectl -l app=podtato-head-hat
```

이전 `pod-memory-hog` 실습 기준 리소스 설정은 다음과 같았다.

```text
Limits:
  cpu:     500m
  memory: 128Mi

Requests:
  cpu:    100m
  memory: 32Mi
```

이번 CPU 실험에서 중요한 값은 `cpu limit = 500m`이다.

`500m`은 CPU 0.5개를 의미한다. 즉 CPU 부하를 강하게 걸어도 이 컨테이너는 최대 0.5 CPU 정도까지만 사용할 수 있고, 그 이상은 CPU limit에 의해 제한될 수 있다. 이때 컨테이너가 바로 죽기보다는 CPU throttling과 응답 지연이 발생할 가능성이 높다.

여기서 `request`는 Pod를 어느 노드에 배치할지 결정할 때 사용하는 스케줄링 기준이고, `limit`은 컨테이너가 실행 중 사용할 수 있는 최대치다. 이번 대상은 CPU request와 limit이 서로 다르므로 QoS Class는 `Burstable`이다.

CPU throttling은 컨테이너가 CPU limit을 넘어서 CPU를 더 쓰려고 할 때, Kubernetes와 container runtime이 CPU 사용을 강제로 늦추는 것이다. 예를 들어 `hat` 컨테이너는 `500m`, 즉 `0.5 CPU` 정도까지만 사용할 수 있으므로 CPU Hog가 더 많은 CPU를 쓰려고 해도 `500m` 근처에서 제한된다.

이때 컨테이너가 바로 죽는 것은 아니고, CPU를 기다리는 시간이 늘어나면서 애플리케이션 응답이 느려질 수 있다.

```text
Memory limit 초과 → OOMKilled 가능
CPU limit 초과 시도 → throttling, 응답 지연 가능
```

정확한 리소스 설정은 다음 명령으로도 확인할 수 있다.

```bash
kubectl get pod -n podtato-kubectl \
  -l app=podtato-head-hat \
  -o jsonpath='{.items[0].spec.containers[0].resources}{"\n"}'
```

---

## 8. `pod-cpu-hog` 실험 이해

`pod-cpu-hog`는 특정 프로세스가 CPU를 과도하게 사용해 같은 컨테이너 안의 애플리케이션 처리 시간을 빼앗는 상황을 재현한다. 이는 Noisy Neighbor 문제의 단순한 예로 볼 수 있다.

### 8.1 기본 검증 기준

Litmus 공식 문서의 기본 검증 기준은 다음과 같다.

```text
The application pods should be in running state before and after chaos injection.
```

즉 CPU 부하 주입 전에도 애플리케이션 Pod가 `Running`이어야 하고, CPU 부하가 끝난 뒤에도 `Running`이어야 한다.

다만 이번 실습에서 의미 있게 봐야 할 항목은 이것보다 넓다.

```text
CPU 사용량이 올라갔는가?
응답 시간이 느려졌는가?
Readiness / Liveness Probe timeout이 발생했는가?
RESTARTS는 증가하지 않았는가?
Litmus HTTP Probe는 통과했는가?
```

### 8.2 관찰 포인트

이번 실험의 가설은 다음과 같다.

```text
podtato-head-hat 컨테이너에 CPU 부하를 주입하면,
컨테이너는 재시작되지 않더라도 CPU 사용량이 증가하고,
응답 시간이 느려지거나 Kubernetes Probe timeout이 발생할 수 있다.
```

실험 중 관찰할 항목은 다음과 같다.

| 관찰 항목 | 기대 / 해석 |
| --- | --- |
| `kubectl top` | CPU 사용량 증가 여부를 확인한다. |
| `kubectl get pod -w` | `RESTARTS`는 보통 `0` 유지가 예상된다. |
| `kubectl describe pod` | Liveness / Readiness Probe timeout 이벤트를 확인한다. |
| HTTP Probe | `/healthz`가 CPU 부하 중에도 HTTP 200을 반환하는지 확인한다. |
| `curl` 응답 시간 | CPU 부하 중 latency 증가 여부를 확인한다. |

---

## 9. 주요 Fault Parameter

`pod-cpu-hog` 실험에서 이번 실습에 중요한 설정값은 다음과 같다.

| 변수 | 의미 | 추천값 |
| --- | --- | --- |
| `CPU_CORES` | CPU stress를 줄 core 수 | `1` |
| `CPU_LOAD` | CPU 사용률 퍼센트. `CPU_CORES=0`일 때 사용 | 필요 시 `100` |
| `TOTAL_CHAOS_DURATION` | CPU 부하 지속 시간 | `60` |
| `TARGET_PODS` | 특정 Pod 이름 지정 | 선택 |
| `TARGET_CONTAINER` | 대상 컨테이너 이름 지정 | `podtato-head-hat` |
| `PODS_AFFECTED_PERC` | 대상 Pod 비율 | `0` 또는 기본값 |
| `CONTAINER_RUNTIME` | 컨테이너 런타임 | `docker` |
| `SOCKET_PATH` | 런타임 socket 경로 | `/var/run/docker.sock` |
| `SEQUENCE` | 여러 Pod 대상일 때 순차 또는 동시 실행 | 기본값 또는 `serial` |

### 9.1 `CPU_CORES` 방식

처음 실습에서는 단순하게 `CPU_CORES=1`로 시작한다.

```yaml
- name: CPU_CORES
  value: "1"
- name: TOTAL_CHAOS_DURATION
  value: "60"
```

이 설정은 대상 Pod에 CPU core 1개만큼의 부하를 걸겠다는 뜻이다.

`podtato-head-hat` 컨테이너의 CPU limit이 `500m`이면 실제로 1 CPU를 모두 쓰지는 못한다. 대신 CPU limit에 의해 0.5 CPU 근처에서 제한될 수 있고, 이때 애플리케이션 응답이 느려질 가능성이 있다.

### 9.2 `CPU_LOAD` 방식

CPU core 개수를 직접 지정하지 않고 사용률을 퍼센트로 지정할 수도 있다. 이때는 `CPU_CORES`를 `0`으로 설정하고 `CPU_LOAD`를 사용한다.

```yaml
- name: CPU_LOAD
  value: "100"
- name: CPU_CORES
  value: "0"
- name: TOTAL_CHAOS_DURATION
  value: "60"
```

이 설정은 CPU core 개수를 직접 지정하지 않고 CPU load를 100%로 주입하겠다는 뜻이다.

처음 실습에서는 `CPU_CORES=1` 방식이 더 단순하다.

### 9.3 Container Runtime Socket Path

이 실험은 대상 컨테이너 안에 CPU stress를 넣어야 하므로, helper가 실제 컨테이너 런타임에 접근할 수 있어야 한다.

이번 minikube 환경은 Docker driver로 실행했으므로 다음처럼 맞춘다.

| 항목 | 값 |
| --- | --- |
| `CONTAINER_RUNTIME` | `docker` |
| `SOCKET_PATH` | `/var/run/docker.sock` |

기본값이 `containerd`와 `/run/containerd/containerd.sock`이면 helper가 대상 컨테이너를 찾지 못해 실험이 실패할 수 있다.

---

## 10. LitmusChaos UI에서 실험 만들기

ChaosCenter에서 새 실험을 만든다.

```text
Chaos Experiments
→ New Experiment
→ Blank Canvas
```

### 10.1 New Experiment

| 항목 | 값 |
| --- | --- |
| Experiment Name | `podtato-head-cpu-hog` |
| Infrastructure | `local` |
| 방식 | `Blank Canvas` |

### 10.2 Add Fault

ChaosHub에서 다음 fault를 선택한다.

```text
pod-cpu-hog
```

문서상 `pod-cpu-hog`는 대상 application container의 CPU resource를 소비하고, CPU spike 상황을 재현하는 실험이다.

### 10.3 Target Application

| 항목 | 값 |
| --- | --- |
| App Kind | `deployment` |
| App Namespace | `podtato-kubectl` |
| App Label | `app=podtato-head-hat` |

---

## 11. Probe 설정

`pod-memory-hog` 때 만든 HTTP Probe를 그대로 재사용해도 된다.

| 항목 | 값 |
| --- | --- |
| Probe Name | `check-podtato-head-hat-http` |
| Probe Type | HTTP Probe |
| Mode | `Continuous` |
| URL | `http://podtato-head-hat.podtato-kubectl.svc.cluster.local:8080/healthz` |
| Method | `GET` |
| Expected Response Code | `200` |
| Timeout | `10s` |
| Interval | `2s` |

Mode는 이번에도 `Continuous`로 둔다. CPU 부하 실험은 실험 종료 후 복구됐는지보다, 부하가 진행되는 동안 계속 응답하는지가 더 중요하기 때문이다.

이 Probe는 다음 조건을 검증한다.

```text
CPU 부하 중에도 /healthz가 HTTP 200을 반환하는가?
```

---

## 12. Tune Fault 설정

`pod-cpu-hog`의 정확한 UI 항목 이름은 LitmusChaos 버전에 따라 조금 다를 수 있다. 핵심은 CPU core 수, duration, 대상 컨테이너, runtime 설정이다.

처음에는 너무 강하게 설정하지 않고 다음 값으로 시작한다.


| 파라미터 | 값 |
| --- | --- |
| `CPU_CORES` | `1` |
| `TOTAL_CHAOS_DURATION` | `60` |
| `TARGET_CONTAINER` | `podtato-head-hat` |
| `PODS_AFFECTED_PERC` | `0` 또는 기본값 |
| `CONTAINER_RUNTIME` | `docker` |
| `SOCKET_PATH` | `/var/run/docker.sock` |
| `SEQUENCE` | 기본값 또는 `serial` |

이전 `pod-memory-hog`와 마찬가지로 runtime 설정을 실제 클러스터 환경에 맞추는 것이 중요하다.

```text
CONTAINER_RUNTIME = docker
SOCKET_PATH = /var/run/docker.sock
```

이 값을 맞추지 않으면 helper가 대상 컨테이너를 찾지 못해 실험이 실패할 수 있다.

---

## 13. 실험 중 관찰 명령

실험을 실행하기 전에 관찰용 터미널을 4개 준비한다. CPU hog는 컨테이너 재시작보다 CPU 사용량, 응답 시간, Probe timeout을 같이 보는 것이 중요하다.

### 터미널 1: Pod 상태

```bash
kubectl get pod -n podtato-kubectl -l app=podtato-head-hat -w
```

관찰할 항목:

```text
RESTARTS 변화?
READY 1/1 유지?
READY가 0/1로 내려가는 순간이 있는가?
```

### 터미널 2: CPU 사용량

```bash
watch -n 1 'kubectl top pod -n podtato-kubectl -l app=podtato-head-hat'
```

관찰할 항목:

```text
CPU(cores)가 평소보다 올라가는가?
CPU limit인 500m 근처까지 올라가는가?
```

### 터미널 3: Kubernetes 이벤트

```bash
kubectl get events -n podtato-kubectl --sort-by='.lastTimestamp' -w
```

관찰할 항목:

```text
Liveness probe failed
Readiness probe failed
context deadline exceeded
```

### 터미널 4: 직접 응답 시간 측정

임시 `curl` Pod를 실행한다.

```bash
kubectl run curl-test -n podtato-kubectl --rm -it --restart=Never \
  --image=curlimages/curl -- sh
```

Pod 안에 들어간 뒤 `/healthz` 응답 시간과 상태 코드를 1초마다 확인한다.

```sh
for i in $(seq 1 120); do
  printf "$(date +%H:%M:%S) "
  curl -s -o /dev/null -w "status=%{http_code} time=%{time_total}\n" \
    http://podtato-head-hat.podtato-kubectl.svc.cluster.local:8080/healthz
  sleep 1
done
```

관찰할 항목:

```text
status=200이 유지되는가?
time_total이 증가하는가?
timeout 또는 status=000이 발생하는가?
```

필요하면 별도 터미널에서 Pod 상세 정보도 확인한다.

```bash
kubectl describe pod -n podtato-kubectl -l app=podtato-head-hat
```

---

## 14. ChaosCenter에서 Run

위 관찰 터미널들을 켜둔 상태에서 ChaosCenter UI에서 `Run`을 누른다.

실행 중에는 특히 다음 조합을 함께 본다.

```text
CPU 사용량 증가
+ RESTARTS 0 유지
+ /healthz 200 유지
+ 응답 시간 증가 여부
+ Readiness / Liveness timeout 이벤트 여부
```

예상 결과는 다음과 같이 해석한다.

| 관찰 결과 | 해석 |
| --- | --- |
| CPU 증가, Litmus Probe Pass, `RESTARTS=0` | CPU 부하는 들어갔지만 서비스는 버틴 상태 |
| CPU 증가, Litmus Probe Pass, Kubernetes Probe timeout | 서비스는 응답했지만 1초 기준 Kubernetes Probe에는 느렸던 상태 |
| CPU 증가, Litmus Probe Fail | CPU 부하가 서비스 응답 조건을 깨뜨린 상태 |
| CPU 변화 없음, 실험 Fail | runtime 또는 socket 설정 문제 가능 |

---

## 15. 첫 번째 실행 결과: `CPU_CORES=1`, `CPU_LOAD=0`

처음에는 다음 값으로 설정하였다.

| 파라미터 | 값 |
| --- | --- |
| `TOTAL_CHAOS_DURATION` | `60` |
| `CPU_CORES` | `1` |
| `CPU_LOAD` | `0` |
| `PODS_AFFECTED_PERC` | `0` |
| `CONTAINER_RUNTIME` | `docker` |
| `SOCKET_PATH` | `/var/run/docker.sock` |
| `TARGET_CONTAINER` | `podtato-head-hat` |

이 설정으로 실행했을 때 `ChaosResult`는 `Pass`였지만, `kubectl top`에서 CPU 사용량은 계속 낮게 유지되었다.

```text
CPU: 1m~3m
```

따라서 이 실행만으로는 CPU 부하가 대상 컨테이너에 유의미하게 주입되었다고 보기 어려웠다.

이 과정에서 확인한 점은 `CPU_LOAD` 방식을 사용할 때 문서 기준으로 `CPU_CORES=0`으로 설정해야 한다는 것이다. `CPU_CORES`와 `CPU_LOAD`를 애매하게 함께 설정하면 기대한 방식으로 CPU 부하가 들어가지 않을 수 있다.

첫 번째 실행에서 의도한 것은 `CPU_CORES=1`로 CPU core 1개만큼 부하를 거는 것이었다. 하지만 실제 설정에는 `CPU_LOAD=0`도 함께 들어가 있었다.

```text
CPU_CORES = 1
CPU_LOAD = 0
```

Litmus 문서 기준으로 CPU load 방식을 사용할 때는 다음처럼 설정해야 한다.

```text
CPU_CORES = 0
CPU_LOAD = 100
```

따라서 첫 번째 실행에서는 UI 또는 runner 내부에서 `CPU_LOAD=0` 값이 함께 전달되면서, 실제로는 CPU load 0%에 가까운 설정으로 해석되었을 가능성이 있다. 즉 의도는 CPU core 1개만큼 부하를 거는 것이었지만, `CPU_LOAD=0`이 같이 적용되어 유의미한 CPU stress가 발생하지 않았을 수 있다.

---

## 16. 두 번째 실행 결과: `CPU_CORES=0`, `CPU_LOAD=100`

이후 설정을 다음처럼 변경하였다.

| 파라미터 | 값 |
| --- | --- |
| `TOTAL_CHAOS_DURATION` | `120` |
| `CPU_CORES` | `0` |
| `CPU_LOAD` | `100` |
| `PODS_AFFECTED_PERC` | `0` |
| `CONTAINER_RUNTIME` | `docker` |
| `SOCKET_PATH` | `/var/run/docker.sock` |
| `TARGET_CONTAINER` | `podtato-head-hat` |

이 설정은 CPU core 개수를 직접 지정하는 대신 CPU load를 100%로 주입하는 방식이다.

다시 실행하자 CPU 부하가 정상적으로 관찰되었다.

```text
CPU: 439m
CPU: 500m
```

다른 실행에서는 최대 약 `399m`까지 관찰되었다.

실험 전 CPU 사용량이 `1m~3m` 수준이었고, 실험 중 CPU 사용량이 컨테이너 limit인 `500m` 근처까지 상승했으므로 CPU 부하가 실제로 주입되었다고 해석할 수 있다.

---

## 17. ChaosResult 확인

실험 후 `ChaosResult`를 확인하였다.

```bash
kubectl describe chaosresult pod-cpu-hog-p3ojsvzd-pod-cpu-hog -n litmus
```

확인된 주요 내용은 다음과 같았다.

```text
Phase: Completed
Probe Success Percentage: 100
Verdict: Pass
```

대상 Pod도 정상적으로 잡혔다.

```text
Targets:
  Chaos Status: reverted
  Kind: pod
  Name: podtato-head-hat-8699f5d6d4-7mcsn
```

HTTP Probe도 통과하였다.

```text
Mode: Continuous
Name: check-podtato-head-hat-http
Verdict: Passed
Type: httpProbe
```

Probe 설명에는 `/healthz`가 기대한 상태 코드 `200`으로 응답했다고 기록되었다.

```text
Actual code: '200'
Expected code: '200'
```

즉 CPU 부하가 들어가는 동안에도 `hat` 서비스의 `/healthz` endpoint는 정상 응답을 유지하였다.

---

## 18. `/healthz` 응답 시간 확인

CPU 부하 주입 중 임시 `curl` Pod에서 `/healthz` 응답 상태와 응답 시간을 1초마다 확인하였다.

```sh
for i in $(seq 1 180); do
  printf "$(date +%H:%M:%S) "
  curl -s -o /dev/null -w "status=%{http_code} time=%{time_total}\n" \
    http://podtato-head-hat.podtato-kubectl.svc.cluster.local:8080/healthz
  sleep 1
done
```

초기 구간에서는 대부분 수 ms 수준으로 응답하였다.

```text
11:56:15 status=200 time=0.008443
11:56:16 status=200 time=0.003378
11:56:17 status=200 time=0.005656
11:56:18 status=200 time=0.002357
11:56:19 status=200 time=0.036467
```

CPU 부하가 관찰된 일부 구간에서는 응답 시간이 `60ms~170ms` 수준까지 증가하였다.

```text
11:58:18 status=200 time=0.061659
11:58:19 status=200 time=0.073708
11:58:20 status=200 time=0.079257
11:58:27 status=200 time=0.081900
11:58:30 status=200 time=0.169407
11:58:54 status=200 time=0.102684
11:59:01 status=200 time=0.103306
11:59:08 status=200 time=0.101346
```

모든 요청은 HTTP 200으로 응답하였다. 따라서 CPU 부하 주입 중 `/healthz` 기준의 서비스 중단은 발생하지 않았다.

다만 평소 수 ms 수준이던 응답 시간이 일부 구간에서 `60ms~170ms` 수준까지 증가했으므로, CPU 부하로 인한 경미한 응답 지연은 관찰되었다. 이 지연은 Kubernetes Probe의 `timeoutSeconds=1` 기준인 1초를 넘지는 않았기 때문에 Liveness / Readiness Probe timeout으로 이어질 정도의 지연은 아니었다.

정리하면 다음과 같다.

```text
/healthz 요청은 모두 HTTP 200으로 응답했다.
일부 구간에서 응답 시간이 60ms~170ms 수준까지 증가했다.
Kubernetes Probe timeout 기준인 1초를 넘지는 않았다.
```

---

## 19. 결론

이번 `pod-cpu-hog` 실험에서는 `podtato-head-hat` 컨테이너에 CPU 부하를 주입하였다. 초기 설정인 `CPU_CORES=1`, `CPU_LOAD=0`에서는 CPU 사용량 증가가 관찰되지 않았지만, 이후 `CPU_CORES=0`, `CPU_LOAD=100`으로 변경하자 CPU 사용량이 최대 `399m~500m`까지 증가하였다.

이는 `hat` 컨테이너의 CPU limit인 `500m` 근처까지 CPU 부하가 실제로 주입되었음을 의미한다. 실험 중 LitmusChaos의 Continuous HTTP Probe는 `/healthz` endpoint가 계속 `200`으로 응답했음을 확인했고, `ChaosResult`도 `Pass`로 기록되었다. 직접 `curl`로 측정한 `/healthz` 응답도 모두 HTTP 200이었지만, 일부 구간에서는 평소 수 ms 수준에서 `60ms~170ms` 수준까지 증가하여 경미한 응답 지연이 관찰되었다.

Pod는 최종적으로 `Running` / `Ready` 상태를 유지했으며, 이번 실험 중 새롭게 발생한 컨테이너 재시작은 명확히 관찰되지 않았다.

따라서 이번 설정에서는 CPU 부하가 실제로 발생했고 경미한 응답 지연도 관찰되었지만, `hat` 서비스의 health check 응답은 유지되었고 컨테이너 재시작이나 Kubernetes Probe timeout으로 이어지지는 않았다.

한 줄로 정리하면:

```text
pod-cpu-hog 실험을 통해 hat 컨테이너의 CPU 사용량이 limit 근처까지 상승하는 것을 확인했지만,
서비스의 /healthz 응답은 유지되었다. 일부 경미한 응답 지연은 있었지만 명확한 신규 Probe timeout이나 재시작은 관찰되지 않았다.
```

---

## 20. 이번 실험으로 알 수 있는 것

이번 `pod-cpu-hog` 실험으로 알 수 있는 것은 크게 3가지다.

### 20.1 CPU 부하가 실제로 주입되었는지 확인할 수 있었다

처음에는 `CPU_CORES=1`, `CPU_LOAD=0` 설정에서 CPU 사용량이 `1m~3m` 정도로 거의 변하지 않았다. 그래서 `ChaosResult`가 `Pass`였더라도 실제 CPU 부하가 들어갔는지 의심할 수 있었다.

이후 `CPU_CORES=0`, `CPU_LOAD=100`으로 바꾸자 CPU가 `399m~500m`까지 올라갔다. `hat` 컨테이너의 CPU limit이 `500m`였으므로 CPU 부하가 실제로 컨테이너 limit 근처까지 들어간 것이다.

즉 이번 실험에서 가장 확실히 확인한 것은 다음이다.

```text
pod-cpu-hog를 통해 target Pod의 CPU 사용량을 실제로 증가시킬 수 있었다.
```

Litmus 문서에서도 `pod-cpu-hog`는 애플리케이션 컨테이너의 CPU 자원을 소비해서 CPU spike 상황을 시뮬레이션하는 실험이라고 설명한다.

### 20.2 CPU 부하는 컨테이너를 바로 죽이는 장애가 아니다

`pod-memory-hog`에서는 memory limit을 넘으면 cgroup OOM이 발생했고, 경우에 따라 프로세스가 종료될 수 있었다.

하지만 `pod-cpu-hog`는 다르게 해석해야 한다. CPU는 limit을 넘는다고 `OOMKilled`처럼 바로 죽는 것이 아니라, 보통 CPU 사용이 제한되거나 응답이 느려지는 방식으로 영향이 나타난다.

이번 실험에서도 CPU는 `500m` 근처까지 올라갔지만 Pod는 계속 `1/1 Running` 상태였고, 이번 CPU Hog로 인한 명확한 신규 재시작은 관찰되지 않았다.

| 실험 | 주요 영향 |
| --- | --- |
| Memory Hog | OOM, 프로세스 종료, Restart 가능성 |
| CPU Hog | CPU 사용량 증가, throttling, 응답 지연 가능성 |

### 20.3 CPU 부하 중에도 `/healthz`는 유지되었다

`ChaosResult`에서는 다음 결과가 확인되었다.

```text
Phase: Completed
Probe Success Percentage: 100
Verdict: Pass
```

HTTP Probe도 `/healthz`가 기대한 상태 코드 `200`으로 응답했다고 기록하였다.

```text
Actual code: '200'
Expected code: '200'
```

즉 이번 설정에서는 CPU 부하가 들어갔지만 `hat` 서비스의 health endpoint는 계속 정상 응답을 유지했다. 다만 직접 `curl`로 측정한 응답 시간은 일부 구간에서 `60ms~170ms` 수준까지 증가하였다.

다만 이것을 서비스 전체가 완전히 영향이 없었다는 뜻으로 해석하면 안 된다. 이번 Probe는 `/healthz`가 HTTP 200인지 확인한 것이고, 실제 사용자 요청의 응답 지연, frontend 화면 변화, 다른 API의 성능까지 검증한 것은 아니다.

정확한 결론은 다음이다.

```text
CPU 부하 중에도 health check endpoint는 정상 응답을 유지했다.
다만 일부 구간에서 경미한 응답 지연은 관찰되었다.
```
