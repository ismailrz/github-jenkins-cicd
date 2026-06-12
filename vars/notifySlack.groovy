/**
 * vars/notifySlack.groovy
 *
 * Centralised Slack notification step.  Usage:
 *
 *   notifySlack(status: 'SUCCESS', message: 'Build 42 passed')
 *   notifySlack(status: 'FAILURE')   // uses default message
 *
 * Keeping notification logic here means you change the Slack channel or
 * message format in one place, not in every Jenkinsfile.
 */
def call(Map config = [:]) {
    String status  = config.status  ?: currentBuild.currentResult
    String channel = config.channel ?: '#ci-alerts'
    String msg     = config.message ?: defaultMessage(status)

    // The slack-notification plugin must be installed and configured with a
    // credential named 'slack-token'.  This step degrades gracefully when
    // Slack isn't configured (useful in local dev environments).
    try {
        slackSend(
            channel: channel,
            color:   statusColor(status),
            message: msg
        )
    } catch (err) {
        echo "Slack notification skipped (plugin not configured): ${err.message}"
    }
}

private String defaultMessage(String status) {
    String emoji = status == 'SUCCESS' ? ':white_check_mark:' : ':x:'
    return "${emoji} *${status}* — ${env.JOB_NAME} #${env.BUILD_NUMBER} " +
           "(<${env.BUILD_URL}|Open>)"
}

private String statusColor(String status) {
    return status == 'SUCCESS' ? 'good' : 'danger'
}
