# LitmusChaos Spring Boot App Kill 실험 정리

## 1. 실험 배경

이번 실험은 Kubernetes 환경에서 Spring Boot 애플리케이션이 종료되는 상황을 의도적으로 주입하고, Kubernetes가 이를 어떻게 복구하는지 확인하기 위해 진행했다.

실험 주제는 LitmusChaos의 `spring-boot-app-kill`이다.

이 실험은 2026 AWS Summit Seoul에서 들은 KB국민은행 KBaaS 사례와 연결하여 선정했다.

KBaaS 강연에서 인상 깊었던 점은 다음과 같다.

* KBaaS는 임베디드 금융 서비스를 위한 API 기반 인프라를 운영한다.
* 금융 API 플랫폼에서는 안정적이고 확장 가능한 API 인프라가 핵심이다.
* 높은 TPS와 장애 상황에서도 API 응답 안정성이 중요하다.
* Blue/Green, Canary 배포 방식도 검토했지만 최종적으로 Graceful Shutdown을 적용한 Rolling 배포를 선택했다.
* Pod가 종료될 때 기존 거래 요청을 안전하게 처리하고 종료되는 구조가 중요하다.

즉, 금융 API 플랫폼에서는 일부 애플리케이션 인스턴스가 종료되더라도 전체 서비스가 중단되지 않고, Kubernetes가 정상 상태를 회복할 수 있어야 한다.

이번 실험은 이러한 상황을 로컬 Kubernetes 환경에서 재현해보는 것을 목표로 했다.

---

## 2. 실험 목적

이번 실험의 목적은 다음과 같다.

```text
Spring Boot 애플리케이션 프로세스가 강제로 종료되었을 때,
Kubernetes가 컨테이너 상태를 감지하고 정상 상태로 복구하는지 확인한다.
```

구체적으로는 다음을 확인하고자 했다.

1. LitmusChaos가 Spring Boot App Kill 실험을 정상 실행하는가?
2. 대상 Spring Boot Pod를 식별할 수 있는가?
3. Chaos Monkey endpoint를 통해 App Kill fault를 주입할 수 있는가?
4. App Kill 이후 대상 Pod의 컨테이너가 재시작되는가?
5. Deployment의 replica 수가 유지되는가?
6. Service 요청은 다른 정상 Pod를 통해 계속 처리될 수 있는가?

---

## 3. 실험 구성

전체 구성은 다음과 같다.

```text
Client(curl-test)
  ↓
Service: spring-boot-demo-service
  ↓
Deployment: spring-boot-demo
  ├── Spring Boot Pod 1
  └── Spring Boot Pod 2

LitmusChaos
  ↓
ChaosEngine
  ↓
spring-boot-app-kill
  ↓
Spring Boot App Kill 주입
```

이번 실험에서는 `replicas: 2`로 Spring Boot Pod를 구성했다.

```yaml
replicas: 2
```

따라서 한 Pod의 애플리케이션 프로세스가 종료되더라도 다른 Pod가 트래픽을 처리할 수 있는지 확인할 수 있다.

---

## 4. 실험 환경

| 항목            | 내용                         |
| ------------- | -------------------------- |
| Kubernetes 환경 | kind                       |
| 클러스터 이름       | `pod-chaos`                |
| 실험 도구         | LitmusChaos                |
| 애플리케이션        | Spring Boot                |
| Java          | 17                         |
| 대상 Deployment | `spring-boot-demo`         |
| 대상 Service    | `spring-boot-demo-service` |
| replica 수     | 2                          |
| 실험 종류         | `spring-boot-app-kill`     |
| 테스트 클라이언트     | `curl-test` Pod            |

---


## 5. 실험에 사용한 Manifest 파일 역할

이번 실험에서는 Spring Boot 애플리케이션을 배포하는 파일과 LitmusChaos 실험을 실행하기 위한 RBAC/ChaosEngine 파일을 함께 사용했다.

전체 역할은 다음과 같이 나눌 수 있다.

