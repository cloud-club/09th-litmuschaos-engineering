## 1. Pod Delete Chaos
   
### 1-1. 실험 목적
> Pod 하나가 강제로 삭제되었을 때 Kubernetes가 새로운 Pod를 생성하여 원하는 replica 수를 회복하는지 확인한다.
   또한 replica 수가 2개 이상일 때, 일부 Pod 장애가 발생해도 Service가 나머지 정상 Pod로 트래픽을 전달할 수 있는지 확인한다. 
   
### 1-2. 실험 가설
```text
Deployment로 관리되는 Pod 하나가 삭제되더라도,
ReplicaSet이 새로운 Pod를 생성하여 원하는 replica 수를 회복할 것이다.

replica가 2개 이상이면 일부 Pod 장애가 발생해도
Service는 나머지 정상 Pod로 트래픽을 전달할 수 있다.
```
### 1-3. 실험 구성
```text
Client
  ↓
Service
  ↓
Deployment(replicas: 3)
  ├── Pod 1
  ├── Pod 2
  └── Pod 3
```
### 1-4. 실습 과정
**kind 클러스터 생성**
```bash
kind create cluster --name pod-chaos
```
현재 `kubectl`이 kind 클러스터를 바라보고 있는지 확인한다.
```bash
kubectl config current-context
kubectl get nodes
```
> kind 클러스터 생성 및 현재 context 확인 화면

---

**실험용 YAML 작성**

작업 폴더를 만들고 이동한다.
```bash
mkdir pod-chaos-study
cd pod-chaos-study
```
`pod-chaos-demo.yaml` 파일을 작성한다.
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pod-chaos-demo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: pod-chaos-demo
  template:
    metadata:
      labels:
        app: pod-chaos-demo
    spec:
      containers:
        - name: app
          image: nginx:1.25
          ports:
            - containerPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: pod-chaos-demo-service
spec:
  selector:
    app: pod-chaos-demo
  ports:
    - port: 80
      targetPort: 80
  type: ClusterIP
```
이 구성은 `app=pod-chaos-demo` 라벨을 가진 Pod들을 Service가 찾아 트래픽을 전달하는 구조이다.
```text
Client
  ↓
Service
  ↓
Deployment(replicas: 3)
  ├── Pod 1
  ├── Pod 2
  └── Pod 3
```
---

### 리소스 배포

```bash
kubectl apply -f pod-chaos-demo.yaml
```
배포 상태를 확인한다.
```bash
kubectl get deploy
kubectl get rs
kubectl get pods -l app=pod-chaos-demo
kubectl get svc pod-chaos-demo-service
```
> Deployment, ReplicaSet, Pod, Service 생성 확인 화면

Pod 관리 흐름은 다음과 같다.
```text
Deployment
  ↓ 생성/관리
ReplicaSet
  ↓ 실제 Pod 개수 유지
