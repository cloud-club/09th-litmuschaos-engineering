# 20260620 litmus study

# 05. HTTP 응답 지연 장애의 단계적 확산과 방어 시나리오 (LitmusChaos)

## 1. 시나리오 목적

LitmusChaos의 `pod-http-latency` 실험을 사용하여 backend Pod의 HTTP 응답에 지연(2초)을 주입한다.

단일 실험이 아니라, 응답 지연이 점진적으로 확산되는 상황을 5단계로 가정하고, 각 단계에서 Service와 Probe가 어떻게 반응하는지 확인한다.

핵심 질문은 다음과 같다.

> 응답이 느려지는 장애(지연)는 Pod가 죽는 장애와 어떻게 다른가? Probe와 replica는 이를 막아낼 수 있는가?

4주차 실험(HTTP 500 주입)에서 "잘못된 응답 코드는 Probe가 감지해 격리한다"는 것을 확인했다. 이 시나리오는 그 후속으로, 지연 장애가 Probe 설정에 따라 어떻게 다르게 처리되고, 장애가 어떻게 확산되며, 무엇으로 방어할 수 있는지를 5단계로 검증한다.

---

## 2. 가정한 실제 장애 상황

실제 운영 환경에서 다음 상황을 가정한다.

backend 서비스 인스턴스 하나가 외부 의존성(외부 API, 느린 DB 쿼리 등)으로 인해 응답이 느려졌다. 해당 Pod는 죽지 않았고 HTTP 200도 정상적으로 반환한다. 따라서 기본 health check는 통과할 수 있다. 그러나 사용자 일부는 로딩이 느리다고 불만을 제기한다.

이 상황이 방치되면 트래픽이 몰리며 지연이 확산되고, 일부 Pod가 부하를 견디지 못해 추가 장애로 번질 수 있다.

---

## 3. 시나리오 가설

- **단계 2 (엄격한 Probe, timeout 1초)**: 2초 지연이 Probe timeout(1초)을 초과하여 Probe가 실패한다. 그 결과 느린 Pod가 재시작·격리되고, 클라이언트는 정상 Pod로만 라우팅되어 지연을 거의 느끼지 못할 것이다.
- **단계 3 (느슨한 Probe, timeout 5초)**: 2초 지연이 Probe timeout(5초) 안에 들어와 Probe가 통과한다. 느린 Pod가 격리되지 않고 트래픽을 계속 받아, 클라이언트가 2초 지연을 직접 경험할 것이다.
- **단계 4 (Pod 삭제)**: 느린 Pod가 있는 상태에서 정상 Pod가 줄어들면, 느린 Pod로 라우팅될 확률이 높아져 지연 체감이 악화될 것이다.
- **단계 5 (replica 증설)**: replica를 늘리면 느린 Pod 1개의 영향이 희석되어 지연 체감이 완화될 것이다.

---

## 4. 실험 환경

### 4-1. 대상 애플리케이션

- Go로 작성한 backend 서버 (`service-demo:v1`)
- Deployment `service-demo` (replicas: 3)
- Service `service-demo-service` (ClusterIP, port 80 → targetPort 8080)
- 클라이언트: `curl-test` Pod (상주)
- Namespace: `chaos-demo` (이전 실험 week4-service와 환경 분리를 위해 새 namespace 사용)

### 4-2. 클러스터

- minikube (`k8s-practice` 프로파일)
- Driver: docker
- Container Runtime: docker
- Kubernetes v1.30.0

---

## 5. LitmusChaos 설치

### 5-1. ChaosExperiment 설치

이 시나리오는 두 가지 장애 도구를 사용한다. `pod-http-latency`(응답 지연)와 `pod-delete`(Pod 삭제)를 설치한다.

```powershell
kubectl apply -n chaos-demo -f https://hub.litmuschaos.io/api/chaos/master?file=faults/kubernetes/pod-http-latency/fault.yaml
kubectl apply -n chaos-demo -f https://hub.litmuschaos.io/api/chaos/master?file=faults/kubernetes/pod-delete/fault.yaml
```

실행 결과:

```
chaosexperiment.litmuschaos.io/pod-http-latency created
chaosexperiment.litmuschaos.io/pod-delete created
```

확인:

```powershell
kubectl get chaosexperiments -n chaos-demo
```

실행 결과:

```
NAME               AGE
pod-delete         5s
pod-http-latency   8s
```

### 5-2. RBAC 설치

실험용 ServiceAccount, Role, RoleBinding을 생성한다 (`chaos-sa`). latency와 delete 두 실험이 공용으로 사용한다.

```powershell
kubectl apply -f manifests/chaos-rbac-latency.yaml
```

