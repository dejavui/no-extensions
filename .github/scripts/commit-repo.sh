#!/bin/bash
set -e

git config --global user.email "github-actions[bot]@users.noreply.github.com"
git config --global user.name "github-actions[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push

    if [ -n "$REPO_NAME" ]; then
        curl "https://purge.jsdelivr.net/gh/${GITHUB_REPOSITORY_OWNER}/${REPO_NAME}@repo/index.min.json"
    else
        curl "https://purge.jsdelivr.net/gh/dejavui/not-extensions@repo/index.min.json"
    fi
else
    echo "No changes to commit"
fi
