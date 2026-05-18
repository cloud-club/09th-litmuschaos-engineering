# ☸️ Kubernetes Resilience & Chaos Engineering Labs

쿠버네티스 환경에서 시스템의 고가용성(HA)과 안정성을 보장하기 위해 진행한 3가지 카오스 엔지니어링 실습 로그입니다.

## 🛠️ 실습 목차
1. [Pod Delete Chaos](./01-k8s-pod-delete-chaos.md): 레플리카 수에 따른 서비스 가용성 변화 및 자가 치유(Self-Healing) 검증
2. [Graceful Shutdown Chaos](./02-k8s-graceful-shutdown-chaos.md): SIGTERM 신호 처리를 통한 무중단 서비스 인프라-어플리케이션 정렬 실습
3. [Resource Chaos](./03-k8s-resource-oomkilled-chaos.md): Memory Limit 초과 시 발생하는 OOMKilled(Exit Code 137) 매커니즘 분석

## 이번 실습에서 배운 점

이번 실습에서는 Pod 장애를 세 가지 관점에서 확인했습니다.

첫 번째 실험에서는 Pod가 삭제되더라도 Deployment/ReplicaSet이 새로운 Pod를 생성해 원하는 replica 수를 회복하는 것을 확인했습니다. 

또한 replica 수가 충분하면 Service가 나머지 정상 Pod로 트래픽을 전달해 서비스 중단을 줄일 수 있음을 확인했습니다.

두 번째 실험에서는 Pod 삭제 시 Kubernetes가 `SIGTERM`을 전달하고, 애플리케이션이 graceful shutdown을 구현하면 기존 요청을 마무리할 수 있음을 확인했습니다.  

다만 `terminationGracePeriodSeconds`가 요청 처리 시간보다 짧으면 graceful shutdown을 구현했더라도 강제 종료될 수 있음을 확인했습니다.

마지막 실험에서는 컨테이너가 memory limit을 초과하면 `OOMKilled`가 발생하고 restart count가 증가하는 것을 확인했습니다.

결론적으로 Kubernetes에서 안정적인 서비스를 운영하려면 다음 요소를 함께 고려해야 한다는 것을 알 수 있었습니다.

- 단일 Pod에 의존하지 않는 replica 구성
- Service를 통한 트래픽 라우팅
- Readiness Probe를 통한 준비된 Pod만 트래픽 수신
- 애플리케이션의 graceful shutdown 구현
- 적절한 `terminationGracePeriodSeconds` 설정
- 실제 사용량에 맞는 resource requests/limits 설정 

결론을 이렇게 내려볼 수 있을 것 같아요....!
> Kubernetes의 안정성은 개별 Pod가 절대 죽지 않는 데서 나오는 것이 아니라, Pod가 언제든 죽을 수 있다는 전제하에 시스템이 원하는 상태로 복구되도록 설계하는 데서 나온다.