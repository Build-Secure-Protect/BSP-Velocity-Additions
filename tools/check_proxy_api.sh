#!/bin/sh
# Before a release: checks that the built plugin jar calls no Velocity or Adventure method the network's proxy lacks
# (bug B3). Arguments: [plugin jar] [proxy velocity.jar]; see tools/check_proxy_api.py.
cd "$(dirname "$0")/.." || exit 2
exec python3 tools/check_proxy_api.py "$@"
