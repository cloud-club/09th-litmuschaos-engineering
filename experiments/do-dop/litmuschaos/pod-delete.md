# LitmusChaos Pod Delete 실습 기록

## 실습 목적

LitmusChaos의 `pod-delete` 실험을 사용해 Kubernetes 애플리케이션의 Pod가 삭제되었을 때 복구되는 흐름을 관찰한다.

이번 실습은 LitmusChaos 공식 문서의 Pod Delete 실험 설명과 Podtato-head 튜토리얼을 기준으로 진행했다.

- Pod Delete 실험 문서: https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-delete/
- Podtato-head 튜토리얼: https://docs.litmuschaos.io/docs/3.13.0/tutorials/podtato-head

`pod-delete` 실험은 애플리케이션의 특정 Pod 또는 임의 Pod를 삭제해 replica 가용성, 서비스 지속성, 복구 동작을 확인하는 실험이다. 이번 실습에서는 LitmusChaos 플랫폼을 로컬 minikube 클러스터에 설치하고, 샘플 마이크로서비스 애플리케이션인 Podtato-head를 실험 대상으로 배포했다.

기본 환경 세팅 내용은 [LitmusChaos 기본 환경 세팅 기록](basic-environment-setup.md)으로 분리했다.

## 1. 실험 설계

 `pod-delete` 실험의 구성은 다음과 같다.

| 항목 | 설명 |
| --- | --- |
| 정상 상태 | `podtato-head-hat` Deployment가 Pod를 1개 이상 `Running` 상태로 유지한다. |
| 가설 | `podtato-head-hat` Pod가 삭제되어도 Kubernetes Deployment가 새 Pod를 생성해 실험 종료 시점에는 다시 `Running` 상태가 된다. |
| 장애 주입 | LitmusChaos `pod-delete` fault로 `podtato-kubectl` namespace의 `app=podtato-head-hat` 라벨을 가진 Deployment Pod를 삭제한다. |
| 관찰과 검증 | 실험 중 Pod가 `Terminating`, `Pending`, `ContainerCreating`, `Running` 순서로 재생성되는지 관찰하고, CMD Resilience Probe로 `Running` 상태의 `podtato-head-hat` Pod 개수가 1개 이상인지 검증한다. |
| 중단 조건 | 실험 대상이 아닌 Pod가 삭제되거나, `podtato-head-hat` Pod가 재생성되지 않거나, LitmusChaos 실행 컴포넌트가 비정상 상태가 되면 실험을 중단한다. |

이 실험의 핵심 검증 기준은 다음과 같다.

```text
Running 상태의 podtato-head-hat Pod 개수 > 0
```

## 2. Resilience Probe 생성

Chaos Infrastructure 연결 후, ChaosCenter UI에서 Infrastructure 상태가 `CONNECTED`가 된 것을 확인했다.

그 다음 `pod-delete` 실험 결과를 검증하기 위한 Resilience Probe를 생성했다. 이번 실습에서는 `podtato-head-hat` Pod가 실험 종료 시점에 다시 `Running` 상태인지 확인하기 위해 CMD Probe를 사용했다.

### 2.1 Probe 기본 정보

Probe Type은 `CMD`로 설정했다.

기본 정보는 다음과 같이 입력했다.

| 항목 | 값 |
| --- | --- |
| Probe Name | `check-podtato-head-hat-pod` |

이름을 입력한 뒤 `Configure Properties` 단계로 이동했다.

### 2.2 Probe Properties

Probe를 언제, 얼마나 자주, 몇 번 검사할지 설정했다.

| 항목 | 값 | 의미 |
| --- | --- | --- |
| Timeout | `10s` | 명령어 실행 제한 시간 |
| Interval | `1s` | 검사를 반복하는 간격 |
| Attempt | `1` | 재시도 횟수 |
| Polling Interval | `1s` | 상태를 확인하는 주기 |
| Initial Delay | 비워둠 | 첫 검사 전 대기 시간 |
| Stop On Failure | 체크 안 함 | 검사 실패 시 실험을 즉시 중단할지 여부 |

값을 입력한 뒤 `Configure Details` 단계로 이동했다.

### 2.3 Probe Details

검사에 사용할 명령어와 결과 판정 기준을 설정했다.

| 항목 | 값 |
| --- | --- |
| Command | `kubectl get pods -n podtato-kubectl \| grep podtato-head-hat \| grep Running \| wc -l` |
| Type | `Int` |
| Comparison Criteria | `>` |
| Value | `0` |

이 명령어는 `podtato-kubectl` namespace에서 `podtato-head-hat` 이름을 가진 Pod 중 `Running` 상태인 Pod 개수를 세는 명령어다.

