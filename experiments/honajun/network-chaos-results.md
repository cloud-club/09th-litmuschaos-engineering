# Network Chaos Engineering 실험 결과 (2026-06-01)

## 환경

- Cluster: minikube v1.38.1 (Docker driver, container-runtime=containerd 2.2.1)
- Kubernetes: v1.35.1
- Namespace: chaos-demo
- LitmusChaos: Operator v3.0.0
- Workloads:
  - web (nginx:1.25, replicas=3, exec-based probes)
  - dns-client (curlimages/curl:8.5.0, replicas=2)

---

## Steady State

```
web:        READY 3/3, RESTARTS 0
dns-client: READY 2/2
DNS 해석:   Address: 10.98.37.134 (성공)
HTTP:       200
CoreDNS:    Running
```

---

## 시나리오 1 — Pod DNS Error (LitmusChaos)

### 가설

> DNS 해석을 차단하면 dns-client에서 web 서비스로의 HTTP 호출이 실패하지만,
> web Pod 자체는 정상 Running을 유지한다. DNS 복구 후 즉시 통신이 재개된다.

### 실험 방법: LitmusChaos pod-dns-error

### 시도 1 — Docker runtime (실패)

- ChaosEngine 설정: `CONTAINER_RUNTIME=docker`, `SOCKET_PATH=/var/run/docker.sock`
- 결과: **Helper Pod 실패**
- 에러: `client version 1.40 is too old. Minimum supported API version is 1.44`
- 원인: minikube Docker driver 내부의 Docker API 버전(29.x)이 LitmusChaos go-runner 3.0.0의 Docker client(1.40)와 호환되지 않음

### 시도 2 — containerd runtime (실패)

- minikube를 `--container-runtime=containerd`로 재생성
- ChaosEngine 설정: `CONTAINER_RUNTIME=containerd`, `SOCKET_PATH=/run/containerd/containerd.sock`
- 결과: **Helper Pod 실패**
- 에러: `dns_interceptor` exit status 1
- 원인: LitmusChaos 3.0.0의 dns_interceptor 바이너리가 containerd 2.2.1 환경에서 호환성 문제

### 시도 3 — NetworkPolicy 수동 주입 (성공)

LitmusChaos가 동작하지 않아 NetworkPolicy로 DNS 트래픽을 차단하는 수동 방식으로 전환.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: block-dns
  namespace: chaos-demo
spec:
  podSelector:
    matchLabels:
      app: dns-client
  policyTypes:
    - Egress
  egress:
    - to: []
      ports:
        - protocol: TCP
          port: 80
