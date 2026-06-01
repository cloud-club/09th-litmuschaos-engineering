# Network Chaos Engineering 실습 계획

## 개요

minikube 로컬 클러스터에서 Pod의 네트워크 장애(DNS 해석 실패, 네트워크 완전 차단)가 애플리케이션에 미치는 영향을 과학적으로 검증한다.
실험 방식: **Steady State 정의 → 가설 수립 → 장애 주입 → 관찰 → 복구 확인**

---

## 사전 조건

- `deployment-chaos-plan.md`의 1~2단계 완료 (minikube + web Deployment 배포)
- LitmusChaos 설치 완료

### LitmusChaos 설치 (미설치 시)

```bash
# Litmus namespace 생성
kubectl create namespace litmus

# LitmusChaos Operator 설치
kubectl apply -f https://litmuschaos.github.io/litmus/litmus-operator-v3.0.0.yaml -n litmus

# 설치 확인
kubectl get pods -n litmus
# 기대값: chaos-operator-ce, chaos-exporter Running

# Generic experiments 설치
kubectl apply -f https://hub.litmuschaos.io/api/chaos/3.0.0?file=charts/generic/experiments.yaml -n chaos-demo
```

### Chaos 대상 Annotation 추가

```bash
kubectl annotate deployment/web -n chaos-demo litmuschaos.io/chaos="true"
```

### ChaosServiceAccount 생성

```yaml
# chaos-sa.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: chaos-sa
  namespace: chaos-demo
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: chaos-cluster-role
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/exec", "pods/log", "events", "services"]
    verbs: ["get", "list", "watch", "create", "delete", "patch"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["litmuschaos.io"]
    resources: ["chaosengines", "chaosexperiments", "chaosresults"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["get", "list", "watch", "create", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: chaos-cluster-role-binding
subjects:
  - kind: ServiceAccount
    name: chaos-sa
    namespace: chaos-demo
roleRef:
  kind: ClusterRole
  name: chaos-cluster-role
  apiGroup: rbac.authorization.k8s.io
```

```bash
kubectl apply -f chaos-sa.yaml
```

---

## 실험 대상 — DNS 의존 워크로드 추가

DNS 실험을 위해 외부 서비스를 호출하는 워크로드가 필요하다.

```yaml
# dns-client-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dns-client
  namespace: chaos-demo
  annotations:
    litmuschaos.io/chaos: "true"
spec:
  replicas: 2
  selector:
    matchLabels:
      app: dns-client
  template:
    metadata:
      labels:
        app: dns-client
    spec:
      containers:
        - name: client
          image: curlimages/curl:8.5.0
          command: ["/bin/sh", "-c"]
          args:
            - |
              while true; do
                echo "$(date +%H:%M:%S) DNS=$(nslookup web.chaos-demo.svc.cluster.local 2>&1 | tail -2) HTTP=$(curl -s -o /dev/null -w '%{http_code}' http://web.chaos-demo.svc.cluster.local)"
                sleep 2
              done
          resources:
            requests:
              cpu: 50m
              memory: 32Mi
            limits:
              cpu: 100m
              memory: 64Mi
```

```bash
kubectl apply -f dns-client-deployment.yaml
kubectl rollout status deployment/dns-client -n chaos-demo

# DNS 해석 정상 확인
kubectl logs -n chaos-demo -l app=dns-client --tail=3
# 기대값: DNS=... HTTP=200
```

---

## Steady State 정의

```bash
# 1. web Deployment READY
kubectl get deployment web -n chaos-demo
# 기대값: READY 3/3

# 2. dns-client Deployment READY
kubectl get deployment dns-client -n chaos-demo
# 기대값: READY 2/2

# 3. DNS 해석 성공
kubectl exec -n chaos-demo $(kubectl get pod -n chaos-demo -l app=dns-client -o name | head -1) -- nslookup web.chaos-demo.svc.cluster.local
# 기대값: Address: 10.x.x.x

# 4. 서비스 간 HTTP 통신 성공
kubectl exec -n chaos-demo $(kubectl get pod -n chaos-demo -l app=dns-client -o name | head -1) -- curl -s -o /dev/null -w "%{http_code}" http://web.chaos-demo.svc.cluster.local
# 기대값: 200

# 5. CoreDNS 정상
kubectl get pods -n kube-system -l k8s-app=kube-dns
# 기대값: Running
```

5개 항목 모두 통과해야 실험을 진행한다.

---

## 부하 생성기 (실험 중 상시 유지)

```bash
# Terminal A — dns-client 로그 실시간 관찰
kubectl logs -n chaos-demo -l app=dns-client -f

# Terminal B — Pod 상태 실시간
kubectl get pods -n chaos-demo -w

# Terminal C — ChaosEngine/ChaosResult 관찰
watch -n 2 "kubectl get chaosengine,chaosresult -n chaos-demo"
```