판정 기준은 다음과 같다.

```text
Running 상태의 podtato-head-hat Pod 개수 > 0
```

즉 실험 종료 시점에 `podtato-head-hat` Pod가 하나 이상 `Running` 상태이면 Probe가 성공한다.

## 3. Pod Delete 실험 생성 및 실행

### 3.1 실험 기본 정보

ChaosCenter UI에서 새 실험을 생성했다.

기본 정보는 다음과 같이 설정했다.

| 항목 | 값 |
| --- | --- |
| Experiment Name | `podtato-head` |
| Infrastructure | `local` |
| 시작 방식 | `Blank Canvas` |

Blank Canvas에서 `pod-delete` fault를 추가했다.

### 3.2 pod-delete fault 대상 설정

`pod-delete` fault가 삭제할 대상 애플리케이션은 앞에서 라벨을 추가했던 `podtato-head-hat` Deployment로 설정했다.

| 항목 | 값 |
| --- | --- |
| App Kind | `deployment` |
| App Namespace | `podtato-kubectl` |
| App Label | `app=podtato-head-hat` |

이 설정은 `podtato-kubectl` namespace에서 `app=podtato-head-hat` 라벨을 가진 Deployment의 Pod를 대상으로 `pod-delete` 실험을 수행한다는 뜻이다.

### 3.3 Probe 연결

앞에서 만든 Resilience Probe를 `pod-delete` fault에 연결했다.

| 항목 | 값 |
| --- | --- |
| Probe Name | `check-podtato-head-hat-pod` |
| Probe Mode | `EOT` |

`EOT`는 End Of Test를 의미한다. 즉 카오스 주입이 끝난 뒤 Probe를 실행해 최종 상태를 검증한다.

이번 실습에서는 Pod가 삭제되는 순간 자체보다, 실험 종료 후 Deployment가 새 Pod를 만들고 `Running` 상태로 복구되었는지를 확인하는 것이 목적이므로 `EOT` 모드를 사용했다.

### 3.4 실험 실행 전 Litmus Pod 확인

실험 실행 전후로 `litmus` namespace의 Pod 상태를 확인했다.

```bash
kubectl get pods -n litmus
```

실행 결과:

```text
NAME                                        READY   STATUS    RESTARTS   AGE
chaos-exporter-54fbc884c8-psb5g             1/1     Running   0          7m29s
chaos-litmus-auth-server-6576749b77-gtbnn   1/1     Running   0          112m
chaos-litmus-frontend-cc4c59b46-qmnvt       1/1     Running   0          112m
chaos-litmus-server-7c88f5bc5d-7tlws        1/1     Running   0          112m
chaos-mongodb-0                             1/1     Running   0          112m
chaos-mongodb-1                             1/1     Running   0          111m
chaos-mongodb-2                             1/1     Running   0          110m
chaos-mongodb-arbiter-0                     1/1     Running   0          112m
chaos-operator-ce-7d9777db84-wj22n          1/1     Running   0          7m29s
event-tracker-5bc7ff94ff-l4zzj              1/1     Running   0          7m29s
subscriber-856888ddd4-8m2lq                 1/1     Running   0          7m15s
workflow-controller-5848497c75-xxxbq        1/1     Running   0          7m29s
```

Chaos Infrastructure를 연결한 뒤 `chaos-exporter`, `chaos-operator-ce`, `event-tracker`, `subscriber`, `workflow-controller` 같은 실험 실행 관련 컴포넌트가 `Running` 상태로 동작하고 있었다.

### 3.5 podtato-head-hat Pod 삭제 및 재생성 관찰

실험 실행 중 대상 Pod의 상태 변화를 관찰했다.

```bash
kubectl get pod -n podtato-kubectl -l app=podtato-head-hat -w
```

실행 결과:

```text
NAME                                READY   STATUS    RESTARTS   AGE
podtato-head-hat-8699f5d6d4-k446s   1/1     Running   0          103m
podtato-head-hat-8699f5d6d4-k446s   1/1     Terminating   0          106m
podtato-head-hat-8699f5d6d4-k446s   1/1     Terminating   0          106m
podtato-head-hat-8699f5d6d4-z2hcs   0/1     Pending       0          0s
podtato-head-hat-8699f5d6d4-z2hcs   0/1     Pending       0          0s
podtato-head-hat-8699f5d6d4-z2hcs   0/1     ContainerCreating   0          0s
podtato-head-hat-8699f5d6d4-z2hcs   0/1     Running             0          1s
podtato-head-hat-8699f5d6d4-z2hcs   1/1     Running             0          1s
podtato-head-hat-8699f5d6d4-z2hcs   1/1     Terminating         0          11s
podtato-head-hat-8699f5d6d4-z2hcs   1/1     Terminating         0          11s
podtato-head-hat-8699f5d6d4-tzld5   0/1     Pending             0          0s
podtato-head-hat-8699f5d6d4-tzld5   0/1     Pending             0          0s
podtato-head-hat-8699f5d6d4-tzld5   0/1     ContainerCreating   0          0s
podtato-head-hat-8699f5d6d4-tzld5   0/1     Running             0          2s
podtato-head-hat-8699f5d6d4-tzld5   1/1     Running             0          2s
```