```

### 관찰 결과

| 시점 | 확인 항목 | 결과 |
|------|-----------|------|
| 주입 전 | dns-client 로그 | DNS=Address:10.98.37.134, HTTP=200 |
| 주입 후 (~6초) | dns-client 로그 | `connection timed out; no servers could be reached` HTTP=000 |
| 주입 중 | web Pod 상태 | 3/3 Running 유지 (무영향) |
| 복구 후 (~3초) | dns-client 로그 | DNS 성공, HTTP=200 재개 |

### 타임라인

```
15:33:30 — NetworkPolicy 적용
15:33:35 — DNS timeout 시작 (약 5초 후)
15:34:15 — NetworkPolicy 삭제
15:34:18 — DNS 해석 + HTTP 200 복구 (약 3초 후)
```

### 결론

- 가설 검증: **통과** (수동 방식)
- DNS 차단 시 HTTP 통신 완전 실패, web Pod 무영향, 복구 즉시 재개
- LitmusChaos pod-dns-error는 현재 환경(minikube + containerd 2.2.1)에서 호환성 문제로 동작하지 않음

---

## 시나리오 2 — Pod Network Loss (LitmusChaos)

### 가설

> 네트워크를 100% 차단하면 대상 Pod가 모든 통신을 잃고,
> 네트워크 복구 후 정상 통신이 재개된다.

### 실험 방법: LitmusChaos pod-network-loss (containerd)

### ChaosEngine 설정

```yaml
TOTAL_CHAOS_DURATION: 60
NETWORK_INTERFACE: eth0
NETWORK_PACKET_LOSS_PERCENTAGE: 100
PODS_AFFECTED_PERC: 50
CONTAINER_RUNTIME: containerd
SOCKET_PATH: /run/containerd/containerd.sock
```

### 실행 결과: **성공**

Helper Pod가 정상 동작하여 tc netem으로 100% packet loss 주입.

```
tc qdisc replace dev eth0 root netem loss 100
chaos injected successfully on {pod: web-7f7bc8cfd9-qp9fk, container: web}
```

### 관찰 결과

| 시점 | 확인 항목 | 결과 |
|------|-----------|------|
| 주입 중 | 영향받은 Pod (10.244.0.7) 직접 접근 | HTTP 000 (timeout) |
| 주입 중 | 영향받지 않은 Pod (10.244.0.4) 직접 접근 | HTTP 200 |
| 주입 중 | web Pod STATUS | 3/3 Running (exec probe라 네트워크 무관) |
| 주입 중 | Endpoint | 3개 유지 (exec probe 통과) |
| 주입 중 | Service 경유 접근 | 간헐적 실패 (영향 Pod로 라우팅 시) |
| 복구 후 | 영향받은 Pod 직접 접근 | HTTP 200 |

### 타임라인

```
15:34:48 — tc netem loss 100 주입
15:35:07 — 영향 Pod 통신 불가 확인 (HTTP 000)
15:35:48 — 60초 경과, tc rule 제거
15:35:58 — 영향 Pod 통신 복구 확인 (HTTP 200)
```

### 예상과 다른 점

- **Endpoint에서 제외되지 않음**: Readiness Probe가 `exec` 방식(파일 존재 확인)이라 네트워크 차단과 무관하게 통과
- 만약 HTTP 기반 Readiness Probe였다면 endpoint에서 제외되어 Service 트래픽이 정상 Pod로만 라우팅됐을 것

### 결론

- 가설 검증: **부분 통과**
- 네트워크 차단 → 대상 Pod 통신 불가 ✅
- 복구 후 즉시 통신 재개 ✅
- Readiness Probe 실패로 endpoint 제외 ❌ (exec probe는 네트워크 무관)
- **교훈**: 네트워크 장애 감지를 위해서는 HTTP 기반 Readiness Probe가 필수

---

## LitmusChaos 호환성 이슈 정리

| 실험 | Runtime | 결과 | 에러 |
|------|---------|------|------|
| pod-dns-error | docker | ❌ 실패 | Docker API 1.44 최소 요구, go-runner는 1.40 사용 |
| pod-dns-error | containerd | ❌ 실패 | dns_interceptor exit status 1 |
| pod-network-loss | containerd | ✅ 성공 | tc netem 정상 동작 |

### 원인 분석

- LitmusChaos 3.0.0의 `go-runner` 이미지가 최신 Docker/containerd 버전과 호환성 문제
- `pod-network-loss`는 단순 tc 명령으로 동작하여 호환성 영향 적음
- `pod-dns-error`는 `dns_interceptor` 바이너리가 네트워크 namespace에 진입하여 DNS를 가로채는 복잡한 동작이라 실패

### 해결 방안 (향후)

1. LitmusChaos 3.x 최신 버전 사용 (go-runner 이미지 업데이트)
2. minikube 대신 kind 클러스터 사용 (containerd 직접 접근 가능)
3. DNS 실험은 NetworkPolicy 방식으로 대체 가능

---

## 전체 요약

| 시나리오 | 방법 | 결과 | 복구 시간 | 핵심 관찰 |
|----------|------|------|-----------|-----------|
| DNS Error (LitmusChaos) | pod-dns-error | ❌ 실패 | - | Runtime 호환성 문제 |
| DNS Error (수동) | NetworkPolicy | ✅ 성공 | ~3초 | DNS 차단 → HTTP 실패, web 무영향 |
| Network Loss (LitmusChaos) | pod-network-loss | ✅ 성공 | 즉시 | tc netem으로 패킷 100% drop |

## 실제 운영 상황과의 연결

### DNS Error → CoreDNS 장애, DNS 설정 오류, 클라우드 DNS 서비스 장애

| 실험 상황 | 실제 운영 상황 |
|-----------|---------------|
| NetworkPolicy로 UDP 53 차단 | CoreDNS Pod 장애, kube-dns Service 삭제, VPC DNS resolver 장애 |
| nslookup timeout → HTTP 000 | 마이크로서비스 간 통신 전면 실패 (서비스 디스커버리 불가) |
| web Pod 무영향 | 이미 연결된 TCP 세션은 유지, 새 연결만 실패 |

**운영 시사점**:
- DNS는 Kubernetes 서비스 통신의 단일 장애점(SPOF) — CoreDNS 장애 시 클러스터 전체 통신 마비
- `dnsPolicy: ClusterFirst` 기본 설정에서 CoreDNS 2개 replica로는 부족할 수 있음
- DNS 캐시(NodeLocal DNSCache) 도입으로 CoreDNS 장애 시 영향 범위 축소 가능
- 서비스 간 통신에 retry + circuit breaker 패턴 적용 시 일시적 DNS 실패에 대한 내성 확보
- **실제 사례**: 2021년 AWS Route 53 장애 시 DNS 의존 서비스 전면 중단

### Network Loss → NIC 장애, 네트워크 파티션, AZ 간 통신 단절

| 실험 상황 | 실제 운영 상황 |
|-----------|---------------|
| tc netem loss 100% | 물리 NIC 장애, 스위치 장애, AZ 간 네트워크 파티션 |
| 영향 Pod 통신 불가 | 특정 노드의 Pod들이 격리됨 |
| exec Probe 통과 → endpoint 유지 | 장애 Pod로 트래픽 라우팅 → 사용자 에러 발생 |
| 복구 후 즉시 통신 재개 | 네트워크 복구 시 자동 회복 |

**운영 시사점**:
- **Probe 설계가 핵심**: exec 기반 Probe는 네트워크 장애를 감지하지 못함
  - HTTP Probe: `httpGet: {path: /healthz, port: 8080}` → 네트워크 장애 시 timeout → endpoint 제외
  - TCP Probe: `tcpSocket: {port: 80}` → 네트워크 장애 시 연결 실패 → endpoint 제외
- 50% Pod 영향 시 Service가 장애 Pod로 라우팅하면 사용자 요청의 ~50%가 실패
- Pod Anti-Affinity로 Pod를 여러 노드에 분산 → 단일 노드 네트워크 장애 시 영향 최소화
- **실제 사례**: 클라우드 AZ 간 네트워크 파티션 시 split-brain 발생, 데이터 정합성 문제

### Probe 설계 권장사항 (이번 실험에서 도출)

```yaml
# 네트워크 장애 감지가 가능한 Probe 설계
readinessProbe:
  httpGet:
    path: /readyz      # 의존 서비스 연결 상태 포함
    port: 8080
  periodSeconds: 3
  failureThreshold: 2  # 6초 후 endpoint 제외

