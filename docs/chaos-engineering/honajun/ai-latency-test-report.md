# AI Server Latency Chaos 테스트 설계 및 실행 보고서

## 한눈에 보는 실험 요약

### 실제 장애 가정

외부 AI 서버 또는 LLM Gateway가 죽지는 않았지만 응답이 비정상적으로 느려지는 상황을 가정한다.

운영 환경에서는 다음과 같은 상황에 해당한다.

- AI 추론 서버의 처리 지연 증가
- 외부 LLM API 응답 지연
- AI Gateway 또는 네트워크 구간 지연
- Pod는 Running 상태지만 사용자 요청 응답 시간이 길어지는 장애

### 확인할 것

이 실험에서는 Kubernetes가 Pod를 재시작하는지를 보는 것이 아니라, 외부 의존성이 느려졌을 때 Spring Boot API 서버가 사용자 요청을 어떻게 처리하는지 확인한다.

- 정상 상태에서 Spring API가 AI 서버 응답을 받아 `success`를 반환하는지 확인
- AI 서버 응답이 timeout 기준을 넘으면 Spring API가 retry를 수행하는지 확인
- retry가 모두 실패하면 fallback 응답을 반환하는지 확인
- 장애 중에도 Spring API Pod와 AI Server Pod는 Running 상태를 유지하는지 확인
- 지연이 제거되면 별도 재배포 없이 정상 응답으로 복구되는지 확인

### 핵심 관찰 포인트

Pod와 Service는 정상으로 보이지만 사용자 응답은 실패하거나 느려질 수 있다.

이번 실행에서는 AI 서버 지연을 3초로 올렸을 때 Spring API가 `HttpTimeoutException` 이후 `fallback`을 반환했고, 지연 제거 후 다시 `success`로 복구되는 것을 확인했다.

---

## 1. 개요

본 문서는 Spring Boot API 서버가 외부 AI 서버에 의존하는 상황에서, AI 서버의 지연 또는 장애가 사용자 응답에 어떤 영향을 주는지 검증하기 위한 테스트 설계 및 실행 보고서이다.

실험은 Kubernetes 환경에서 Spring Boot API 서버와 FastAPI 기반 샘플 AI 서버를 배포한 뒤, LitmusChaos로 AI 서버 Pod에 네트워크 지연 장애를 주입하는 방식으로 수행한다.

핵심 검증 대상은 다음과 같다.

- Spring Boot API 서버가 사용자 요청을 정상적으로 수신하는지 확인
- Spring Boot API 서버가 FastAPI AI 서버를 정상 호출하는지 확인
- AI 서버 지연 시 Spring Boot API 서버의 timeout, retry, fallback 동작 확인
- Kubernetes 리소스가 Running 상태여도 사용자 응답 품질이 저하될 수 있음을 관찰

---

## 2. 테스트 대상 시스템

### 2.1 구성 요소

| 구성 요소 | 기술 | 역할 |
|-----------|------|------|
| Spring Boot API 서버 | Spring Boot 3.3.4, Java 21 | 사용자 요청 수신, AI 서버 호출, timeout/retry/fallback 처리 |
| FastAPI AI 서버 | FastAPI, Uvicorn, Python 3.11 | 외부 AI 서버 역할, `/infer` 응답 제공 |
| Kubernetes | minikube | 애플리케이션 배포 및 서비스 디스커버리 제공 |
| LitmusChaos | LitmusChaos Operator | AI 서버 Pod에 장애 주입 |

### 2.2 서비스 흐름

```text
User
  -> spring-api-svc
  -> spring-api Pod
  -> ai-server-svc
  -> ai-server Pod
```

### 2.3 주요 API

| 서비스 | Endpoint | 설명 |
|--------|----------|------|
| Spring Boot API | `GET /` | Spring API 설정 확인 |
| Spring Boot API | `GET /ask?prompt=hello` | AI 서버 호출 후 결과 반환 |
| FastAPI AI Server | `GET /health` | AI 서버 상태 확인 |
| FastAPI AI Server | `GET /infer?prompt=hello` | 샘플 AI 응답 반환 |

