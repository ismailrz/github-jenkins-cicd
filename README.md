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
│   ├── app.py
│   ├── requirements.txt
│   ├── Dockerfile
│   └── tests/
│       ├── conftest.py
│       └── test_app.py
│
├── jenkins/
│   ├── Dockerfile               # Custom Jenkins LTS image with Docker CLI + plugins
│   ├── plugins.txt              # Plugins baked in at image build time
│   ├── init.groovy.d/
│   │   └── 01-create-admin.groovy   # Creates admin user on first boot
│   └── casc/
│       └── jenkins.yaml         # Jenkins Configuration as Code
│
├── scripts/
│   └── setup.sh                 # First-time setup helper
├── docker-compose.yml
└── .env                         # Local secrets (never commit real values)
```

---

## Deploy locally (macOS / Linux)

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (macOS / Windows) or Docker Engine + Compose plugin (Linux)
- Git

### Steps

**1. Clone the repo**

```bash
git clone https://github.com/ismailrz/github-jenkins-cicd.git
cd github-jenkins-cicd
```

**2. Set your admin password**

```bash
# .env is already in .gitignore — safe to put real values here
echo "JENKINS_ADMIN_PASSWORD=yourpassword" > .env
```

**3. Start Jenkins**

```bash
docker compose up -d --build jenkins
```

Jenkins takes ~60 seconds on first boot to install plugins.

**4. Open the UI**

```
http://localhost:8080
```

Login: `admin` / the password you set in `.env`

**5. Create a pipeline job**

1. **New Item** → name it → **Pipeline** → OK
2. Pipeline section → Definition: **Pipeline script from SCM**
3. SCM: **Git**, Repository URL: `https://github.com/ismailrz/github-jenkins-cicd.git`
4. Branch Specifier: `*/main`
5. Script Path: `Jenkinsfile`
6. **Save** → **Build Now**

### Stopping

```bash
docker compose down        # stop, keep Jenkins data
docker compose down -v     # stop and wipe all data (fresh start)
```

---

## Deploy to AWS (EC2 + Docker Compose)

### Prerequisites

- An AWS account
- AWS CLI or console access

### 1. Launch an EC2 instance

| Setting | Value |
|---|---|
| AMI | Ubuntu 24.04 LTS |
| Instance type | `t3.medium` (minimum — Jenkins needs 2 vCPU / 4 GB RAM) |
| Storage | 20 GB gp3 |
| Key pair | Create or select one for SSH access |

**Security group — open these ports:**

| Port | Source | Purpose |
|---|---|---|
| 22 | Your IP | SSH |
| 8080 | 0.0.0.0/0 | Jenkins UI |
| 50000 | 0.0.0.0/0 | Jenkins inbound agents |

### 2. SSH in and install Docker

```bash
ssh -i your-key.pem ubuntu@<EC2-PUBLIC-IP>

# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu
newgrp docker

# Install Compose plugin
sudo apt-get install -y docker-compose-plugin
```

### 3. Clone the repo and configure

```bash
git clone https://github.com/ismailrz/github-jenkins-cicd.git
cd github-jenkins-cicd

# Set your admin password
echo "JENKINS_ADMIN_PASSWORD=yourpassword" > .env
```

### 4. Start Jenkins

```bash
docker compose up -d --build jenkins
```

Wait ~60 seconds, then open:

```
http://<EC2-PUBLIC-IP>:8080
```

Login: `admin` / the password you set in `.env`

### 5. Create a pipeline job

Same as local — use the GitHub URL for the repository:

1. **New Item** → name it → **Pipeline** → OK
2. Pipeline → Definition: **Pipeline script from SCM**
3. SCM: **Git**, Repository URL: `https://github.com/ismailrz/github-jenkins-cicd.git`
4. Branch Specifier: `*/main`
5. Script Path: `Jenkinsfile`
6. **Save** → **Build Now**

### 6. (Optional) Add a domain + HTTPS

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

Create `/etc/nginx/sites-available/jenkins`:

```nginx
server {
    listen 80;
    server_name jenkins.yourdomain.com;

    location / {
        proxy_pass         http://localhost:8080;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/jenkins /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d jenkins.yourdomain.com
```

Then update `jenkins/casc/jenkins.yaml` to reflect the real URL:

```yaml
unclassified:
  location:
    url: "https://jenkins.yourdomain.com/"
```

