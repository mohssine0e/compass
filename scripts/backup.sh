#!/usr/bin/env bash
# Compass backup (V3-0.3).
#
# Two things get copied, because two things are unrecoverable if this laptop dies:
#
#   1. The database — every idea, roadmap, step, and the learner profile.
#   2. The markdown docs — CLAUDE.md, TASKS_v*.md, SETUP.md, docs/. These are deliberately
#      gitignored (only README.md is tracked), so unlike the source code they have no copy in
#      any git remote. CLAUDE.md in particular is the stated source of truth for the project.
#
# Run it by hand, or nightly via cron — see SETUP.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${COMPASS_BACKUP_DIR:-$HOME/compass-backups}"
KEEP="${COMPASS_BACKUP_KEEP:-14}"
STAMP="$(date +%Y-%m-%d_%H%M%S)"

# Same defaults as application.properties, overridable by the same env vars.
DB_HOST="${COMPASS_DB_HOST:-localhost}"
DB_PORT="${COMPASS_DB_PORT:-5433}"
DB_NAME="${COMPASS_DB_NAME:-compass}"
DB_USER="${COMPASS_DB_USER:-$USER}"

mkdir -p "$BACKUP_DIR"

dump="$BACKUP_DIR/compass-db-$STAMP.sql.gz"
echo "Dumping $DB_NAME@$DB_HOST:$DB_PORT -> $dump"
pg_dump --host="$DB_HOST" --port="$DB_PORT" --username="$DB_USER" \
        --no-owner --no-privileges "$DB_NAME" | gzip > "$dump"

docs="$BACKUP_DIR/compass-docs-$STAMP.tar.gz"
echo "Archiving untracked docs -> $docs"
tar --create --gzip --file "$docs" --directory "$REPO_ROOT" \
    --ignore-failed-read \
    CLAUDE.md SETUP.md README.md docs \
    $(cd "$REPO_ROOT" && ls TASKS*.md 2>/dev/null || true)

# Keep the last N of each kind; a backup dir that grows forever is its own problem.
prune() {
  local pattern="$1"
  # shellcheck disable=SC2012  # filenames are timestamped by this script, so ls sorts correctly
  ls -1t "$BACKUP_DIR"/$pattern 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r old; do
    echo "Pruning $(basename "$old")"
    rm -f "$old"
  done
}
prune 'compass-db-*.sql.gz'
prune 'compass-docs-*.tar.gz'

echo "Done. $(ls -1 "$BACKUP_DIR" | wc -l) files in $BACKUP_DIR"