Pod 3개
```
---
### 1-5. Case 1: replicas: 3
### 예상 결과
```text
Pod 하나가 삭제되어도 나머지 Pod가 트래픽을 처리할 수 있다.
```
Service 요청 확인
`ClusterIP` Service는 클러스터 내부에서만 접근 가능하다.  
따라서 테스트용 `curl-test` Pod를 생성해 클러스터 내부에서 Service를 호출한다.
```bash
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
```

| 옵션 | 의미 |
| --- | --- |
| `kubectl run curl-test` | `curl-test`라는 이름의 테스트용 Pod 생성 |
| `--image=curlimages/curl` | curl 명령어가 포함된 이미지 사용 |
| `-it` | Pod 내부 셸에 대화형 터미널로 접속 |
| `--rm` | 셸 종료 시 테스트용 Pod 자동 삭제 |
| `-- sh` | Pod 안에서 `sh` 셸 실행 |
```bash
curl pod-chaos-demo-service
```
nginx 기본 HTML이 출력되면 Service 호출이 성공한 것이다.
이후 반복 요청을 실행한다.
```bash
while true; do curl -s --connect-timeout 1 --max-time 2 http://pod-chaos-demo-service > /dev/null && echo "OK" || echo "FAIL"; sleep 1; done
```

| 명령 | 의미 |
| --- | --- |
| `while true; do ... done` | `Ctrl + C`로 종료하기 전까지 무한 반복 |
| `curl -s` | 불필요한 진행 메시지 없이 요청 |
| `--connect-timeout 1` | 연결 시도를 최대 1초만 대기 |
| `--max-time 2` | 전체 요청 시간을 최대 2초로 제한 |
| `> /dev/null` | nginx HTML 응답 본문을 화면에 출력하지 않음 |
| `&& echo "OK"` | 요청 성공 시 `OK` 출력 |
| `echo FAIL` | 요청 실패 시 `FAIL` 출력 |
| `sleep 1` | 1초 간격으로 요청 |

> 반복 요청에서 `OK`가 출력되는 화면

### Pod 삭제
다른 터미널에서 Pod 목록을 확인한다.

```bash
kubectl get pods -l app=pod-chaos-demo
```

Pod 하나를 삭제한다.
```bash
kubectl delete pod <pod-name>
```

복구 과정을 관찰한다.
```bash
kubectl get pods -l app=pod-chaos-demo -w
```

### 관찰 결과

`replicas: 3` 상태에서는 Pod 하나를 삭제해도 반복 요청이 계속 `OK`로 유지되었다.

해석은 다음과 같다.

```text
Pod 하나 삭제
→ ReplicaSet이 새 Pod 생성
→ Service는 남아 있는 정상 Pod들로 트래픽 전달
→ 클라이언트 입장에서는 서비스가 계속 정상 응답
```
---
### 1-6. Case 2: replicas: 1
### 예상 결과

```text
Pod 하나가 삭제되면 새 Pod가 뜰 때까지 서비스 중단 가능성이 크다.
```

Deployment의 replica 수를 1개로 줄인다.

```bash
kubectl scale deployment pod-chaos-demo --replicas=1
```

Pod가 1개만 남았는지 확인한다.

```bash
kubectl get pods -l app=pod-chaos-demo
```

남아 있는 유일한 Pod를 삭제한다.

```bash
kubectl delete pod <pod-name>
```
> 캡처 삽입: `replicas: 1` 상태에서 Pod 삭제 후 반복 요청 중 `FAIL`이 출력된 화면

### 관찰 결과
예상대로 중간에 `FAIL`이 발생했다.
`replicas: 1` 상태의 흐름은 다음과 같다.

```text
Client
  ↓
Service
  ↓
Pod 1
```

이 상태에서 유일한 Pod를 삭제하면 순간적으로 Service 뒤에 요청을 받을 수 있는 Pod가 존재하지 않는다.
```text
Client
  ↓
Service
  ↓
Ready Pod 없음
```
반대로 `replicas: 3` 상태에서는 Pod 하나가 삭제되어도 나머지 두 Pod가 살아 있다.
```text
Pod 1 삭제
Pod 2 정상
Pod 3 정상

Service → Pod 2 또는 Pod 3으로 트래픽 전달
```
### 1-7. Case 비교
조건	Pod 삭제 시 결과	해석

| 조건            | Pod 삭제 시 결과      | 해석                          |
| ------------- | ---------------- | --------------------------- |
| `replicas: 3` | 요청이 계속 `OK`로 유지됨 | 나머지 정상 Pod가 트래픽 처리          |
| `replicas: 1` | 일시적으로 `FAIL` 발생  | 요청을 받을 다른 Pod가 없어 서비스 중단 발생 |


### 1-8. 결론
> Pod는 영구적으로 유지되는 서버가 아니라 언제든 삭제되고 새로 생성될 수 있는 일시적인 실행 단위이다.

Kubernetes는 삭제된 Pod 자체를 되살리는 것이 아니라, Deployment/ReplicaSet을 통해 원하는 replica 수를 만족하도록 새로운 Pod를 생성한다.

또한 Service는 살아 있는 정상 Pod로 트래픽을 전달하지만, `replicas: 1`처럼 대체 Pod가 없는 경우에는 Pod 삭제가 곧 일시적인 서비스 중단으로 이어질 수 있다.
따라서 Kubernetes 환경에서 고가용성을 확보하려면 단일 Pod에 의존하지 않고, 적절한 replica 수와 Service 라우팅, Readiness Probe를 함께 고려해야 한다.