실행 결과:

```
serviceaccount/chaos-sa created
role.rbac.authorization.k8s.io/chaos-sa created
rolebinding.rbac.authorization.k8s.io/chaos-sa created
```

---

## 6. 카오스 설정 (ChaosEngine)

`pod-http-latency` 실험은 대상 Pod 내부에 프록시 서버를 띄우고, 트래픽을 프록시로 우회시켜 응답에 지연을 추가한다.

```yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: latency-full
  namespace: chaos-demo
spec:
  engineState: "active"
  annotationCheck: "false"
  appinfo:
    appns: "chaos-demo"
    applabel: "app=service-demo"
    appkind: "deployment"
  chaosServiceAccount: chaos-sa
  experiments:
  - name: pod-http-latency
    spec:
      components:
        env:
        - name: TARGET_SERVICE_PORT      # Pod 컨테이너 포트 (Service 포트 80이 아님)
          value: "8080"
        - name: PROXY_PORT
          value: "20000"
        - name: LATENCY                  # 주입할 지연 시간 (ms)
          value: "2000"
        - name: TOXICITY                 # 영향받는 요청 비율 (%)
          value: "100"
        - name: TOTAL_CHAOS_DURATION     # 장애 지속 시간 (초)
          value: "90"
        - name: CONTAINER_RUNTIME        # 본 환경은 docker 드라이버
          value: "docker"
        - name: SOCKET_PATH
          value: "/var/run/docker.sock"
```

핵심 설정:

- `TARGET_SERVICE_PORT: 8080` — Service 레벨 포트(80)가 아니라 Pod 컨테이너가 실제 listen하는 포트를 지정해야 한다.
- `LATENCY: 2000` — 응답에 2초 지연을 추가한다.
- `CONTAINER_RUNTIME: docker` / `SOCKET_PATH: /var/run/docker.sock` — minikube docker 드라이버 환경에 맞춘 설정.
- `PODS_AFFECTED_PERC` 미지정 — 기본값에 따라 Pod 중 1개만 대상이 된다.

---

## 7. 단계 1: 정상 상태 (Baseline)

평상시 서비스가 어떻게 동작하는지 기준을 측정한다. 이후 모든 단계는 이 기준과 비교한다.

지연 실험이므로 응답 시간(`%{time_total}`)을 측정한다.

```powershell
1..15 | ForEach-Object {
  $t = kubectl exec curl-test -n chaos-demo -- curl -s -o /dev/null -w "%{time_total}" --max-time 5 http://service-demo-service
  Write-Host "요청 $_`t응답시간: ${t}s"
  Start-Sleep -Milliseconds 500
}
```

실행 결과 (일부):

```
요청 1  응답시간: 0.004778s
요청 2  응답시간: 0.001822s
요청 3  응답시간: 0.001459s
...
요청 15 응답시간: 0.001171s
```

응답 시간이 전부 약 0.001~0.005초(1~5ms)로 빠르다. 평균 약 1.5ms. 이것이 정상 상태 기준값이며, 이후 지연 주입 시 이 값이 2초대로 뛰는 것을 비교한다.

---

## 8. 단계 2: 엄격한 Probe (timeout 1초)에서 지연 주입

기본 Probe 설정은 `timeoutSeconds: 1`이다.

```
Liveness:   http-get http://:8080/healthz delay=5s timeout=1s period=5s #success=1 #failure=3
Readiness:  http-get http://:8080/ready delay=2s timeout=1s period=2s #success=1 #failure=3
```

이 상태에서 2초 지연을 주입했다.

```powershell
kubectl apply -f manifests/chaos-engine-latency-full.yaml
```

### 8-1. 관찰 결과

응답 시간을 관찰한 결과, 클라이언트 요청은 대부분 빠른 응답(1~2ms)만 반환되었다. 2초 지연이 거의 관측되지 않았다.

그러나 대상 Pod 상태를 확인하니 다음과 같았다.

```powershell
kubectl get pods -l app=service-demo -n chaos-demo -o wide
```

실행 결과:

```
NAME                            READY   STATUS    RESTARTS
service-demo-5fb9464d7c-f8bfq   1/1     Running   4
```

ChaosResult:

```
Phase:           Completed
Verdict:         Pass
Chaos Status:    reverted
Name:            service-demo-5fb9464d7c-f8bfq
```

대상 Pod `f8bfq`가 **4회 재시작**되었다.

### 8-2. 해석

2초 지연 주입 → Probe(`/healthz`, `/ready`)도 응답이 2초 느려짐 → Probe timeout(1초)을 초과 → Probe 실패.

그 결과:

- Liveness 실패 → 컨테이너 재시작 (RESTARTS 4)
- Readiness 실패 → Service의 EndpointSlice에서 느린 Pod 제외

결국 Service는 정상 Pod로만 트래픽을 전달했고, 클라이언트는 지연을 거의 느끼지 못했다.

> 2초 지연이 Probe timeout(1초)을 넘으면서, Probe가 느린 Pod를 감지해 자동으로 격리·재시작했다. 클라이언트는 지연의 영향을 거의 받지 않았다.

---

## 9. 단계 3: 느슨한 Probe (timeout 5초)에서 지연 주입

단계 2에서 Probe가 지연을 잡은 이유는 timeout(1초)이 지연(2초)보다 짧았기 때문이다. 운영 환경에서는 Probe timeout을 넉넉하게 설정하는 경우가 흔하다. 이를 재현하기 위해 Probe timeout을 5초로 늘렸다.

```powershell
kubectl patch deployment service-demo -n chaos-demo --type=json -p '[{"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe","value":{"httpGet":{"path":"/healthz","port":8080},"initialDelaySeconds":5,"periodSeconds":5,"timeoutSeconds":5}},{"op":"replace","path":"/spec/template/spec/containers/0/readinessProbe","value":{"httpGet":{"path":"/ready","port":8080},"initialDelaySeconds":2,"periodSeconds":5,"timeoutSeconds":5}}]'
```

변경 확인:

```
Liveness:   http-get http://:8080/healthz delay=5s timeout=5s period=5s #success=1 #failure=3
Readiness:  http-get http://:8080/ready delay=2s timeout=5s period=5s #success=1 #failure=3
```

timeout이 5초가 되었으므로, 2초 지연은 Probe를 통과한다.

### 9-1. 장애 주입 및 관찰

ChaosEngine을 다시 적용하고, 프록시 준비를 위해 15초 대기 후 관찰했다.

```powershell
kubectl apply -f manifests/chaos-engine-latency-full.yaml
Start-Sleep -Seconds 15

