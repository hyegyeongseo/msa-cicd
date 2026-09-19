# MSA CI/CD Lab

기존 MSA 프로젝트를 기반으로 **Jenkins를 활용한 CI/CD와 배포 자동화**를 실습하는 프로젝트입니다.

## Goal

동일한 MSA 애플리케이션을 서로 다른 환경에 배포하며 CI/CD 방식을 비교합니다.

* **VM**: Docker Compose + Ansible
* **Kubernetes**: Kubernetes + Ansible / Argo CD

## Architecture

```text
GitHub
   ↓
Jenkins
   ↓
Docker Build & Push
   ↓
Docker Hub
   │
   ├── VM
   │    └── Ansible → Docker Compose
   │
   └── Kubernetes
        ├── Ansible → Kubernetes
        └── Argo CD → GitOps
```

## Application

* Frontend
* User Service
* Order Service
* Kafka
* Redis
* MySQL

## Learning

* Jenkins CI/CD Pipeline
* Docker Image Build & Registry
* Ansible 기반 배포 자동화
* Docker Compose 기반 VM 배포
* Kubernetes 기반 배포
* Jenkins + Ansible와 Argo CD 기반 GitOps 비교
