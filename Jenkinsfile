// MSA CI/CD 파이프라인:
//   Checkout -> Test -> Docker Image Build -> Docker Image Tag -> Container Registry Push
//   -> Deploy to VM (Ansible) -> Promote latest (배포 성공 후에만)
//
// 전제:
//  - Jenkins가 Docker 컨테이너(local/jenkins-docker)로 떠 있고, 호스트 docker.sock을 마운트해서
//    호스트 Docker 데몬을 직접 제어한다 (DooD).
//  - Jenkins Credentials(System 범위)에 'dockerhub-credentials' (Username with password)가 등록되어 있다.
//  - Docker Hub에 hkseo01/msa-user-service, hkseo01/msa-order-service, hkseo01/msa-frontend
//    리포지토리가 이미 만들어져 있다 (Public).
//  - Deploy 단계용 Credentials 가 등록되어 있다:
//      msa-vm-ssh (SSH Username with private key), msa-secrets (Secret file: DB 비밀번호 secrets.yml)
//  - Jenkins 이미지에 ansible-core + community.docker 가 설치되어 있고, VM 에는 Docker 가 설치되어 있다.
pipeline {
    agent any

    environment {
        REGISTRY_NAMESPACE = 'hkseo01'
    }

    options {
        // 두 배포가 동시에 VM 을 바꾸면 롤백 기준(직전 버전)이 흔들리므로 한 번에 하나만 실행한다.
        disableConcurrentBuilds()
    }

    // Jenkins 가 localhost 에 있어서 GitHub webhook 을 받을 수 없으므로 2분마다 변경을 확인한다.
    triggers {
        pollSCM('H/2 * * * *')
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // 커밋 짧은 SHA를 이미지 태그로 사용 (변경 불가능한 태그)
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                }
                echo "IMAGE_TAG = ${env.IMAGE_TAG}"
            }
        }

        stage('Test') {
            steps {
                // 현재 user-service/order-service에는 테스트 코드가 없다.
                // 그래도 컴파일 검증 + 향후 테스트 추가 시 자동으로 의미가 생기도록 gradlew test를 실행한다.
                // Jenkins 컨테이너엔 JDK가 없으므로 gradle:jdk21 컨테이너를 한 번 더 띄워서 처리한다.
                // jenkins_home을 동일 경로로 마운트해서 $WORKSPACE 경로가 그대로 맞도록 한다.
                sh '''
                    docker run --rm \
                      -v jenkins_home:/var/jenkins_home \
                      -w "$WORKSPACE/user-service" \
                      gradle:jdk21 ./gradlew test --no-daemon

                    docker run --rm \
                      -v jenkins_home:/var/jenkins_home \
                      -w "$WORKSPACE/order-service" \
                      gradle:jdk21 ./gradlew test --no-daemon
                '''
            }
        }

        stage('Docker Image Build') {
            steps {
                sh '''
                    docker build -t ${REGISTRY_NAMESPACE}/msa-user-service:${IMAGE_TAG} ./user-service
                    docker build -t ${REGISTRY_NAMESPACE}/msa-order-service:${IMAGE_TAG} ./order-service
                    docker build -t ${REGISTRY_NAMESPACE}/msa-frontend:${IMAGE_TAG} ./frontend-service
                '''
            }
        }

        stage('Docker Image Tag') {
            steps {
                sh '''
                    docker tag ${REGISTRY_NAMESPACE}/msa-user-service:${IMAGE_TAG} ${REGISTRY_NAMESPACE}/msa-user-service:latest
                    docker tag ${REGISTRY_NAMESPACE}/msa-order-service:${IMAGE_TAG} ${REGISTRY_NAMESPACE}/msa-order-service:latest
                    docker tag ${REGISTRY_NAMESPACE}/msa-frontend:${IMAGE_TAG} ${REGISTRY_NAMESPACE}/msa-frontend:latest
                '''
            }
        }

        stage('Container Registry Push') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-credentials',
                    usernameVariable: 'DOCKERHUB_USER',
                    passwordVariable: 'DOCKERHUB_PASS'
                )]) {
                    sh '''
                        echo "$DOCKERHUB_PASS" | docker login -u "$DOCKERHUB_USER" --password-stdin

                        # 여기서는 커밋 SHA 태그만 올린다. latest 는 배포가 성공한 뒤(Promote latest)에 올린다.
                        docker push ${REGISTRY_NAMESPACE}/msa-user-service:${IMAGE_TAG}
                        docker push ${REGISTRY_NAMESPACE}/msa-order-service:${IMAGE_TAG}
                        docker push ${REGISTRY_NAMESPACE}/msa-frontend:${IMAGE_TAG}

                        docker logout
                    '''
                }
            }
        }

        stage('Deploy to VM') {
            steps {
                // Ansible 이 VM 에 SSH 로 접속해 방금 push 한 이미지(IMAGE_TAG)로 배포한다.
                // SSH 개인키와 DB 비밀번호 파일은 Jenkins Credentials 에서 실행 시점에만 꺼내 쓴다.
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'msa-vm-ssh',
                        keyFileVariable: 'VM_SSH_KEY',
                        usernameVariable: 'VM_SSH_USER'
                    ),
                    file(credentialsId: 'msa-secrets', variable: 'SECRETS_FILE')
                ]) {
                    sh '''
                        cd ansible
                        ansible-playbook -i inventory.ini deploy.yml \
                          -e image_tag=${IMAGE_TAG} \
                          -e ansible_user=${VM_SSH_USER} \
                          -e ansible_ssh_private_key_file=${VM_SSH_KEY} \
                          -e @${SECRETS_FILE}
                    '''
                }
            }
        }

        stage('Promote latest') {
            // 배포(헬스체크 포함)가 성공한 버전만 latest 로 올린다.
            // Deploy to VM 이 실패하면 이 stage 는 실행되지 않으므로 latest 는 마지막 성공 버전을 가리킨다.
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-credentials',
                    usernameVariable: 'DOCKERHUB_USER',
                    passwordVariable: 'DOCKERHUB_PASS'
                )]) {
                    sh '''
                        echo "$DOCKERHUB_PASS" | docker login -u "$DOCKERHUB_USER" --password-stdin

                        docker push ${REGISTRY_NAMESPACE}/msa-user-service:latest
                        docker push ${REGISTRY_NAMESPACE}/msa-order-service:latest
                        docker push ${REGISTRY_NAMESPACE}/msa-frontend:latest

                        docker logout
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "빌드/푸시/배포 성공: 태그 ${env.IMAGE_TAG}"
        }
        failure {
            echo "빌드 실패: 로그를 확인하세요."
        }
    }
}
