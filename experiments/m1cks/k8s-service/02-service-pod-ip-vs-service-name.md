# 02. Pod IP 직접 접근 vs Service 이름 접근 실험

## 1. 실험 목적

Pod IP는 특정 Pod에 직접 연결된 임시 주소이다.  
Pod가 삭제되고 새로 생성되면 기존 Pod IP는 더 이상 유효하지 않을 수 있다.

반면 Service 이름은 Pod가 바뀌어도 유지되는 안정적인 접근 지점이다.

이번 실험에서는 특정 Pod IP로 직접 접근했을 때와 Service 이름으로 접근했을 때의 차이를 비교한다.

---

## 2. 실험 가설

특정 Pod IP로 직접 접근하면 해당 Pod가 삭제된 뒤 요청이 실패할 것이다.

반면 Service 이름으로 접근하면 Pod가 삭제되더라도 남아 있는 Ready Pod를 통해 요청이 계속 성공할 것이다.

---

## 3. 실험 구성

```text
Client Pod
  ├── 직접 접근: Pod IP:8080
  └── 안정 접근: service-demo-service

Service
  ↓
Deployment(replicas: 3)
  ├── Pod A
  ├── Pod B
  └── Pod C
```

Service는 `app=service-demo` 라벨을 가진 Pod들을 대상으로 트래픽을 전달한다.

---

## 4. 사전 상태 확인

현재 backend Pod 목록과 Service 상태를 확인한다.

```bash
kubectl get pods -l app=service-demo -o wide
kubectl get svc service-demo-service
kubectl get endpointslice -l kubernetes.io/service-name=service-demo-service -o wide
```

실행 결과:

```text
# Pod
NAME                            READY   STATUS    RESTARTS   AGE   IP           NODE           NOMINATED NODE   READINESS GATES
service-demo-5fb9464d7c-md9n5   1/1     Running   0          21m   10.244.0.3   k8s-practice   <none>           <none>
service-demo-5fb9464d7c-shgmn   1/1     Running   0          21m   10.244.0.4   k8s-practice   <none>           <none>
service-demo-5fb9464d7c-x5qc6   1/1     Running   0          21m   10.244.0.5   k8s-practice   <none>           <none>

#Service
NAME                   TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)   AGE
service-demo-service   ClusterIP   10.104.98.86   <none>        80/TCP    21m

#EndpointSlice
NAME                         ADDRESSTYPE   PORTS   ENDPOINTS                          AGE
service-demo-service-6bfwh   IPv4          8080    10.244.0.5,10.244.0.4,10.244.0.3   21m
```

Service 이름으로 backend에 접근 가능한지 확인한다.

```bash
kubectl exec curl-test -- curl -s http://service-demo-service
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T12:41:21Z"}
```

정상적으로 Service 이름을 통해 Go backend Pod에 접근할 수 있음을 확인했다.

---

## 5. 대상 Pod 선택

실험에 사용할 Pod 하나를 선택하고, 해당 Pod의 IP를 확인한다.

```powershell
$POD = kubectl get pod -l app=service-demo -o jsonpath="{.items[0].metadata.name}"
$PODIP = kubectl get pod $POD -o jsonpath="{.status.podIP}"

Write-Host "TARGET POD = $POD"
Write-Host "TARGET IP  = $PODIP"
```

실행 결과:

```text
TARGET POD = service-demo-5fb9464d7c-md9n5
TARGET IP  = 10.244.0.3
```

---

## 6. Pod IP 직접 접근 확인

선택한 Pod IP로 직접 요청을 보낸다.

```bash
kubectl exec curl-test -- curl -s http://10.244.0.3:8080
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-md9n5","podIP":"10.244.0.3","mode":"normal","time":"2026-05-18T12:42:47Z"}
```

특정 Pod IP로 직접 접근했을 때는 정상 응답을 확인할 수 있었다.

---

## 7. Service 이름 접근 확인

