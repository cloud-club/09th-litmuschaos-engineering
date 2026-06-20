# Kubernetes API 서버 장애 대응 시나리오: 감지, 격리, 복구, 안전한 종료

## 1. 주제 선정 배경

이번 스터디에서는 Kubernetes와 Chaos Engineering을 함께 학습했다.
개인적으로 Kubernetes를 처음 접하는 입장이었기 때문에, 단순히 특정 Chaos 실험을 실행하는 것보다 Kubernetes가 장애 상황에서 어떤 방식으로 서비스를 유지하고 복구하는지 이해하는 데 초점을 두었다.

Kubernetes 환경에서 API 서버를 운영할 때 장애는 다양한 형태로 발생할 수 있다.

* Pod 자체가 삭제되는 경우
* 애플리케이션 프로세스만 종료되는 경우
* Pod는 Running 상태지만 아직 요청을 받을 준비가 되지 않은 경우
* 애플리케이션은 살아 있지만 정상 응답을 하지 못하는 경우
* 배포 또는 종료 과정에서 기존 요청이 남아 있는 경우

따라서 이번 정리는 단일 실험 결과를 나열하기보다, API 서버 장애가 발생했을 때 Kubernetes의 주요 구성 요소가 어떤 단계에서 작동하는지를 하나의 장애 대응 시나리오로 정리하는 것을 목표로 한다.

핵심 질문은 다음과 같다.

```text
Kubernetes 환경에서 API 서버 장애가 발생했을 때,
Service, Probe, Replica, Graceful Shutdown은 각각 어떤 역할을 하며
서비스 안정성에 어떻게 기여하는가?
```

---

## 2. 가정한 서비스 구조

이번 시나리오에서는 Spring Boot 기반 API 서버가 Kubernetes 위에서 운영된다고 가정한다.

```text
Client
  ↓
Service
  ↓
Deployment
  ├── Pod 1: Spring Boot API Server
  └── Pod 2: Spring Boot API Server
```

기본 구성은 다음과 같다.

| 구성 요소                         | 역할                                    |
| ----------------------------- | ------------------------------------- |
| Pod                           | Spring Boot 애플리케이션 컨테이너 실행 단위         |
| Deployment                    | 원하는 replica 수를 유지하고 배포를 관리            |
| ReplicaSet                    | Deployment에 의해 생성되며 실제 Pod 수를 유지      |
| Service                       | Pod 앞에 고정된 접근 지점을 제공하고 정상 Pod로 트래픽 전달 |
| Readiness Probe               | Pod가 트래픽을 받을 준비가 되었는지 판단              |
| Liveness Probe                | 애플리케이션이 정상적으로 살아 있는지 판단               |
| terminationGracePeriodSeconds | Pod 종료 시 강제 종료 전 대기 시간                |
| Spring Boot Graceful Shutdown | 기존 요청을 마무리하고 안전하게 종료                  |

---

## 3. 장애 대응 시나리오 전체 흐름

Kubernetes 기반 API 서버의 장애 대응 흐름은 다음과 같이 볼 수 있다.

```text
1. 정상 상태
   ↓
2. Pod 또는 애플리케이션 장애 발생
   ↓
3. Kubernetes 또는 Probe가 비정상 상태 감지
   ↓
4. Service가 비정상 Pod로의 트래픽 전달을 중단하거나 정상 Pod로 우회
   ↓
5. Kubelet, ReplicaSet, Deployment가 복구 수행
   ↓
6. 종료 중인 요청은 Graceful Shutdown을 통해 안전하게 마무리
   ↓
7. 정상 replica 수와 서비스 상태 회복
```

이 흐름에서 중요한 점은 Kubernetes가 단순히 “장애가 나면 다시 띄운다” 수준이 아니라는 것이다.
트래픽을 받을 수 있는 Pod인지 판단하고, 비정상 Pod를 제외하고, 필요한 경우 컨테이너를 재시작하거나 새 Pod를 생성하며, 종료 과정에서는 기존 요청을 보호할 수 있도록 여러 계층의 메커니즘을 제공한다.

