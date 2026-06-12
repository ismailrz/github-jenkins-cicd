#!/usr/bin/env bash
# scripts/setup.sh — first-time setup helper

set -euo pipefail

echo "=== Jenkins CI/CD Practice Setup ==="

# 1. Start Jenkins
echo "Starting Jenkins..."
docker compose up -d --build jenkins

# 2. Wait for Jenkins to be ready
echo "Waiting for Jenkins to start (this takes ~60 s on first boot)..."
until curl -sf http://localhost:8080/login > /dev/null 2>&1; do
    printf '.'
    sleep 3
done
echo ""
echo "Jenkins is up at http://localhost:8080  (admin / admin)"

# 3. Initialise a local git repo so Jenkins can scan it as a shared library
if [ ! -d .git ]; then
    git init
    git add .
    git commit -m "Initial commit — Jenkins CI/CD practice setup"
    echo "Git repo initialised."
fi

echo ""
echo "Next steps:"
echo "  1. Open http://localhost:8080 and log in (admin/admin)"
echo "  2. Create a Pipeline job → set Script Path to 'Jenkinsfile'"
echo "  3. Or create a Multi-Branch Pipeline → set Script Path to 'Jenkinsfile.multibranch'"
echo "  4. See README.md for full walkthrough."
