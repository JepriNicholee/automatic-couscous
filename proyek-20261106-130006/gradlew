#!/bin/sh

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"

DEFAULT_JVM_OPTS=""

if [ -n "$JAVA_HOME" ] ; then
    for i in "$JAVA_HOME/bin/java" ; do
        if [ -x "$i" ] ; then
            JAVACMD="$i"
            break
        fi
    done
fi

if [ -z "$JAVACMD" ] ; then
    if [ -n "$JAVA_HOME" ] ; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        JAVACMD="java"
    fi
fi

if [ ! -x "$JAVACMD" ] ; then
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    echo "Please set the JAVA_HOME variable in your environment to match the"
    echo "location of your Java installation."
    exit 1
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"