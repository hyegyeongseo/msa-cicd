# Jenkins 컨테이너에서 호스트 Docker 데몬을 직접 제어하기 위한 이미지.
# (Docker outside of Docker: /var/run/docker.sock을 마운트해서 쓰는 방식)
#
# 공식 jenkins/jenkins 이미지에는 docker CLI가 없어서,
# docker build / docker push를 실행하려면 CLI만 추가로 설치해야 한다.
# Jenkins 컨테이너 안에 별도 Docker 데몬을 띄우지 않는다 (DinD 아님).
FROM jenkins/jenkins:lts-jdk17

USER root

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates curl gnupg && \
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" \
        > /etc/apt/sources.list.d/docker.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends docker-ce-cli && \
    rm -rf /var/lib/apt/lists/*

# CD 단계에서 VM에 SSH로 접속해 배포하기 위한 Ansible.
# docker compose 배포용 모듈(community.docker.docker_compose_v2)은 별도 collection이라 함께 설치한다.
RUN apt-get update && \
    apt-get install -y --no-install-recommends ansible-core openssh-client && \
    ansible-galaxy collection install community.docker -p /usr/share/ansible/collections && \
    rm -rf /var/lib/apt/lists/*

USER jenkins