1..25 | ForEach-Object {
  $t = kubectl exec curl-test -n chaos-demo -- curl -s -o /dev/null -w "%{time_total}" --max-time 8 http://service-demo-service
  Write-Host "$(Get-Date -Format 'HH:mm:ss')  요청 $_`t응답시간: ${t}s"
  Start-Sleep -Seconds 2
}
```

실행 결과 (발췌):

```
12:34:43  요청 1   응답시간: 0.001426s
...
12:34:56  요청 6   응답시간: 2.002630s   ← 2초 지연 등장
12:35:00  요청 7   응답시간: 2.002842s
12:35:04  요청 8   응답시간: 2.002060s
12:35:08  요청 10  응답시간: 0.001656s
12:35:13  요청 11  응답시간: 2.002660s
12:35:17  요청 12  응답시간: 2.005077s
...
12:35:43  요청 22  응답시간: 2.002546s
12:35:49  요청 24  응답시간: 2.002938s
```

총 25회 중 **8회가 2초 지연 (약 32%)**, 나머지 17회는 빠른 응답이었다. 3개 Pod 중 1개가 대상이므로 이론값 33%에 거의 일치한다.

### 9-2. 관찰 결과

```
NAME                            READY   STATUS    RESTARTS
service-demo-59c664fb76-n2bpr   1/1     Running   0
service-demo-59c664fb76-nssqm   1/1     Running   0
service-demo-59c664fb76-qxgmn   1/1     Running   0
```

ChaosResult Verdict는 Pass였고, 대상 Pod의 **재시작 횟수는 0회**였다. 단계 2(재시작 4회)와 명확히 대비된다.

### 9-3. 해석

2초 지연이 Probe timeout(5초) 안에 들어와 Probe가 통과했다.

그 결과:

- Liveness 통과 → 재시작 없음 (RESTARTS 0)
- Readiness 통과 → Service가 느린 Pod를 계속 트래픽 대상으로 유지

결국 느린 Pod가 끝까지 EndpointSlice에 남아, 그 Pod로 라우팅된 요청(약 32%)은 클라이언트가 2초 지연을 그대로 경험했다.

> Probe가 있어도 timeout이 지연보다 길면 지연 장애를 감지하지 못한다. 느린 Pod가 트래픽을 계속 받아 사용자가 지연을 직접 경험한다. Probe의 존재 여부보다 timeout 설정이 지연 장애 대응을 좌우한다.

---

## 10. 단계 4: Pod 삭제로 장애 확산

느린 Pod가 있는 상태(느슨한 Probe)에서, 정상 Pod 하나를 추가로 삭제하여 연쇄 장애를 재현했다.

지연을 다시 주입하고, 관찰 도중 정상 Pod 하나(`qxgmn`)를 삭제했다.

```powershell
# 관찰 중 별도 창에서 실행
kubectl delete pod service-demo-59c664fb76-qxgmn -n chaos-demo
kubectl get endpointslice -n chaos-demo -o wide
```

### 10-1. 관찰 결과

관찰 로그 (발췌, 삭제는 요청 13~14 근처):

```
12:43:49  요청 5   응답시간: 2.002662s
12:43:53  요청 6   응답시간: 2.002403s
12:43:55  요청 7   응답시간: 0.001273s
...
12:44:04  요청 10  응답시간: 2.001659s
12:44:08  요청 11  응답시간: 2.002026s
12:44:12  요청 12  응답시간: 2.003339s
12:44:21  요청 15  응답시간: 2.005442s
12:44:27  요청 17  응답시간: 2.002399s
12:44:31  요청 18  응답시간: 2.003210s
12:44:36  요청 19  응답시간: 2.002163s
12:44:42  요청 21  응답시간: 2.001936s
12:44:46  요청 22  응답시간: 2.001639s
12:44:53  요청 24  응답시간: 2.002246s
12:44:57  요청 25  응답시간: 2.002077s
12:45:01  요청 26  응답시간: 2.001625s
12:45:03  요청 27  응답시간: 0.001241s   ← 회복 시작
```

지연 비율 변화:

- 삭제 전 구간 (요청 1~13): 2초 지연 약 31%
- 삭제 후 구간 (요청 14~26): 2초 지연 약 69%

**정상 Pod가 줄어들자 지연 비율이 31%에서 69%로 약 2배 증가했다.**

EndpointSlice 변화:

```
삭제 전: 10.244.0.47, 10.244.0.48, 10.244.0.49
삭제 후: 10.244.0.47, 10.244.0.49, 10.244.0.56   ← .48 제거, .56 신규
```

### 10-2. 해석

정상 Pod 하나가 삭제되자, 남은 Pod 중 느린 Pod의 비중이 상대적으로 커졌다.

- 삭제 전: 느린 1 / 정상 2 → 약 33% 확률로 지연
- 삭제 직후: 느린 1 / 정상 1 → 약 50% 확률로 지연 + 새 Pod 생성 중 트래픽 집중

또한 Deployment의 self-healing이 작동하여 새 Pod(`.56`)가 자동 생성되었고, EndpointSlice가 이를 자동 반영했다. 삭제된 Pod IP는 빠지고 새 Pod IP가 추가되었다.

> 느린 Pod가 있는 상태에서 정상 Pod가 줄어들면, 느린 Pod로 라우팅될 확률이 높아져 장애 체감이 악화된다. 단일 Pod의 지연이 정상 Pod 감소와 맞물려 연쇄 장애로 번질 수 있다.

---

## 11. 단계 5: Replica 증설로 방어

같은 지연 조건에서 replica 수를 늘렸을 때 서비스가 더 잘 견디는지 확인했다.

replica를 3에서 6으로 증설했다.

```powershell
kubectl scale deployment service-demo -n chaos-demo --replicas=6
```

Pod 6개가 Running 상태가 된 후, 같은 지연을 다시 주입하고 관찰했다.

### 11-1. 관찰 결과

실행 결과 (발췌):

```
12:51:39  요청 1   응답시간: 0.001595s
...
12:52:05  요청 12  응답시간: 2.003161s   ← 2초 지연
...
12:52:24  요청 20  응답시간: 2.002939s   ← 2초 지연
...
12:52:35  요청 25  응답시간: 0.001113s
```

총 25회 중 **2회만 2초 지연 (약 8%)**, 나머지 23회는 빠른 응답이었다.

### 11-2. 해석

Pod가 6개이고 대상은 여전히 1개이므로, 느린 Pod의 비율은 1/6(약 17%)로 줄었다. 실측 8%는 표본이 적어 생긴 편차이지만, 단계 3(32%)과 비교하면 지연 비율이 약 4분의 1 수준으로 감소했다.

> replica를 늘리면 느린 Pod 1개의 영향이 정상 Pod들에 희석되어 사용자 지연 체감이 완화된다. replica 증설은 지연 장애의 영향을 줄이는 완화책이 될 수 있다.

---

## 12. 단계별 비교

| 단계 | 조건 | replica | 대상 Pod 재시작 | 2초 지연 비율 |
| --- | --- | --- | --- | --- |
| 1. 정상 | baseline | 3 | - | 0% (평균 1.5ms) |
| 2. 엄격한 Probe | timeout 1초 | 3 | 4회 (격리됨) | 거의 0% (Probe가 차단) |
| 3. 느슨한 Probe | timeout 5초 | 3 | 0회 (유지됨) | 약 32% |
| 4. Pod 삭제 | timeout 5초 + Pod 삭제 | 3 | 0회 | 31% → 69% (악화) |
| 5. Replica 증설 | timeout 5초 | 6 | 0회 | 약 8% (완화) |

같은 2초 지연을 주입했지만, Probe timeout 설정·Pod 수·replica 수에 따라 클라이언트 체감이 단계마다 다르게 나타났다.

---

## 13. 결론

LitmusChaos의 HTTP Latency 실험을 통해, 지연 장애가 어떻게 처리되고 확산되며 방어되는지를 단계적으로 확인했다.

- 지연 장애는 Pod가 죽는 장애와 다르다. Pod는 Running·Ready를 유지하며 HTTP 200을 반환하므로, 응답 코드만 보는 기본 점검으로는 정상으로 보인다.
- Probe가 지연을 감지하는지는 timeout 설정에 달려 있다. timeout이 지연보다 짧으면 Probe가 느린 Pod를 격리하지만, timeout이 길면 느린 Pod가 트래픽에 남아 사용자가 지연을 직접 경험한다.
- 느린 Pod가 있는 상태에서 정상 Pod가 줄어들면 장애가 연쇄적으로 악화된다.
- replica 증설은 단일 Pod 지연의 영향을 희석하는 완화책이 된다.

이는 1주차 Service 정리와 4주차 실험(HTTP 500)의 결론을 지연 장애 관점에서 확장한 것이다. 4주차에서는 잘못된 응답 코드를 Probe가 감지했다면, 이번에는 느린 응답을 Probe가 감지하는지가 timeout 설정에 달려 있음을 확인했다.

> 안정적인 서비스를 위해서는 Service만으로는 부족하며, Probe의 적절한 timeout 설정, 충분한 replica, 그리고 응답 시간 기반의 추가 방어 메커니즘(timeout, circuit breaker 등)이 함께 구성되어야 한다.

---

## 14. 카오스 엔지니어링 관점 정리

이번 시나리오는 "정상 상태 정의 → 장애 주입 → 관찰 → 개선점 도출"이라는 카오스 엔지니어링의 기본 흐름을, 단일 실험이 아닌 단계적 시나리오로 확장했다.

- **정상 상태(steady state)**: Service 응답 시간이 약 1.5ms, 성공률 유지
- **주입한 장애**: 대상 Pod의 HTTP 응답에 2초 지연 (`pod-http-latency`), 이후 Pod 삭제로 확산
- **관찰 대상**: 응답 시간 분포, 지연 비율, 대상 Pod 재시작 여부, EndpointSlice 변화, replica 효과
- **발견**: 지연 장애 대응의 관건은 Probe의 존재 여부가 아니라 timeout 설정이며, 단일 장애가 정상 Pod 감소와 맞물려 연쇄 장애로 번질 수 있다
- **개선점**: 지연을 감지하도록 Probe timeout을 조정하고, 충분한 replica를 확보하며, 응답 시간 기반 방어 메커니즘을 도입해야 한다

특히 단계 2에서 "지연을 주입했는데 클라이언트가 빠른 응답만 받은" 현상은, 처음에는 주입 실패처럼 보였으나 실제로는 Probe timeout(1초)이 지연(2초)을 잡아 느린 Pod를 격리한 결과였다. 이는 Probe timeout 설정이 지연 장애 대응에 미치는 영향을 확인한 사례다.

---

## References

- [Litmus Docs - What is Litmus?](https://docs.litmuschaos.io/docs/introduction/what-is-litmus)
- [Litmus Experiments - Pod HTTP Latency](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-http-latency/)
- [Litmus Experiments - Pod Delete](https://litmuschaos.github.io/litmus/experiments/categories/pods/pod-delete/)
- [Litmus Experiments - Contents (전체 실험 목록)](https://litmuschaos.github.io/litmus/experiments/categories/contents/)
- [Litmus Concepts - ChaosEngine](https://litmuschaos.github.io/litmus/experiments/concepts/chaos-resources/chaos-engine/contents/)
- [Kubernetes Docs - Pod Lifecycle: Container probes](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#container-probes)
- [Kubernetes Docs - EndpointSlices](https://kubernetes.io/docs/concepts/services-networking/endpoint-slices/)
- [Kubernetes Docs - Service](https://kubernetes.io/docs/concepts/services-networking/service/)
- [Principles of Chaos Engineering](https://principlesofchaos.org/)
