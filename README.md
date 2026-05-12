# 09th-litmuschaos-engineering

## 1. 스터디 소개

Kubernetes 환경에서 실행되는 애플리케이션에 의도적으로 장애를 주입하고, 시스템이 어떻게 실패하고 복구되는지 관찰하는 실습 중심 스터디입니다.

초반에는 Chaos Engineering 실습에 필요한 Go, Kubernetes 기초를 함께 학습하고, 후반에는 LitmusChaos를 활용해 실제 장애 실험을 설계하고 실행합니다.

단순히 장애를 발생시키는 것이 아니라, 다음 개념을 바탕으로 실험을 설계하고 결과를 분석하는 것을 목표로 합니다.

- Steady State
- Hypothesis
- Observability
- SLI / SLO
- 장애 주입 및 복구 과정 분석

---

## 2. 진행 방식

- 총 8주간 진행
- 주 1회 온/오프라인 병행
- 온라인: Discord 기반 진행
- 오프라인: 격주 진행 예정
- 실습 및 결과 공유 중심
- 실습 코드는 스터디 GitHub Repository에 업로드

---

## 3. 주요 학습 자료

### Go

- [A Tour of Go](https://go.dev/tour/welcome/1)
- [Learn Go with Tests](https://quii.gitbook.io/learn-go-with-tests)

### Kubernetes

- [Kubernetes 공식 문서](https://kubernetes.io/docs/home/)

### Chaos Engineering

- [Principles of Chaos Engineering](https://principlesofchaos.org/)

### LitmusChaos

- [LitmusChaos 공식 문서](https://docs.litmuschaos.io/docs/introduction/what-is-litmus)
- [LitmusChaos Experiments](https://litmuschaos.github.io/litmus/experiments/)

---

## 4. 목표

이 스터디의 최종 목표는 단순히 도구 사용법을 익히는 것이 아니라, 장애를 실험으로 바라보고 시스템의 회복력을 분석하는 관점을 익히는 것입니다.

스터디가 끝날 때까지 다음을 경험하는 것을 목표로 합니다.

- Go 기반 간단한 서버 애플리케이션 구현
- Kubernetes 환경에서 애플리케이션 배포
- Probe, Deployment, Service 등 주요 Kubernetes 개념 이해
- LitmusChaos를 활용한 장애 주입 실험 수행
- 실험 전 가설 설정 및 실험 후 결과 분석
- 관측 지표를 바탕으로 시스템 회복력 평가