---

## 4. 장애 유형별 대응 메커니즘

| 장애 상황                    | 주요 Kubernetes 기능                                 | 기대 동작                    | 검증 방법                |
| ------------------------ | ------------------------------------------------ | ------------------------ | -------------------- |
| Pod 자체가 삭제됨              | Deployment, ReplicaSet, Service                  | 새 Pod 생성, 정상 Pod로 트래픽 전달 | Pod Delete Chaos     |
| 앱 프로세스만 종료됨              | Kubelet, restartPolicy, Liveness Probe           | 컨테이너 재시작, `RESTARTS` 증가  | Spring Boot App Kill |
| Pod는 Running이지만 준비되지 않음  | Readiness Probe, Service Endpoint                | 준비 전까지 트래픽 제외            | startup 지연 실험        |
| 앱은 떠 있지만 응답 불가           | Liveness Probe                                   | 컨테이너 비정상 판단 후 재시작        | `/healthz` 실패 유도     |
| 배포 중 기존 요청이 남아 있음        | Graceful Shutdown, terminationGracePeriodSeconds | 기존 요청 완료 후 종료            | `/slow` 요청 중 Pod 종료  |
| replica가 1개뿐인 상태에서 장애 발생 | Replica 수, Service                               | 복구 전까지 요청 실패 가능          | replicas 1/2 비교      |

이번 문서에서는 이 중 Pod 삭제, 애플리케이션 프로세스 종료, Probe 기반 감지, Graceful Shutdown의 필요성을 중심으로 정리한다.

---

## 5. 시나리오 A: Pod 자체가 사라지는 경우

### 5-1. 상황

첫 번째 장애 상황은 Pod 자체가 삭제되는 경우이다.
예를 들어 노드 장애, 운영자 실수, 배포 과정, Chaos 실험 등에 의해 Pod가 사라질 수 있다.

```text
Deployment(replicas: 3)
  ├── Pod 1
  ├── Pod 2
  └── Pod 3

Pod 1 삭제
```

### 5-2. 기대 동작

Pod가 하나 삭제되면 ReplicaSet은 현재 Pod 수가 원하는 replica 수보다 부족하다는 것을 감지한다.
이후 새로운 Pod를 생성하여 다시 원하는 상태를 맞춘다.

```text
Pod 삭제
↓
ReplicaSet이 replica 부족 감지
↓
새 Pod 생성
↓
Deployment의 desired state 회복
```

Service는 삭제된 Pod가 아니라 남아 있는 정상 Pod로 트래픽을 전달한다.
따라서 replica가 충분히 있다면 사용자는 장애를 거의 느끼지 못할 수 있다.

### 5-3. 학습 포인트

이 시나리오에서 중요한 점은 Pod가 영구적인 존재가 아니라는 것이다.

```text
Pod는 언제든 사라질 수 있는 일시적인 객체이고,
Deployment와 ReplicaSet은 원하는 수의 Pod를 유지하기 위한 컨트롤러이다.
```

즉, Kubernetes에서는 개별 Pod를 살리는 것보다, 원하는 상태를 선언하고 컨트롤러가 그 상태를 회복하게 하는 방식이 중요하다.

### 5-4. 기존 실험으로 확인한 내용

기존 Pod Delete Chaos 실험에서 `replicas: 3`일 때는 Pod 하나가 삭제되어도 요청이 대부분 `OK`로 유지되었다.
반면 `replicas: 1`일 때는 요청을 받을 다른 Pod가 없기 때문에 새 Pod가 생성될 때까지 일시적으로 `FAIL`이 발생했다.

이를 통해 replica 수가 서비스 연속성과 직접 연결된다는 점을 확인했다.

---

## 6. 시나리오 B: Pod는 남아 있지만 애플리케이션 프로세스만 종료되는 경우

### 6-1. 상황

두 번째 장애 상황은 Pod 자체는 남아 있지만, 내부 애플리케이션 프로세스가 종료되는 경우이다.

예를 들어 Spring Boot 프로세스가 예외, OOM, 강제 종료, Chaos 실험 등에 의해 종료될 수 있다.