---

## 시나리오 1 — Pod DNS Error

### 가설

> DNS 해석을 차단하면 dns-client Pod에서 web 서비스로의 HTTP 호출이 실패하지만,
> web Pod 자체는 정상 Running을 유지한다. DNS 복구 후 즉시 통신이 재개된다.

### 실험 설명

`pod-dns-error`는 대상 Pod의 DNS 요청을 가로채서 에러 응답을 반환한다.
실제 네트워크는 차단하지 않으며, DNS 해석만 실패시킨다.

### ChaosEngine YAML

```yaml
# dns-error-engine.yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: dns-error-chaos
  namespace: chaos-demo
spec:
  appinfo:
    appns: chaos-demo
    applabel: "app=dns-client"
    appkind: deployment
  annotationCheck: "true"
  engineState: active
  chaosServiceAccount: chaos-sa
  experiments:
    - name: pod-dns-error
      spec:
        components:
          env:
            - name: TOTAL_CHAOS_DURATION
              value: "60"
            - name: TARGET_HOSTNAMES
              value: "web.chaos-demo.svc.cluster.local"
            - name: MATCH_SCHEME
              value: "exact"
            - name: PODS_AFFECTED_PERC
              value: "100"
            - name: CONTAINER_RUNTIME
              value: "docker"
            - name: SOCKET_PATH
              value: "/var/run/docker.sock"
        probe:
          - name: web-pod-running
            type: cmdProbe
            mode: Continuous
            runProperties:
              probeTimeout: 5s
              interval: 5s
              retry: 2
            cmdProbe/inputs:
              command: "kubectl get deployment web -n chaos-demo -o jsonpath='{.status.readyReplicas}'"
              comparator:
                type: int
                criteria: "=="
                value: "3"
```

### 실행

```bash
kubectl apply -f dns-error-engine.yaml

# 실험 상태 확인
kubectl get chaosengine dns-error-chaos -n chaos-demo -o jsonpath='{.status.engineStatus}'
# 기대값: completed (60초 후)
```

### 관찰 포인트

| 시점 | 확인 항목 | 기대값 |
|------|-----------|--------|
| 주입 중 | dns-client 로그 | `nslookup` 실패, HTTP 000 또는 timeout |
| 주입 중 | web Pod 상태 | Running 3/3 유지 |
| 주입 중 | web Service 직접 IP 접근 | 200 (DNS 우회 시) |
| 복구 후 | dns-client 로그 | DNS 성공, HTTP 200 재개 |

### 관찰 명령어

```bash
# DNS 실패 확인 (dns-client 내부)
kubectl logs -n chaos-demo -l app=dns-client --tail=10

# web Pod는 영향 없음 확인
kubectl get pods -n chaos-demo -l app=web

# ChaosResult 확인
kubectl get chaosresult dns-error-chaos-pod-dns-error -n chaos-demo -o jsonpath='{.status.experimentStatus.verdict}'
```

### 복구 확인

실험 종료(60초) 후 자동 복구. Steady State 체크리스트 재실행.

```bash
# DNS 재해석 확인
kubectl exec -n chaos-demo $(kubectl get pod -n chaos-demo -l app=dns-client -o name | head -1) -- nslookup web.chaos-demo.svc.cluster.local
```

### 정리

```bash
kubectl delete chaosengine dns-error-chaos -n chaos-demo
```

---

## 시나리오 2 — Pod Network Loss

### 가설

> 네트워크를 100% 차단하면 대상 Pod가 모든 통신(인바운드/아웃바운드)을 잃고,
> Readiness Probe 실패로 Service endpoint에서 제외된다.
> 네트워크 복구 후 Readiness Probe 통과 시 endpoint에 재등록된다.

### 실험 설명

`pod-network-loss`는 tc(traffic control)를 사용하여 대상 Pod의 네트워크 패킷을 100% drop한다.
DNS뿐 아니라 모든 TCP/UDP 통신이 차단된다.

### ChaosEngine YAML

```yaml
# network-loss-engine.yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: network-loss-chaos
  namespace: chaos-demo
spec:
  appinfo:
    appns: chaos-demo
    applabel: "app=web"
    appkind: deployment
  annotationCheck: "true"
  engineState: active
  chaosServiceAccount: chaos-sa
  experiments:
    - name: pod-network-loss
      spec:
        components:
          env:
            - name: TOTAL_CHAOS_DURATION
              value: "60"
            - name: NETWORK_INTERFACE
              value: "eth0"
            - name: NETWORK_PACKET_LOSS_PERCENTAGE
              value: "100"
            - name: PODS_AFFECTED_PERC
              value: "50"
            - name: TARGET_PODS
              value: ""
            - name: CONTAINER_RUNTIME
              value: "docker"
            - name: SOCKET_PATH
              value: "/var/run/docker.sock"
        probe:
          - name: service-available
            type: httpProbe
            mode: Continuous
            runProperties:
              probeTimeout: 5s
              interval: 5s
              retry: 2
            httpProbe/inputs:
              url: "http://web.chaos-demo.svc.cluster.local"
              method:
                get:
                  criteria: "=="
                  responseCode: "200"
```