관찰 결과, `pod-delete` 실험이 `podtato-head-hat` Pod를 삭제했고 Deployment가 새 Pod를 자동으로 생성했다.

흐름은 다음과 같았다.

```text
기존 Pod Running
→ 기존 Pod Terminating
→ 새 Pod Pending
→ 새 Pod ContainerCreating
→ 새 Pod Running
→ 새 Pod Ready
```

로그상에서 같은 흐름이 한 번 더 반복되었으므로, 실험 중 `podtato-head-hat` Pod가 여러 차례 삭제되고 다시 생성된 것을 확인할 수 있었다.

## 4. 실험 결과 확인

### 4.1 ChaosResult 목록 확인

실험 실행 후 생성된 ChaosResult를 확인했다.

```bash
kubectl get chaosresult -n litmus
```

실행 결과:

```text
NAME                             AGE
pod-delete-8s6s64jt-pod-delete   2m45s
```

### 4.2 ChaosResult 상세 확인

ChaosResult를 상세 조회했다.

```bash
kubectl describe chaosresult pod-delete-8s6s64jt-pod-delete -n litmus
```

주요 결과:

```text
Name:         pod-delete-8s6s64jt-pod-delete
Namespace:    litmus
API Version:  litmuschaos.io/v1alpha1
Kind:         ChaosResult
Spec:
  Engine:      pod-delete-8s6s64jt
  Experiment:  pod-delete
Status:
  Experiment Status:
    Phase:                     Completed
    Probe Success Percentage:  100
    Verdict:                   Pass
  History:
    Failed Runs:   0
    Passed Runs:   1
    Stopped Runs:  0
    Targets:
      Chaos Status:  targeted
      Kind:          deployment
      Name:          podtato-head-hat
  Probe Statuses:
    Mode:  EOT
    Name:  check-podtato-head-hat-pod
    Status:
      Description:  Actual value: '1'. Expected value: '0'
      Verdict:      Passed
    Type:           cmdProbe
Events:
  Type    Reason   Age    From                     Message
  ----    ------   ----   ----                     -------
  Normal  Awaited  4m     pod-delete-3z6l9a-zbtbg  experiment: pod-delete, Result: Awaited
  Normal  Pass     3m24s  pod-delete-3z6l9a-zbtbg  experiment: pod-delete, Result: Pass
```

실험 결과는 다음과 같이 해석할 수 있다.

| 항목 | 결과 | 의미 |
| --- | --- | --- |
| Phase | `Completed` | 실험이 정상 종료됨 |
| Verdict | `Pass` | 실험 판정이 성공임 |
| Probe Success Percentage | `100` | 연결한 Probe가 모두 성공함 |
| Target Kind | `deployment` | Deployment를 대상으로 실험함 |
| Target Name | `podtato-head-hat` | 대상 Deployment 이름 |
| Probe Mode | `EOT` | 실험 종료 시점에 Probe 실행 |
| Probe Type | `cmdProbe` | CMD Probe로 검증 |
| Probe Verdict | `Passed` | Probe 판정 성공 |

Probe 결과의 `Actual value: '1'. Expected value: '0'`는 실제 값이 `1`이고, 설정한 조건이 `> 0`이었기 때문에 성공했다는 의미다.

즉 실험 종료 시점에 `podtato-head-hat` Pod가 1개 이상 `Running` 상태였고, Deployment가 Pod 삭제 상황에서 정상적으로 새 Pod를 생성해 복구한 것을 확인했다.

이번 실습을 통해 LitmusChaos의 `pod-delete` 실험이 대상 Pod를 삭제하고, Kubernetes Deployment가 새 Pod를 생성해 복구하는 과정을 확인했다. 또한 CMD Resilience Probe를 통해 실험 종료 후 `podtato-head-hat` Pod가 다시 `Running` 상태인지 검증했고, 최종 실험 결과는 `Pass`로 기록되었다.