```text
1. Spring Boot 앱 배포 파일
   → 실험 대상 애플리케이션과 Service 생성

2. Litmus Operator 권한 파일
   → ChaosEngine을 감지하고 실험 Runner Pod를 생성할 수 있도록 권한 부여

3. Spring Boot App Kill 실험 권한 파일
   → 실제 실험 Pod가 대상 Pod와 ReplicaSet을 조회하고 ChaosResult를 기록할 수 있도록 권한 부여

4. ChaosEngine 파일
   → 어떤 대상에 어떤 실험을 실행할지 선언
```

---

### 5-1. `spring-boot-demo.yaml`

`spring-boot-demo.yaml`은 실험 대상 Spring Boot 애플리케이션을 Kubernetes에 배포하는 파일이다.

이 파일은 크게 `Deployment`와 `Service`로 구성된다.

```text
Deployment
→ Spring Boot Pod 2개 유지

Service
→ Pod IP가 바뀌어도 고정된 이름으로 접근 가능하게 함
```

Deployment의 핵심 설정은 다음과 같다.

```yaml
replicas: 2
```

이는 Spring Boot Pod를 항상 2개 유지하겠다는 의미다.  
따라서 App Kill로 하나의 Pod 안에서 컨테이너가 재시작되더라도, 다른 Pod가 계속 트래픽을 처리할 수 있다.

```yaml
labels:
  app: spring-boot-demo
```

이 라벨은 Service와 LitmusChaos가 대상 Pod를 찾는 기준으로 사용된다.

Service는 다음 selector를 통해 Spring Boot Pod로 트래픽을 전달한다.

```yaml
selector:
  app: spring-boot-demo
```

ChaosEngine 역시 같은 라벨을 사용해 실험 대상을 지정한다.

```yaml
applabel: "app=spring-boot-demo"
```

또한 다음 설정을 통해 Pod 종료 시 Kubernetes가 강제 종료 전에 최대 30초까지 기다릴 수 있도록 했다.

```yaml
terminationGracePeriodSeconds: 30
```

다만 이번 `spring-boot-app-kill` 실험은 Pod 자체를 삭제하는 것이 아니라 Pod 내부의 Spring Boot 프로세스를 종료시키는 실험에 가까우므로, 직접적인 관찰 지표는 Pod 이름 변경이 아니라 `RESTARTS` 증가였다.

정리하면 `spring-boot-demo.yaml`은 다음 역할을 한다.

```text
Spring Boot 앱을 Pod 2개로 배포하고,
ClusterIP Service를 통해 클러스터 내부에서 접근할 수 있게 하는 실험 대상 구성 파일
```

---

### 5-2. `spring-boot-app-kill-chaosengine.yaml`

`spring-boot-app-kill-chaosengine.yaml`은 LitmusChaos에게 어떤 실험을 어떤 대상에 실행할지 알려주는 핵심 파일이다.

```yaml
kind: ChaosEngine
metadata:
  name: spring-boot-app-kill-chaos
```

`ChaosEngine`은 LitmusChaos에서 실험 실행 의도를 선언하는 리소스다.

쉽게 말하면 다음과 같은 지시를 Kubernetes 리소스로 표현한 것이다.

```text
Litmus야,
default namespace에 있는 app=spring-boot-demo Deployment에
spring-boot-app-kill 실험을 실행해줘.
```

핵심 설정은 다음과 같다.

```yaml
engineState: active
```

실험을 활성화한다는 의미다.  
`active` 상태여야 Litmus Operator가 이 ChaosEngine을 감지하고 실험 Runner Pod를 생성한다.

```yaml
annotationCheck: "false"
```

대상 애플리케이션에 Litmus용 annotation이 없어도 실험을 실행하겠다는 의미다.

```yaml
appinfo:
  appns: default
  applabel: "app=spring-boot-demo"
  appkind: deployment
```

실험 대상을 지정하는 부분이다.

| 항목 | 의미 |
| --- | --- |
| `appns` | 대상 애플리케이션이 있는 namespace |
| `applabel` | 대상 Pod를 찾기 위한 label |
| `appkind` | 대상 애플리케이션의 Workload 종류 |

실제 실험 로그에서도 Litmus가 이 설정을 기반으로 대상 Pod를 찾았다.

```text
Target pods list for chaos, [spring-boot-demo-5ff77f5cb-z6brf]
```

```yaml
chaosServiceAccount: spring-boot-app-kill-sa
```