livenessProbe:
  httpGet:
    path: /livez       # 프로세스 생존만 확인
    port: 8080
  periodSeconds: 5
  failureThreshold: 3  # 15초 후 재시작
```

| Probe 방식 | 네트워크 장애 감지 | 적합한 상황 |
|------------|-------------------|-------------|
| exec (파일 확인) | ❌ 불가 | sidecar 없는 단순 컨테이너 |
| httpGet | ✅ 가능 | 웹 서버, API 서버 |
| tcpSocket | ✅ 가능 | DB, 메시지 큐 등 HTTP 없는 서비스 |

---

## Lessons Learned

1. **LitmusChaos는 환경 호환성이 중요** — container runtime 버전, API 버전에 따라 실험이 실패할 수 있음
2. **exec 기반 Probe는 네트워크 장애를 감지하지 못함** — HTTP/TCP Probe를 사용해야 네트워크 장애 시 endpoint 제외 동작
3. **NetworkPolicy는 DNS 장애 시뮬레이션의 간단한 대안** — LitmusChaos 없이도 네트워크 격리 실험 가능
4. **tc netem은 안정적** — container PID만 확보하면 네트워크 장애 주입이 확실하게 동작
5. **실험 도구 실패도 학습** — 도구가 실패하는 원인을 분석하는 것 자체가 시스템 이해도를 높임
