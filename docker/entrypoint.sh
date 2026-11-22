#!/bin/sh
# Runs the manager and starts it again when it exits with 75, which the Plugins page asks for
# to load plugin changes. Any other exit code ends the container as usual.
set -u
child=0
trap 'if [ "$child" -ne 0 ]; then kill -TERM "$child" 2>/dev/null; wait "$child"; fi; exit 143' TERM INT
export ZENITH_SUPERVISED=1
while true; do
  java -XX:+UseSerialGC -Xmx160m -XX:+ExitOnOutOfMemoryError -jar "${ZENITH_MANAGER_JAR:-/app/manager.jar}" "$@" &
  child=$!
  wait "$child"
  code=$?
  child=0
  if [ "$code" -ne 75 ]; then exit "$code"; fi
  echo "Restarting ZenithHub to load plugin changes"
done
