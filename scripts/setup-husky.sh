#!/bin/bash
set -e

echo "Setting up Git hooks with Husky..."

if [ ! -d ".husky" ]; then
  echo "Initializing Husky..."
  npx husky install
fi

echo "Creating pre-commit hook..."
cat > .husky/pre-commit << 'EOF'
#!/usr/bin/env sh
. "$(dirname -- "$0")/_/husky.sh"

npx lint-staged
EOF

echo "Creating commit-msg hook..."
cat > .husky/commit-msg << 'EOF'
#!/usr/bin/env sh
. "$(dirname -- "$0")/_/husky.sh"

npx --no -- commitlint --edit ${1}
EOF

chmod +x .husky/pre-commit
chmod +x .husky/commit-msg

echo "Husky setup completed successfully!"