실제 실험 Pod가 사용할 ServiceAccount를 지정한다.  
따라서 `spring-boot-app-kill-sa`에는 대상 Pod 조회, ReplicaSet 조회, ChaosResult 업데이트 등에 필요한 권한이 있어야 한다.

```yaml
jobCleanUpPolicy: retain
```

실험 종료 후 Runner/Experiment Pod를 바로 삭제하지 않고 남겨두겠다는 의미다.  
이번 실험에서는 이 설정 덕분에 실험 Pod 로그를 확인할 수 있었다.

실험 환경 변수의 의미는 다음과 같다.

| 설정 | 의미 |
| --- | --- |
| `CM_PORT: "8080"` | Spring Boot 애플리케이션 포트 |
| `CM_LEVEL: "1"` | Chaos Monkey fault 발생 수준 |
| `CM_WATCHERS: "restController"` | `@RestController` 계층에 fault 주입 |
| `PODS_AFFECTED_PERC: "50"` | 대상 Pod 중 50%에 장애 주입 |
| `SEQUENCE: "serial"` | 여러 Pod 대상 시 순차적으로 실험 실행 |

이번 실험에서는 Spring Boot Pod가 2개였고 `PODS_AFFECTED_PERC`가 50이었기 때문에, 실제로 1개의 Pod가 실험 대상으로 선택되었다.

정리하면 `spring-boot-app-kill-chaosengine.yaml`은 다음 역할을 한다.

```text
spring-boot-demo Deployment를 대상으로
spring-boot-app-kill 실험을 실행하도록 LitmusChaos에 지시하는 실험 정의 파일
```

---

### 5-3. `spring-boot-app-kill-sa.yaml`

`spring-boot-app-kill-sa.yaml`은 실제 `spring-boot-app-kill` 실험 Pod가 사용할 ServiceAccount와 기본 권한을 정의하는 파일이다.

```yaml
kind: ServiceAccount
metadata:
  name: spring-boot-app-kill-sa
  namespace: default
```

이 ServiceAccount는 ChaosEngine에서 다음과 같이 참조된다.

```yaml
chaosServiceAccount: spring-boot-app-kill-sa
```

즉, 실제 실험 Pod는 `spring-boot-app-kill-sa` 권한으로 Kubernetes API를 호출한다.

실험 Pod는 다음 작업을 수행해야 한다.

```text
대상 Pod 조회
Pod 로그/이벤트 확인
실험 Job 생성 및 상태 확인
ChaosEngine/ChaosExperiment/ChaosResult 조회 및 업데이트
```

이를 위해 다음 권한을 부여했다.

| 리소스 | 역할 |
| --- | --- |
| `pods` | 대상 Pod 조회 |
| `pods/log` | Pod 로그 조회 |
| `pods/exec` | 필요 시 Pod 내부 명령 실행 |
| `events` | 실험 관련 이벤트 기록 |
| `jobs` | 실험 Job 생성/조회/삭제 |
| `chaosengines` | ChaosEngine 조회 및 업데이트 |
| `chaosexperiments` | 실험 정의 조회 |
| `chaosresults` | 실험 결과 생성 및 업데이트 |

정리하면 `spring-boot-app-kill-sa.yaml`은 다음 역할을 한다.

```text
실제 spring-boot-app-kill 실험 Pod가 default namespace에서
Pod, Job, ChaosResult 등을 다룰 수 있도록 권한을 부여하는 파일
```

---

### 5-4. `spring-boot-app-kill-apps-extra-role.yaml`

`spring-boot-app-kill-apps-extra-role.yaml`은 `spring-boot-app-kill-sa`에 추가로 apps 리소스 조회 권한을 부여하기 위해 만든 파일이다.

처음 실험을 실행했을 때 다음 오류가 발생했다.

```text
replicasets.apps "spring-boot-demo-698794f6bc" is forbidden:
User "system:serviceaccount:default:spring-boot-app-kill-sa"
cannot get resource "replicasets" in API group "apps"
```

이 오류는 실험 Pod가 대상 Pod의 상위 리소스를 확인하는 과정에서 발생했다.

Kubernetes에서 Deployment가 Pod를 관리하는 구조는 다음과 같다.

