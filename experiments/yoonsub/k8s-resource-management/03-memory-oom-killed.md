## 3. Memory Limit 초과와 OOMKilled 확인

시나리오 2의 `resource-demo:latest` 이미지를 그대로 사용한다.

### YAML 작성

`memory-limit.yaml`
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resource-demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: resource-demo
  template:
    metadata:
      labels:
        app: resource-demo
    spec:
      containers:
        - name: app
          image: resource-demo:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            periodSeconds: 3
          resources:
            requests:
              cpu: "100m"
              memory: "32Mi"
            limits:
              cpu: "500m"
              memory: "64Mi"
---
apiVersion: v1
kind: Service
metadata:
  name: resource-demo-service
spec:
  selector:
    app: resource-demo
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

### 배포

```bash
kubectl apply -f memory-limit.yaml
kubectl get pods -l app=resource-demo
```

### /memory-hog 호출

터미널 1: Pod 상태 모니터링
```bash
kubectl get pods -l app=resource-demo -w
```

터미널 2: 로그 모니터링
```bash
kubectl logs -l app=resource-demo -f
```

터미널 3: `/memory-hog` 호출
```bash
kubectl run curl-test --image=curlimages/curl -it --rm -- sh
# curl-test Pod 안에서
curl http://resource-demo-service/memory-hog
```

### 결과 확인

```bash
kubectl describe pod <pod-name>
kubectl logs <pod-name> --previous
kubectl get pod <pod-name>
```

### 정리

```bash
kubectl delete -f memory-limit.yaml
```
