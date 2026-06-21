### Resource Management

- 개념: 컨테이너의 CPU/메모리 사용량을 제한하고, Pod 스케줄링과 장애 상황에서의 동작에 영향을 주는 설정
- 링크: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
- 관련 개념: requests, limits, CPU hog, OOM

---

#### 1. 개념

Kubernetes의 Resource Management는 Pod 내부 컨테이너가 사용할 수 있는 CPU와 메모리 자원을 관리하는 기능이다.

컨테이너별로 `requests`와 `limits`를 설정하여 최소 필요 자원과 최대 사용 가능 자원을 정의할 수 있다. 이 설정은 Pod가 어떤 Node에 배치될지 결정하는 스케줄링 과정과, 실행 중 리소스 초과 상황에서 컨테이너가 어떻게 동작할지에 영향을 준다.

카오스 엔지니어링 관점에서는 CPU 과부하, 메모리 부족, 컨테이너 종료와 같은 장애 상황을 재현하고 관찰하기 위한 기본 개념이다.

---

#### 2. Requests

`requests`는 컨테이너가 실행되기 위해 필요한 최소 리소스 요구량이다.

Kubernetes Scheduler는 이 값을 기준으로 Pod를 배치할 Node를 선택한다. 예를 들어 컨테이너가 `cpu: 250m`, `memory: 64Mi`를 요청하면 해당 리소스를 제공할 수 있는 Node에만 Pod가 배치된다.

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "64Mi"
```

| 항목 | 설명 |
| --- | --- |
| CPU request | 컨테이너가 최소로 필요로 하는 CPU 양 |
| Memory request | 컨테이너가 최소로 필요로 하는 메모리 양 |
| 주요 영향 | Pod 스케줄링 기준으로 사용됨 |

`requests`는 최소 요구량일 뿐, 실제 사용량을 강제로 제한하는 값은 아니다.

---

#### 3. Limits

`limits`는 컨테이너가 사용할 수 있는 최대 리소스 제한값이다.

컨테이너가 설정된 limit을 초과하려고 하면 CPU와 메모리는 서로 다르게 동작한다.

```yaml
resources:
  limits:
    cpu: "500m"
    memory: "128Mi"
```

| 항목 | 설명 |
| --- | --- |
| CPU limit | 컨테이너가 사용할 수 있는 최대 CPU 양 |
| Memory limit | 컨테이너가 사용할 수 있는 최대 메모리 양 |
| 주요 영향 | 실행 중 리소스 사용량 제한 |

CPU limit을 초과하면 컨테이너가 바로 종료되지는 않고 CPU throttling이 발생할 수 있다. 반면 Memory limit을 초과하면 컨테이너가 OOMKilled될 수 있다.

---

#### 4. CPU Hog

CPU hog는 컨테이너가 CPU를 과도하게 사용하는 상황을 의미한다.

애플리케이션 버그, 무한 루프, 과도한 요청 처리 등으로 CPU 사용량이 급격히 증가할 수 있다. 이때 CPU limit이 설정되어 있으면 컨테이너의 CPU 사용량은 제한된다.

| 상황 | 결과 |
| --- | --- |
| CPU limit 이하 사용 | 정상 동작 |
| CPU limit 초과 시도 | CPU throttling 발생 |
| 지속적인 CPU throttling | 응답 지연, 처리량 감소 가능 |

카오스 엔지니어링에서는 CPU hog 상황을 통해 서비스가 CPU 과부하 상태에서도 계속 동작하는지, 응답 시간이 얼마나 증가하는지 확인할 수 있다.

---

#### 5. OOM

OOM은 Out Of Memory의 약자로, 컨테이너가 사용할 수 있는 메모리를 초과한 상황을 의미한다.

Kubernetes에서 컨테이너가 Memory limit을 초과하면 해당 컨테이너는 종료될 수 있으며, 이때 상태 정보에서 `OOMKilled`를 확인할 수 있다.

| 상황 | 결과 |
| --- | --- |
| Memory limit 이하 사용 | 정상 동작 |
| Memory limit 초과 사용 | OOMKilled 가능 |
| 반복적인 OOM 발생 | CrashLoopBackOff 가능 |

카오스 엔지니어링에서는 OOM 상황을 통해 메모리 부족 시 컨테이너가 어떻게 종료되고, 재시작 정책에 따라 서비스가 복구되는지 확인할 수 있다.

---

#### 6. 정리

Resource Management는 Kubernetes에서 컨테이너의 리소스 사용을 제어하고, 장애 상황의 영향을 제한하기 위한 기본 설정이다.

| 개념 | 핵심 역할 |
| --- | --- |
| requests | Pod 스케줄링에 사용되는 최소 리소스 요구량 |
| limits | 컨테이너 실행 중 최대 리소스 사용량 제한 |
| CPU hog | CPU 과다 사용으로 인한 성능 저하 상황 |
| OOM | 메모리 초과로 컨테이너가 종료될 수 있는 상황 |

CPU 문제는 주로 성능 저하로 나타나고, 메모리 문제는 컨테이너 종료로 이어질 수 있다. 따라서 카오스 실험에서는 CPU hog와 OOM을 구분하여 관찰하고, 모니터링 지표와 복구 동작을 함께 확인해야 한다.