---

## 3. 테스트 목적

이 테스트의 목적은 Pod가 죽는 장애가 아니라, Pod는 Running 상태를 유지하지만 서비스 품질이 저하되는 장애를 검증하는 것이다.

일반적인 Pod Delete 실험은 Kubernetes가 Pod를 재생성하는지를 확인하는 데 적합하다. 반면 본 시나리오는 AI 서버가 살아 있지만 응답이 느려지는 상황을 만들고, Spring Boot API 서버가 사용자 응답을 어떻게 보호하는지 확인한다.

따라서 주요 관찰 포인트는 다음과 같다.

- `Deployment READY 2/2`
- `Pod Running`
- `Service` 및 `EndpointSlice` 정상
- 하지만 `/ask` 응답 지연 또는 fallback 발생

---

## 4. 테스트 범위

### 4.1 포함 범위

- Spring Boot API 서버 정상 응답 확인
- FastAPI AI 서버 정상 응답 확인
- AI 서버 호출 성공 케이스 확인
- AI 서버 지연 장애 주입
- Spring Boot timeout 동작 확인
- Spring Boot retry 횟수 확인
- retry 실패 후 fallback 응답 확인
- 장애 제거 후 정상 응답 복구 확인

### 4.2 제외 범위

- 실제 AI 모델 추론 정확도 검증
- AI 모델 성능 벤치마크
- EKS/ECR 기반 운영 배포 검증
- Resilience4j 등 별도 라이브러리 기반 회복성 패턴 검증
- 장시간 부하 테스트 및 용량 산정

---

## 5. 사전 조건

### 5.1 로컬 환경

- minikube 설치 완료
- kubectl 설치 완료
- Docker 또는 minikube image build 사용 가능
- LitmusChaos Operator 설치 완료

### 5.2 Kubernetes 리소스

```bash
kubectl apply -f experiments/honajun/ai-latency-demo/k8s/namespace.yaml
kubectl apply -f experiments/honajun/ai-latency-demo/k8s/ai-server.yaml
kubectl apply -f experiments/honajun/ai-latency-demo/k8s/spring-api.yaml
```

### 5.3 이미지 빌드

minikube 실습 환경에서는 외부 이미지 레지스트리 없이 로컬 이미지를 사용할 수 있다.

```bash
minikube image build -t ai-server:local experiments/honajun/ai-latency-demo/ai-server
minikube image build -t spring-api:local experiments/honajun/ai-latency-demo/spring-api
```

### 5.4 배포 확인

```bash
kubectl get pods -n app-demo
kubectl get svc -n app-demo
kubectl get endpointslice -n app-demo
```

기대 상태:

```text
ai-server   READY 2/2
spring-api  READY 2/2
```

---

## 6. Steady State 정의

장애 주입 전 다음 조건을 모두 만족해야 한다.

| 항목 | 확인 명령 | 기대값 |
|------|-----------|--------|
| Namespace 존재 | `kubectl get ns app-demo` | `Active` |
| AI 서버 Pod 정상 | `kubectl get pods -n app-demo -l app=ai-server` | 모든 Pod `Running`, `READY 1/1` |
| Spring API Pod 정상 | `kubectl get pods -n app-demo -l app=spring-api` | 모든 Pod `Running`, `READY 1/1` |
| AI 서버 Service 정상 | `kubectl get svc ai-server-svc -n app-demo` | Port `8000` 노출 |
| Spring API Service 정상 | `kubectl get svc spring-api-svc -n app-demo` | Port `8080` 노출 |
| AI 서버 health 정상 | `curl http://ai-server-svc:8000/health` | `status: ok` |
| 사용자 API 정상 | `curl "$SPRING_URL/ask?prompt=hello"` | `status: success` |

---

## 7. 가설

### 7.1 정상 상태 가설

> AI 서버 응답 지연이 기본값 `0.2s`일 때 Spring Boot API 서버는 첫 번째 시도에서 AI 서버 응답을 받아 사용자에게 `success`를 반환한다.