```text
Pod
  └── Spring Boot Process 종료
```

이 경우 Pod 자체가 삭제되는 것은 아니다.
대신 컨테이너 내부의 프로세스가 종료되면서 컨테이너가 종료되고, Kubelet이 이를 감지해 컨테이너를 재시작할 수 있다.

### 6-2. 기대 동작

Spring Boot 애플리케이션 프로세스가 종료되면 컨테이너도 종료된다.
Kubernetes는 Pod의 `restartPolicy`에 따라 같은 Pod 안에서 컨테이너를 재시작한다.

```text
Spring Boot 프로세스 종료
↓
컨테이너 종료
↓
Kubelet이 컨테이너 종료 감지
↓
같은 Pod 안에서 컨테이너 재시작
↓
Pod의 RESTARTS 값 증가
```

따라서 이 시나리오에서는 Pod 이름이 바뀌는 것이 아니라, 기존 Pod의 `RESTARTS` 값이 증가하는 것이 중요한 관찰 지표다.

### 6-3. Pod Delete와 App Kill의 차이

| 구분       | Pod Delete          | Spring Boot App Kill  |
| -------- | ------------------- | --------------------- |
| 장애 대상    | Pod 자체              | Pod 내부 애플리케이션 프로세스    |
| 주요 복구 주체 | ReplicaSet          | Kubelet               |
| 관찰 지표    | 새 Pod 생성, Pod 이름 변경 | 기존 Pod의 `RESTARTS` 증가 |
| 의미       | Pod 단위 장애 복구 확인     | 컨테이너/프로세스 단위 복구 확인    |

### 6-4. 기존 실험으로 확인한 내용

LitmusChaos의 `spring-boot-app-kill` 실험을 통해 Spring Boot 애플리케이션 프로세스를 종료하는 상황을 주입했다.

실험 후 대상 Pod의 `RESTARTS` 값이 증가했다.

```text
spring-boot-demo-5ff77f5cb-z6brf   RESTARTS 0 → 1
```

이를 통해 Spring Boot 프로세스가 종료되었고, Kubernetes가 컨테이너 종료를 감지하여 같은 Pod 안에서 컨테이너를 재시작했음을 확인했다.

실험 로그에서는 runtime attack 호출 시 `EOF`가 발생했다.
이는 Litmus가 Chaos Monkey runtime attack API를 호출하는 순간 대상 Spring Boot 프로세스가 종료되면서 HTTP 응답을 정상적으로 반환하기 전에 연결이 끊긴 것으로 해석할 수 있다.

---

## 7. 시나리오 C: Pod는 Running이지만 아직 요청을 받을 준비가 되지 않은 경우

### 7-1. 상황

세 번째 장애 상황은 Pod가 Running 상태이지만, 실제로는 아직 요청을 받을 준비가 되지 않은 경우이다.

예를 들어 다음과 같은 상황이 있을 수 있다.

* 애플리케이션 초기화가 아직 끝나지 않음
* DB 연결이 아직 완료되지 않음
* 캐시 로딩이 끝나지 않음
* 외부 API 연결 준비가 되지 않음
* 배포 직후 Spring Boot 애플리케이션이 아직 완전히 뜨지 않음

Kubernetes에서 Pod 상태가 Running이라고 해서 애플리케이션이 즉시 요청을 처리할 수 있다는 의미는 아니다.

### 7-2. Readiness Probe의 역할

Readiness Probe는 Pod가 트래픽을 받을 준비가 되었는지 판단한다.

```yaml
readinessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
```

Readiness Probe가 실패하면 Kubernetes는 해당 Pod를 Service endpoint에서 제외한다.

```text
Readiness Probe 실패
↓
Service endpoint에서 제외
↓
해당 Pod로 트래픽 전달 중단
```

이 기능은 특히 배포 직후나 장애 복구 직후에 중요하다.
새 Pod가 생성되었더라도 준비가 끝나기 전까지는 트래픽을 받지 않아야 하기 때문이다.

### 7-3. 장애 대응 관점의 의미

