#!/bin/bash
# Bounded CI waiter for one run id. Polls `runs` every 90s, exits as
# soon as the run completes (or after 40 minutes). Never an open loop.
RUN_ID="$1"
DEADLINE=$((SECONDS + 2400))
while [ $SECONDS -lt $DEADLINE ]; do
  LINE=$(python3 ~/workspace/custom-connectors/custom.github/bin/gh_api.py runs DocBobert6 NotscapePioneer 2>/dev/null | grep "^$RUN_ID ")
  echo "$LINE"
  case "$LINE" in
    *"completed success"*) echo "CI GREEN run $RUN_ID"; exit 0 ;;
    *"completed"*) echo "CI NOT GREEN run $RUN_ID"; exit 1 ;;
  esac
  sleep 90
done
echo "CI WAIT TIMEOUT run $RUN_ID"
exit 2
