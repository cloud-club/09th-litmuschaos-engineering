# 발표 대본
## LitmusChaos Pod IO Stress 실험 트러블슈팅

---

## 인트로 (약 30초)

안녕하세요. 저는 이번 스터디에서 LitmusChaos를 활용한 Pod IO Stress와 Pod Network Corruption 실험을 진행했습니다.

실험 자체보다 실험 환경을 구성하는 과정에서 예상치 못한 에러가 연속으로 발생했고, 각 에러의 원인을 추적하면서 Kubernetes 컨테이너 런타임과 LitmusChaos 내부 동작 구조를 더 깊이 이해하게 됐습니다. 오늘은 그 트러블슈팅 과정을 공유하겠습니다.

---

## 1. 실험 목표 소개 (약 1분)

제가 진행하려 했던 실험은 두 가지입니다.

첫 번째는 Pod IO Stress입니다. 컨테이너 내부에 디스크 I/O 부하를 인위적으로 주입해서, 스토리지 자원이 고갈되는 상황에서 애플리케이션이 어떻게 반응하는지 확인하는 실험입니다.

두 번째는 Pod Network Corruption입니다. 컨테이너에서 나가는 네트워크 패킷을 손상시켜서, 불안정한 네트워크 환경에서의 서비스 복원력을 검증하는 실험입니다.

두 실험 모두 다른 팀원들이 진행하는 실험과 겹치지 않는 주제로 선택했습니다.

실험 환경은 WSL2 Ubuntu, Docker Desktop, minikube docker driver 구성이었습니다.

---

## 2. 에러 1 — containerd v1alpha2 API 미지원 (약 2분)

처음에는 LitmusChaos 1.13.8 버전을 설치하고, chaosengine을 적용했습니다.

그런데 실험이 계속 Fail로 끝났습니다. helper Pod 로그를 확인했더니 이런 에러가 나왔습니다.

```
rpc error: code = Unimplemented
desc = unknown service runtime.v1alpha2.RuntimeService
```

처음엔 소켓 경로 문제인 줄 알았습니다. `/run/containerd/containerd.sock`이 실제로 존재하는지 minikube 노드에 직접 SSH로 접속해서 확인도 했고, 파일도 있었습니다.

그런데 파일이 있는데 왜 실패하는지 계속 의문이었습니다.

원인을 찾아보니, 이건 소켓 경로 문제가 아니었습니다. containerd 1.7 버전부터 `runtime.v1alpha2` API가 완전히 제거됐는데, LitmusChaos 1.13.8이 사용하는 crictl이 이 구버전 API를 호출하고 있었던 겁니다.

즉, 소켓 연결은 성공했지만, 요청한 서비스 자체가 없어진 상황이었습니다.

---

## 3. 에러 2 — Docker API 버전 불일치 (약 2분)

containerd가 안 되니까, CONTAINER_RUNTIME을 docker로 바꿔봤습니다.

minikube 노드 안에서 Docker 소켓이 있는지 확인했고, `/var/run/docker.sock`도 존재했습니다. 그리고 `docker ps`로 nginx Pod 컨테이너가 실제로 Docker로 관리되고 있다는 것도 확인했습니다.

컨테이너 ID도 LitmusChaos가 찾은 ID와 정확히 일치했습니다.

이번엔 진짜 되겠다 싶었는데, 또 Fail이 났습니다.

helper Pod 로그를 보니 이런 에러가 나왔습니다.

```
Error response from daemon: client version 1.40 is too old.
Minimum supported API version is 1.44
```

이게 핵심이었습니다.

LitmusChaos 1.13.8의 go-runner는 Docker API 클라이언트 버전 1.40을 사용하는데, minikube 안에 설치된 Docker 29.2.1은 최소 1.44를 요구합니다. 이건 설정으로 해결이 안 됩니다. LitmusChaos 자체를 업그레이드해야 했습니다.

---

## 4. 에러 3 — LitmusChaos 2.x 아키텍처 변화 (약 1분 30초)

그래서 LitmusChaos 2.14.0으로 업그레이드를 시도했습니다.

`litmus-2.14.0.yaml`을 적용하고 CRD를 확인했는데, `chaosengines` CRD만 있고 `chaosexperiments`와 `chaosresults`가 없었습니다.

이유는 LitmusChaos 2.x부터 아키텍처가 바뀌었기 때문입니다.

