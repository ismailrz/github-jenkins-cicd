/**
 * Jenkinsfile  (declarative pipeline)
 *
 * This is what you'd put in a real repo's root.  It delegates all
 * the implementation details to the shared library step `pythonPipeline`.
 *
 * The pipeline:
 *  Install → Quality Gates (lint ‖ security) → Test → Build image
 *      → Push image (main only) → Deploy (main only)
 *
 * Advanced features demonstrated:
 *  ✓ Shared library global var (pythonPipeline)
 *  ✓ Docker agent — build runs inside a container
 *  ✓ Credentials binding — no secrets in source code
 *  ✓ Parallel stages — lint and security run concurrently
 *  ✓ Branch-conditional stages (when { branch 'main' })
 *  ✓ Human approval gate before production deploy
 *  ✓ Post-build actions (JUnit, coverage, workspace cleanup)
 *  ✓ Build timeout and disableConcurrentBuilds
 */

// @Library annotation is optional because the library is loaded as
// 'implicit: true' in JCasC.  Un-comment if you need a specific version:
// @Library('pipeline-library@v1.2.0') _

pythonPipeline(
    appDir:       'app',
    pythonVersion: '3.12',
    imageName:    'my-org/flask-app',
    deployBranch: 'main'
)