Readiness Probe는 장애를 “복구”하는 기능이라기보다, 준비되지 않은 Pod로 트래픽이 들어가는 것을 막는 “격리” 기능에 가깝다.

```text
준비되지 않은 Pod를 서비스 트래픽에서 격리한다.
```

즉, Service 안정성 관점에서는 Readiness Probe가 매우 중요하다.
Pod가 떠 있는지보다, 실제 요청을 받을 준비가 되었는지가 더 중요하기 때문이다.

---

## 8. 시나리오 D: 애플리케이션은 떠 있지만 정상 응답을 하지 못하는 경우

### 8-1. 상황

네 번째 장애 상황은 애플리케이션 프로세스는 살아 있지만 정상 응답을 하지 못하는 경우이다.

예를 들어 다음과 같은 상황이 있을 수 있다.

* deadlock
* 무한 루프
* 요청 처리 스레드 고갈
* 내부 상태 오류
* 특정 의존성 장애로 인한 응답 불가

이 경우 컨테이너 프로세스는 살아 있기 때문에 단순히 프로세스 존재 여부만으로는 장애를 감지하기 어렵다.

### 8-2. Liveness Probe의 역할

Liveness Probe는 애플리케이션이 정상적으로 살아 있는지 확인한다.

```yaml
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
```

Liveness Probe가 일정 횟수 이상 실패하면 Kubernetes는 해당 컨테이너를 비정상으로 판단하고 재시작할 수 있다.

```text
Liveness Probe 실패
↓
컨테이너 비정상 판단
↓
컨테이너 재시작
```

### 8-3. Readiness Probe와 Liveness Probe의 차이

| 구분       | Readiness Probe       | Liveness Probe         |
| -------- | --------------------- | ---------------------- |
| 목적       | 트래픽을 받을 준비가 되었는지 확인   | 애플리케이션이 살아 있는지 확인      |
| 실패 시 동작  | Service endpoint에서 제외 | 컨테이너 재시작               |
| 주요 사용 시점 | 배포 직후, 일시적 준비 부족      | deadlock, 응답 불가, 내부 장애 |
| 관점       | 트래픽 제어                | 복구 트리거                 |

두 Probe를 같은 `/healthz`로 둘 수도 있지만, 운영 환경에서는 목적에 따라 더 세분화할 수 있다.

예를 들어 Readiness는 DB 연결 상태를 포함하고, Liveness는 애플리케이션 프로세스 자체의 생존 여부만 확인하도록 분리할 수 있다.

---

## 9. 시나리오 E: 배포 또는 종료 중 기존 요청이 남아 있는 경우

### 9-1. 상황

다섯 번째 장애 상황은 배포나 종료 과정에서 기존 요청이 처리 중인 경우이다.

예를 들어 다음과 같은 상황이다.

```text
Client가 /payment 요청 전송
↓
Spring Boot 서버가 요청 처리 중
↓
Rolling Update 또는 Pod 종료 시작
```

이때 애플리케이션이 즉시 종료되면 처리 중이던 요청이 중간에 끊길 수 있다.
금융 API 환경에서는 이런 상황이 특히 위험하다.

### 9-2. Kubernetes의 terminationGracePeriodSeconds

Kubernetes는 Pod 종료 시 컨테이너에 SIGTERM을 보내고, `terminationGracePeriodSeconds` 동안 기다린다.

```yaml
terminationGracePeriodSeconds: 30
```

이는 Kubernetes가 애플리케이션에게 다음과 같이 말하는 것과 같다.

```text
종료해야 하니 SIGTERM을 보낼게.
최대 30초까지 기다릴 테니 정상적으로 종료해.
```

하지만 Kubernetes가 기다려준다고 해서 애플리케이션이 자동으로 요청을 안전하게 마무리하는 것은 아니다.
애플리케이션도 종료 신호를 받았을 때 기존 요청을 처리하고 종료하도록 구성되어 있어야 한다.

### 9-3. Spring Boot Graceful Shutdown