성공 기준:

- `/ask` 응답의 `status`가 `success`
- `attempt` 값이 `1`
- `elapsedMs`가 `AI_TIMEOUT_MS`보다 작음

### 7.2 장애 상태 가설

> AI 서버 Pod에 네트워크 지연이 주입되어 Spring Boot의 `AI_TIMEOUT_MS`를 초과하면, Spring Boot API 서버는 retry를 수행하고 모든 시도가 실패하면 fallback 응답을 반환한다.

성공 기준:

- `/ask` 응답의 `status`가 `fallback`
- `attempts` 값이 `AI_RETRIES + 1`
- `error` 값이 timeout 관련 예외
- Spring Boot Pod는 Running 상태 유지
- AI Server Pod도 Running 상태 유지

### 7.3 복구 가설

> 네트워크 지연 장애가 제거되면 Spring Boot API 서버는 별도 재시작 없이 다시 AI 서버 응답을 받아 `success`를 반환한다.

성공 기준:

- 장애 제거 후 `/ask` 응답의 `status`가 `success`
- `attempt` 값이 `1`
- `elapsedMs`가 정상 범위로 회복

---

## 8. 테스트 케이스

### TC-01. 정상 상태 응답 확인

| 항목 | 내용 |
|------|------|
| 목적 | 장애 주입 전 Spring API와 AI 서버의 정상 연동 확인 |
| 사전 조건 | `spring-api`, `ai-server` Pod 모두 Running |
| 실행 명령 | `curl "$SPRING_URL/ask?prompt=hello"` |
| 기대 결과 | `status: success`, `attempt: 1` |
| 판정 | 기대 결과와 일치하면 통과 |

### TC-02. AI 서버 직접 호출 확인

| 항목 | 내용 |
|------|------|
| 목적 | AI 서버 자체 응답과 지연 기본값 확인 |
| 실행 명령 | `kubectl port-forward svc/ai-server-svc 8000:8000 -n app-demo` 후 `curl "http://localhost:8000/infer?prompt=hello"` |
| 기대 결과 | `result`, `delay: 0.2`, `server` 필드 반환 |
| 판정 | AI 서버가 JSON 응답을 반환하면 통과 |

### TC-03. AI 서버 네트워크 지연 주입

| 항목 | 내용 |
|------|------|
| 목적 | AI 서버가 느려질 때 Spring API의 timeout/retry/fallback 동작 확인 |
| 장애 대상 | `app=ai-server` |
| 장애 방식 | LitmusChaos `pod-network-latency` |
| 기대 결과 | `/ask` 호출 시 `fallback` 응답 반환 |
| 판정 | fallback 발생 및 Pod Running 유지 시 통과 |

### TC-04. 장애 중 Kubernetes 상태 확인

| 항목 | 내용 |
|------|------|
| 목적 | Pod는 Running이지만 사용자 응답 품질이 저하되는 상황 확인 |
| 실행 명령 | `kubectl get deployment,pod,svc,endpointslice -n app-demo` |
| 기대 결과 | Deployment READY 유지, Pod Running 유지 |
| 판정 | 리소스 상태 정상 + API fallback 동시 관찰 시 통과 |

### TC-05. 장애 제거 후 복구 확인

| 항목 | 내용 |
|------|------|
| 목적 | 장애 종료 후 정상 응답 복구 확인 |
| 실행 명령 | `curl "$SPRING_URL/ask?prompt=hello"` |
| 기대 결과 | `status: success`, `attempt: 1` |
| 판정 | 별도 재배포 없이 정상 응답으로 회복되면 통과 |

---

## 9. LitmusChaos 장애 주입 설계

### 9.1 주입 대상

| 항목 | 값 |
|------|----|
| Target Namespace | `app-demo` |
| Target App Label | `app=ai-server` |
| Target Container | `ai-server` |
| Target Experiment | `pod-network-latency` |