1.x에서는 하나의 YAML로 모든 게 설치됐는데, 2.x는 ChaosCenter와 chaos-operator가 완전히 분리됐습니다.

`litmus-2.14.0.yaml`은 ChaosCenter, 즉 포털과 프론트엔드, MongoDB만 설치합니다. 실제 실험을 실행하는 chaos-operator는 UI를 통해 별도로 연결하거나, 직접 다른 방법으로 설치해야 합니다.

---

## 5. 에러 4 — minikube 버전 제약 (약 1분)

이 문제를 우회하려고 다른 방법을 시도했습니다. containerd 구버전을 사용하는 Kubernetes 1.24를 minikube에 설치하려 했습니다. containerd 1.6.x는 v1alpha2를 아직 지원하거든요.

그런데 minikube v1.38.1이 지원하는 최소 Kubernetes 버전은 v1.28.0이었습니다. Kubernetes 1.24는 지원하지 않아서 이 방법도 막혔습니다.

결국 이 시점에서 선택지가 명확해졌습니다.

Kubernetes 1.28 이상을 써야 하고, containerd는 1.7.x를 쓸 수밖에 없으며, 그러면 LitmusChaos도 CRI v1을 지원하는 2.x 이상을 써야 한다는 결론이 나왔습니다.

---

## 6. 최종 해결 방향 (약 1분)

최종 해결 방향은 다음과 같습니다.

minikube를 Kubernetes 1.28과 containerd 런타임으로 새로 시작하고, LitmusChaos 2.x의 chaos-operator만 ChaosCenter 없이 직접 설치합니다. 이렇게 하면 CRI v1을 지원하는 go-runner 2.x를 사용하게 되어 containerd 1.7과 정상적으로 통신할 수 있습니다.

이 과정에서 각 컴포넌트의 버전 사이에 어떤 의존관계가 있는지를 정확히 파악해야 했습니다.

---

## 7. 배운 점 정리 (약 1분 30초)

이번 트러블슈팅에서 세 가지를 배웠습니다.

첫째, 오픈소스 도구는 버전 간 호환성을 반드시 확인해야 합니다. LitmusChaos 1.13.8은 2022년에 출시된 버전인데, Docker 29.x나 최신 containerd와 함께 사용하면 API 불일치로 동작하지 않습니다.

둘째, 에러 메시지를 정확하게 읽는 것이 디버깅의 핵심이었습니다. `unknown service runtime.v1alpha2.RuntimeService`는 소켓 경로 문제가 아니라 API 버전 문제였고, `client version 1.40 is too old`는 애플리케이션 설정 문제가 아니라 라이브러리 버전 문제였습니다. 에러 메시지 자체가 이미 원인을 설명하고 있었습니다.

셋째, Kubernetes의 컨테이너 런타임 구조를 이해하게 됐습니다. Docker, containerd, CRI API 버전, 소켓 경로가 서로 어떻게 연결되어 있는지, 그리고 그 사이에서 LitmusChaos가 어떻게 컨테이너에 진입하는지를 직접 겪으면서 배웠습니다.

실험 자체보다 이 과정이 더 많은 것을 가르쳐 줬습니다.

이상입니다. 감사합니다.

---

## 예상 질문 & 답변

**Q. LitmusChaos가 컨테이너에 어떻게 진입하나요?**

A. helper Pod가 대상 컨테이너의 ID를 컨테이너 런타임(containerd 또는 Docker)에서 찾고, nsenter로 컨테이너의 네임스페이스에 진입해서 stress-ng 같은 도구를 실행합니다. 그래서 런타임 소켓 접근과 API 버전이 중요합니다.

**Q. 왜 v1alpha2가 제거됐나요?**

A. Kubernetes는 1.25부터 dockershim을 공식 제거했고, containerd도 1.7부터 구버전 CRI API인 v1alpha2를 제거했습니다. 이는 Kubernetes 생태계가 CRI v1로 통일되는 과정입니다.

**Q. LitmusChaos 2.x와 1.x의 가장 큰 차이는 뭔가요?**

A. 1.x는 단순히 operator 하나로 ChaosEngine을 처리했는데, 2.x부터 ChaosCenter(관리 UI)와 chaos-operator(실행 엔진)가 분리됐습니다. 관리 규모가 커지면 편리하지만, 학습 환경에서는 오히려 설정이 복잡해집니다.
