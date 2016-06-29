#!/bin/sh
# To run, pipe a JSON string into this puppy

CLASSPATH="build:$JAVA_HOME/lib/tools.jar:jar/javax.json-1.0.jar"
# Since JAVA_HOME might differ from the executable path
JAVA="$JAVA_HOME/jre/bin/java"
cat | $JAVA -cp $CLASSPATH $FLAGS traceprinter.InMemory

