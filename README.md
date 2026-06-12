# Jenkins CI/CD Practice

A self-contained Jenkins CI/CD practice environment with a Python Flask app, Docker Compose infrastructure, and a shared pipeline library covering advanced Jenkins patterns.

## What's inside

| Advanced topic | Where it lives |
|---|---|
| Shared library global vars | `vars/pythonPipeline.groovy`, `vars/deployApp.groovy` |
| Shared library classes | `src/com/myorg/GitUtils.groovy` |
| Docker agents | `Jenkinsfile.advanced` — `agent { docker { image ... } }` |
| Credentials binding | `credentials()` env var + `withCredentials` block |
| Parallel stages | Quality Gates — lint, security scan, dep-audit run concurrently |
| Multi-branch strategy | `Jenkinsfile.multibranch` — different behaviour per branch pattern |
| Human approval gate | `input` step before production deploy |
| Build parameters | `parameters { choice ... booleanParam ... }` |
| `when` conditions | `branch`, `expression`, `allOf`, `anyOf`, `not { buildingTag() }` |
| Jenkins Configuration as Code | `jenkins/casc/jenkins.yaml` — no click-ops required |
| `[skip ci]` support | `GitUtils.shouldSkipCi()` reads last commit message |

---

## Project layout

```
.
├── Jenkinsfile                  # Simple: delegates everything to pythonPipeline()
├── Jenkinsfile.advanced         # Annotated: every advanced pattern with comments
├── Jenkinsfile.multibranch      # Branch strategy: main / release/* / feature/*
│
├── vars/                        # Shared library — global steps
│   ├── pythonPipeline.groovy    # Full pipeline as a single callable step
│   ├── deployApp.groovy         # Reusable deploy step with prod approval gate
│   └── notifySlack.groovy       # Centralised Slack notifications
│
├── src/com/myorg/
│   └── GitUtils.groovy          # Shared library class (instance state, private helpers)
│
├── app/                         # Flask REST API
│   ├── app.py                   # Application code
│   ├── requirements.txt
│   ├── Dockerfile
│   └── tests/
│       ├── conftest.py
│       └── test_app.py
│
├── jenkins/
│   ├── Dockerfile               # Custom Jenkins LTS image with Docker CLI + plugins
│   ├── plugins.txt              # Plugins baked in at image build time
│   └── casc/
│       └── jenkins.yaml         # Jenkins Configuration as Code
│
├── scripts/
│   └── setup.sh                 # First-time setup helper
├── docker-compose.yml
└── .env                         # Local secrets (never commit real values)
```

---

## Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin)
- Git

---

## Quick start

```bash
# 1. Boot Jenkins
docker compose up -d --build jenkins

# 2. Wait ~60 s, then open the UI
open http://localhost:8080
# Login: admin / admin  (set JENKINS_ADMIN_PASSWORD in .env to change)

# 3. Initialise a local git repo (required for the shared library)
git init && git add . && git commit -m "init"
```

Or use the helper script which does all three steps:

```bash
./scripts/setup.sh
```

---

## Creating pipeline jobs

### Option A — Simple Pipeline (uses shared library)

1. Jenkins UI → **New Item** → **Pipeline**
2. Pipeline → Definition: **Pipeline script from SCM**
3. SCM: **Git**, Repository URL: `file:///path/to/this/repo`
4. Script Path: `Jenkinsfile`
5. Save → **Build Now**

`Jenkinsfile` is a single call to `pythonPipeline()`. All the implementation is in `vars/pythonPipeline.groovy`.

### Option B — Advanced Pipeline (all patterns inline)

Same steps as above but set Script Path to `Jenkinsfile.advanced`.

This file is heavily commented and demonstrates every advanced pattern directly — Docker agents, parallel stages, credentials binding, `when` conditions, build parameters, and the human approval gate.

### Option C — Multi-Branch Pipeline

1. Jenkins UI → **New Item** → **Multibranch Pipeline**
2. Branch Sources → **Git**, Repository URL: `file:///path/to/this/repo`
3. Build Configuration → Script Path: `Jenkinsfile.multibranch`
4. Save

