#!/usr/bin/env bash
# Source this file to activate the toolchain installed by setup-cloud.sh.
export JAVA_HOME=/workspace/.ai-assistant-toolchain/jdk-21.0.13.1
export MAVEN_USER_HOME=/workspace/.ai-assistant-toolchain/maven-user
export PATH="$JAVA_HOME/bin:/workspace/.ai-assistant-toolchain/apache-maven-3.9.16/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dmaven.repo.local=$MAVEN_USER_HOME/repository"

# Maven needs explicit proxy settings in Codex Cloud. The shell already
# supplies the proxy and CA to download tools such as curl.
if [[ -n ${CODEX_PROXY_CERT:-} && -n ${HTTPS_PROXY:-} ]]; then
    if [[ $HTTPS_PROXY =~ ^http://([a-zA-Z0-9._-]+):([0-9]+)/?$ ]]; then
        export AI_ASSISTANT_PROXY_HOST="${BASH_REMATCH[1]}"
        cloud_proxy_port="${BASH_REMATCH[2]}"
    else
        printf 'Unsupported Codex HTTPS proxy URL.\n' >&2
        return 1
    fi
    cloud_settings="$(dirname -- "${BASH_SOURCE[0]}")/cloud-maven-settings.xml"
    mkdir -p "$MAVEN_USER_HOME" || return
    sed "s/@PROXY_PORT@/$cloud_proxy_port/" "$cloud_settings" > "$MAVEN_USER_HOME/cloud-settings.xml" || return
    export MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }--global-settings $MAVEN_USER_HOME/cloud-settings.xml"
    export MAVEN_OPTS="$MAVEN_OPTS -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
    unset cloud_settings cloud_proxy_port
fi