```text
Deployment
  ↓
ReplicaSet
  ↓
Pod
```

Litmus는 대상 Pod를 찾은 뒤, 이 Pod가 어떤 상위 리소스에 의해 관리되는지 확인하려고 ReplicaSet을 조회한다.  
그런데 기존 `spring-boot-app-kill-sa`에는 `replicasets` 조회 권한이 없어 실험이 실패했다.

이를 해결하기 위해 다음 리소스에 대한 권한을 추가했다.

```yaml
apiGroups: ["apps"]
resources:
  - deployments
  - replicasets
  - statefulsets
  - daemonsets
```

정리하면 이 파일은 다음 역할을 한다.

```text
spring-boot-app-kill 실험 Pod가 대상 Pod의 상위 리소스인
ReplicaSet/Deployment를 조회할 수 있도록 추가 권한을 부여하는 파일
```

---

### 5-5. `litmus-lease-role.yaml`

`litmus-lease-role.yaml`은 Litmus Operator가 leader election을 수행할 수 있도록 `leases` 권한을 부여하는 파일이다.

Litmus Operator는 Kubernetes Operator이므로 leader election 과정을 수행한다.  
이는 여러 Operator 인스턴스가 있을 때 하나의 인스턴스만 실제 작업을 수행하도록 lock을 잡는 구조다.

이때 Kubernetes의 `Lease` 리소스가 사용된다.

처음에는 다음 오류가 발생했다.

```text
leases.coordination.k8s.io "chaos-operator.lock" is forbidden
```

즉, `litmus` ServiceAccount가 `leases` 리소스를 조회하거나 생성할 권한이 없어 leader election에 실패한 것이다.

이를 해결하기 위해 `litmus` namespace에서 `leases`를 다룰 수 있는 Role을 만들고, 이를 `litmus` ServiceAccount에 연결했다.

정리하면 이 파일은 다음 역할을 한다.

```text
Litmus Operator가 chaos-operator.lock Lease를 획득하고
정상적으로 leader election을 완료할 수 있도록 권한을 부여하는 파일
```

이후 Operator 로그에서 다음과 같은 정상 흐름을 확인했다.

```text
successfully acquired lease litmus/chaos-operator.lock
```

---

### 5-6. `litmus-operator-cluster-role.yaml`

`litmus-operator-cluster-role.yaml`은 Litmus Operator가 클러스터 범위에서 ChaosEngine, Pod, Job 등을 감시하고 관리할 수 있도록 권한을 부여하는 파일이다.

`litmus-lease-role.yaml`은 `litmus` namespace 안에서 `leases`를 다루기 위한 Role이었다.  
하지만 이번 실험에서 Litmus Operator와 실험 대상 리소스는 서로 다른 namespace에 있었다.

```text
Litmus Operator: litmus namespace
Spring Boot 앱: default namespace
ChaosEngine: default namespace
```

따라서 Litmus Operator가 `default` namespace의 ChaosEngine과 Pod를 감시하려면 namespace 범위를 넘어서는 권한이 필요했다.

이 권한이 없을 때 다음 오류가 발생했다.

```text
pods is forbidden:
User "system:serviceaccount:litmus:litmus" cannot list resource "pods" at the cluster scope

chaosengines.litmuschaos.io is forbidden:
User "system:serviceaccount:litmus:litmus" cannot list resource "chaosengines" at the cluster scope
```

이를 해결하기 위해 `ClusterRole`과 `ClusterRoleBinding`을 사용했다.

주요 권한은 다음과 같다.

| 리소스 | 역할 |
| --- | --- |
| `pods`, `pods/log`, `pods/exec` | 대상 Pod 및 로그/exec 관련 작업 |
| `events` | 실험 이벤트 기록 |
| `services`, `configmaps`, `secrets` | 실험 실행에 필요한 기본 리소스 조회/수정 |
| `deployments`, `replicasets` | 대상 Workload 구조 파악 |
| `jobs` | Runner/Experiment Job 생성 및 관리 |
| `chaosengines` | ChaosEngine 감시 |
| `chaosexperiments` | 실험 정의 조회 |
| `chaosresults` | 실험 결과 관리 |
| `leases` | leader election 수행 |

