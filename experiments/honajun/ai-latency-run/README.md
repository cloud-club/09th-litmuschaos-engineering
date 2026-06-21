# AI Latency 테스트 실행 캡처

실행일: 2026-06-21 17:30 KST

## 실행 환경

- minikube profile: `codex-ai-latency`
- Kubernetes: v1.32.0
- Container runtime: containerd 1.7.24
- Namespace: `app-demo`
- Spring API URL: `http://127.0.0.1:49825`

## 결과 요약

| 단계 | 결과 |
|------|------|
| 정상 상태 | `/ask` 응답 `success`, `attempt: 1`, `elapsedMs: 214` |
| 지연 주입 | AI 서버 `DEFAULT_DELAY=3.0` 적용 |
| 장애 상태 | `/ask` 응답 `fallback`, `attempts: 3`, `elapsedMs: 5132`, `error: HttpTimeoutException` |
| 복구 | AI 서버 `DEFAULT_DELAY=0.2` 복구 후 `/ask` 응답 `success`, `attempt: 1` |

## 캡처 파일

| 파일 | 내용 |
|------|------|
| `01-steady-state-k8s.txt` | 정상 상태 Kubernetes 리소스 |
| `02-steady-state-ask.json` | 정상 상태 `/ask` 응답 |
| `03-latency-injected-k8s.txt` | 지연 주입 중 Kubernetes 리소스 |
| `04-latency-injected-ask.json` | 지연 주입 중 `/ask` fallback 응답 |
| `05-recovered-k8s.txt` | 복구 후 Kubernetes 리소스 |
| `06-recovered-ask.json` | 복구 후 `/ask` 응답 |
| `07-litmus-operator-status.txt` | LitmusChaos Operator 상태 |
| `08-litmus-experiments-status.txt` | LitmusChaos Experiment 설치 상태 |

## LitmusChaos 메모

LitmusChaos Operator 설치는 성공했지만, Hub의 generic experiment manifest 경로가 파일 파싱 오류를 반환해 `pod-network-latency` 실험 리소스 설치는 진행하지 못했다.

이번 실행에서는 대체 방식으로 AI 서버의 `DEFAULT_DELAY`를 3초로 올려 timeout/retry/fallback을 검증했다.
