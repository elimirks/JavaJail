#!/bin/sh

wget --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u20-b26/jdk-8u20-linux-x64.tar.gz && \
tar xzf jdk-8u20-linux-x64.tar.gz && \
mv jdk1.8.0_20/* java && \
rm -r jdk1.8.0_20 jdk-8u20-linux-x64.tar.gz && \
ant build

