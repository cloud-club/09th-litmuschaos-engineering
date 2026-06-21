## 1. Requests 설정에 따른 스케줄링 동작 확인

### 노드 가용 리소스 확인

```bash
kubectl describe node k8s-practice
```

### Case 1: requests 없는 Pod

`no-resources.yaml`
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: no-resources-demo
spec:
  containers:
    - name: app
      image: nginx:1.25
      ports:
        - containerPort: 80
```

```bash
kubectl apply -f no-resources.yaml
kubectl get pod no-resources-demo
```

### Case 2: 과도한 requests Pod

`high-request.yaml`
```yaml
apiVersion: v1
kind: Pod
metadata:
  name: high-request-demo
spec:
  containers:
    - name: app
      image: nginx:1.25
      ports:
        - containerPort: 80
      resources:
        requests:
          cpu: "4"
          memory: "4Gi"
```

```bash
kubectl apply -f high-request.yaml
kubectl get pod high-request-demo
kubectl describe pod high-request-demo
```

### Case 3: 적절한 requests Pod

`high-request.yaml`의 resources를 수정한다.
```yaml
resources:
  requests:
    cpu: "100m"
    memory: "128Mi"
  limits:
    cpu: "500m"
    memory: "256Mi"
```

```bash
kubectl delete pod high-request-demo
kubectl apply -f high-request.yaml
kubectl get pod high-request-demo
```

### 정리

```bash
kubectl delete pod no-resources-demo high-request-demo
```
