# 03. Readiness 실패 Pod의 Service 트래픽 제외 실험

## 1. 실험 목적

Pod가 `Running` 상태라고 해서 항상 Service 트래픽을 받는 것은 아니다.

이번 실험에서는 같은 `app=service-demo` label을 가진 Pod라도, Readiness Probe가 실패하면 Service 트래픽 대상에서 제외되는지 확인한다.

---

## 2. 실험 가설

`app=service-demo` label을 가진 bad Pod를 추가하더라도, Readiness Probe가 실패하면 해당 Pod는 Ready endpoint로 사용되지 않을 것이다.

따라서 Service로 반복 요청을 보내도 `mode=not-ready`인 bad Pod 응답은 나오지 않을 것이다.

---

## 3. 실험 구성

```text
Service: service-demo-service
selector: app=service-demo
  ↓
Good Pod 1: Running / Ready
Good Pod 2: Running / Ready
Good Pod 3: Running / Ready
Bad Pod:  Running / Not Ready
```

bad Pod도 Service selector와 일치하는 label을 가진다.

```yaml
labels:
  app: service-demo
  role: bad
```

하지만 `/ready` endpoint가 실패하도록 `APP_MODE=not-ready`로 실행한다.

---

## 4. bad Pod 생성

`bad-pod.yaml` 파일을 생성한다.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: service-demo-bad
  labels:
    app: service-demo    # ← Service selector와 일치 (라우팅 대상에 포함시키기 위함)
    role: bad            # ← good Pod와 구분용
spec:
  containers:
    - name: app
      image: service-demo:v1
      imagePullPolicy: Never
      ports:
        - containerPort: 8080
      env:
        - name: APP_MODE
          value: "not-ready"   # ← /ready endpoint가 503 반환하도록 설정 (Probe 실패 시나리오)
        - name: POD_IP
          valueFrom:
            fieldRef:
              fieldPath: status.podIP
      readinessProbe:
        httpGet:
          path: /ready         # ← 2초마다 이 경로 호출
          port: 8080
        initialDelaySeconds: 2 # Pod 시작 후 2초 뒤부터 체크 시작
        periodSeconds: 2       # 2초 주기로 반복 체크
      livenessProbe:
        httpGet:
          path: /healthz       # ← 살아있는지 확인용 (실패 시 컨테이너 재시작)
          port: 8080
        initialDelaySeconds: 5
        periodSeconds: 5
```

생성한 파일을 적용한다.

```powershell
kubectl apply -f manifests/bad-pod.yaml
```

---

## 5. Pod 상태 확인

```powershell
kubectl get pods -l app=service-demo -o wide
```

실행 결과:

```text
NAME                            READY   STATUS    RESTARTS   AGE   IP           NODE           NOMINATED NODE   READINESS GATES
service-demo-5fb9464d7c-9dbzx   1/1     Running   0          18m   10.244.0.7   k8s-practice   <none>           <none>
service-demo-5fb9464d7c-shgmn   1/1     Running   0          43m   10.244.0.4   k8s-practice   <none>           <none>
service-demo-5fb9464d7c-x5qc6   1/1     Running   0          43m   10.244.0.5   k8s-practice   <none>           <none>
service-demo-bad                0/1     Running   0          5s    10.244.0.8   k8s-practice   <none>           <none>
```

bad Pod는 `Running` 상태이지만 `READY 0/1` 상태로 표시된다.

---

## 6. bad Pod 직접 접근 확인

bad Pod의 IP를 확인한다.

```powershell
$BADIP = kubectl get pod service-demo-bad -o jsonpath="{.status.podIP}"
Write-Host "BAD IP = $BADIP"
```

bad Pod의 `/` endpoint는 응답할 수 있다.

```powershell
kubectl exec curl-test -- curl -s http://10.244.0.8:8080
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-bad","podIP":"10.244.0.8","mode":"not-ready","time":"2026-05-18T13:03:59Z"}
```

하지만 `/ready` endpoint는 실패한다.

```powershell
kubectl exec curl-test -- curl -s -i http://10.244.0.8:8080/ready
```

실행 결과:

```text
HTTP/1.1 503 Service Unavailable
Content-Type: text/plain; charset=utf-8
X-Content-Type-Options: nosniff
Date: Mon, 18 May 2026 13:04:38 GMT
Content-Length: 10

