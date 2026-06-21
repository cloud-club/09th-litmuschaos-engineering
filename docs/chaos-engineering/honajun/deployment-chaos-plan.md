# Deployment Chaos Engineering 실습 계획

## 개요

minikube 로컬 클러스터에서 Kubernetes Deployment의 자가 회복 메커니즘을 과학적으로 검증한다.
실험 방식: **Steady State 정의 → 가설 수립 → 장애 주입 → 관찰 → 복구 확인**

---

## 1단계 — 환경 준비 (minikube 설치 및 클러스터 구성)

### 1-1. 사전 요구사항 설치

```bash
# Homebrew (macOS)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Docker Desktop 설치 (minikube driver로 사용)
brew install --cask docker

# kubectl 설치
brew install kubectl

# minikube 설치
brew install minikube
```

### 1-2. minikube 클러스터 시작

```bash
# 클러스터 시작 (Docker driver 사용)
minikube start --driver=docker

# 상태 확인
minikube status
kubectl cluster-info
kubectl get nodes
```

기대 출력:
```
NAME       STATUS   ROLES           AGE   VERSION
minikube   Ready    control-plane   ...   v1.x.x
```

---

## 2단계 — 실험 대상 배포

### 2-1. Namespace 생성

```bash
kubectl create namespace chaos-demo
```

### 2-2. Deployment YAML 작성

아래 내용을 `web-deployment.yaml`로 저장한다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: chaos-demo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
        - name: web
          image: nginx:1.25
          ports:
            - containerPort: 80
          readinessProbe:
            exec:
              command: ["test", "-f", "/readyz"]
            initialDelaySeconds: 3
            periodSeconds: 3
            failureThreshold: 2
          livenessProbe:
            exec:
              command: ["test", "-f", "/livez"]
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 3
          lifecycle:
            postStart:
              exec:
                command: ["/bin/sh", "-c", "touch /readyz /livez"]
```

### 2-3. Service YAML 작성

아래 내용을 `web-service.yaml`로 저장한다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: web
  namespace: chaos-demo
spec:
  selector:
    app: web
  type: NodePort
  ports:
    - port: 80
      targetPort: 80
      nodePort: 30080
```

### 2-4. 리소스 적용

```bash
kubectl apply -f web-deployment.yaml
kubectl apply -f web-service.yaml

# 배포 완료 대기
kubectl rollout status deployment/web -n chaos-demo
```

### 2-5. SERVICE_URL 환경변수 설정

```bash
export SERVICE_URL=$(minikube service web -n chaos-demo --url)
echo $SERVICE_URL

# 접속 확인
curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL
# 기대값: 200
```

---

## 3단계 — Steady State 확인 (모든 실험 전 필수)

```bash
# 1. Deployment READY 확인
kubectl get deployment web -n chaos-demo
# 기대값: READY 3/3

# 2. Pod 상태 확인
kubectl get pods -n chaos-demo -l app=web
# 기대값: 3개 Running, RESTARTS 0

# 3. Endpoint 확인
kubectl get endpoints web -n chaos-demo
# 기대값: 3개 endpoint

# 4. HTTP 응답 확인
curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL
# 기대값: 200
```

4개 항목 모두 통과해야 실험을 진행한다.

---

## 4단계 — 부하 생성기 실행 (실험 중 상시 유지)

실험 전 아래 3개 터미널을 열어 놓는다.

```bash
# Terminal A — HTTP 상태 모니터링
while true; do
  code=$(curl -s -o /dev/null -w "%{http_code}" $SERVICE_URL)
  echo "$(date +%H:%M:%S) HTTP $code"
  sleep 0.5
done

# Terminal B — Pod 상태 실시간 관찰
kubectl get pods -n chaos-demo -w

# Terminal C — Endpoint 변화 관찰
kubectl get endpoints web -n chaos-demo -w
```

---

## 5단계 — 실험 시나리오

### 시나리오 1 — Pod 1개 삭제

**가설**: Pod 1개 삭제 시 ReplicaSet이 30초 이내에 새 Pod를 생성하고 READY 3/3으로 회복한다.

```bash
# 장애 주입
kubectl delete pod -n chaos-demo $(kubectl get pods -n chaos-demo -l app=web -o name | head -1)

# 관찰 포인트
# - ReplicaSet이 새 Pod를 생성하는가
# - READY 3/3 회복까지 걸리는 시간
# - HTTP 200 유지 여부
```

복구 확인: Steady State 체크리스트 재실행