정리하면 이 파일은 다음 역할을 한다.

```text
Litmus Operator가 namespace를 넘어 ChaosEngine을 감시하고,
대상 Pod를 식별하며, 실험 Runner Pod를 생성할 수 있도록
클러스터 범위 권한을 부여하는 파일
```

---

### 5-7. ServiceAccount 역할 구분

이번 실험에서 헷갈리기 쉬운 부분은 `litmus` ServiceAccount와 `spring-boot-app-kill-sa` ServiceAccount의 역할 차이다.

| ServiceAccount | Namespace | 사용하는 주체 | 역할 |
| --- | --- | --- | --- |
| `litmus` | `litmus` | Litmus Operator | ChaosEngine 감시, Runner Pod 생성 |
| `spring-boot-app-kill-sa` | `default` | spring-boot-app-kill 실험 Pod | 대상 Pod 조회, Chaos Monkey API 호출, ChaosResult 업데이트 |

즉, 두 계정은 역할이 다르다.

```text
Operator 권한 문제
→ ChaosEngine을 감지하지 못하거나 Runner Pod를 생성하지 못함

Experiment 권한 문제
→ Runner Pod는 생성되지만 대상 Pod 조회나 실험 실행 중 실패함
```

이번 실험에서 발생한 문제도 이 순서로 정리할 수 있다.

```text
Operator 권한 부족
→ Job/ChaosResult가 생성되지 않음

Experiment 권한 부족
→ ReplicaSet 조회 실패

Spring Boot 앱 설정 문제
→ /actuator/chaosmonkey 404

최종 해결 후
→ App Kill 실행
→ 대상 Pod RESTARTS 증가
```

---


## 6. Spring Boot 애플리케이션 구성

실험용 Spring Boot 애플리케이션에는 간단한 health check API와 결제 API를 구성했다.

```java
@RestController
public class PaymentController {

    @GetMapping("/healthz")
    public String healthz() {
        return "ok";
    }

    @GetMapping("/payment")
    public String payment() {
        return "payment done";
    }
}
```

`/payment`는 KBaaS의 금융 거래 API를 단순화한 엔드포인트로 가정했다.

---

## 7. Kubernetes Deployment / Service

Spring Boot 앱은 Deployment와 ClusterIP Service로 배포했다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: spring-boot-demo
spec:
  replicas: 2
  selector:
    matchLabels:
      app: spring-boot-demo
  template:
    metadata:
      labels:
        app: spring-boot-demo
    spec:
      terminationGracePeriodSeconds: 30
      containers:
        - name: spring-boot-demo
          image: spring-boot-chaos-demo:v3
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: spring-boot-demo-service
spec:
  selector:
    app: spring-boot-demo
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

배포 후 Pod 2개가 정상 실행되는 것을 확인했다.

```bash
kubectl get pods -l app=spring-boot-demo
```

결과:

```text
spring-boot-demo-5ff77f5cb-7642w   1/1   Running   0
spring-boot-demo-5ff77f5cb-z6brf   1/1   Running   0
```

---

## 8. Chaos Monkey endpoint 확인

LitmusChaos의 `spring-boot-app-kill` 실험은 대상 Spring Boot 애플리케이션의 Chaos Monkey Actuator endpoint를 사용한다.

따라서 실험 전에 다음 endpoint가 정상적으로 열려 있어야 한다.

```text
/actuator/chaosmonkey
```

클러스터 내부에서 `curl-test` Pod를 띄워 확인했다.

```bash
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
```

curl-test 내부에서 실행:

```bash
curl -i http://spring-boot-demo-service/actuator/chaosmonkey
```

결과:

```text
HTTP/1.1 200
```

이전에는 해당 endpoint가 `404`를 반환하여 Litmus 실험이 PreChaos 단계에서 실패했지만, 의존성 수정 후 `200 OK`로 정상 응답하는 것을 확인했다.

---

## 9. LitmusChaos Spring Boot App Kill 실행

사용한 ChaosEngine은 다음과 같다.

```yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: spring-boot-app-kill-chaos
  namespace: default
spec:
  engineState: active
  annotationCheck: "false"
  appinfo:
    appns: default
    applabel: "app=spring-boot-demo"
    appkind: deployment
  chaosServiceAccount: spring-boot-app-kill-sa
  jobCleanUpPolicy: retain
  experiments:
    - name: spring-boot-app-kill
      spec:
        components:
          env:
            - name: CM_PORT
              value: "8080"
            - name: CM_LEVEL
              value: "1"
            - name: CM_WATCHERS
              value: "restController"
            - name: PODS_AFFECTED_PERC
              value: "50"
            - name: SEQUENCE
              value: "serial"
```

적용 명령어:

```bash
kubectl apply -f spring-boot-app-kill-chaosengine.yaml
```

실험 실행 후 Runner Pod와 Experiment Pod가 생성되었다.

```bash
kubectl get pods
```

예시:

```text
spring-boot-app-kill-chaos-runner   1/1   Running
spring-boot-app-kill-0iezl6-s8ljm   0/1   Completed
```

---

## 10. 실험 로그

Experiment Pod 로그를 확인했다.

```bash
kubectl logs spring-boot-app-kill-0iezl6-s8ljm
```

주요 로그는 다음과 같다.

```text
[PreCheck]: Target pods list for chaos, [spring-boot-demo-5ff77f5cb-z6brf]
[PreCheck]: Checking for ChaosMonkey endpoint in target pods
[Check]: Checking pod: spring-boot-demo-5ff77f5cb-z6brf (endpoint: http://10.244.0.20:8080/actuator/chaosmonkey)
[Info]: Chaos monkeys watchers will be injected to the target pods as follows
WebClient=false Service=false Component=false Repository=false Controller=false RestController=true
[Chaos]: Injecting on target pod
[Chaos]: Setting Chaos Monkey watchers on pod: spring-boot-demo-5ff77f5cb-z6brf
[Chaos]: Setting Chaos Monkey assault on pod: spring-boot-demo-5ff77f5cb-z6brf
[Chaos]: Activating Chaos Monkey assault on pod: spring-boot-demo-5ff77f5cb-z6brf
```

이 로그를 통해 다음을 확인했다.

```text
대상 Pod 식별 성공
Chaos Monkey endpoint 확인 성공
RestController watcher 설정 성공
Assault 설정 성공
Runtime attack 호출 시도
```

마지막에는 다음과 같은 EOF 로그가 발생했다.

```text
Post "http://10.244.0.20:8080/actuator/chaosmonkey/assaults/runtime/attack": EOF
```

이는 Litmus가 runtime attack API를 호출하는 순간, 대상 Spring Boot 애플리케이션이 종료되면서 HTTP 응답을 정상적으로 반환하기 전에 연결이 끊긴 것으로 해석할 수 있다.

---

## 11. 실험 결과

가장 중요한 결과는 대상 Spring Boot Pod의 `RESTARTS` 값이 증가했다는 점이다.

실험 전:

```text
spring-boot-demo-5ff77f5cb-z6brf   1/1   Running   0
```

실험 후:

```text
spring-boot-demo-5ff77f5cb-z6brf   1/1   Running   1
```

실제 확인 결과:

```text
NAME                               READY   STATUS    RESTARTS        AGE
spring-boot-demo-5ff77f5cb-7642w   1/1     Running   0               9m8s
spring-boot-demo-5ff77f5cb-z6brf   1/1     Running   1 (2m46s ago)   9m31s
```

따라서 Spring Boot App Kill fault가 실제로 대상 Pod의 애플리케이션 프로세스를 종료시켰고, Kubernetes가 같은 Pod 안에서 컨테이너를 재시작한 것으로 볼 수 있다.

---

## 12. App Kill에서 RESTARTS가 중요한 이유

`spring-boot-app-kill`은 Pod 자체를 삭제하는 실험이 아니다.

이 실험은 Pod 안에서 실행 중인 Spring Boot 애플리케이션 프로세스를 종료시키는 방식에 가깝다.

흐름은 다음과 같다.

```text
Spring Boot App Kill
↓
Pod 안의 Spring Boot 프로세스 종료
↓
컨테이너 종료
↓
Kubernetes Kubelet이 컨테이너 종료 감지
↓
restartPolicy: Always에 따라 같은 Pod 안에서 컨테이너 재시작
↓
Pod의 RESTARTS 값 증가
```