not ready
```

즉, bad Pod는 애플리케이션 프로세스는 실행 중이지만, 트래픽을 받을 준비가 되지 않은 상태이다.

---

## 7. Service를 통한 반복 요청

Service 이름으로 여러 번 요청을 보낸다.

```powershell
1..10 | ForEach-Object {
  kubectl exec curl-test -- curl -s http://service-demo-service
  Start-Sleep -Milliseconds 300
}
```

실행 결과:

```json
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-x5qc6","podIP":"10.244.0.5","mode":"normal","time":"2026-05-18T13:05:09Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-x5qc6","podIP":"10.244.0.5","mode":"normal","time":"2026-05-18T13:05:10Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-9dbzx","podIP":"10.244.0.7","mode":"normal","time":"2026-05-18T13:05:11Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T13:05:12Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-9dbzx","podIP":"10.244.0.7","mode":"normal","time":"2026-05-18T13:05:13Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T13:05:14Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T13:05:15Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T13:05:16Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T13:05:17Z"}
{"message":"hello from Go service demo","host":"service-demo-5fb9464d7c-shgmn","podIP":"10.244.0.4","mode":"normal","time":"2026-05-18T13:05:18Z"}
```

반복 요청 결과에서 `mode=normal`인 Pod 응답만 확인되고, `mode=not-ready`인 bad Pod 응답은 나오지 않았다.

---

## 8. EndpointSlice 확인

```powershell
kubectl get endpointslice -l kubernetes.io/service-name=service-demo-service -o yaml
```

실행 결과:

```text
apiVersion: v1
items:
- addressType: IPv4
  apiVersion: discovery.k8s.io/v1
  endpoints:
  - addresses:
    - 10.244.0.5
    conditions:
      ready: true
      serving: true
      terminating: false
    nodeName: k8s-practice
    targetRef:
      kind: Pod
      name: service-demo-5fb9464d7c-x5qc6
      namespace: week3-service
      uid: 021b2a76-6d6f-4c4d-9ef0-eb093e6f0bc7
  - addresses:
    - 10.244.0.4
    conditions:
      ready: true
      serving: true
      terminating: false
    nodeName: k8s-practice
    targetRef:
      kind: Pod
      name: service-demo-5fb9464d7c-shgmn
      namespace: week3-service
      uid: 2693143c-00c0-4085-81eb-175c86e50850
  - addresses:
    - 10.244.0.7
    conditions:
      ready: true
      serving: true
      terminating: false
    nodeName: k8s-practice
    targetRef:
      kind: Pod
      name: service-demo-5fb9464d7c-9dbzx
      namespace: week3-service
      uid: b06ab56a-efde-4bc7-8176-526b5fdd95e1
  - addresses:
    - 10.244.0.8
    conditions:
      ready: false
      serving: false
      terminating: false
    nodeName: k8s-practice
    targetRef:
      kind: Pod
      name: service-demo-bad
      namespace: week3-service
      uid: 00408708-f917-4737-81d3-373bc56daa51
  kind: EndpointSlice
  metadata:
    annotations:
      endpoints.kubernetes.io/last-change-trigger-time: "2026-05-18T13:02:15Z"
    creationTimestamp: "2026-05-18T12:19:15Z"
    generateName: service-demo-service-
    generation: 8
    labels:
      endpointslice.kubernetes.io/managed-by: endpointslice-controller.k8s.io
      kubernetes.io/service-name: service-demo-service
    name: service-demo-service-6bfwh
    namespace: week3-service
    ownerReferences:
    - apiVersion: v1
      blockOwnerDeletion: true
      controller: true
      kind: Service
      name: service-demo-service
      uid: f93e6c12-6fc3-45b5-ad92-a613c6d8b035
    resourceVersion: "3112"
    uid: 0a42e10d-07f9-4d99-bec2-9edf3722e4e8
  ports:
  - name: ""
    port: 8080
    protocol: TCP
kind: List
metadata:
  resourceVersion: ""
```

Kubernetes 버전에 따라 bad Pod가 EndpointSlice에 보이더라도 `ready: false` 상태로 표시될 수 있다. 중요한 점은 Service 트래픽이 Ready 상태가 아닌 Pod로 전달되지 않는다는 것이다.

---

## 9. 관찰 결과

bad Pod는 `app=service-demo` label을 가지고 있었기 때문에 Service selector 조건에는 일치했다.

하지만 Readiness Probe가 실패했기 때문에 `READY 0/1` 상태가 되었고, Service 트래픽 대상으로 사용되지 않았다.

Service로 반복 요청을 보냈을 때도 `mode=not-ready` 응답은 나오지 않았다.

---

## 10. 결론

Service는 단순히 label이 일치하는 모든 Pod로 트래픽을 보내는 것이 아니다.

Pod가 `Running` 상태여도 `Ready` 상태가 아니면 Service 트래픽 대상에서 제외될 수 있다.

따라서 Kubernetes에서 안정적인 트래픽 처리를 위해서는 Service뿐만 아니라 Readiness Probe 설정도 함께 중요하다.

Service 관점에서 중요한 것은 Pod가 단순히 살아 있는지가 아니라, 실제로 요청을 받을 준비가 된 Ready endpoint인지 여부이다.