### 실행

```bash
kubectl apply -f network-loss-engine.yaml

# 실험 상태 확인
kubectl get chaosengine network-loss-chaos -n chaos-demo -o jsonpath='{.status.engineStatus}'
```

### 관찰 포인트

| 시점 | 확인 항목 | 기대값 |
|------|-----------|--------|
| 주입 중 | 대상 Pod Readiness | Not Ready (Probe 실패) |
| 주입 중 | Endpoint 수 | 3 → 2 (50% 영향) |
| 주입 중 | Service HTTP 응답 | 200 유지 (나머지 Pod가 처리) |
| 주입 중 | 대상 Pod 내부 통신 | 모든 outbound 실패 |
| 복구 후 | Endpoint 수 | 2 → 3 복구 |
| 복구 후 | 대상 Pod Readiness | Ready |

### 관찰 명령어

```bash
# Endpoint 변화 실시간
kubectl get endpoints web -n chaos-demo -w

# Pod Readiness 상태
kubectl get pods -n chaos-demo -l app=web -o custom-columns=NAME:.metadata.name,READY:.status.conditions[?(@.type=="Ready")].status

# 서비스 접근 테스트 (영향받지 않은 Pod 경유)
curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL

# 대상 Pod 내부에서 통신 시도 (실패 예상)
POD=$(kubectl get pods -n chaos-demo -l app=web -o name | head -1 | cut -d/ -f2)
kubectl exec -n chaos-demo $POD -- curl -s --max-time 3 http://web.chaos-demo.svc.cluster.local
# 기대값: timeout

# ChaosResult 확인
kubectl get chaosresult network-loss-chaos-pod-network-loss -n chaos-demo -o jsonpath='{.status.experimentStatus.verdict}'
```

### 복구 확인

실험 종료(60초) 후 자동 복구.

```bash
# Readiness 복구 확인
kubectl get pods -n chaos-demo -l app=web
# 기대값: 3/3 READY

# Endpoint 복구 확인
kubectl get endpoints web -n chaos-demo
# 기대값: 3개 IP

# HTTP 정상 확인
curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL
# 기대값: 200
```

### 정리

```bash
kubectl delete chaosengine network-loss-chaos -n chaos-demo
```

---

## 두 실험 비교 분석

| 항목 | Pod DNS Error | Pod Network Loss |
|------|---------------|------------------|
| 영향 범위 | DNS 해석만 차단 | 모든 네트워크 통신 차단 |
| 메커니즘 | DNS 응답 조작 | tc를 통한 패킷 drop |
| Readiness 영향 | 없음 (exec probe는 DNS 불필요) | 실패 (네트워크 기반 probe 시) |
| Service endpoint | 유지 | 제외됨 |
| 인바운드 트래픽 | 정상 수신 | 차단 |
| 복구 방식 | 실험 종료 시 자동 | 실험 종료 시 tc rule 제거 |
| 실제 장애 시뮬레이션 | CoreDNS 장애, DNS 설정 오류 | NIC 장애, 네트워크 파티션 |

---

## 실험 결과 기록 템플릿

```markdown
## 실험 결과 — {시나리오 이름} ({날짜})

### Steady State (실험 전)
- web: READY 3/3, RESTARTS 0
- dns-client: READY 2/2
- DNS 해석: 성공
- HTTP: 200

### 가설
{가설 내용}

### 관찰 결과
- 장애 주입 시점: {HH:MM:SS}
- 영향 감지 시점: {HH:MM:SS} (지연: {n}초)
- 복구 시점: {HH:MM:SS}
- 총 영향 시간: {n}초
- HTTP 실패 횟수: {n}회
- Endpoint 변화: {3 → n → 3}
- Readiness 변화: {상세}

### 결론
- 가설 검증: 통과 / 실패 / 부분 통과
- 예상과 다른 점: {내용}
- 개선 포인트: {내용}
```

---

## 환경 정리

```bash
# ChaosEngine 정리
kubectl delete chaosengine --all -n chaos-demo

# 추가 워크로드 정리
kubectl delete deployment dns-client -n chaos-demo
kubectl delete -f chaos-sa.yaml

# 전체 정리 (실험 완전 종료 시)
kubectl delete namespace chaos-demo
```

---

## 참조

- [LitmusChaos — pod-dns-error](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-dns-error/)
- [LitmusChaos — pod-network-loss](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-network-loss/)
- [Kubernetes DNS for Services and Pods](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/)
- [Principles of Chaos Engineering](https://principlesofchaos.org/)
