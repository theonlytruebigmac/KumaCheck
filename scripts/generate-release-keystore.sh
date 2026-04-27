#!/usr/bin/env bash
#
# Generate the release-signing keystore and write keystore.properties.
# Run from the repo root. Outputs are gitignored.
#
# Both files end up in the repo root:
#   release.keystore       — the keystore itself (binary)
#   keystore.properties    — credentials Gradle reads at build time
#
# To use the same keystore in GitHub Actions, base64-encode it:
#   base64 -i release.keystore | pbcopy
# and paste into the RELEASE_KEYSTORE_BASE64 repo secret. Also add the
# store password / key alias / key password as separate secrets — see
# docs/RELEASE.md for the secret names the release workflow expects.

set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="release.keystore"
PROPS="keystore.properties"

if [[ -e "$KEYSTORE" || -e "$PROPS" ]]; then
    echo "Refusing to overwrite existing $KEYSTORE or $PROPS." >&2
    echo "Move or delete them first if you really want a fresh keystore." >&2
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "keytool not found on PATH. Install a JDK (or run 'brew install openjdk')." >&2
    exit 1
fi

read -r -p "Key alias [release]: " ALIAS
ALIAS="${ALIAS:-release}"

read -r -s -p "Store password (min 6 chars): " STORE_PW; echo
read -r -s -p "Confirm store password:       " STORE_PW_CONFIRM; echo
if [[ "$STORE_PW" != "$STORE_PW_CONFIRM" ]]; then
    echo "Store passwords don't match." >&2
    exit 1
fi

read -r -s -p "Key password (blank = same as store): " KEY_PW; echo
KEY_PW="${KEY_PW:-$STORE_PW}"

read -r -p "Distinguished Name (e.g. 'CN=KumaCheck, OU=Dev, O=KumaCheck, L=, S=, C=US'): " DNAME
DNAME="${DNAME:-CN=KumaCheck, OU=Dev, O=KumaCheck, C=US}"

keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PW" \
    -keypass "$KEY_PW" \
    -dname "$DNAME"

umask 077
cat > "$PROPS" <<EOF
storeFile=$KEYSTORE
storePassword=$STORE_PW
keyAlias=$ALIAS
keyPassword=$KEY_PW
EOF

chmod 600 "$PROPS"

echo
echo "Wrote $KEYSTORE and $PROPS (gitignored)."
echo "Test with: ./gradlew :app:assembleRelease"
