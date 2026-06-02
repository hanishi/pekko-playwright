# syntax=docker/dockerfile:1
#
# crawler.Main — the Pekko + Playwright web crawler.
#
# The app launches headless Chromium in-process via Playwright, so the runtime
# stage is Microsoft's official Playwright-for-Java image, whose bundled
# Chromium build is version-matched to the
# `com.microsoft.playwright % playwright % 1.53.0` dependency. A pinned
# Temurin 21 JRE is copied in so the Java version is deterministic regardless
# of what the Playwright image ships.
#
# Build (from repo root):
#   docker build -t pekko-crawler:latest .
#
# Run (writes ./out/crawler-output.csv on the host):
#   mkdir -p out
#   docker run --rm --ipc=host -v "$PWD/out:/data" pekko-crawler:latest \
#     https://edition.cnn.com/business 2

# ---- build stage: compile + stage the app with sbt-native-packager ----
# The bundled sbt/scala in the tag are irrelevant — the sbt launcher
# auto-fetches the version pinned in project/build.properties and build.sbt's
# scalaVersion. We only need a Java 21 base.
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.11_3.8.3 AS build
WORKDIR /src

# Warm the dependency cache on the build definition only.
COPY build.sbt .
COPY project/ project/
RUN sbt update

# Now the sources.
COPY src/ src/
RUN sbt stage
# Output: /src/target/universal/stage/{bin,lib}

# ---- jre stage: a pinned Java 21 runtime to copy in ----
FROM eclipse-temurin:21-jre-jammy AS jre

# ---- runtime stage: Playwright (Chromium + system libs) + JRE 21 ----
FROM mcr.microsoft.com/playwright/java:v1.53.0-jammy

# Deterministic Java 21 (the Playwright image's bundled JDK version is not
# guaranteed). The sbt-native-packager launcher prefers $JAVA_HOME.
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /app
COPY --from=build /src/target/universal/stage/ /app/

# In a container Chromium can't use its namespace sandbox; BrowserResource
# reads CHROMIUM_NO_SANDBOX. Default the CSV under the /data volume.
ENV CHROMIUM_NO_SANDBOX=true
ENV CRAWLER_OUTPUT=/data/crawler-output.csv
RUN mkdir -p /data
VOLUME ["/data"]

# bin/crawler is the native-packager launcher (mainClass=crawler.Main).
# Args: [seed-url] [max-depth] [output-csv]
ENTRYPOINT ["/app/bin/crawler"]
