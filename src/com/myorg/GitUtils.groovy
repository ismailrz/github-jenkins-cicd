/**
 * src/com/myorg/GitUtils.groovy
 *
 * Shared library class (as opposed to a global var).
 * Classes live under src/ and are imported explicitly:
 *
 *   import com.myorg.GitUtils
 *   def utils = new GitUtils(this)
 *   String sha = utils.shortSha()
 *
 * Use classes when you need:
 *  - Instance state across multiple method calls
 *  - Private helper methods you don't want to expose as pipeline steps
 *  - Cleaner namespacing than global vars/
 */
package com.myorg

class GitUtils implements Serializable {
    // 'steps' is the pipeline context — needed to call sh, echo, etc.
    private final def steps

    GitUtils(steps) { this.steps = steps }

    String shortSha() {
        return steps.sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    }

    String branch() {
        return steps.env.BRANCH_NAME ?: steps.sh(
            script: 'git rev-parse --abbrev-ref HEAD',
            returnStdout: true
        ).trim()
    }

    /** Returns true when the last commit message contains [skip ci] */
    boolean shouldSkipCi() {
        String msg = steps.sh(
            script: 'git log -1 --pretty=%B',
            returnStdout: true
        ).trim()
        return msg.contains('[skip ci]')
    }

    /** Tag the commit with the given label (e.g. 'release-1.2.3') */
    void tagCommit(String tag, String credentialsId = '') {
        steps.withCredentials([
            steps.usernamePassword(
                credentialsId: credentialsId ?: 'github-token',
                usernameVariable: 'GIT_USER',
                passwordVariable: 'GIT_PASS'
            )
        ]) {
            steps.sh """
                git config user.email "ci@example.com"
                git config user.name  "Jenkins CI"
                git tag -a ${tag} -m "Tagged by Jenkins build ${steps.env.BUILD_NUMBER}"
                git push origin ${tag}
            """
        }
    }
}