Spring Boot API 서버가 아니라 AI 서버에 장애를 주입해야 한다. 그래야 실제 외부 의존성이 느려지는 상황에서 Spring Boot API 서버의 회복성 동작을 관찰할 수 있다.

### 9.2 예시 ChaosEngine

```yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: ai-server-latency-chaos
  namespace: app-demo
spec:
  appinfo:
    appns: app-demo
    applabel: "app=ai-server"
    appkind: deployment
  annotationCheck: "false"
  engineState: active
  chaosServiceAccount: chaos-sa
  experiments:
    - name: pod-network-latency
      spec:
        components:
          env:
            - name: TOTAL_CHAOS_DURATION
              value: "60"
            - name: NETWORK_INTERFACE
              value: "eth0"
            - name: NETWORK_LATENCY
              value: "3000"
            - name: JITTER
              value: "500"
            - name: PODS_AFFECTED_PERC
              value: "100"
            - name: CONTAINER_RUNTIME
              value: "containerd"
            - name: SOCKET_PATH
              value: "/run/containerd/containerd.sock"
```

참고: minikube 런타임 및 LitmusChaos 버전에 따라 `CONTAINER_RUNTIME`, `SOCKET_PATH` 값은 조정이 필요할 수 있다.

---

## 10. 실행 절차

### 10.1 정상 상태 확인

```bash
kubectl get pods -n app-demo

SPRING_URL=$(minikube service spring-api-svc -n app-demo --url)
curl "$SPRING_URL/"
curl "$SPRING_URL/ask?prompt=hello"
```

기대 응답:

```json
{
  "status": "success",
  "attempt": 1,
  "elapsedMs": 300,
  "aiResponse": "{...}"
}
```

### 10.2 장애 주입

```bash
kubectl apply -f experiments/honajun/ai-latency-demo/k8s/ai-server-latency-chaos.yaml
kubectl get chaosengine,chaosresult -n app-demo
```

### 10.3 장애 중 관찰

```bash
watch -n 2 "kubectl get pods -n app-demo"
watch -n 2 "kubectl get chaosengine,chaosresult -n app-demo"

while true; do
  date '+%H:%M:%S'
  curl -s "$SPRING_URL/ask?prompt=hello"
  echo
  sleep 2
done
```

기대 응답:

```json
{
  "status": "fallback",
  "message": "AI server is slow or unavailable. Returning fallback response.",
  "attempts": 3,
  "elapsedMs": 5200,
  "error": "HttpTimeoutException"
}
```

### 10.4 복구 확인

```bash
kubectl get chaosresult -n app-demo
curl "$SPRING_URL/ask?prompt=hello"
```

기대 응답:

```json
{
  "status": "success",
  "attempt": 1,
  "elapsedMs": 300,
  "aiResponse": "{...}"
}
```

---

## 11. 실행 결과 기록

### 11.1 환경

| 항목 | 값 |
|------|----|
| 실행일 | 2026-06-21 17:30 KST |
| Cluster | minikube profile `codex-ai-latency` |
| Kubernetes Version | v1.32.0 |
| kubectl Client Version | v1.32.2 |
| Container Runtime | containerd 1.7.24 |
| LitmusChaos Version | Operator v3.0.0 |
| Namespace | `app-demo` |
| Spring API Replicas | `2` |
| AI Server Replicas | `2` |
| `AI_TIMEOUT_MS` | `1500` |
| `AI_RETRIES` | `2` |
| 정상 `DEFAULT_DELAY` | `0.2` |
| 장애 주입 `DEFAULT_DELAY` | `3.0` |
| Spring API URL | `http://127.0.0.1:49825` |

### 11.2 Steady State 결과

| 확인 항목 | 기대값 | 실제 결과 | 판정 |
|-----------|--------|-----------|------|
| AI 서버 Pod | Running, Ready | `2/2`, Pod 2개 `Running`, restart 0 | 통과 |
| Spring API Pod | Running, Ready | `2/2`, Pod 2개 `Running`, restart 0 | 통과 |
| Spring API `/` | 설정 JSON 반환 | `timeoutMillis: 1500`, `maxRetries: 2` | 통과 |
| Spring API `/ask` | `status: success` | `status: success`, `attempt: 1`, `elapsedMs: 214` | 통과 |
| AI Server `/infer` | AI 응답 반환 | `delay: 0.2`, `result: AI response for: hello` | 통과 |

