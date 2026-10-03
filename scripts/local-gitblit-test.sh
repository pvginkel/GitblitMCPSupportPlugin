#!/usr/bin/env bash
# Runs the pytest suite in tests/ against a throwaway Gitblit 1.10.0 with this
# repo's built plugin installed. `kc project test` runs it after the build.
#
# Each run unpacks a fresh Gitblit under $LOCAL_GITBLIT_DIR (the tarball is
# downloaded once and kept), bare-clones this environment's three checkouts
# into it with `gitblit.indexBranch default`, installs
# target/mcp-support-plugin-1.0.0.zip, starts Gitblit in the `java` tool
# container on port 8089 and waits for the first Lucene index pass, about a
# minute after start. Gitblit is stopped when the script exits.
#
# Run it from the dev container; extra arguments go to pytest, e.g.
#   scripts/local-gitblit-test.sh tests/test_search_files.py -k wildcard

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${LOCAL_GITBLIT_DIR:-/work/scratch/local-gitblit}
VERSION=1.10.0
PORT=8089
TARBALL=$WORK/gitblit-$VERSION.tar.gz
GITBLIT_HOME=$WORK/gitblit-$VERSION
LOG=$WORK/gitblit.log
PLUGIN_ZIP=$REPO_ROOT/target/mcp-support-plugin-1.0.0.zip
SEED_REPOS=(GitblitMCPServer GitblitMCPSupportPlugin GitSyncDeploy)
# How long to wait for the first index pass, and the hard cap on Gitblit's life.
INDEX_TIMEOUT=300
GITBLIT_TIMEOUT=1800

log() { echo "local-gitblit: $*" >&2; }

if [[ ! -f $PLUGIN_ZIP ]]; then
    log "$PLUGIN_ZIP is missing; run \`kc project build\` first"
    exit 1
fi
if curl -s -o /dev/null -m 2 "http://localhost:$PORT/"; then
    log "something already listens on port $PORT; stop it first"
    exit 1
fi

mkdir -p "$WORK"
if [[ ! -f $TARBALL ]]; then
    log "downloading Gitblit $VERSION"
    curl -sSfL -o "$TARBALL.part" \
        "https://github.com/gitblit-org/gitblit/releases/download/v$VERSION/gitblit-$VERSION.tar.gz"
    mv "$TARBALL.part" "$TARBALL"
fi

# A fresh unpack each run, so no index, temp dir or extracted plugin of an
# earlier run survives into this one.
rm -rf "$GITBLIT_HOME"
tar xzf "$TARBALL" -C "$WORK"

for name in "${SEED_REPOS[@]}"; do
    bare=$GITBLIT_HOME/data/git/pvginkel/$name.git
    git clone -q --bare --no-hardlinks "/work/$name" "$bare"
    git --git-dir="$bare" config gitblit.indexBranch default
done

cat >> "$GITBLIT_HOME/data/gitblit.properties" <<EOF
server.httpPort = $PORT
server.httpsPort = 0
git.sshPort = 0
git.daemonPort = 0
EOF
mkdir -p "$GITBLIT_HOME/data/plugins"
cp "$PLUGIN_ZIP" "$GITBLIT_HOME/data/plugins/"

log "starting Gitblit on port $PORT, log in $LOG"
timeout "$GITBLIT_TIMEOUT" cexec java sh -c \
    "cd '$GITBLIT_HOME' && exec java -cp 'gitblit.jar:ext/*' com.gitblit.GitBlitServer --baseFolder data" \
    > "$LOG" 2>&1 &
gitblit_pid=$!

# Stopping the cexec stops the java process in the tool container.
stop_gitblit() {
    if kill -0 "$gitblit_pid" 2>/dev/null; then
        log "stopping Gitblit"
        kill "$gitblit_pid" 2>/dev/null || true
        wait "$gitblit_pid" 2>/dev/null || true
    fi
}
trap stop_gitblit EXIT

log "waiting up to ${INDEX_TIMEOUT}s for the first Lucene index pass"
deadline=$((SECONDS + INDEX_TIMEOUT))
for name in "${SEED_REPOS[@]}"; do
    until grep -q "Built pvginkel/$name.git Lucene index" "$LOG"; do
        if ! kill -0 "$gitblit_pid" 2>/dev/null; then
            log "Gitblit exited before indexing; see $LOG"
            exit 1
        fi
        if ((SECONDS >= deadline)); then
            log "no index of pvginkel/$name.git after ${INDEX_TIMEOUT}s; see $LOG"
            exit 1
        fi
        sleep 2
    done
done
log "indexed; running pytest"

cd "$REPO_ROOT"
cexec python sh -c \
    'cd tests && poetry install -q && exec env GITBLIT_URL="$0" poetry run pytest "$@"' \
    "http://localhost:$PORT" "$@"
