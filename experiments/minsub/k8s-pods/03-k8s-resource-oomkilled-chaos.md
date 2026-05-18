## 3. Resource Chaos

### 3-1. 실험 목적
> Pod가 Memory limit을 초과했을 때 어떤 상태 변화가 발생하는지 확인하고자 한다.

### 3-2. 배경 개념
Kubernetes에서는 컨테이너별로 resource requests와 limits를 설정할 수 있다.

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "128Mi"
  limits:
    cpu: "500m"
    memory: "256Mi"
```
### 설정	의미

- `requests`	스케줄링 시 필요한 최소 리소스 기준
- `limits`	컨테이너가 사용할 수 있는 최대 리소스 제한

Memory limit을 초과하면 컨테이너가 `OOMKilled` 될 수 있다.

CPU limit이 낮으면 컨테이너가 바로 죽지는 않지만, CPU throttling으로 응답 시간이 증가할 수 있다.

### 3-3. 실험 가설
```text
Memory limit이 설정된 Pod가 제한을 초과하면 OOMKilled 되고,
컨테이너 restart count가 증가할 것이다.
```
---
### 3-4. 실습 과정

### 메모리 카오스 실험 YAML 작성

`memory-chaos-demo.yaml` 파일을 작성한다.
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: memory-chaos-demo
spec:
  restartPolicy: Always
  containers:
    - name: memory-demo
      image: polinux/stress
      command: ["stress"]
      args: ["--vm", "1", "--vm-bytes", "300M", "--vm-hang", "1"]
      resources:
        requests:
          memory: "64Mi"
        limits:
          memory: "128Mi"
```


| 설정 | 의미 |
| --- | --- |
| `requests.memory: 64Mi` | 스케줄링 시 필요한 최소 메모리 요청량 |
| `limits.memory: 128Mi` | 컨테이너가 사용할 수 있는 최대 메모리 |
| `--vm-bytes 300M` | stress가 사용하려는 메모리 크기 |

즉, 컨테이너는 최대 `128Mi`까지만 사용할 수 있지만, 내부에서는 `300M` 메모리 사용을 시도한다.  

그래서 memory limit을 초과해 `OOMKilled`가 발생할 것으로 예상했다.

---

### 실험 실행
```bash
kubectl apply -f memory-chaos-demo.yaml
```
Pod 상태를 관찰한다.
```bash
kubectl get pod memory-chaos-demo -w
```
> `memory-chaos-demo` Pod 상태 변화 화면

<img width="70%" height="368" alt="image" src="https://github.com/user-attachments/assets/18e819a7-a33b-46f3-9c1f-c3a2dc1f2f92" />

---

### 원인 확인
```bash
kubectl describe pod memory-chaos-demo
```
다음 내용을 확인한다.
```text
Reason: OOMKilled
Exit Code: 137
Restart Count 증가
```

`Exit Code 137`은 일반적으로 프로세스가 `SIGKILL`로 종료되었을 때 나타난다.  
이 실험에서는 메모리 초과로 인해 커널 또는 컨테이너 런타임에 의해 프로세스가 강제 종료된 상황으로 해석할 수 있다.

> `kubectl describe pod`에서 `OOMKilled`, `Exit Code: 137` 확인 화면

 <img width="60%" height="368" alt="image" src="https://github.com/user-attachments/assets/58bc3829-283a-4ba9-aed9-40c0fc460942" />

---

### 로그 확인
```bash
kubectl logs memory-chaos-demo
```
실험 중 다음 로그를 확인할 수 있었다.
```text
stress: info: [1] dispatching hogs: 0 cpu, 0 io, 1 vm, 0 hdd
```
이는 `stress`가 메모리 부하 작업자 1개를 실행했다는 의미이다.
이전 컨테이너 로그를 확인하려면 다음 명령어를 사용할 수 있다.
```bash
kubectl logs memory-chaos-demo --previous
```
---

### 최종 상태 확인
```bash
kubectl get pod memory-chaos-demo
```

### 실험 결과 

Pod 상태는 아래와 같다.

```text
NAME                READY   STATUS      RESTARTS         AGE
memory-chaos-demo   0/1     OOMKilled   10 (6m13s ago)   28m
```
이는 컨테이너가 memory limit을 초과해 Kubernetes에 의해 강제 종료되었음을 의미한다.

또한 `RESTARTS` 값이 10으로 증가한 것을 통해, 컨테이너가 종료된 뒤에도 `restartPolicy`에 따라 반복적으로 재시작되었음을 확인했다.
> `kubectl get pod memory-chaos-demo`에서 `OOMKilled`, `RESTARTS` 확인 화면

<img width="60%" height="148" alt="image" src="https://github.com/user-attachments/assets/2207c529-66ff-4cd5-abe5-29c15247524b" />


## 3-5. 결론
> Memory Chaos 실험을 통해 컨테이너가 설정된 memory limit을 초과하면 `OOMKilled`가 발생하고, 컨테이너가 강제 종료될 수 있음을 확인했다.

Pod 장애는 애플리케이션 코드 오류뿐 아니라, 메모리 제한 설정이나 실제 사용량 증가 같은 운영 환경 요인으로도 발생할 수 있다.

따라서 Kubernetes 환경에서는 애플리케이션의 실제 리소스 사용량을 관찰하고, 적절한 `requests`와 `limits`를 설정하는 것이 중요하다.