### 11.3 장애 주입 결과

| 시점 | 확인 항목 | 기대값 | 실제 결과 | 판정 |
|------|-----------|--------|-----------|------|
| 주입 전 | `/ask` 응답 | `success`, `attempt: 1` | `success`, `attempt: 1`, `elapsedMs: 214` | 통과 |
| 주입 중 | `/ask` 응답 | `fallback`, `attempts: 3` | `fallback`, `attempts: 3`, `elapsedMs: 5132`, `error: HttpTimeoutException` | 통과 |
| 주입 중 | Spring API Pod | Running 유지 | Pod 2개 `Running`, restart 0 | 통과 |
| 주입 중 | AI Server Pod | Running 유지 | Pod 2개 `Running`, restart 0 | 통과 |
| 주입 중 | EndpointSlice | endpoint 유지 | `ai-server-svc` endpoint 2개 유지 | 통과 |
| 복구 후 | `/ask` 응답 | `success`, `attempt: 1` | `success`, `attempt: 1`, `elapsedMs: 214` | 통과 |

### 11.4 타임라인

| 시간 | 이벤트 | 관찰 결과 |
|------|--------|-----------|
| 17:21 KST | `codex-ai-latency` minikube profile 시작 | Kubernetes v1.32.0, containerd 1.7.24 |
| 17:23 KST | `ai-server:local` 이미지 빌드 | 성공 |
| 17:24 KST | `spring-api:local` 이미지 빌드 | Maven build success |
| 17:25 KST | 애플리케이션 배포 | `ai-server`, `spring-api` 모두 rollout 성공 |
| 17:26 KST | 정상 상태 확인 | `/ask` `success`, `attempt: 1`, `elapsedMs: 214` |
| 17:27 KST | LitmusChaos Operator 설치 | `chaos-operator-ce` Running |
| 17:28 KST | Litmus generic experiment 설치 시도 | Hub manifest 경로 오류로 실패 |
| 17:29 KST | 대체 지연 주입 | `DEFAULT_DELAY=3.0`으로 AI 서버 rollout |
| 17:29 KST | 장애 상태 확인 | `/ask` `fallback`, `attempts: 3`, `elapsedMs: 5132` |
| 17:30 KST | 지연 제거 | `DEFAULT_DELAY=0.2`로 AI 서버 rollout |
| 17:30 KST | 복구 확인 | `/ask` `success`, `attempt: 1`, `elapsedMs: 214` |

### 11.5 캡처 파일

실행 중 확인한 명령 출력은 다음 파일로 저장했다.

| 파일 | 내용 |
|------|------|
| `experiments/honajun/ai-latency-run/01-steady-state-k8s.txt` | 정상 상태 Kubernetes 리소스 |
| `experiments/honajun/ai-latency-run/02-steady-state-ask.json` | 정상 상태 `/ask` 응답 |
| `experiments/honajun/ai-latency-run/03-latency-injected-k8s.txt` | 지연 주입 중 Kubernetes 리소스 |
| `experiments/honajun/ai-latency-run/04-latency-injected-ask.json` | 지연 주입 중 `/ask` fallback 응답 |
| `experiments/honajun/ai-latency-run/05-recovered-k8s.txt` | 복구 후 Kubernetes 리소스 |
| `experiments/honajun/ai-latency-run/06-recovered-ask.json` | 복구 후 `/ask` 응답 |
| `experiments/honajun/ai-latency-run/07-litmus-operator-status.txt` | LitmusChaos Operator 상태 |
| `experiments/honajun/ai-latency-run/08-litmus-experiments-status.txt` | LitmusChaos Experiment 설치 상태 |

### 11.6 LitmusChaos 실행 시도 결과

LitmusChaos Operator 설치는 성공했다.