따라서 Pod 이름이 바뀌는 것보다, 기존 Pod의 `RESTARTS` 값이 증가하는 것이 App Kill 성공의 직접적인 증거다.

이번 실험에서는 대상 Pod인 `spring-boot-demo-5ff77f5cb-z6brf`의 `RESTARTS`가 `0 → 1`로 증가했으므로 App Kill이 실제로 발생했다고 볼 수 있다.

---

## 13. Service 관점의 해석

이번 실험에서는 Spring Boot Pod를 2개로 구성했다.

```text
replicas: 2
```

따라서 하나의 Pod에서 App Kill이 발생하더라도, 다른 Pod가 계속 Running 상태로 남아 있었다.

```text
spring-boot-demo-5ff77f5cb-7642w   Running   RESTARTS 0
spring-boot-demo-5ff77f5cb-z6brf   Running   RESTARTS 1
```

이 구조에서는 Service가 정상 Pod로 트래픽을 전달할 수 있다.

즉, 사용자는 한 Pod의 애플리케이션 프로세스가 종료되는 상황에서도 서비스 전체 장애를 경험하지 않거나, 매우 짧은 순간의 영향만 받을 수 있다.

이전 Pod Delete Chaos 실험에서 확인했던 것처럼, replica가 1개일 때는 Pod 장애가 곧 서비스 중단으로 이어질 가능성이 높다. 반면 replica가 2개 이상이면 장애가 난 Pod 외의 정상 Pod가 트래픽을 처리할 수 있으므로 서비스 연속성이 높아진다.

---

## 14. Graceful Shutdown과의 관계

이번 App Kill 실험의 직접적인 관찰 지표는 `RESTARTS` 증가였다.

다만 운영 환경 관점에서는 단순히 컨테이너가 재시작되는 것만으로 충분하지 않다.

중요한 질문은 다음과 같다.

```text
앱이 종료될 때 기존 요청은 안전하게 마무리되는가?
종료 중인 Pod로 새 요청이 계속 들어가지는 않는가?
Kubernetes가 충분한 종료 유예 시간을 제공하는가?
```

Kubernetes에는 다음 설정이 있다.

```yaml
terminationGracePeriodSeconds: 30
```

이는 Pod 종료 시 Kubernetes가 컨테이너를 강제로 죽이기 전에 최대 30초까지 정상 종료를 기다려주는 설정이다.

Spring Boot에도 별도의 Graceful Shutdown 설정이 있다.

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

두 설정은 역할이 다르다.

| 구분                              | 역할                                        |
| ------------------------------- | ----------------------------------------- |
| `terminationGracePeriodSeconds` | Kubernetes가 강제 종료 전에 기다려주는 최대 시간          |
| Spring Boot Graceful Shutdown   | 애플리케이션이 종료 신호를 받았을 때 기존 요청을 마무리하고 종료하는 동작 |

즉, 안정적인 종료를 위해서는 Kubernetes의 종료 유예 시간과 Spring Boot의 graceful shutdown 설정이 함께 맞아야 한다.

---

## 15. KB국민은행 KBaaS 사례와의 연결

KB국민은행 KBaaS 사례에서 중요한 주제는 **금융 API 인프라의 안정성과 확장성**이었다.

KBaaS는 임베디드 금융 서비스를 제공하기 위해 API 기반 인프라를 운영한다. 이러한 시스템에서는 외부 플랫폼이나 고객이 금융 API를 호출하기 때문에, 일부 애플리케이션 인스턴스가 종료되더라도 전체 서비스가 안정적으로 유지되어야 한다.

특히 강연에서 언급된 핵심은 다음과 같다.

```text
Pod가 종료될 때 기존 거래 요청을 안전하게 마무리한다.
배포 중에도 서비스 중단을 최소화한다.
Graceful Shutdown을 적용한 Rolling 배포를 사용한다.
```

이번 실험은 이 내용을 기술적으로 이해하기 위한 실습이었다.

Spring Boot App Kill 실험을 통해 다음을 확인했다.

