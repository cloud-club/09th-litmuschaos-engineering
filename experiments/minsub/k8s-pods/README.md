# ☸️ Kubernetes Resilience & Chaos Engineering Labs

쿠버네티스 환경에서 시스템의 고가용성(HA)과 안정성을 보장하기 위해 진행한 3가지 카오스 엔지니어링 실습 로그입니다.

## 🛠️ 실습 목차
1. [Pod Delete Chaos](./01-k8s-pod-delete-chaos.md): 레플리카 수에 따른 서비스 가용성 변화 및 자가 치유(Self-Healing) 검증
2. [Graceful Shutdown Chaos](./02-k8s-graceful-shutdown-chaos.md): SIGTERM 신호 처리를 통한 무중단 서비스 인프라-어플리케이션 정렬 실습
3. [Resource Chaos](./03-k8s-resource-oomkilled-chaos.md): Memory Limit 초과 시 발생하는 OOMKilled(Exit Code 137) 매커니즘 분석