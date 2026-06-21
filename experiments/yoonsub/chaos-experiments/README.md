# Chaos Experiments — Pod Network Loss & Node Drain

## 실험 개요

| 실험 | 가설 | 장애 범위 |
|------|------|-----------|
| **Pod Network Loss** | 패킷 손실 발생 시 Circuit Breaker/Retry가 2차 장애 없이 동작하는가? | Pod 레벨 |
| **Node Drain** | 노드 장애 시 PDB + 스케줄러가 Pod를 무중단으로 재배치하는가? | Node 레벨 |

---

## 디렉토리 구조

```
chaos-experiments/
└── manifests/
    ├── 01-target-app.yaml              # 대상 앱 (nginx 3 replica + PDB)
    ├── 02-rbac-pod-network-loss.yaml   # pod-network-loss 전용 RBAC
    ├── 03-rbac-node-drain.yaml         # node-drain 전용 RBAC (ClusterRole)
    ├── pod-network-loss/
    │   ├── experiment.yaml             # ChaosExperiment CR
    │   └── engine.yaml                 # ChaosEngine CR (실험 트리거)
    └── node-drain/
        ├── experiment.yaml             # ChaosExperiment CR
        └── engine.yaml                 # ChaosEngine CR (실험 트리거)
```

---

## Step 1 — Minikube 시작 (2 노드 필수)

Node Drain은 워커 노드를 drain하므로 **최소 2 노드**가 필요합니다.

```bash
minikube start --nodes 2 --driver=docker --cpus=2 --memory=4096

# 노드 확인
kubectl get nodes
# 출력 예시:
# NAME           STATUS   ROLES           AGE
# minikube       Ready    control-plane   1m
# minikube-m02   Ready    <none>          1m
```

> Node Drain 실험 대상은 `minikube-m02` (워커 노드) 입니다.  
> `manifests/node-drain/engine.yaml`의 `TARGET_NODE` 값을 실제 노드 이름으로 수정하세요.

---

## Step 2 — LitmusChaos Operator 설치

```bash
# Helm 레포 추가
helm repo add litmuschaos https://litmuschaos.github.io/litmus-helm/
helm repo update

# litmus 네임스페이스에 설치
helm install chaos litmuschaos/litmus \
  --namespace litmus \
  --create-namespace \
  --set portal.frontend.service.type=NodePort

# 설치 확인 (모든 Pod가 Running 상태가 될 때까지 대기)
kubectl get pods -n litmus --watch
```

---

## Step 3 — 대상 애플리케이션 배포

```bash
kubectl apply -f manifests/01-target-app.yaml

# Pod 3개가 두 노드에 분산 배포되는지 확인
kubectl get pods -o wide
```

---

## Step 4 — RBAC 적용

```bash
kubectl apply -f manifests/02-rbac-pod-network-loss.yaml
kubectl apply -f manifests/03-rbac-node-drain.yaml
```

---

## Step 5-A — Pod Network Loss 실험

### 실험 전 상태 확인

```bash
# 별도 터미널에서 서비스 응답 지속 모니터링
kubectl run curl-test --image=curlimages/curl --restart=Never --rm -it -- \
  sh -c 'while true; do curl -s -o /dev/null -w "%{http_code}\n" nginx-target-svc; sleep 1; done'
```

### 실험 실행

```bash
kubectl apply -f manifests/pod-network-loss/experiment.yaml
kubectl apply -f manifests/pod-network-loss/engine.yaml
```

### 진행 상황 모니터링

```bash
# ChaosEngine 상태 확인
kubectl describe chaosengine nginx-pod-network-loss

# 실험 결과 확인
kubectl describe chaosresult nginx-pod-network-loss-pod-network-loss

# 실험 Job 로그 확인
kubectl logs -l name=pod-network-loss --tail=50
```

### 실험 종료 후 정리

```bash
kubectl delete chaosengine nginx-pod-network-loss
```

---

## Step 5-B — Node Drain 실험

### 노드 이름 확인 및 engine.yaml 수정

```bash
kubectl get nodes
# minikube-m02 가 아닌 다른 이름이면 engine.yaml의 TARGET_NODE 수정 필요
```

### 실험 전 상태 확인

```bash
# Pod 분포 확인
kubectl get pods -o wide

# 별도 터미널에서 Pod 이벤트 실시간 모니터링
kubectl get events --watch --field-selector reason=Evicted
```

### 실험 실행

```bash
kubectl apply -f manifests/node-drain/experiment.yaml
kubectl apply -f manifests/node-drain/engine.yaml
```

### 진행 상황 모니터링

```bash
# 노드 상태 변화 확인 (SchedulingDisabled → Ready)
kubectl get nodes --watch

# Pod 재배치 확인
kubectl get pods -o wide --watch

# 실험 결과 확인
kubectl describe chaosresult nginx-node-drain-node-drain
```

### 실험 종료 후 정리

```bash
kubectl delete chaosengine nginx-node-drain

# 노드가 uncordon 되었는지 확인
kubectl get nodes
```

---

## 전체 정리 (실험 완료 후)

```bash
kubectl delete -f manifests/node-drain/engine.yaml
kubectl delete -f manifests/pod-network-loss/engine.yaml
kubectl delete -f manifests/node-drain/experiment.yaml
kubectl delete -f manifests/pod-network-loss/experiment.yaml
kubectl delete -f manifests/03-rbac-node-drain.yaml
kubectl delete -f manifests/02-rbac-pod-network-loss.yaml
kubectl delete -f manifests/01-target-app.yaml
helm uninstall chaos -n litmus
minikube stop
```

---

## 주요 파라미터 조정 포인트

### Pod Network Loss (`engine.yaml`)

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `NETWORK_PACKET_LOSS_PERCENTAGE` | `100` | 패킷 손실 비율 (50으로 낮추면 부분 손실) |
| `TOTAL_CHAOS_DURATION` | `60` | 카오스 지속 시간 (초) |
| `PODS_AFFECTED_PERC` | `50` | 영향받는 Pod 비율 (0 = 전체) |
| `DESTINATION_IPS` | `""` | 특정 IP만 차단 (빈 값 = 모든 트래픽) |

### Node Drain (`engine.yaml`)

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `TARGET_NODE` | `minikube-m02` | drain할 노드 이름 |
| `TOTAL_CHAOS_DURATION` | `60` | drain 유지 시간 (초) — 이후 자동 uncordon |

---

## 관찰 포인트

### Pod Network Loss
- 패킷 손실이 발생한 Pod로의 요청이 실패하는가?
- 다른 Pod (영향받지 않은 50%)가 트래픽을 정상 처리하는가?
- Retry 폭풍(retry storm)으로 인한 cascading failure가 발생하는가?

### Node Drain
- `minAvailable: 1` PDB 덕분에 서비스가 유지되는가?
- drain된 노드의 Pod가 나머지 노드(minikube)로 재배치되는가?
- 실험 종료 후 노드가 자동으로 uncordon되어 스케줄링이 재개되는가?
