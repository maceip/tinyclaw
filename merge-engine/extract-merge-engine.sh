#!/usr/bin/env bash
set -euo pipefail

# extract-merge-engine.sh
#
# Extract the merge-engine crate from the tinyclaw monorepo into its own
# standalone git repository, preserving the commit history for files under
# merge-engine/.
#
# Prerequisites:
#   pip install git-filter-repo   (or: brew install git-filter-repo)
#
# Usage:
#   ./merge-engine/extract-merge-engine.sh [target-dir]
#
# This will:
#   1. Clone tinyclaw into a temp directory
#   2. Use git-filter-repo to keep only merge-engine/ history
#   3. Move files from merge-engine/ subdirectory to repo root
#   4. Set up the new remote (github.com/maceip/merge-engine)
#   5. Copy result to target directory (default: ../merge-engine)

TARGET_DIR="${1:-$(dirname "$(cd "$(dirname "$0")" && pwd)")/merge-engine-standalone}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TMPDIR="$(mktemp -d)"

echo "==> Extracting merge-engine from tinyclaw"
echo "    Source: $REPO_ROOT"
echo "    Target: $TARGET_DIR"
echo "    Temp:   $TMPDIR"
echo

# Step 1: Clone the repo (local clone for speed)
echo "==> Step 1: Cloning tinyclaw..."
git clone --no-hardlinks "$REPO_ROOT" "$TMPDIR/tinyclaw"
cd "$TMPDIR/tinyclaw"

# Step 2: Filter to only merge-engine/ files
echo "==> Step 2: Filtering to merge-engine/ subtree..."
if command -v git-filter-repo &>/dev/null; then
    git filter-repo --subdirectory-filter merge-engine/ --force
elif python3 -c "import git_filter_repo" 2>/dev/null; then
    python3 -m git_filter_repo --subdirectory-filter merge-engine/ --force
else
    echo "ERROR: git-filter-repo is not installed."
    echo "Install it with: pip install git-filter-repo"
    echo ""
    echo "Alternative manual steps:"
    echo "  1. cp -r $REPO_ROOT/merge-engine $TARGET_DIR"
    echo "  2. cd $TARGET_DIR"
    echo "  3. git init && git add -A && git commit -m 'Initial commit: extract from tinyclaw'"
    echo "  4. git remote add origin https://github.com/maceip/merge-engine.git"
    rm -rf "$TMPDIR"
    exit 1
fi

# Step 3: Clean up workspace references
echo "==> Step 3: Cleaning up..."
# Remove any workspace-level references that might remain
if grep -q "workspace" Cargo.toml 2>/dev/null; then
    echo "    (Cargo.toml already standalone — no workspace references)"
fi

# Step 4: Set up the new remote
echo "==> Step 4: Setting up remote..."
git remote add origin https://github.com/maceip/merge-engine.git 2>/dev/null || \
    git remote set-url origin https://github.com/maceip/merge-engine.git

# Step 5: Copy to target directory
echo "==> Step 5: Copying to $TARGET_DIR..."
if [ -d "$TARGET_DIR" ]; then
    echo "WARNING: $TARGET_DIR already exists. Overwriting..."
    rm -rf "$TARGET_DIR"
fi
cp -r "$TMPDIR/tinyclaw" "$TARGET_DIR"

# Cleanup
rm -rf "$TMPDIR"

echo
echo "==> Done! merge-engine extracted to: $TARGET_DIR"
echo
echo "Next steps:"
echo "  cd $TARGET_DIR"
echo "  git log --oneline        # verify history was preserved"
echo "  cargo test               # verify everything still works"
echo "  git push -u origin main  # push to github.com/maceip/merge-engine"
echo
echo "To create the GitHub repo first:"
echo "  gh repo create maceip/merge-engine --public --description 'Non-LLM merge conflict resolver'"