Spring Boot에서는 다음과 같은 설정을 통해 Graceful Shutdown을 적용할 수 있다.

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

이 설정을 적용하면 Spring Boot는 종료 신호를 받았을 때 새 요청 수락을 중단하고, 기존 요청이 마무리될 시간을 제공한다.

```text
SIGTERM 수신
↓
새 요청 수락 중단
↓
기존 요청 마무리
↓
정상 종료
```

### 9-4. 장애 대응 관점의 의미

Graceful Shutdown은 단순히 “잘 종료한다”는 의미가 아니다.
운영 환경에서는 배포도 하나의 장애 상황처럼 다룰 수 있다.

배포 중에도 사용자의 요청은 계속 들어오고, 기존 요청이 처리 중일 수 있다.
따라서 안전한 종료 전략이 없다면 Rolling Update 중에도 요청 실패가 발생할 수 있다.

Graceful Shutdown은 다음 문제를 줄이기 위한 전략이다.

```text
배포 중 요청 중단
처리 중 요청 손실
종료 중인 Pod로 트래픽 유입
비정상적인 API 응답
```

---

## 10. Service, Probe, Replica, Graceful Shutdown의 역할 연결

Kubernetes 기반 API 서버의 안정성은 하나의 기능만으로 만들어지지 않는다.
각 기능은 서로 다른 단계에서 역할을 나누어 수행한다.

```text
Replica
→ 장애가 발생해도 대체 인스턴스가 존재하도록 함

Service
→ 정상 Pod로 트래픽을 전달함

Readiness Probe
→ 준비되지 않은 Pod를 트래픽 대상에서 제외함

Liveness Probe
→ 비정상 컨테이너를 감지하고 재시작을 유도함

Graceful Shutdown
→ 종료 중인 애플리케이션이 기존 요청을 안전하게 마무리하도록 함

terminationGracePeriodSeconds
→ Kubernetes가 강제 종료 전 애플리케이션에게 정상 종료 시간을 제공함
```

이를 하나의 흐름으로 보면 다음과 같다.

```text
장애 발생
↓
Probe 또는 Kubelet이 상태 감지
↓
Service가 정상 Pod 중심으로 트래픽 전달
↓
Kubelet 또는 ReplicaSet이 복구 수행
↓
종료 중인 요청은 Graceful Shutdown으로 보호
↓
정상 상태 회복
```

즉, Kubernetes의 안정성은 “자동 재시작” 하나로 설명할 수 없다.
트래픽 제어, 장애 감지, 복구, 안전한 종료가 함께 맞물려야 실제 서비스 안정성을 확보할 수 있다.

---

## 11. KB국민은행 KBaaS 사례와의 연결

AWS Summit Seoul에서 들었던 KB국민은행 KBaaS 사례에서는 금융 API 인프라의 안정성과 확장성이 중요하게 다루어졌다.

KBaaS는 임베디드 금융 서비스를 제공하기 위한 API 기반 플랫폼이다.
외부 플랫폼이나 고객이 금융 API를 호출하기 때문에, 일부 인스턴스가 종료되더라도 전체 서비스가 안정적으로 유지되어야 한다.

특히 인상 깊었던 점은 Blue/Green, Canary 배포 방식도 검토했지만 최종적으로 Graceful Shutdown을 적용한 Rolling 배포를 사용했다는 점이다.

이는 다음과 같은 운영 요구사항과 연결된다.

```text
처리 중인 금융 거래 요청이 중간에 끊기지 않아야 한다.
배포 중에도 서비스 중단을 최소화해야 한다.
일부 인스턴스 장애가 전체 API 장애로 확산되지 않아야 한다.
트래픽 증가에 따라 확장 가능해야 한다.
```

이번 시나리오에서 정리한 Kubernetes 메커니즘은 이러한 요구사항과 연결된다.

| 운영 요구사항              | Kubernetes 관점의 대응             |
| -------------------- | ----------------------------- |
| 일부 인스턴스 장애에도 서비스 유지  | Replica, Service              |
| 준비되지 않은 인스턴스로 트래픽 방지 | Readiness Probe               |
| 응답 불가 인스턴스 복구        | Liveness Probe                |
| 배포 중 요청 손실 최소화       | Graceful Shutdown             |
| 원하는 상태 회복            | Deployment, ReplicaSet        |
| 안전한 종료 시간 확보         | terminationGracePeriodSeconds |

