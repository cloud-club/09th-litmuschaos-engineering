# 01. Service Selector와 EndpointSlice 실험

## 1. 실험 목적

Service가 존재하더라도 `selector`와 Pod의 `label`이 일치하지 않으면 실제 backend Pod를 찾지 못한다는 것을 확인한다.

즉, Service는 Pod 이름이나 IP가 아니라 **label/selector**를 기준으로 트래픽을 전달할 endpoint를 구성한다는 점을 검증한다.

---

## 2. 실험 가설

Service의 selector가 Pod label과 일치하지 않으면 EndpointSlice에 backend endpoint가 등록되지 않고, Service 요청은 실패할 것이다.

반대로 selector를 올바르게 수정하면 EndpointSlice에 Pod IP와 port가 다시 등록되고, Service 요청은 성공할 것이다.

---

## 3. 실험 구성

```text
Client Pod
  ↓
Service: service-demo-service
  selector: app=wrong
  ↓
Endpoint 없음

Backend Pods
  label: app=service-demo
```

정상 상태에서는 다음 구조가 되어야 한다.

```text
Client Pod
  ↓
Service: service-demo-service
  selector: app=service-demo
  ↓
EndpointSlice
  ↓
Backend Pod IP:8080
```

---

## 4. 사전 상태 확인

Go로 작성한 backend 서버를 Deployment로 배포하고, `service-demo-service`라는 ClusterIP Service를 생성했다.

Service 호출 확인:

```bash
kubectl exec curl-test -- curl -s http://service-demo-service
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-x5qc6","podIP":"10.244.0.5","mode":"normal","time":"2026-05-18T12:20:35Z"}
```

정상적으로 Service 이름을 통해 Go backend Pod에 접근할 수 있음을 확인했다.

---

## 5. 실습 과정

### 5-1. 정상 EndpointSlice 확인

```bash
kubectl get endpointslice -l kubernetes.io/service-name=service-demo-service -o wide
kubectl describe svc service-demo-service
```

실행 결과:

```text
# endpointslice
NAME                         ADDRESSTYPE   PORTS   ENDPOINTS                          AGE
service-demo-service-6bfwh   IPv4          8080    10.244.0.5,10.244.0.4,10.244.0.3   6m8s

# service
Name:                     service-demo-service
Namespace:                week3-service
Labels:                   <none>
Annotations:              <none>
Selector:                 app=service-demo
Type:                     ClusterIP
IP Family Policy:         SingleStack
IP Families:              IPv4
IP:                       10.104.98.86
IPs:                      10.104.98.86
Port:                     <unset>  80/TCP
TargetPort:               8080/TCP
Endpoints:                10.244.0.5:8080,10.244.0.4:8080,10.244.0.3:8080
Session Affinity:         None
Internal Traffic Policy:  Cluster
Events:                   <none>
```

---

### 5-2. Service selector를 잘못된 값으로 변경

Service의 selector를 `app=wrong`으로 변경한다.

```bash
kubectl patch svc service-demo-service -p '{\"spec\":{\"selector\":{\"app\":\"wrong\"}}}'
```

변경 후 EndpointSlice와 Service 상태를 확인한다.

```bash
kubectl get endpointslice -l kubernetes.io/service-name=service-demo-service -o wide
kubectl describe svc service-demo-service
```

실행 결과:

```text
#endpointsslice
NAME                         ADDRESSTYPE   PORTS     ENDPOINTS   AGE
service-demo-service-6bfwh   IPv4          <unset>   <unset>     13m

#service
Name:                     service-demo-service
Namespace:                week3-service
Labels:                   <none>
Annotations:              <none>
Selector:                 app=wrong
Type:                     ClusterIP
IP Family Policy:         SingleStack
IP Families:              IPv4
IP:                       10.104.98.86
IPs:                      10.104.98.86
Port:                     <unset>  80/TCP
TargetPort:               8080/TCP
Endpoints:
Session Affinity:         None
Internal Traffic Policy:  Cluster
Events:                   <none>

```

---

### 5-3. Service 요청 실패 확인

```bash
kubectl exec curl-test -- sh -c "curl -s --connect-timeout 1 --max-time 2 http://service-demo-service && echo OK || echo FAIL"
```

실행 결과:

```text
FAIL
```

selector가 Pod label과 일치하지 않기 때문에 Service 뒤에 연결된 endpoint가 없고, 요청이 실패했다.

---

### 5-4. Service selector를 정상 값으로 복구

Service selector를 다시 `app=service-demo`로 수정한다.

```bash
kubectl patch svc service-demo-service -p '{\"spec\":{\"selector\":{\"app\":\"service-demo\"}}}'
```

EndpointSlice가 다시 생성/갱신되는지 확인한다.

```bash
kubectl get endpointslice -l kubernetes.io/service-name=service-demo-service -o wide
kubectl describe svc service-demo-service
```

실행 결과:

```text
# endpointslice
NAME                         ADDRESSTYPE   PORTS   ENDPOINTS                          AGE
service-demo-service-6bfwh   IPv4          8080    10.244.0.5,10.244.0.4,10.244.0.3   14m

# Service
Name:                     service-demo-service
Namespace:                week3-service
Labels:                   <none>
Annotations:              <none>
Selector:                 app=service-demo
Type:                     ClusterIP
IP Family Policy:         SingleStack
IP Families:              IPv4
IP:                       10.104.98.86
IPs:                      10.104.98.86
Port:                     <unset>  80/TCP
TargetPort:               8080/TCP
Endpoints:                10.244.0.5:8080,10.244.0.4:8080,10.244.0.3:8080
Session Affinity:         None
Internal Traffic Policy:  Cluster
Events:                   <none>

```

---

### 5-5. Service 요청 성공 확인

```bash
kubectl exec curl-test -- curl -s http://service-demo-service
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-x5qc6","podIP":"10.244.0.5","mode":"normal","time":"2026-05-18T12:35:13Z"}
```

---

## 6. 관찰 결과

Service selector가 `app=wrong`일 때는 Service는 존재하지만 EndpointSlice에 backend endpoint가 등록되지 않았다.

그 결과 `service-demo-service`로 요청했을 때 실패했다.

반대로 selector를 `app=service-demo`로 수정하자 EndpointSlice에 backend Pod의 IP와 port가 다시 등록되었고, Service 요청도 정상적으로 성공했다.

---

## 7. 결론

Service는 Pod 이름이나 Pod IP를 직접 기준으로 backend를 찾지 않는다.

Service는 `selector`와 Pod의 `label`을 기준으로 backend Pod를 찾고, 그 결과를 EndpointSlice에 반영한다.

따라서 Service가 존재하더라도 selector와 label이 일치하지 않으면 실제 요청을 전달할 endpoint가 없기 때문에 서비스 호출은 실패할 수 있다.