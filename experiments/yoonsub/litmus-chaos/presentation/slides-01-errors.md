# LitmusChaos 실험 트러블슈팅
## 에러 분석편

---

## 실험 개요

### 선택한 실험

| 실험 | 설명 |
|------|------|
| **Pod IO Stress** | 컨테이너 내부 디스크 I/O 부하 주입 |
| **Pod Network Corruption** | 송신 패킷 손상으로 네트워크 불안정 재현 |

### 실험 환경

```
WSL2 Ubuntu
  └── Docker Desktop
        └── minikube (docker driver)
              └── LitmusChaos 1.13.8
```

---

## 실험 흐름

```text
ChaosEngine 생성
  ↓
chaos-operator가 runner Pod 생성
  ↓
runner가 helper Pod 생성
  ↓
helper Pod → 컨테이너 런타임 소켓 접근
  ↓
대상 컨테이너에 stress-ng 주입
  ↓
ChaosResult 기록
```

> helper Pod가 컨테이너 런타임과 통신하는 단계에서 문제가 발생

---

## 에러 1

### `unknown service runtime.v1alpha2.RuntimeService`

**발생 위치**: helper Pod 로그

```log
level=error msg="[cri]: Failed to run crictl:
  level=fatal msg=\"Getting the status of the container failed:
  rpc error: code = Unimplemented
  desc = unknown service runtime.v1alpha2.RuntimeService\""
```

---

## 에러 1 — 원인 분석

### 처음 의심한 원인 (틀림)

```
/run/containerd/containerd.sock 경로가 잘못됐다?
```

```bash
minikube ssh -- "ls -la /run/containerd/containerd.sock"
# srw-rw---- 1 root root 0 → 파일 존재함
```

→ 소켓 파일은 있었음. 경로 문제가 아니었음

---

## 에러 1 — 실제 원인

### containerd API 버전 변화

```
LitmusChaos 1.13.8
  └── crictl → runtime.v1alpha2.RuntimeService 호출

containerd 1.7+
  └── v1alpha2 API 완전 제거 (v1만 지원)
```

| containerd 버전 | v1alpha2 지원 |
|-----------------|--------------|
| 1.5.x | ✅ 지원 |
| 1.6.x | ⚠️ deprecated |
| **1.7.x** | **❌ 제거됨** |

> 소켓 연결은 성공했지만, 요청한 서비스 자체가 없어진 상황

---

## 에러 2

### `client version 1.40 is too old`

**시도**: CONTAINER_RUNTIME을 `docker`로 변경

**근거**
```bash
# Container ID 확인
kubectl get node -o jsonpath='{.status.nodeInfo.containerRuntimeVersion}'
# docker://29.2.1

# minikube 안에서 docker ps로 nginx 확인
docker ps | grep nginx
# 1608fcc98e53  nginx  ← LitmusChaos가 찾은 ID와 일치
```

→ Docker가 실제 CRI이고, 컨테이너 ID도 맞음

---

## 에러 2 — 발생

```log
level=error msg="[docker]: Failed to run docker inspect: []
  Error response from daemon:
  client version 1.40 is too old.
  Minimum supported API version is 1.44,
  please upgrade your client to a newer version"
```

---

## 에러 2 — 원인 분석

### Docker API 버전 불일치

```
LitmusChaos 1.13.8 go-runner
  └── Docker client API v1.40 사용

Docker 29.2.1 (minikube 내부)
  └── 최소 요구 버전: API v1.44
```

| 구분 | 버전 |
|------|------|
| LitmusChaos 1.13.8 Docker client | **v1.40** |
| Docker 29.x 최소 요구 | **v1.44** |
| 차이 | **4단계 gap** |

> 설정 변경으로 해결 불가 → LitmusChaos 업그레이드 필요

---

## 에러 3

### LitmusChaos 2.14.0 설치 후 CRD 누락

**시도**: LitmusChaos 2.14.0으로 업그레이드

```bash
kubectl apply -f https://litmuschaos.github.io/litmus/2.14.0/litmus-2.14.0.yaml

kubectl get crd | grep litmus
# chaosengines.litmuschaos.io ← 하나만 존재
# chaosexperiments, chaosresults 없음
```

```bash
kubectl apply -f "...pod-io-stress/experiment.yaml"
# Error: the server could not find the requested resource
# (post chaosexperiments.litmuschaos.io)
```

---

## 에러 3 — 원인 분석

### LitmusChaos 2.x 아키텍처 변화

**1.x 구조 (단순)**
```
litmus-operator-v1.13.8.yaml
  └── CRDs + chaos-operator → 모두 포함
```

**2.x 구조 (분리됨)**
```
litmus-2.14.0.yaml
  └── ChaosCenter (포털 + 프론트엔드 + MongoDB)
        → chaos-operator는 별도 설치 필요
```

| 컴포넌트 | 1.x | 2.x |
|----------|-----|-----|
| ChaosCenter | ❌ 없음 | ✅ 포함 |
| chaos-operator | ✅ 포함 | ❌ 별도 설치 |
| ChaosExperiment CRD | ✅ 포함 | ❌ 별도 설치 |

---

## 에러 4

### minikube 버전 제약

**시도**: containerd 1.6.x를 쓰기 위해 Kubernetes 1.24 설치

```bash
minikube start --kubernetes-version=v1.24.17

# ❌ Exiting due to K8S_OLD_UNSUPPORTED:
# Kubernetes 1.24.17 is not supported by this release of minikube
```

| minikube 버전 | 지원 최소 k8s |
|---------------|--------------|
| v1.38.1 | **v1.28.0** |
| 시도한 버전 | v1.24.17 ❌ |

> 이 시점에서 가능한 경로가 하나로 확정됨

---

## 4번의 에러 요약

```
에러 1. containerd v1alpha2 제거
  → CONTAINER_RUNTIME을 docker로 변경 시도

에러 2. Docker API v1.40 → v1.44 요구
  → LitmusChaos 2.14.0 업그레이드 시도

에러 3. 2.x ChaosCenter만 설치됨, chaos-operator 누락
  → k8s 1.24 + 구버전 containerd 시도

에러 4. minikube v1.38.1이 k8s 1.24 미지원
  → 해결 방향 확정
```

---

## 다음 장에서

### → 최종 해결 방향과 배운 점