결국 금융 API 플랫폼에서 중요한 것은 장애가 절대 발생하지 않는다는 가정이 아니다.
장애나 배포가 발생해도 전체 서비스가 유지되고, 처리 중인 요청이 안전하게 마무리되며, 시스템이 빠르게 정상 상태로 회복되는 구조가 중요하다.

---

## 12. 기존 스터디 실험과의 연결

이번 시나리오를 이해하기 위해 기존 스터디에서 수행한 실험을 일부 검증 사례로 활용했다.

### 12-1. Pod Delete Chaos

Pod를 삭제했을 때 Deployment/ReplicaSet이 새로운 Pod를 생성하여 replica 수를 회복하는지 확인했다.

`replicas: 3`인 경우에는 하나의 Pod가 삭제되어도 남은 Pod가 요청을 처리했기 때문에 서비스 영향이 작았다.
반면 `replicas: 1`인 경우에는 Pod가 복구되기 전까지 요청 실패가 발생했다.

이를 통해 replica 수와 Service가 서비스 연속성에 중요한 역할을 한다는 점을 확인했다.

### 12-2. Spring Boot App Kill

LitmusChaos의 `spring-boot-app-kill` 실험을 통해 Spring Boot 애플리케이션 프로세스를 종료하는 상황을 주입했다.

실험 결과 대상 Pod의 `RESTARTS` 값이 증가했다.

```text
spring-boot-demo-5ff77f5cb-z6brf   RESTARTS 0 → 1
```

이를 통해 Pod 자체가 삭제되지 않더라도, 컨테이너 내부 애플리케이션 프로세스가 종료되면 Kubernetes가 이를 감지하고 컨테이너를 재시작할 수 있음을 확인했다.

### 12-3. Graceful Shutdown 실험

기존 Graceful Shutdown 실험에서는 `/slow` 요청을 처리하는 중 Pod 종료가 발생했을 때, 종료 유예 시간이 충분한 경우 요청이 정상 완료되고, 유예 시간이 부족한 경우 요청이 끊길 수 있음을 확인했다.

이를 통해 `terminationGracePeriodSeconds`와 애플리케이션 레벨의 Graceful Shutdown 설정이 함께 맞아야 한다는 점을 학습했다.

---

## 13. 최종 정리

이번 정리에서는 Kubernetes 기반 API 서버 장애 대응 시나리오를 설계하고, 각 장애 상황에서 Kubernetes 구성 요소가 어떤 역할을 하는지 정리했다.

핵심 내용은 다음과 같다.


- Pod는 언제든 사라질 수 있는 일시적인 객체이다.
- Deployment와 ReplicaSet은 원하는 Pod 수를 유지한다.
- Service는 정상 Pod로 트래픽을 전달한다.
- Readiness Probe는 준비되지 않은 Pod를 트래픽에서 제외한다.
- Liveness Probe는 비정상 컨테이너 재시작의 기준이 된다.
- Graceful Shutdown은 종료 중인 기존 요청을 안전하게 마무리하기 위해 필요하다.
- terminationGracePeriodSeconds는 Kubernetes가 강제 종료 전 기다려주는 시간이다.


이번 시나리오를 통해 Kubernetes의 안정성은 단순히 “Pod를 다시 띄우는 기능”이 아니라는 점을 이해할 수 있었다.

실제 운영 환경에서는 다음 요소가 함께 맞물려야 한다.

- 트래픽 제어
- 장애 감지
- 자동 복구
- 안전한 종료
- 충분한 replica 수



따라서 Kubernetes 기반 API 서버를 설계할 때는 Service, Probe, Replica, Graceful Shutdown을 각각 독립적인 기능으로 보는 것이 아니라, 하나의 장애 대응 흐름 안에서 함께 고려해야 할 것이다.