```text
chaos-operator-ce   1/1   Running   0
```

하지만 generic experiment manifest 설치는 실패했다.

```text
error parsing https://hub.litmuschaos.io/api/chaos/3.0.0?file=charts/generic/experiments.yaml:
error converting YAML to JSON: yaml: mapping values are not allowed in this context
```

원격 응답 확인 결과, 해당 Hub 경로는 다음 오류를 반환했다.

```text
file content parsing error, err : unable to read file
```

따라서 이번 실행에서는 LitmusChaos `pod-network-latency` 대신 AI 서버의 `DEFAULT_DELAY` 값을 `0.2`에서 `3.0`으로 변경하는 방식으로 지연을 주입했다. 이 방식은 네트워크 계층 장애는 아니지만, 본 시나리오의 핵심인 Spring Boot API 서버의 timeout/retry/fallback 동작 검증에는 충분하다.

---

## 12. 판정 기준

| 항목 | 통과 조건 |
|------|-----------|
| 정상 상태 | 장애 주입 전 `/ask`가 `success` 반환 |
| 장애 감지 | 지연 주입 중 `/ask`가 timeout 후 retry 수행 |
| fallback | retry 실패 후 fallback 응답 반환 |
| 격리성 | Spring API Pod는 장애 주입 대상이 아니므로 Running 유지 |
| 복구성 | 장애 종료 후 재시작 없이 `/ask`가 다시 `success` 반환 |
| 관찰성 | 응답 상태, 시도 횟수, 소요 시간이 기록됨 |

최종 판정:

- 모든 항목 통과: 테스트 성공
- fallback은 발생했지만 복구 실패: 부분 성공
- fallback 없이 요청이 장시간 대기: 실패
- Spring API Pod가 재시작됨: 실패 또는 추가 분석 필요

---

## 13. 예상 리스크 및 한계

| 리스크 | 설명 | 대응 |
|--------|------|------|
| LitmusChaos 런타임 호환성 | minikube 런타임에 따라 helper pod가 실패할 수 있음 | containerd/docker 설정과 socket path 확인 |
| timeout 값 부적절 | 지연 시간이 timeout보다 짧으면 fallback이 발생하지 않음 | `NETWORK_LATENCY`를 `AI_TIMEOUT_MS`보다 크게 설정 |
| retry로 인한 응답 지연 | retry 횟수가 많으면 fallback까지 시간이 길어짐 | `AI_RETRIES`와 backoff 값 조정 |
| readiness probe 한계 | Pod Running과 사용자 품질은 다를 수 있음 | 애플리케이션 수준 지표와 API 응답 관찰 병행 |

---

## 14. 운영 관점 시사점

이번 실험은 외부 AI 서버 또는 LLM Gateway가 느려지는 운영 상황과 유사하다.

운영 환경에서는 다음 대응이 필요하다.

- 외부 의존성 호출에 timeout을 반드시 설정한다.
- retry는 무제한으로 수행하지 않고 최대 횟수를 제한한다.
- retry 실패 시 사용자에게 fallback 응답을 제공한다.
- Pod 상태뿐 아니라 사용자 응답 시간과 fallback 비율을 관찰한다.
- AI 서버 지연이 Spring API 서버의 thread 고갈로 이어지지 않도록 동시성 제한을 고려한다.
- 장애가 반복되는 경우 circuit breaker를 적용해 불필요한 외부 호출을 줄인다.

---

## 15. 결론

본 테스트는 Kubernetes 리소스가 정상 상태로 보이더라도 외부 의존 서비스의 지연으로 인해 사용자 경험이 저하될 수 있음을 검증하기 위한 실습이다.

Spring Boot API 서버가 timeout, retry, fallback을 제공하면 AI 서버가 느려지거나 일시적으로 응답하지 않는 상황에서도 사용자에게 통제된 응답을 반환할 수 있다.

따라서 이 시나리오는 단순한 Pod 복구 실험보다 실제 운영 장애에 가까운 카오스 엔지니어링 실습으로 활용할 수 있다.
