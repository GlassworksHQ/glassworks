#!/bin/sh
# Deploys Refract to Cloudflare Workers at refract.glassworks.dev, with the wrangler that this
# folder's package.json pins (its own lockfile; Refract isn't a root workspace). Touches the
# Cloudflare account only: uploads the Worker script, its two rate limiters, and attaches its custom
# domain (Cloudflare creates the DNS record and certificate). Runs the typecheck and tests first.
#
# Sign in either way, never with a file:
#   - `node_modules/.bin/wrangler login` once on this machine (OAuth), then ./deploy.sh
#   - CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID in the environment (permissions in README.md)
# Extra arguments go to `wrangler deploy`: ./deploy.sh --dry-run --outdir /tmp/refract builds only.
set -eu
cd "$(dirname "$0")"
export WRANGLER_SEND_METRICS=false

if [ -n "${CLOUDFLARE_API_TOKEN:-}" ] && [ -z "${CLOUDFLARE_ACCOUNT_ID:-}" ]; then
  echo "CLOUDFLARE_API_TOKEN is set but CLOUDFLARE_ACCOUNT_ID isn't; set both, or unset the token to use wrangler login" >&2
  exit 1
fi

wrangler=node_modules/.bin/wrangler
want=$(node -p 'require("./package.json").devDependencies.wrangler')
if [ ! -x "$wrangler" ]; then
  echo "No wrangler here. Run: npm ci --prefix $(pwd)" >&2
  exit 1
fi
have=$("$wrangler" --version | tail -n 1)
case "$have" in
  *"$want"*) ;;
  *) echo "wrangler $want expected (package.json), found: $have. Run: npm ci --prefix $(pwd)" >&2; exit 1 ;;
esac

node_modules/.bin/tsc --noEmit
node_modules/.bin/vitest run
exec "$wrangler" deploy "$@"
