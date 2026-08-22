// Jenkinsfile — Pattern B deploy pipeline for spring-vault-job-demo
//
// Trigger: webhook on push to master, or manual.
// Does:
//   1. Build Docker image (with build number tag).
//   2. Inject image into cluster (kubeadm + cri-dockerd on ${K8S_HOST}).
//   3. Use CI Vault creds to inject a fresh wrapping-token into K8s Secret.
//   4. Apply Job manifest — Job gets re-created with the fresh wrapping token.
//
// Required Jenkins Credentials (kind: Secret text):
//   ci-vault-role-id    ← from scripts/vault-setup.sh CI section
//   ci-vault-secret-id  ← from scripts/vault-setup.sh CI section
//
// Optional Jenkins Credentials:
//   k8s-kubeconfig       ← if kubectl on Jenkins can't auto-resolve a cluster
//                          (file credential, ID = k8s-kubeconfig, variable = KUBECONFIG)

pipeline {
    agent any

    options {
        timeout(time: 15, unit: 'MINUTES')
        disableConcurrentBuilds()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        VAULT_ADDR        = 'http://192.168.232.128:8200'
        JOB_APPROLE_NAME  = 'spring-vault-job-demo'
        K8S_NAMESPACE     = 'vault-demo'
        K8S_SECRET_NAME   = 'vault-job-credentials'
        K8S_JOB_NAME      = 'spring-vault-job-demo'
        K8S_MANIFEST      = 'deploy/k8s/job.yaml'
        K8S_HOST          = '192.168.232.128'    // kubeadm node, kubelet=docker
        IMAGE_NAME        = 'spring-vault-job-demo'
        IMAGE_TAG         = "0.${BUILD_NUMBER}.0"
        WRAP_TTL_SECONDS  = '300'
        RESTART_JOB       = 'true'
    }

    stages {

        stage('1. Build Docker image') {
            steps {
                sh '''
                    set -euo pipefail
                    docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .
                    docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest
                    echo "built ${IMAGE_NAME}:${IMAGE_TAG}"
                '''
            }
        }

        stage('2. Inject image into cluster node') {
            // kubeadm + cri-dockerd — images must be in the host docker daemon
            // where kubelet runs. If Jenkins shares that host, this stage is
            // effectively a no-op (image already local).
            steps {
                sh '''
                    set -euo pipefail
                    REMOTE_HAS=$(ssh -o StrictHostKeyChecking=no -o BatchMode=yes \
                        ${K8S_HOST} \
                        "docker images --format '{{.Repository}}:{{.Tag}}' ${IMAGE_NAME}:${IMAGE_TAG} 2>/dev/null || true")
                    if echo "$REMOTE_HAS" | grep -q "^${IMAGE_NAME}:${IMAGE_TAG}\$"; then
                        echo "image already present on ${K8S_HOST} — skipping ship"
                    else
                        echo "shipping image to ${K8S_HOST}..."
                        docker save ${IMAGE_NAME}:${IMAGE_TAG} | \
                            ssh -o StrictHostKeyChecking=no -o BatchMode=yes ${K8S_HOST} docker load
                    fi
                '''
            }
        }

        stage('3. Inject Vault wrapping-token into K8s Secret') {
            steps {
                withCredentials([
                    string(credentialsId: 'ci-vault-role-id',   variable: 'CI_VAULT_ROLE_ID'),
                    string(credentialsId: 'ci-vault-secret-id', variable: 'CI_VAULT_SECRET_ID'),
                ]) {
                    sh '''
                        set -euo pipefail
                        export VAULT_ADDR CI_VAULT_ROLE_ID CI_VAULT_SECRET_ID \
                               JOB_APPROLE_NAME K8S_NAMESPACE K8S_SECRET_NAME \
                               K8S_JOB_NAME WRAP_TTL_SECONDS RESTART_JOB
                        # K8S_CONTEXT intentionally unset → kubectl uses default context.
                        # Override via Jenkins job parameter if you have multiple clusters.
                        ./scripts/wrap-secret-id.sh
                    '''
                }
            }
        }

        stage('4. Apply Job manifest') {
            // The Job was deleted by wrap-secret-id.sh (RESTART_JOB=true); we
            // re-apply the manifest here so the orchestrator (kubectl / ArgoCD
            // / your GitOps tool) re-creates it with the fresh wrapping token.
            steps {
                sh "kubectl apply -f ${K8S_MANIFEST}"
            }
        }
    }

    post {
        success {
            echo ""
            echo "Pipeline OK — Job should now be running in cluster '${K8S_NAMESPACE}'."
            echo "  tail logs:  kubectl -n ${K8S_NAMESPACE} logs -l app=${JOB_APPROLE_NAME} -f"
            echo "  status:     kubectl -n ${K8S_NAMESPACE} get jobs -l app=${JOB_APPROLE_NAME}"
        }
        failure {
            echo "Pipeline failed. Check console output above."
            echo "Common causes:"
            echo "  - Jenkins Credentials missing (ci-vault-role-id / ci-vault-secret-id)"
            echo "  - Vault unreachable from Jenkins (firewall / wrong VAULT_ADDR)"
            echo "  - K8s Secret patch failed (kubectl context wrong, namespace missing)"
        }
        always {
            // Don't keep build images around forever on the Jenkins host.
            sh 'docker rmi ${IMAGE_NAME}:${IMAGE_TAG} 2>/dev/null || true'
        }
    }
}