---

### 시나리오 2 — Pod 2개 동시 삭제

**가설**: Pod 2개 삭제 시 남은 1개 Pod가 트래픽을 처리하고, 60초 이내에 READY 3/3으로 회복한다.

```bash
# 장애 주입
kubectl get pods -n chaos-demo -l app=web -o name | head -2 | xargs kubectl delete -n chaos-demo

# 관찰 포인트
# - 남은 Pod 1개로 트래픽이 유지되는가
# - Endpoint 수 변화 (3 → 1 → 3)
# - 복구 시간
```

> 주의: `maxUnavailable: 1` 설정으로 capacity가 일시적으로 33%까지 감소한다.

복구 확인: Steady State 체크리스트 재실행

---

### 시나리오 3 — 잘못된 이미지 배포

**가설**: 잘못된 이미지 배포 시 기존 Pod 3개가 유지되고, rollback으로 정상 복구된다.

```bash
# 장애 주입
kubectl set image deployment/web web=nginx:invalid-tag -n chaos-demo

# 관찰 포인트
# - ImagePullBackOff 발생 여부
# - 기존 Pod 3개 유지 여부 (RollingUpdate 보호)

# 복구
kubectl rollout undo deployment/web -n chaos-demo
kubectl rollout status deployment/web -n chaos-demo
```

복구 확인: Steady State 체크리스트 재실행

---

### 시나리오 4 — Readiness 실패

**가설**: Readiness 실패 시 해당 Pod가 Service endpoint에서 제외되고, HTTP 200이 유지된다.

```bash
# 장애 주입 (Pod 1개 선택)
POD=$(kubectl get pods -n chaos-demo -l app=web -o name | head -1 | cut -d/ -f2)
kubectl exec -n chaos-demo $POD -- rm /readyz

# 관찰 포인트
# - Endpoint에서 해당 Pod 제외 여부
# - HTTP 200 유지 여부 (나머지 2개 Pod로)
# - RESTARTS 증가 없음 확인

# 복구
kubectl exec -n chaos-demo $POD -- touch /readyz
```

복구 확인: Steady State 체크리스트 재실행

---

### 시나리오 5 — Liveness 실패

**가설**: Liveness 실패 시 kubelet이 컨테이너를 재시작하고, 재시작 후 정상 상태로 회복된다.

```bash
# 장애 주입
POD=$(kubectl get pods -n chaos-demo -l app=web -o name | head -1 | cut -d/ -f2)
kubectl exec -n chaos-demo $POD -- rm /livez

# 관찰 포인트
# - RESTARTS 카운트 증가
# - kubelet이 컨테이너를 재시작하는가
# - 재시작 후 자동 회복 여부
```

> 이 시나리오는 별도 복구 명령이 없다. 컨테이너 재시작 시 `postStart` hook이 `/livez`를 재생성한다.

복구 확인: Steady State 체크리스트 재실행

---

## 6단계 — 실험 결과 기록

실험 완료 후 `experiments/` 디렉토리에 아래 형식으로 기록한다.

```markdown
## 실험 결과 — {시나리오 이름} ({날짜})

### 가설
{가설 내용}

### 관찰 결과
- 복구 시간: {n}초
- HTTP 실패 횟수: {n}회
- Endpoint 변화: {변화 내용}
- 기타 관찰: {내용}

### 결론
- 가설 검증: 통과 / 실패
- 개선 포인트: {내용}
```

---

## 7단계 — 환경 정리

```bash
# 실습 완료 후 namespace 삭제 (명시적 확인 후 실행)
kubectl delete namespace chaos-demo

# minikube 중지 (클러스터 유지)
minikube stop

# minikube 삭제 (완전 초기화, 필요 시에만)
minikube delete
```

> `minikube delete`는 클러스터 전체를 삭제한다. 실험 결과를 먼저 저장했는지 확인 후 실행한다.

---

## 실험 순서 요약

```
1. minikube start
2. Namespace / Deployment / Service 생성
3. Steady State 확인 (4개 항목)
4. 부하 생성기 실행 (Terminal A/B/C)
5. 시나리오 1~5 순서대로 실험
   └── 각 실험 후 Steady State 재확인 → 결과 기록
6. 환경 정리
```

---

## 참조

- [minikube 공식 문서](https://minikube.sigs.k8s.io/docs/)
- [Kubernetes Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
- [Liveness, Readiness, Startup Probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/)
- [Principles of Chaos Engineering](https://principlesofchaos.org/)
