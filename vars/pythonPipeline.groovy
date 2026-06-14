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

    pipeline {
        // --------------- Agent strategy ---------------
        // Use a Docker-based agent so the controller stays clean and each
        // build gets a fresh, reproducible environment.
        agent {
            docker {
                image "python:${pythonVer}-slim"
                // Re-use the pip cache across builds for speed.
                args  '-v pip-cache:/root/.cache/pip'
            }
        }

        options {
            timestamps()
            ansiColor('xterm')
            timeout(time: 20, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '10'))
            // Prevent concurrent builds on the same branch from colliding.
            disableConcurrentBuilds()
        }

        // --------------- Environment / credentials ---------------
        environment {
            DOCKER_CREDS = credentials('docker-hub-creds')
            IMAGE_LATEST = "${imageName}:latest"
        }

        stages {
            // ---- 1. Install dependencies ----
            stage('Install') {
                steps {
                    script {
                        // GIT_COMMIT is set by Jenkins after checkout; compute
                        // derived image tag here so all later stages can use it.
                        env.GIT_SHORT_SHA = env.GIT_COMMIT ? env.GIT_COMMIT.take(7) : 'unknown'
                        env.IMAGE_TAG = "${imageName}:${env.GIT_SHORT_SHA}"
                    }
                    dir(appDir) {
                        sh 'pip install -r requirements.txt'
                    }
                }
            }

            // ---- 2. Parallel quality gates ----
            // Running lint and security scan in parallel cuts wall-clock time.
            stage('Quality Gates') {
                parallel {
                    stage('Lint') {
                        steps {
                            dir(appDir) {
                                sh 'flake8 . --count --max-line-length=120 --statistics'
                            }
                        }
                    }
                    stage('Security Scan') {
                        steps {
                            dir(appDir) {
                                // bandit: static analysis for common security issues
                                sh 'bandit -r . -ll -x ./tests'
                            }
                        }
                    }
                }
            }

            // ---- 3. Tests with coverage ----
            stage('Test') {
                steps {
                    dir(appDir) {
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
                        // Publish JUnit results — visible in the build UI
                        junit 'reports/junit.xml'
                        // Publish coverage — requires Cobertura plugin
                        cobertura coberturaReportFile: 'reports/coverage.xml',
                                  failNoReports: false
                    }
                }
            }

            // ---- 4. Build Docker image ----
            stage('Build Image') {
                // Only build images on main / feature branches; skip on tags
                when {
                    not { buildingTag() }
                }
                steps {
                    dir(appDir) {
                        sh "docker build -t ${IMAGE_TAG} -t ${IMAGE_LATEST} ."
                    }
                }
            }

            // ---- 5. Push to registry ----
            stage('Push Image') {
                when {
                    // Only push from the deploy branch (e.g. main)
                    branch deployBranch
                }
                steps {
                    // withCredentials gives tightly-scoped access to the secret.
                    // DOCKER_CREDS_USR / DOCKER_CREDS_PSW are set automatically
                    // by the credentials() binding above.
                    sh '''
                        echo "${DOCKER_CREDS_PSW}" | \
                          docker login -u "${DOCKER_CREDS_USR}" --password-stdin
                        docker push ${IMAGE_TAG}
                        docker push ${IMAGE_LATEST}
                    '''
                }
            }

            // ---- 6. Deploy ----
            stage('Deploy') {
                when {
                    branch deployBranch
                }
                steps {
                    deployApp(imageName: IMAGE_TAG, environment: 'staging')
                }
            }
        }

        post {
            always {
                // Clean the workspace so the Docker agent volume doesn't fill up
                cleanWs()
            }
            success {
                echo "Build ${env.BUILD_NUMBER} succeeded — image: ${IMAGE_TAG}"
            }
            failure {
                echo "Build ${env.BUILD_NUMBER} FAILED — check the Test / Quality Gates stages"
            }
        }
    }
}
