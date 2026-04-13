#!/bin/bash
#
# consilens-cli.sh - Consilens CLI Launcher Script
#
# This script launches the Consilens CLI application with proper classpath
# configuration and JVM settings.
#

# Exit on error
set -e

# Exit codes
EXIT_OK=0
EXIT_ERROR=1

#
# Function: print_error
# Description: Print error message to stderr
#
print_error() {
    echo "ERROR: $1" >&2
}

#
# Function: check_java
# Description: Check if Java is installed and meets version requirements
#
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        print_error "Please install Java 11 or higher"
        exit $EXIT_ERROR
    fi
    
    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{print $1}')
    if [ "$JAVA_VERSION" -lt 11 ]; then
        print_error "Java version 11 or higher is required"
        print_error "Current version: $(java -version 2>&1 | head -1)"
        exit $EXIT_ERROR
    fi
}

#
# Function: determine_app_home
# Description: Automatically infer APP_HOME directory
#
determine_app_home() {
    # Get the directory where this script is located
    local SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    
    # APP_HOME is the parent directory of bin
    APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
    
    # Verify APP_HOME structure
    if [ ! -d "$APP_HOME/bin" ]; then
        print_error "Invalid APP_HOME structure: $APP_HOME/bin not found"
        exit $EXIT_ERROR
    fi
}

#
# Function: build_classpath
# Description: Build classpath with conf first, then libs, then plugins
#
build_classpath() {
    local CLASSPATH=""
    
    # 1. Add conf directory (highest priority)
    if [ -d "$APP_HOME/conf" ]; then
        CLASSPATH="$APP_HOME/conf"
    fi
    
    # 2. Add all JARs from libs directory
    if [ -d "$APP_HOME/libs" ]; then
        for jar in "$APP_HOME/libs"/*.jar; do
            if [ -f "$jar" ]; then
                if [ -z "$CLASSPATH" ]; then
                    CLASSPATH="$jar"
                else
                    CLASSPATH="$CLASSPATH:$jar"
                fi
            fi
        done
    fi
    
    # 3. Add all JARs from plugins directory
    if [ -d "$APP_HOME/plugins" ]; then
        for jar in "$APP_HOME/plugins"/*.jar; do
            if [ -f "$jar" ]; then
                if [ -z "$CLASSPATH" ]; then
                    CLASSPATH="$jar"
                else
                    CLASSPATH="$CLASSPATH:$jar"
                fi
            fi
        done
    fi
    
    if [ -z "$CLASSPATH" ]; then
        print_error "No JAR files found in libs or plugins directories"
        exit $EXIT_ERROR
    fi
    
    echo "$CLASSPATH"
}

#
# Function: load_setenv
# Description: Load environment variables from bin/setenv.sh
#
load_setenv() {
    local SETENV_SCRIPT="$APP_HOME/bin/setenv.sh"
    
    if [ -f "$SETENV_SCRIPT" ]; then
        # Source the setenv script
        . "$SETENV_SCRIPT"
    fi
}

#
# Main execution
#

# Check Java environment
check_java

# Determine APP_HOME directory
determine_app_home

# Create logs directory if it doesn't exist
LOG_DIR="$APP_HOME/logs"
mkdir -p "$LOG_DIR"

# Load environment variables from setenv.sh
load_setenv

# Build classpath
CLASSPATH=$(build_classpath)

# Execute CLI application with all arguments
java $JAVA_OPTS -cp "$CLASSPATH" com.consilens.cli.ConsilensCliApplication "$@"

# Capture and return the exit code
EXIT_CODE=$?
exit $EXIT_CODE
