/**
 * vars/pythonPipeline.groovy
 *
 * Global variable (a "step") that any Jenkinsfile can call as:
 *
 *   pythonPipeline(appDir: 'app', pythonVersion: '3.12')
 *
 * Shared-library steps centralise the "how" so individual Jenkinsfiles
 * only declare the "what".  All the boilerplate (venv setup, test
 * reporting, Docker tagging) lives here, versioned alongside the code.
 */
def call(Map config = [:]) {
    String appDir       = config.appDir       ?: 'app'
    String pythonVer    = config.pythonVersion ?: '3.12'
    String imageName    = config.imageName     ?: 'my-org/flask-app'
    String deployBranch = config.deployBranch  ?: 'main'

    // Agent strings used in multiple stages
    String pythonImage = "python:${pythonVer}-slim"
    String pythonArgs  = '-v pip-cache:/root/.cache/pip'

    pipeline {
        // --------------- Agent strategy ---------------
        // agent none: each stage declares its own agent so Python stages run
        // in the Python container and Docker stages run in docker:cli without
        // the withDockerContainer decorator leaking into nested allocations.
        agent none

        options {
            timestamps()
            ansiColor('xterm')
            timeout(time: 20, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '10'))
            disableConcurrentBuilds()
        }

        environment {
            IMAGE_LATEST = "${imageName}:latest"
        }

        stages {
            // ---- 1. Install dependencies ----
            stage('Install') {
                agent { docker { image pythonImage; args pythonArgs } }
                steps {
                    script {
                        env.GIT_SHORT_SHA = env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : 'unknown'
                        env.IMAGE_TAG     = "${imageName}:${env.GIT_SHORT_SHA}"
                    }
                    dir(appDir) {
                        sh 'pip install -r requirements.txt'
                    }
                }
            }

            // ---- 2. Parallel quality gates ----
            // Running lint and security scan in parallel cuts wall-clock time.
            // agent must be on each branch — not on the parent parallel stage.
            stage('Quality Gates') {
                parallel {
                    stage('Lint') {
                        agent { docker { image pythonImage; args pythonArgs } }
                        steps {
                            dir(appDir) {
                                sh 'pip install -r requirements.txt -q'
                                sh 'flake8 . --count --max-line-length=120 --statistics'
                            }
                        }
                    }
                    stage('Security Scan') {
                        agent { docker { image pythonImage; args pythonArgs } }
                        steps {
                            dir(appDir) {
                                sh 'pip install -r requirements.txt -q'
                                sh 'bandit -r . -ll -x ./tests'
                            }
                        }
                    }
                }
            }

            // ---- 3. Tests with coverage ----
            stage('Test') {
                agent { docker { image pythonImage; args pythonArgs } }
                steps {
                    dir(appDir) {
                        sh 'pip install -r requirements.txt -q'
                        sh '''
                            pytest tests/ \
                              --junitxml=../reports/junit.xml \
                              --cov=. \
                              --cov-report=xml:../reports/coverage.xml \
                              --cov-report=term-missing \
                              -v
                        '''
                    }
                }
                post {
                    always {
                        junit 'reports/junit.xml'
                        recordCoverage(tools: [[parser: 'COBERTURA', pattern: 'reports/coverage.xml']])
                    }
                }
            }

            // ---- 4. Build Docker image ----
            // docker:cli provides the Docker CLI; the host socket reaches it
            // via --volumes-from the Jenkins container (DooD pattern).
            stage('Build Image') {
                agent {
                    docker {
                        image 'docker:cli'
                        args  '-v /var/run/docker.sock:/var/run/docker.sock'
                    }
                }
                when {
                    beforeAgent true
                    not { buildingTag() }
                }
                steps {
                    dir(appDir) {
                        sh "docker build -t ${env.IMAGE_TAG} -t ${IMAGE_LATEST} ."
                    }
                }
            }

            // ---- 5. Push to registry ----
            stage('Push Image') {
                agent {
                    docker {
                        image 'docker:cli'
                        args  '-v /var/run/docker.sock:/var/run/docker.sock'
                    }
                }
                when {
                    beforeAgent true
                    branch deployBranch
                }
                steps {
                    withCredentials([usernamePassword(credentialsId: 'docker-hub-creds',
                                                      usernameVariable: 'DOCKER_USR',
                                                      passwordVariable: 'DOCKER_PSW')]) {
                        sh '''
                            echo "${DOCKER_PSW}" | \
                              docker login -u "${DOCKER_USR}" --password-stdin
                            docker push ${IMAGE_TAG}
                            docker push ${IMAGE_LATEST}
                        '''
                    }
                }
            }

            // ---- 6. Deploy ----
            stage('Deploy') {
                agent { label '' }
                when {
                    beforeAgent true
                    branch deployBranch
                }
                steps {
                    deployApp(imageName: env.IMAGE_TAG, environment: 'staging')
                }
            }
        }

        post {
            success {
                echo "Build ${env.BUILD_NUMBER} succeeded — image: ${env.IMAGE_TAG}"
            }
            failure {
                echo "Build ${env.BUILD_NUMBER} FAILED — check the Test / Quality Gates stages"
            }
        }
    }
}
