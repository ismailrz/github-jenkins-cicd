/**
 * vars/deployApp.groovy
 *
 * Reusable deploy step.  Usage in any pipeline:
 *
 *   deployApp(imageName: 'my-org/flask-app:abc123', environment: 'staging')
 *
 * Keeping deployment logic here (not inline in Jenkinsfile) means every
 * project benefits when you update the deploy procedure.
 */
def call(Map config = [:]) {
    String image  = config.imageName   ?: error('imageName is required')
    String env    = config.environment ?: 'staging'
    String port   = config.port        ?: '5000'

    echo "Deploying ${image} to ${env}"

    if (env == 'production') {
        // Require an explicit human approval before touching production.
        // Jenkins pauses the build and emails approvers (configure in JCasC).
        input message: "Deploy ${image} to PRODUCTION?",
              ok: 'Deploy',
              submitter: 'admin,release-team'
    }

    // In a real pipeline this might:
    //  - kubectl set image deployment/flask-app ...
    //  - ansible-playbook deploy.yml -e image=${image}
    //  - call an internal CD API
    // For this practice environment we use docker-compose.
    sh """
        IMAGE=${image} docker compose \
            --profile deploy \
            up -d --pull always app
    """

    // Smoke test — wait for /health to return 200
    retry(5) {
        sleep(time: 3, unit: 'SECONDS')
        sh "curl -sf http://localhost:${port}/health"
    }

    echo "Deploy to ${env} complete."
}
