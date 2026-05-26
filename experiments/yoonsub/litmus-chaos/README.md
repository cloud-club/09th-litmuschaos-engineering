# ☸️ LitmusChaos 실험 - Pod IO Stress & Pod Network Corruption

## 실험 목록

1. [Pod IO Stress](./01-pod-io-stress/) - 디스크 I/O 부하 주입
2. [Pod Network Corruption](./02-pod-network-corruption/) - 네트워크 패킷 손상 주입

## 실험 환경 구성 순서

### Step 1. minikube 시작

```bash
minikube start --profile=k8s-practice
```

### Step 2. LitmusChaos Operator 설치

```bash
kubectl apply -f https://litmuschaos.github.io/litmus/litmus-operator-v1.13.8.yaml
```

설치 확인

```bash
kubectl get pods -n litmus
# NAME                                  READY   STATUS    RESTARTS   AGE
# chaos-operator-ce-xxx                 1/1     Running   0          30s
```

### Step 3. 실험 대상 앱 배포

```bash
kubectl apply -f 00-target-app.yaml
kubectl get pods -l app=chaos-target
```

### Step 4. 각 실험 진행

```bash
# 실험 1
cd 01-pod-io-stress
kubectl apply -f rbac.yaml
kubectl apply -f https://hub.litmuschaos.io/api/chaos/1.13.8?file=charts/generic/pod-io-stress/experiment.yaml
kubectl apply -f chaosengine.yaml

# 실험 2
cd ../02-pod-network-corruption
kubectl apply -f rbac.yaml
kubectl apply -f https://hub.litmuschaos.io/api/chaos/1.13.8?file=charts/generic/pod-network-corruption/experiment.yaml
kubectl apply -f chaosengine.yaml
```

### 결과 확인 공통 명령어

```bash
kubectl describe chaosresult <engine-name>-<experiment-name> -n default
kubectl get chaosresult
kubectl logs -l app=chaos-target -f
```
