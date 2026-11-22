#!/bin/sh
# Builds hello-plugin.zip. Needs JDK 25 and the two jars from `./gradlew :shadowJar :manager:shadowJar`
# (or set ZENITH_JAR and MANAGER_JAR to the ones from your ZenithHub image: /app/zenith/ZenithProxy.jar, /app/manager.jar).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
ZENITH_JAR=${ZENITH_JAR:-$repo/build/libs/ZenithProxy.jar}
MANAGER_JAR=${MANAGER_JAR:-$repo/manager/build/libs/zenith-manager.jar}
out=$here/build
rm -rf "$out" && mkdir -p "$out/classes/manager" "$out/classes/bot" "$out/zip/manager" "$out/zip/bot"

javac --release 25 -proc:none -cp "$MANAGER_JAR" -d "$out/classes/manager" $(find "$here/manager/src" -name '*.java')
javac --release 25 -proc:none -cp "$ZENITH_JAR" -d "$out/classes/bot" $(find "$here/bot/src" -name '*.java')
jar --create --file "$out/zip/manager/hello-manager.jar" -C "$out/classes/manager" .
jar --create --file "$out/zip/bot/hello-bot.jar" -C "$out/classes/bot" .
cp "$here/plugin.json" "$out/zip/"
cp -r "$here/web" "$out/zip/web"
(cd "$out/zip" && rm -f ../hello-plugin.zip && jar --create --no-manifest --file ../hello-plugin.zip .)
echo "$out/hello-plugin.zip"
