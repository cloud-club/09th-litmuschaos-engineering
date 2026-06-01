# LitmusChaos Spring Boot App Kill 실험 정리

## 1. 실험 배경

<p align="center">
  <img width="60%" height="700" alt="image" src="https://github.com/user-attachments/assets/cafc6c2e-8185-4609-a4ee-842414875bcb" />
</p>



이번 실험은 2026 AWS Summit Seoul에서 들은 KB국민은행 KBaaS 사례를 바탕으로, Kubernetes 환경에서 Spring Boot 애플리케이션이 종료되는 상황을 카오스 엔지니어링 관점에서 검증하기 위해 진행했다.

KBaaS 사례에서 인상 깊었던 부분은 다음과 같다.

- KBaaS는 임베디드 금융 서비스를 제공하기 위해 API 기반 인프라를 중심으로 설계되었다.
- 금융 API 플랫폼에서는 안정적이고 확장 가능한 API 인프라가 핵심 경쟁력이다.
- KBaaS는 Cloud Native Architecture, API Gateway, EKS 기반 운영 구조를 활용하고 있다.
- Blue/Green, Canary 배포 방식도 검토했지만, 최종적으로는 Graceful Shutdown을 적용한 Rolling 배포 방식을 선택했다.
- Pod가 종료될 때 기존 거래 요청을 안전하게 마무리한 뒤 종료되도록 Graceful Shutdown을 적용하고 있다.

이 내용은 Kubernetes 환경에서 애플리케이션 인스턴스가 종료되는 상황을 단순 장애로 보는 것이 아니라, **정상적인 복구와 요청 보호가 가능한 구조인지 검증해야 한다**는 점과 연결된다.

따라서 이번 실험에서는 LitmusChaos의 `spring-boot-app-kill` 실험을 사용하여 Spring Boot 애플리케이션 종료 상황을 주입하고, Kubernetes가 이를 어떻게 감지하고 복구하는지 확인하고자 했다.

---

## 2. 실험 목적

이번 실험의 목적은 다음과 같다.

```text
Spring Boot 애플리케이션이 강제로 종료되는 상황에서
Kubernetes가 정상 replica 수를 회복하고,
Service가 정상 Pod로 트래픽을 전달할 수 있는지 확인한다.
```
세부적으로는 다음을 확인해보고자 했다.
1. LimusChaos가 Spring Boot 애플리케이션을 대상으로 App Kill 실험을 실행할 수 있는가?
2. App Kill 상황에서 대상 Pod 또는 컨테이너가 종료되는가?
3. Deployment/ReplicaSet이 새로운 Pod를 생성하여 원하는 replica 수를 회복하는가?
4. Service 요청은 장애 중에도 유지되거나 빠르게 복구되는가?
5. Spring Boot 애플리케이션 종료 시 Graceful Shutdown 로그가 관찰되는가?

---
## 3. KBaaS 사례와의 연결

KBaaS와 같은 금융 API 플랫폼에서는 장애 상황에서 단순히 서버가 다시 뜨는 것만으로는 충분하지 않다.

금융 거래 API는 다음 특성을 가진다.

- 요청 유실에 민감하다.
- 응답 지연과 실패가 사용자 경험 및 신뢰도에 직접 영향을 준다.
- 배포 중에도 기존 거래 요청을 안전하게 처리해야 한다.
- 특정 인스턴스가 종료되어도 전체 서비스는 계속 동작해야 한다.

KBaaS 사례에서 Graceful Shutdown과 Rolling 배포가 중요했던 이유도 이와 같다.

```text
Pod 종료
→ 기존 요청 마무리
→ 정상 종료
→ 새 Pod 생성
→ Service 트래픽 복구
```
이번 Spring Boot App Kill 실험은 위 흐름을 LitmusChaos 기반으로 재현하고자 한 실험이다.

---
## 4. 실험 구성
실험 구성은 다음과 같다.
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
Spring Boot App 종료 시도
```
---
## 5. 실험 환경
| 항목               | 내용                                              |
| ---------------- | ----------------------------------------------- |
| 로컬 클러스터          | kind                                            |
| Kubernetes 실험 도구 | LitmusChaos                                     |
| 애플리케이션           | Spring Boot                                     |
| Java             | 17                                              |
| Spring Boot      | 3.5.14                                          |
| Chaos Monkey     | `de.codecentric:chaos-monkey-spring-boot:2.6.1` |
| 컨테이너 이미지         | `spring-boot-chaos-demo`                        |
| 대상 Deployment    | `spring-boot-demo`                              |
| 대상 Service       | `spring-boot-demo-service`                      |
| replica 수        | 2                                               |

---
## 6. Spring Boot 애플리케이션 구성
### 6-1. Controller
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
`payment`는 KBaaS의 금융 거래 API를 단순화한 엔드포인트로 가정한다.

---
### 6-2. application.yaml
```yaml
server:
  port: 8080

spring:
  application:
    name: spring-boot-chaos-demo
  profiles:
    active: chaos-monkey

management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    chaosmonkey:
      enabled: true
    chaosmonkeyjmx:
      enabled: true

