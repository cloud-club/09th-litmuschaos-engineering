# ☸️ Kubernetes Resource Management 실습

## 🛠️ 실습 목차

1. [Requests 설정에 따른 스케줄링 동작 확인](./01-requests-scheduling.md)
2. [CPU Hog와 CPU Limit 확인](./02-cpu-hog-throttling.md)
3. [Memory Limit 초과와 OOMKilled 확인](./03-memory-oom-killed.md)

## 실습 환경

- minikube (profile: `k8s-practice`)
- Go 1.22
- Docker
- kubectl + metrics-server

## 사전 준비

```bash
minikube start --profile=k8s-practice
minikube addons enable metrics-server --profile=k8s-practice
eval $(minikube docker-env --profile=k8s-practice)
```
