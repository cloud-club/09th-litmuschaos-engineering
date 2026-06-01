# Deployment Chaos Engineering 실험 결과 (2026-06-01)

## 환경

- Cluster: minikube v1.38.1 (Docker driver, Rancher Desktop)
- Kubernetes: v1.35.1
- Namespace: chaos-demo
- Deployment: web (nginx:1.25, replicas=3)
- Probe: readinessProbe (exec /readyz), livenessProbe (exec /livez)

---

## Steady State (실험 전 기준)

```
NAME   READY   UP-TO-DATE   AVAILABLE
web    3/3     3            3

Pods: 3개 Running, RESTARTS 0
Endpoints: 3개 (10.244.0.3, 10.244.0.4, 10.244.0.5)
HTTP: 200
```

---

## 시나리오 1 — Pod 1개 삭제

### 가설

Pod 1개 삭제 시 ReplicaSet이 30초 이내에 새 Pod를 생성하고 READY 3/3으로 회복한다.

### 장애 주입

- 시각: 15:21:14
- 대상: pod/web-7f7bc8cfd9-6ttm2
- 명령: `kubectl delete pod`

### 관찰 결과

- 15:21:22 (삭제 후 8초): 새 Pod `g9srl` 이미 Running + Ready
- 15:21:27 (삭제 후 13초): READY 3/3 완전 복구
- 복구 시간: **약 8초**
- HTTP: 200 유지 (나머지 2개 Pod가 처리)

### 결론

- 가설 검증: **통과** (30초 이내 → 실제 8초)
- ReplicaSet의 self-healing이 즉각 동작함

---

## 시나리오 2 — Pod 2개 동시 삭제

### 가설

Pod 2개 삭제 시 남은 1개 Pod가 트래픽을 처리하고, 60초 이내에 READY 3/3으로 회복한다.

### 장애 주입

- 시각: 15:21:43
- 대상: pod/web-7f7bc8cfd9-g9srl, pod/web-7f7bc8cfd9-l4bgk
- 명령: `kubectl delete` (2개 동시)

### 관찰 결과

- 15:21:45 (삭제 후 2초): 새 Pod 2개 생성됨 (Running, 0/1 Not Ready)
- 15:21:57 (삭제 후 14초): READY 3/3 완전 복구
- Endpoint 변화: 3 → 1 → 3
- 복구 시간: **약 14초**

### 결론

- 가설 검증: **통과** (60초 이내 → 실제 14초)
- 남은 1개 Pod가 트래픽 유지, 새 Pod의 Readiness 통과 후 endpoint 재등록

---

## 시나리오 3 — 잘못된 이미지 배포

### 가설

잘못된 이미지 배포 시 기존 Pod가 유지되고, rollback으로 정상 복구된다.

### 장애 주입

- 시각: 15:22:02
- 명령: `kubectl set image deployment/web web=nginx:invalid-tag`

### 관찰 결과

- 15:22:12 (10초 후):
  - 기존 Pod 2개 Running 유지 (RollingUpdate maxUnavailable=1 보호)
  - 새 Pod 2개 `ErrImagePull` 상태
  - Deployment: READY 2/3
- Rollback 실행: 15:22:16 (`kubectl rollout undo`)
- 15:22:21 (rollback 후 5초): READY 3/3 복구

### 결론

- 가설 검증: **통과**
- RollingUpdate 전략이 기존 Pod를 보호함 (전체 장애 방지)
- Rollback 즉시 동작 확인

---

## 시나리오 4 — Readiness 실패

### 가설

Readiness 실패 시 해당 Pod가 Service endpoint에서 제외되고, HTTP 200이 유지된다.

### 장애 주입

- 시각: 15:22:34
- 대상: web-7f7bc8cfd9-t2xc6
- 명령: `kubectl exec -- rm /readyz`

### 관찰 결과

- 15:22:44 (10초 후):
  - 대상 Pod: 0/1 (Not Ready), STATUS는 Running 유지
  - Endpoint: 3 → 2 (대상 Pod 제외됨)
  - RESTARTS 증가 없음
- 복구 (`touch /readyz`) 후 5초: READY 1/1 복구, Endpoint 3개 복원

### 결론

- 가설 검증: **통과**
- Readiness 실패 → endpoint 제외 → 나머지 Pod로 트래픽 유지
- 복구 즉시 endpoint 재등록

---

## 시나리오 5 — Liveness 실패

### 가설

Liveness 실패 시 kubelet이 컨테이너를 재시작하고, 재시작 후 정상 상태로 회복된다.

### 장애 주입

- 시각: 15:22:58
- 대상: web-7f7bc8cfd9-t2xc6
- 명령: `kubectl exec -- rm /livez`

### 관찰 결과

- 15:23:19 (21초 후):
  - 대상 Pod: RESTARTS 0 → 1
  - STATUS: Running, READY 1/1
  - postStart hook이 `/livez`, `/readyz` 재생성 → 자동 복구
- 별도 복구 명령 불필요

### 결론