chaos:
  monkey:
    enabled: true
    watcher:
      restController: true
      controller: true
      service: true
      repository: false
      component: false
    assaults:
      level: 1
      latencyActive: false
      exceptionsActive: false
      killApplicationActive: true
```
---
### 6-3. build.gradle
```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.14'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-web'

    implementation 'de.codecentric:chaos-monkey-spring-boot:2.6.1'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```
---
## 7. Docker 이미지 생성
Spring Boot 애플리케이션을 빌드한 뒤 Docker 이미지로 생성
(명령어는 하기 참고)
```text
gradlew.bat clean bootJar -x test

docker build -t spring-boot-chaos-demo:latest .
```
이전 실험과 동일한 방식으로, kind 클러스터에서 로컬 Docker 이미지를 사용할 수 있도록 이미지를 kind node에 로드
```text
kind load docker-image spring-boot-chaos-demo:latest --name pod-chaos
```

---
## 8. Kubernetes 배포
### 8-1. Deployment / Service Manifest
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
          image: spring-boot-chaos-demo:latest
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

배포 명령어
```text
kubectl apply -f spring-boot-demo.yaml
```

Pod 확인
```text
kubectl get pods -l app=spring-boot-demo
```

**결과**
```text
spring-boot-demo-698794f6bc-8fpq2   1/1   Running
spring-boot-demo-698794f6bc-n8vlg   1/1   Running
```

Service 확인
```text
kubectl get svc spring-boot-demo-service
```

---
## 9. Service 호출 테스트
ClusterIP Service는 클러스터 내부에서만 접근 가능하므로 테스트용 curl Pod 생성
```text
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
```

curl-test 내부에서 호출

<img width="60%" height="724" alt="image" src="https://github.com/user-attachments/assets/3ec61efc-9ad5-421b-8d93-a2af6c833f6f" />


Spring Boot 애플리케이션과 Service 연결은 정상 동작 확인

---

## 10. LitmusChaos 설치 및 구성 과정
초기에는 Litmus UI Pod는 정상적으로 떠 있었지만, `ChaosExperiment`, `ChaosEngine`, `ChaosResult` CRD가 설치되어 있지 않아 다음 오류가 발생했다.
```text
no matches for kind "ChaosExperiment" in version "litmuschaos.io/v1alpha1"
ensure CRDs are installed first
```
그래서 Litmus CRD와 Chaos Operator를 추가 설치했다.

이후 확인 결과 다음 CRD가 생성되었다.
```text
kubectl get crds | findstr litmus

chaosengines.litmuschaos.io
chaosexperiments.litmuschaos.io
chaosresults.litmuschaos.io
```
---
## 11. Chaos Operator 권한 문제 해결
Chaos Operator 설치 후에도 바로 실험이 실행되지는 않았다.

첫 번째 문제는 ServiceAccount가 없어 Operator Pod가 생성되지 않는 것이었다.
```text
serviceaccount "litmus" not found
```

그래서 ServiceAccount를 생성했고
```text
kubectl create serviceaccount litmus -n litmus
```

이후 Operator는 실행되었지만 leader election 과정에서 `leases` 권한이 없어 실패했다.
```text
leases.coordination.k8s.io "chaos-operator.lock" is forbidden
```

이를 해결하기 위해 권한을 추가해줬고
```text
leases.coordination.k8s.io "chaos-operator.lock" is forbidden
```
추가 이후 Operator는 leader lease를 획득했다.
```text
successfully acquired lease litmus/chaos-operator.lock
```

그 다음에는 cluster scope에서 `pods`, `chaosengines`를 list/watch할 권한이 없어 ChaosEngine을 감시하지 못했다.
```text
pods is forbidden
chaosengines.litmuschaos.io is forbidden
```
그래서 ClusterRole과 ClusterRoleBinding을 추가했고

이후 Operator 로그에서 다음과 같은 정상 흐름이 확인되었다.
```text
Starting Controller
Starting workers
Reconciling ChaosEngine
Targets derived from Chaosengine is "deployment:default:[app=spring-boot-demo]:union"
Creating a new engineRunner Pod
engineRunner Pod created successfully
```

---
## 12. Spring Boot App Kill ChaosEngine
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

ChaosEngine 적용:
```text
kubectl apply -f spring-boot-app-kill-chaosengine.yaml
```
---

## 13. 실험 실행 과정
ChaosEngine 적용 후 Runner Pod와 Experiment Pod가 생성되었다.

```text
kubectl get pods

spring-boot-app-kill-chaos-runner   1/1   Running
spring-boot-app-kill-zuuzai-wkgp8   1/1   Running
```

ChaosResult도 생성되었다.
```text
kubectl get chaosresult

