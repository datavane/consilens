#!/bin/bash
#
# setenv.sh - Environment Variables Configuration for Consilens
#
# This script is sourced by consilens-cli.sh to set up environment variables
# and JVM parameters. You can customize these settings based on your environment.
#
# Usage:
#   This script is automatically loaded by the launcher scripts.
#   You can also override settings by setting environment variables before
#   running the launcher scripts.
#

#
# Java Options
#
# Default JVM parameters if JAVA_OPTS is not already set
# You can override this by setting JAVA_OPTS environment variable before
# running the script, or by modifying the values below.
#
if [ -z "$JAVA_OPTS" ]; then
    # Server mode for better performance
    JAVA_OPTS="-server"
    
    # Heap memory settings (8GB)
    # Adjust these values based on your available memory and workload
    JAVA_OPTS="$JAVA_OPTS -Xmx8g -Xms8g"
    
    # Garbage Collection settings
    # Using G1GC for better performance with large heaps
    JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
    JAVA_OPTS="$JAVA_OPTS -XX:MaxGCPauseMillis=200"
    JAVA_OPTS="$JAVA_OPTS -XX:InitiatingHeapOccupancyPercent=45"
    
    # GC Logging
    # Logs are written to ${LOG_DIR}/gc.log with rotation
    if [ -n "$LOG_DIR" ]; then
        JAVA_OPTS="$JAVA_OPTS -Xlog:gc*:file=${LOG_DIR}/gc.log:time,uptime:filecount=10,filesize=10M"
    fi
    
    # Out of Memory Error handling
    # Generate heap dump on OOM for troubleshooting
    if [ -n "$LOG_DIR" ]; then
        JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError"
        JAVA_OPTS="$JAVA_OPTS -XX:HeapDumpPath=${LOG_DIR}/heap_dump.hprof"
    fi
    
    # Performance tuning
    JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication"
    JAVA_OPTS="$JAVA_OPTS -XX:+OptimizeStringConcat"
fi

#
# JMX Configuration (Optional)
#
# Uncomment and configure these settings to enable JMX monitoring
# Useful for production monitoring and troubleshooting
#
# JMX_PORT=9999
# JMX_OPTS="-Dcom.sun.management.jmxremote"
# JMX_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=$JMX_PORT"
# JMX_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.ssl=false"
# JMX_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
# JMX_OPTS="$JMX_OPTS -Djava.rmi.server.hostname=localhost"
# JAVA_OPTS="$JAVA_OPTS $JMX_OPTS"

#
# Debug Configuration (Optional)
#
# Uncomment these settings to enable remote debugging
# The application will wait for debugger connection on port 5005
#
# DEBUG_PORT=5005
# DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$DEBUG_PORT"
# JAVA_OPTS="$JAVA_OPTS $DEBUG_OPTS"

#
# System Properties
#
# Add any custom system properties here
# Example: JAVA_OPTS="$JAVA_OPTS -Dmy.property=value"
#

# File encoding
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"

# Proxy configuration (disable SOCKS proxy)
JAVA_OPTS="$JAVA_OPTS -DsocksProxyHost="
JAVA_OPTS="$JAVA_OPTS -DsocksProxyPort="
JAVA_OPTS="$JAVA_OPTS -Djava.net.useSystemProxies=false"

# Timezone (optional, uncomment if needed)
# JAVA_OPTS="$JAVA_OPTS -Duser.timezone=UTC"

#
# Application-specific properties
#
# Log4j2 configuration file location
# The launcher script adds conf directory to classpath, so log4j2.xml
# will be automatically discovered. You can override it here if needed.
# JAVA_OPTS="$JAVA_OPTS -Dlog4j.configurationFile=${APP_HOME}/conf/log4j2.xml"

# Export JAVA_OPTS so it's available to the launcher script
export JAVA_OPTS