- 가설 검증: **통과**
- Liveness 실패 → kubelet 컨테이너 재시작 → postStart로 자동 복구
- 재시작까지 약 15~20초 소요 (failureThreshold=3 × periodSeconds=5)

---

## 전체 요약

| 시나리오 | 복구 시간 | 가설 검증 | 핵심 관찰 |
|----------|-----------|-----------|-----------|
| Pod 1개 삭제 | ~8초 | ✅ 통과 | ReplicaSet 즉시 복구 |
| Pod 2개 동시 삭제 | ~14초 | ✅ 통과 | 남은 Pod가 트래픽 유지 |
| 잘못된 이미지 배포 | ~5초 (rollback) | ✅ 통과 | RollingUpdate가 기존 Pod 보호 |
| Readiness 실패 | ~5초 (수동 복구 후) | ✅ 통과 | Endpoint 자동 제외/복원 |
| Liveness 실패 | ~20초 (자동) | ✅ 통과 | kubelet 자동 재시작 |

## 실제 운영 상황과의 연결

### 시나리오 1, 2 (Pod 삭제) → 노드 장애, OOM Kill

| 실험 상황 | 실제 운영 상황 |
|-----------|---------------|
| Pod 1개 삭제 | 노드 1대 장애로 Pod eviction 발생 |
| Pod 2개 동시 삭제 | AZ(가용 영역) 장애로 다수 Pod 동시 소실 |
| 복구 8~14초 | 실제 환경에서는 이미지 pull, resource 확보로 30초~수분 소요 가능 |

**운영 시사점**:
- 트래픽 피크 시간에 Pod 2개 소실 → 남은 1개로 전체 트래픽 처리 → CPU/Memory 급증 → 연쇄 장애 가능
- PodDisruptionBudget(PDB)으로 동시 소실 Pod 수 제한 필요
- HPA 설정으로 남은 Pod 부하 증가 시 자동 확장 필요

### 시나리오 3 (잘못된 이미지) → 배포 사고

| 실험 상황 | 실제 운영 상황 |
|-----------|---------------|
| nginx:invalid-tag | 잘못된 이미지 태그 push, ECR/GCR 권한 만료, registry 장애 |
| RollingUpdate 보호 | 기존 Pod 유지로 서비스 중단 방지 |
| 수동 rollback | CI/CD 파이프라인의 자동 rollback 트리거 |

**운영 시사점**:
- `maxUnavailable: 1`이 전체 장애를 방지했지만, 감지가 늦으면 capacity 감소 상태가 지속됨
- Deployment의 `progressDeadlineSeconds` 설정으로 자동 실패 감지 필요
- ArgoCD/Flux 등 GitOps 도구의 자동 rollback 정책과 연계

### 시나리오 4 (Readiness 실패) → 애플리케이션 초기화 지연, 의존 서비스 장애

| 실험 상황 | 실제 운영 상황 |
|-----------|---------------|
| /readyz 파일 삭제 | DB 연결 실패, Redis timeout, 외부 API 장애로 앱이 요청 처리 불가 |
| Endpoint 제외 | 장애 Pod로 트래픽이 가지 않아 사용자 영향 최소화 |
| 수동 복구 | 의존 서비스 복구 시 자동으로 endpoint 재등록 |

**운영 시사점**:
- Readiness Probe는 "이 Pod가 트래픽을 받을 수 있는가"를 정확히 반영해야 함
- DB connection pool 고갈, 외부 서비스 timeout 등을 Readiness에 반영하면 graceful degradation 가능
- 단, 모든 Pod가 동시에 Readiness 실패하면 Service에 endpoint가 0개 → 전체 장애

### 시나리오 5 (Liveness 실패) → 메모리 릭, 데드락, 무한 루프

| 실험 상황 | 실제 운영 상황 |
|-----------|---------------|
| /livez 파일 삭제 | 앱 프로세스 hang, 메모리 릭으로 GC 멈춤, 데드락 |
| kubelet 재시작 | 프로세스 재시작으로 일시적 복구 |
| RESTARTS 증가 | CrashLoopBackOff 진입 가능 (근본 원인 미해결 시) |

**운영 시사점**:
- Liveness 재시작은 "임시 조치"일 뿐, 근본 원인(메모리 릭 등)은 별도 해결 필요
- RESTARTS 증가를 알림으로 연결해야 운영자가 인지 가능
- `failureThreshold × periodSeconds = 15초` 동안 장애 Pod가 트래픽을 받을 수 있음 → Readiness와 조합 필수

---

## Lessons Learned

1. Kubernetes self-healing은 단일 Pod 장애에 대해 10초 이내 복구 가능
2. RollingUpdate 전략은 잘못된 배포가 전체 장애로 번지는 것을 방지
3. Readiness/Liveness Probe 분리 설계가 중요 — Readiness 실패는 트래픽만 차단, Liveness 실패는 재시작
4. postStart lifecycle hook으로 재시작 후 자동 복구 구조 구현 가능
5. replicas=3은 2개 동시 장애에도 서비스 연속성 유지 (단, capacity 33%로 감소)