### 7. Keep Jenkins running across reboots

```bash
sudo systemctl enable docker
# docker-compose already uses restart: unless-stopped
```

### Stopping / fresh start on EC2

```bash
docker compose down        # stop, keep Jenkins data
docker compose down -v     # stop and wipe all data
```

---

## Creating pipeline jobs

### Option A — Simple Pipeline

Uses the shared library — `Jenkinsfile` is a single call to `pythonPipeline()`:

1. **New Item** → **Pipeline** → OK
2. Definition: **Pipeline script from SCM**
3. SCM: **Git**, Repository URL: `https://github.com/ismailrz/github-jenkins-cicd.git`
4. Branch Specifier: `*/main`, Script Path: `Jenkinsfile`
5. **Save** → **Build Now**

### Option B — Advanced Pipeline (all patterns inline)

Same steps but set Script Path to `Jenkinsfile.advanced`. Heavily commented — every advanced pattern is explained inline.

### Option C — Multi-Branch Pipeline

1. **New Item** → **Multibranch Pipeline** → OK
2. Branch Sources → **Git**, Repository URL: `https://github.com/ismailrz/github-jenkins-cicd.git`
3. Script Path: `Jenkinsfile.multibranch`
4. **Save** — Jenkins scans all branches automatically

| Branch | Stages run |
|---|---|
| `main` | Install → Lint → Test → Build image → Push → Deploy staging |
| `release/*` | Same as main + Deploy production (with approval) |
| `feature/*` | Install → Lint → Test only |

---

## Shared library

The `vars/` and `src/` directories form a Jenkins Shared Library loaded automatically from this repo via JCasC — no `@Library` annotation needed in pipelines.

### Global vars (`vars/`)

```groovy
pythonPipeline(appDir: 'app', pythonVersion: '3.12')
deployApp(imageName: 'my-org/flask-app:abc123', environment: 'staging')
notifySlack(status: 'FAILURE')
```

### Library classes (`src/`)

```groovy
import com.myorg.GitUtils

def git = new GitUtils(this)
echo git.shortSha()       // abc1234
echo git.branch()         // main
git.shouldSkipCi()        // true if [skip ci] in last commit message
git.tagCommit('v1.2.3')   // push a git tag from the pipeline
```

**vars/ vs src/:** Use `vars/` for steps that should read like built-in Jenkins steps. Use `src/` when you need instance state, private helpers, or namespacing.

---

## Credentials

| Credential ID | Type | Used for |
|---|---|---|
| `docker-hub-creds` | Username/Password | `docker login` in Push stage |
| `sonar-token` | Secret text | SonarQube analysis (placeholder) |

Set real values in `.env`:

```bash
DOCKERHUB_USER=myuser
DOCKERHUB_PASS=mypassword
```

### Using credentials in a pipeline

```groovy
// Pattern 1 — environment variable binding
environment {
    DOCKER_CREDS = credentials('docker-hub-creds')
    // Injects DOCKER_CREDS_USR and DOCKER_CREDS_PSW — masked in logs
}

// Pattern 2 — scoped to a single block
withCredentials([usernamePassword(
    credentialsId: 'docker-hub-creds',
    usernameVariable: 'USER',
    passwordVariable: 'PASS'
)]) {
    sh 'echo $PASS | docker login -u $USER --password-stdin'
}
```

---

## Pipeline stages

```
Install → Quality Gates (parallel) → Test → Build Image → Push Image → Deploy
                ├─ Lint
                ├─ Security (Bandit)
                └─ Dependency Audit
```

Push and Deploy only run on the `main` branch.

---

## Human approval gate

The `deployApp` step pauses the build before production and waits up to 1 hour for a named approver:

```groovy
input(
    message: "Deploy ${IMAGE_TAG} to PRODUCTION?",
    ok: 'Approve',
    submitter: 'admin,release-team'
)
```

---

## Running the app locally

```bash
docker compose --profile deploy up -d app

curl http://localhost:5000/health
# {"status": "ok"}

curl -X POST http://localhost:5000/items \
  -H 'Content-Type: application/json' \
  -d '{"name": "widget"}'
# {"id": 1, "name": "widget"}
```

---

## Running tests locally

```bash
cd app
pip install -r requirements.txt
pytest tests/ -v --cov=. --cov-report=term-missing
```