Jenkins scans all branches and applies the appropriate behaviour:

| Branch | Stages run |
|---|---|
| `main` | Install → Lint → Test → Build image → Push → Deploy staging |
| `release/*` | Same as main + Deploy production (with approval) |
| `feature/*` | Install → Lint → Test only |
| Pull requests | Same as feature + GitHub status check |

---

## Shared library

The `vars/` and `src/` directories form a Jenkins Shared Library. Jenkins is configured via JCasC to load it automatically from this repo, so pipelines don't need an explicit `@Library` annotation.

### Global vars (`vars/`)

Each `.groovy` file in `vars/` becomes a callable step:

```groovy
// Any Jenkinsfile can call these directly:
pythonPipeline(appDir: 'app', pythonVersion: '3.12')

deployApp(imageName: 'my-org/flask-app:abc123', environment: 'staging')

notifySlack(status: 'FAILURE')
```

### Library classes (`src/`)

Classes under `src/` are imported explicitly and support instance state and private methods:

```groovy
import com.myorg.GitUtils

def git = new GitUtils(this)
echo git.shortSha()       // abc1234
echo git.branch()         // main
git.shouldSkipCi()        // true if [skip ci] in last commit message
git.tagCommit('v1.2.3')   // push a git tag from the pipeline
```

### When to use vars vs classes

Use a **global var** (`vars/`) when you want a step that reads like a built-in Jenkins step (`sh`, `checkout`, etc.) — clean call-site syntax, no import needed.

Use a **class** (`src/`) when you need instance state shared across method calls, private helper methods, or cleaner namespacing.

---

## Credentials

Credentials are configured in `jenkins/casc/jenkins.yaml`. Secrets are injected via environment variables — never hardcoded.

| Credential ID | Type | Used for |
|---|---|---|
| `docker-hub-creds` | Username/Password | `docker login` in Push stage |
| `sonar-token` | Secret text | SonarQube analysis (placeholder) |

Set real values in `.env` before running:

```bash
DOCKERHUB_USER=myuser
DOCKERHUB_PASS=mypassword
```

### Using credentials in a pipeline

```groovy
// Pattern 1 — environment variable binding (available in all stages)
environment {
    DOCKER_CREDS = credentials('docker-hub-creds')
    // Injects: DOCKER_CREDS_USR and DOCKER_CREDS_PSW
}

// Pattern 2 — scoped to a single block (preferred for tighter scope)
withCredentials([usernamePassword(
    credentialsId: 'docker-hub-creds',
    usernameVariable: 'USER',
    passwordVariable: 'PASS'
)]) {
    sh 'echo $PASS | docker login -u $USER --password-stdin'
}
```

Both patterns mask the secret in build logs automatically.

---

## Pipeline stages

```
Install → Quality Gates (parallel) → Test → Build Image → Push Image → Deploy
                ├─ Lint
                ├─ Security (Bandit)
                └─ Dependency Audit
```

The Push and Deploy stages are gated by `when { branch 'main' }` so feature branches only run the first three stages.

---

## Human approval gate

The `deployApp` step pauses the build and waits for a named approver before touching production:

```groovy
input(
    message: "Deploy ${IMAGE_TAG} to PRODUCTION?",
    ok: 'Approve',
    submitter: 'admin,release-team'
)
```

The build waits up to 1 hour (configurable via `timeout`). If nobody approves, the build is aborted and the deployment does not happen.

---

## Running the app locally

```bash
# Start the Flask app on port 5000
docker compose --profile deploy up -d app

curl http://localhost:5000/health
# {"status": "ok"}

curl -X POST http://localhost:5000/items -H 'Content-Type: application/json' -d '{"name": "widget"}'
# {"id": 1, "name": "widget"}
```

---

## Running tests locally

```bash
cd app
pip install -r requirements.txt
pytest tests/ -v --cov=. --cov-report=term-missing
```

---

## Stopping everything

```bash
docker compose down          # stop containers, keep jenkins_home volume
docker compose down -v       # stop and delete all data (fresh start)
```