이번에는 Pod IP가 아니라 Service 이름으로 요청을 보낸다.

```bash
kubectl exec curl-test -- curl -s http://service-demo-service
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-md9n5","podIP":"10.244.0.3","mode":"normal","time":"2026-05-18T12:43:15Z"}
```

Service 이름으로도 정상적으로 backend Pod에 접근할 수 있었다.

---

## 8. 대상 Pod 삭제

앞에서 직접 접근했던 대상 Pod를 삭제한다.

```bash
kubectl delete pod service-demo-5fb9464d7c-md9n5
```

삭제 후 Pod 목록을 다시 확인한다.

```bash
kubectl get pods -l app=service-demo -o wide
```

실행 결과:

```text
NAME                            READY   STATUS    RESTARTS   AGE   IP           NODE           NOMINATED NODE   READINESS GATES
service-demo-5fb9464d7c-9dbzx   1/1     Running   0          7s    10.244.0.7   k8s-practice   <none>           <none>
service-demo-5fb9464d7c-shgmn   1/1     Running   0          24m   10.244.0.4   k8s-practice   <none>           <none>
service-demo-5fb9464d7c-x5qc6   1/1     Running   0          24m   10.244.0.5   k8s-practice   <none>           <none>
```

Deployment가 replica 수를 유지하기 위해 새로운 Pod를 생성하는 것을 확인할 수 있다.

---

## 9. 기존 Pod IP로 다시 접근

삭제된 Pod의 기존 IP로 다시 요청을 보낸다.

```bash
kubectl exec curl-test -- sh -c "curl -s --connect-timeout 1 --max-time 2 http://10.244.0.3:8080 && echo OK || echo FAIL"
```

실행 결과:

```text
FAIL
```

삭제된 Pod의 IP는 더 이상 안정적인 접근 지점이 아니므로 요청이 실패했다.

---

## 10. Service 이름으로 다시 접근

같은 상황에서 Service 이름으로 요청을 보낸다.

```bash
kubectl exec curl-test -- sh -c "curl -s --connect-timeout 1 --max-time 2 http://service-demo-service && echo OK || echo FAIL"
```

실행 결과:

```text
OK
```

Service 이름으로 접근했을 때는 요청이 계속 성공했다.

EndpointSlice 상태도 다시 확인한다.

```bash
kubectl get endpointslice -l kubernetes.io/service-name=service-demo-service -o wide
```

실행 결과:

```text
NAME                         ADDRESSTYPE   PORTS   ENDPOINTS                          AGE
service-demo-service-6bfwh   IPv4          8080    10.244.0.5,10.244.0.4,10.244.0.7   28m
```

삭제된 Pod의 endpoint는 사라지고, 새로 생성된 Pod가 Ready 상태가 되면 EndpointSlice에 다시 반영된다.

---

## 11. 관찰 결과

Pod IP로 직접 접근했을 때는 해당 Pod가 삭제된 이후 요청이 실패했다.

반면 Service 이름으로 접근했을 때는 Pod가 삭제된 이후에도 요청이 성공했다.

이는 클라이언트가 개별 Pod IP에 직접 의존하지 않고 Service 이름을 사용하면, Pod 생성/삭제로 인한 IP 변경을 직접 알 필요가 없기 때문이다.

---

## 12. 결론

Pod IP는 특정 Pod에 직접 연결된 주소이기 때문에, 해당 Pod가 삭제되면 더 이상 안정적으로 사용할 수 없다.

반면 Service 이름은 Pod IP가 바뀌어도 유지되는 안정적인 접근 지점이다.

클라이언트는 개별 Pod IP를 직접 추적하지 않고 Service 이름만 사용하면 된다.

Service는 EndpointSlice에 등록된 현재 Ready endpoint를 기준으로 요청을 전달한다.

따라서 Kubernetes 환경에서는 클라이언트가 Pod IP에 직접 의존하지 않고 Service를 통해 접근하는 것이 중요하다.