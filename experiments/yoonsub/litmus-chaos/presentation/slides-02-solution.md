# LitmusChaos 실험 트러블슈팅
## 해결 과정편

---

## 문제 전체 구조 정리

```
LitmusChaos 1.13.8
  ├── Docker 29.x    → API v1.40 (최소 v1.44 필요) ❌
  └── containerd 1.7 → v1alpha2 API 제거됨 ❌

LitmusChaos 2.14.0
  ├── ChaosCenter만 설치됨 (chaos-operator 없음) ❌
  └── Kubernetes 1.24 시도 → minikube 미지원 ❌
```

### 결론

> **LitmusChaos 버전 + Kubernetes 버전 + 컨테이너 런타임**  
> 세 가지의 버전 호환성을 동시에 맞춰야 함

---

## 버전 호환성 분석

| 환경 | LitmusChaos 1.13.8 | LitmusChaos 2.x |
|------|--------------------|-----------------|
| Docker 29.x (API v1.44+) | ❌ API v1.40 사용 | ✅ 지원 |
| containerd 1.7.x (CRI v1) | ❌ v1alpha2만 지원 | ✅ CRI v1 지원 |
| containerd 1.6.x (v1alpha2) | ✅ 지원 | ✅ 지원 |
| Kubernetes 1.24 | ✅ 가능 | ✅ 가능 |
| Kubernetes 1.28+ | ⚠️ containerd 1.7 | ✅ 정상 동작 |

---

## 최종 해결 방향

```
minikube v1.38.1
  └── Kubernetes 1.28+ (최소 지원 버전)
        └── containerd 1.7.x (CRI v1)
              └── LitmusChaos 2.x
                    └── go-runner 2.x (CRI v1 지원)
                          └── chaos-operator (ChaosCenter 없이 설치)
```

---

## 해결 Step 1. minikube 재생성

```bash
minikube start \
  --profile=k8s-practice \
  --driver=docker \
  --container-runtime=containerd \
  --cpus=2 \
  --memory=4096
```

```bash
kubectl get node k8s-practice \
  -o jsonpath='{.status.nodeInfo.containerRuntimeVersion}'
# containerd://1.7.x
```

---

## 해결 Step 2. chaos-operator 단독 설치

### ChaosCenter 없이 chaos-operator만 설치

```bash
# chaos-operator 직접 설치
kubectl apply -f https://raw.githubusercontent.com/litmuschaos/\
chaos-operator/v2.14.0/deploy/chaos-operator.yaml
```

```bash
# CRD 3개 모두 확인
kubectl get crd | grep litmus
# chaosengines.litmuschaos.io      ✅
# chaosexperiments.litmuschaos.io  ✅
# chaosresults.litmuschaos.io      ✅
```

---

## 해결 Step 3. 실험 설치 및 실행

```bash
# ChaosExperiment 설치 (2.x)
kubectl apply -f "https://raw.githubusercontent.com/litmuschaos/\
chaos-charts/2.14.0/charts/generic/pod-io-stress/experiment.yaml"

# chaosengine.yaml - containerd 설정 확인
CONTAINER_RUNTIME: containerd
SOCKET_PATH: /run/containerd/containerd.sock

# 실행
kubectl apply -f 01-pod-io-stress/chaosengine.yaml
```

---

## 디버깅 과정에서 사용한 핵심 명령어

### 런타임 확인
```bash
# 컨테이너 런타임 버전 확인
kubectl get node -o jsonpath='{.status.nodeInfo.containerRuntimeVersion}'

# 소켓 존재 여부 확인
minikube ssh -- "ls -la /run/containerd/containerd.sock"
minikube ssh -- "ls -la /var/run/docker.sock"
```

### 에러 추적
```bash
# helper Pod 로그 확인 (핵심)
kubectl logs $(kubectl get pods | grep helper | awk '{print $1}')

# Pod 상세 정보 (Container ID, runtime 확인)
kubectl describe pod <pod-name>
```

---

## LitmusChaos 내부 동작 이해

### helper Pod가 하는 일

```text
1. 컨테이너 런타임 소켓 연결
   └── containerd: /run/containerd/containerd.sock
   └── docker:     /var/run/docker.sock

2. 대상 컨테이너 ID 조회
   └── crictl (containerd) 또는 docker inspect (docker)

3. nsenter로 컨테이너 네임스페이스 진입
   └── 프로세스 격리 우회하여 stress-ng 실행

4. ChaosResult 기록
```

> 런타임과의 통신 방식이 LitmusChaos 버전마다 다름

---

## 배운 점 1 — 버전 호환성 매트릭스

### 오픈소스 도구 사용 시 반드시 확인

```
[내 환경]           [도구]              [의존성]
minikube 버전  →  k8s 버전      →  containerd 버전
                                        ↓
LitmusChaos 버전  →  go-runner  →  CRI API 버전
```

> 한 버전이 바뀌면 연쇄적으로 영향을 줌

---

## 배운 점 2 — 에러 메시지 읽기

### 에러 메시지 자체가 원인을 설명한다

| 에러 메시지 | 실제 의미 |
|-------------|-----------|
| `unknown service runtime.v1alpha2` | API 버전 불일치 (소켓 경로 문제 아님) |
| `client version 1.40 is too old` | 라이브러리 버전 문제 (설정 문제 아님) |
| `cannot find the requested resource` | CRD 미설치 (권한 문제 아님) |

> 에러 메시지가 가리키는 곳을 정확히 읽으면 삽질이 줄어든다

---

## 배운 점 3 — Kubernetes 런타임 구조

### 컨테이너 런타임 계층

```
Kubernetes (kubelet)
  └── CRI (Container Runtime Interface)
        ├── containerd → CRI v1alpha2 (1.5/1.6) → CRI v1 (1.7+)
        └── docker     → dockershim (deprecated) → cri-dockerd
```

### LitmusChaos가 런타임을 직접 다루는 이유

```text
일반 kubectl exec    →  Kubernetes API 경유
LitmusChaos helper  →  런타임 소켓 직접 접근 (더 낮은 레벨)
```

> chaos 도구는 Kubernetes보다 더 낮은 레벨에서 동작하기 때문에
> 런타임 버전 호환성에 더 민감함

---

## 트러블슈팅 타임라인

```
[시도 1] LitmusChaos 1.13.8 + containerd
         → v1alpha2 API 제거됨 ❌

[시도 2] CONTAINER_RUNTIME=docker 로 변경
         → Docker API v1.40 (최소 v1.44 필요) ❌

[시도 3] LitmusChaos 2.14.0 설치
         → ChaosCenter만 설치됨, chaos-operator 없음 ❌

[시도 4] Kubernetes 1.24 + containerd 1.6.x
         → minikube v1.38.1 미지원 ❌

[최종]   k8s 1.28 + containerd 1.7 + LitmusChaos 2.x
         + chaos-operator 단독 설치 ✅
```

---

## 정리

### 실험보다 환경 구성이 더 많이 가르쳐줬다

> 카오스 엔지니어링은 장애를 의도적으로 주입해 시스템의 약점을 찾는 것이다.  
> 그 도구를 다루는 과정에서도 우리는 시스템의 약점을, 그리고 우리 지식의 약점을 발견했다.

- **버전 호환성**은 오픈소스 도구 사용의 핵심
- **에러 메시지**는 디버깅의 출발점
- **런타임 구조**는 chaos 도구를 이해하는 기초