```text
Spring Boot 애플리케이션 프로세스 종료
→ 컨테이너 종료
→ Kubernetes가 종료 상태 감지
→ 같은 Pod에서 컨테이너 재시작
→ replica 수 유지
```

이는 금융 API 플랫폼에서 중요한 회복성 개념과 연결된다.

서비스 운영 관점에서 중요한 것은 개별 Pod를 절대 죽지 않게 만드는 것이 아니다. 실제 운영 환경에서는 배포, 장애, 노드 문제 등으로 애플리케이션 인스턴스는 언제든 종료될 수 있다.

따라서 중요한 것은 다음과 같다.

```text
하나의 인스턴스가 종료되어도 전체 서비스가 유지되는가?
종료 중인 요청을 안전하게 처리할 수 있는가?
Kubernetes가 원하는 상태를 회복할 수 있는가?
Service가 정상 인스턴스로 트래픽을 전달할 수 있는가?
```

이번 실험은 그중 **애플리케이션 인스턴스 종료와 Kubernetes 복구 흐름**을 확인한 실험이라고 정리할 수 있다.

---

## 16. 트러블슈팅 과정

이번 실험에서는 여러 문제를 순차적으로 해결했다.

| 문제                          | 원인                         | 해결                                |
| --------------------------- | -------------------------- | --------------------------------- |
| `ChaosExperiment` 인식 불가     | Litmus CRD 미설치             | Litmus CRD 설치                     |
| ChaosEngine은 생성됐지만 Job 미생성  | Chaos Operator 미동작         | Chaos Operator 설치                 |
| Operator Pod 생성 실패          | `litmus` ServiceAccount 없음 | ServiceAccount 생성                 |
| leader election 실패          | `leases` 권한 없음             | leases Role/RoleBinding 추가        |
| Operator가 ChaosEngine 감시 실패 | cluster scope 권한 부족        | ClusterRole/ClusterRoleBinding 추가 |
| target Pod 탐색 실패            | `replicasets` 조회 권한 없음     | 실험용 ServiceAccount에 apps 권한 추가    |
| `/actuator/chaosmonkey` 404 | Chaos Monkey 의존성/버전 문제     | 의존성 수정 후 endpoint 200 확인          |
| runtime attack 호출 시 EOF     | App Kill로 인한 연결 종료로 추정     | RESTARTS 증가로 실제 효과 확인             |

---

## 17. 최종 결론

이번 실험에서는 LitmusChaos의 `spring-boot-app-kill`을 사용하여 Spring Boot 애플리케이션 종료 상황을 주입했다.

실험 결과 대상 Pod의 `RESTARTS`가 `0 → 1`로 증가했다.

```text
spring-boot-demo-5ff77f5cb-z6brf   RESTARTS 0 → 1
```

따라서 Spring Boot 애플리케이션 프로세스가 종료되었고, Kubernetes가 컨테이너 종료를 감지해 재시작했음을 확인했다.

이번 실험은 다음을 보여준다.

```text
Spring Boot 애플리케이션 인스턴스는 장애나 실험에 의해 종료될 수 있다.
Kubernetes는 컨테이너 종료를 감지하고 restartPolicy에 따라 재시작할 수 있다.
replicas가 2개 이상이면 하나의 Pod에 문제가 생겨도 다른 Pod가 트래픽을 처리할 수 있다.
금융 API 플랫폼에서는 이러한 복구 흐름과 함께 Graceful Shutdown 설정을 적용해 기존 요청을 안전하게 마무리하는 것이 중요하다.
```

KB국민은행 KBaaS 사례와 연결하면, 이번 실험은 **Graceful Shutdown을 적용한 Rolling 배포 전략이 왜 중요한지**를 이해하는 데 도움이 되었다.

금융 API 인프라에서는 배포나 장애 상황에서 일부 인스턴스가 종료되는 일이 발생할 수 있다. 이때 중요한 것은 장애 자체를 완전히 없애는 것이 아니라, 장애가 발생해도 서비스가 유지되고 정상 상태로 빠르게 회복되는 구조를 만드는 것이다.

이번 실험은 그 회복성의 한 부분인 **애플리케이션 종료 후 Kubernetes의 컨테이너 재시작 및 서비스 유지 가능성**을 확인한 실험이다.
