#!/bin/bash
set -e
cd /Users/huangzitong/Desktop/SoloCoder6月/Code/71-75/DF1-75

CLASSPATH="target/classes"
for jar in lib/*.jar; do
  CLASSPATH="$CLASSPATH:$jar"
done

java -cp "$CLASSPATH" com.datateam.loganalyzer.cli.LogAnalyzerCli "$@"
