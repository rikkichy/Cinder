#!/bin/bash
# Run this after: git merge upstream/master
# Removes modules that Cinder doesn't use but upstream still tracks.

set -e
cd "$(git rev-parse --show-toplevel)"

MODULES=(
    TMessagesProj_AppHuawei
    TMessagesProj_AppHockeyApp
    TMessagesProj_AppStandalone
    TMessagesProj_AppTests
)

for mod in "${MODULES[@]}"; do
    if [ -d "$mod" ] || git ls-files --error-unmatch "$mod" &>/dev/null; then
        echo "Removing $mod..."
        git rm -rf --cached "$mod" 2>/dev/null || true
        rm -rf "$mod"
    fi
done

# Restore settings.gradle to only include our modules
cat > settings.gradle << 'EOF'
include ':TMessagesProj'
include ':TMessagesProj_App'
EOF

echo ""
echo "Done. Review these files for merge conflicts before committing:"
echo "  - build.gradle (remove Huawei maven repo and agconnect plugin if re-added)"
echo "  - TMessagesProj/build.gradle (remove HA_*/standalone build types if re-added)"
echo "  - TMessagesProj_App/build.gradle (remove standalone build type if re-added)"