spring-boot-app-kill-chaos-spring-boot-app-kill
```
즉, LitmusChaos가 ChaosEngine을 감지하고, 실제 실험 Pod를 실행하는 단계까지는 성공했다.

---
## 14. 실험 결과

실험 Pod 로그를 확인했다.
```text
kubectl logs spring-boot-app-kill-zuuzai-wkgp8
```

주요 로그는 다음과 같았다.

```text
[PreCheck]: Target pods list for chaos, [spring-boot-demo-698794f6bc-n8vlg]
[PreCheck]: Checking for ChaosMonkey endpoint in target pods
[Check]: Checking pod: spring-boot-demo-698794f6bc-n8vlg (endpoint: http://10.244.0.17:8080/actuator/chaosmonkey)
failed to get chaos monkey endpoint on pod spring-boot-demo-698794f6bc-n8vlg (status: 404)
```
ChaosResult도 다음처럼 `Error`로 기록되었다..

```text
kubectl describe chaosresult spring-boot-app-kill-chaos-spring-boot-app-kill

Phase: Error
Verdict: Error
Reason: failed to check chaos monkey on at least one pod
```

Litmus App Kill 실험에서 Spring Boot Pod를 찾고, 실험 Pod를 실행하는 단계까지는 성공했지만, 대상 Spring Boot 애플리케이션의 `/actuator/chaosmonkey` endpoint가 404를 반환하며 PreChaos 단계에서 중단되었다.

---

## 15. 원인 분석

<p align="center">
  <img width="50%" height="50%" alt="image" src="https://github.com/user-attachments/assets/6da34f67-206f-4ea0-b999-ba468e80e37a" />
</p>

현재 프로젝트는 다음 조합을 사용하고 있다.

```text
Spring Boot: 3.5.14
Chaos Monkey for Spring Boot: 2.6.1
```
그 결과 `/actuator/chaosmonkey` endpoint가 정상적으로 등록되지 않았다.
직접 확인한 결과도 404였다.
```text
curl -i http://spring-boot-demo-service/actuator/chaosmonkey

HTTP/1.1 404
```

그래서 LimusChaos 실행 인프라 문제는 해결되었지만, Spring Boot 3.x 환경에서 Chaos Monkey 2.6.1 의존성이 맞지 않아
Chaos Monkey Actuator endpoint가 등록되지 않았다.

> 의존성 수정해서 실행해보면 되잖아요! -> 실험하다가 시간이 부족해서 시도해보지 못했습니다 ㅠㅠ (to be continued....)

---

## 최종 실험 상태
| 구분                       | 결과    |
| ------------------------ | ----- |
| Spring Boot 앱 배포         | 성공    |
| Service 호출               | 성공    |
| Litmus CRD 설치            | 성공    |
| Chaos Operator 실행        | 성공    |
| ChaosEngine 생성           | 성공    |
| Runner Pod 생성            | 성공    |
| Experiment Pod 생성        | 성공    |
| ChaosResult 생성           | 성공    |
| 대상 Pod 탐색                | 성공    |
| Chaos Monkey endpoint 확인 | 실패    |
| 최종 Verdict               | Error |
| App Kill 실제 주입           | 미완료   |

---
## 17. KBaas 사례와 연결한 결론
KBaaS 사례에서 중요한 것은 API 기반 금융 거래를 안정적으로 처리하는 것이다.

특히 금융 플랫폼에서는 다음 상황이 자주 발생할 수 있다.
```text
배포 중 인스턴스 종료
일부 Pod 장애
트래픽 증가
API Gateway 또는 백엔드 서비스 장애
```
이때 중요한 것은 개별 Pod를 살리는 것이 아닌, Pod가 종료되더라도 다음이 보장되는 구조이다.
```text
기존 요청을 안전하게 마무리한다.
정상 Pod로 트래픽을 우회한다.
Deployment가 원하는 replica 수를 복구한다.
장애가 전체 서비스로 확산되지 않도록 한다.
```
이번 실험은 LitmusChaos의 Spring Boot App Kill을 사용해 이러한 상황을 검증하려고 한 시도였다.

최종적으로 Chaos Monkey endpoint 문제로 App Kill 주입 자체는 완료하지 못했지만, 다음 흐름까지는 확인했다.
```text
ChaosEngine 생성
→ Chaos Operator 감지
→ 대상 Deployment 식별
→ Runner Pod 생성
→ Experiment Pod 생성
→ ChaosResult 생성
→ 대상 Spring Boot Pod 탐색
```

따라서 이번 실험은 KBaaS 사례에서 언급된 Graceful Shutdown과 Rolling 배포의 중요성을 기술적으로 이해하는 데 도움이 되었다.

특히 운영 환경에서는 단순히 애플리케이션을 Kubernetes에 올리는 것만으로는 부족하고, 배포/종료/장애 상황에서도 요청이 안전하게 처리되는지 카오스 실험을 통해 검증해야 한다는 점을 확인했다.

---
## 18. 다음 주차에서의 개선 방향
실험을 완성하려면 다음 개선이 필요하다.

1. Spring Boot 3.5.x에 맞는 Chaos Monkey 3.x 계열 의존성 변경
2. /actuator/chaosmonkey endpoint가 200 OK로 응답하는지 먼저 확인
3. 이후 Litmus spring-boot-app-kill 실험을 다시 실행한다.
4. ChaosResult가 Pass로 변경되는지 확인한다.
5. 대상 Spring Boot Pod의 RESTARTS 증가 여부를 확인한다.
6. curl 반복 요청을 통해 장애 중 서비스 응답이 유지되는지 확인한다.
