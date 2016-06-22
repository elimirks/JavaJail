JavaJail: JVM jail and trace printer
====================================

Based on java_jail by David Pritchard (daveagp@gmail.com), created May 2013
https://github.com/daveagp/java_jail

This is the backend for PCRS-Java
https://mcs.utm.utoronto.ca/~pcrs/java-programming/index.shtml

The significant code in this directory is the documentation, and the
contents of ./src/traceprinter. Both are released under the GNU Affero
General Public License, versions 3 or later. See LICENSE or visit:
http://www.gnu.org/licenses/agpl.html

This project would not be possible without the package
com.sun.tools.example.trace, written by Robert Field. The traceprinter
package was initially created from that package.

Installation steps
------------------

0. It is highly recommended to NOT put this directory anywhere accessible
    via http, just for sanity's sake.
1. Get java. As of the time of writing, a suitable link for wget is:

`wget --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u20-b26/jdk-8u20-linux-x64.tar.gz`

(props to http://stackoverflow.com/questions/10268583)
Extract java with gunzip and tar -xvf.
Move jdk1.8.0_20 contents to "java"

2. Run `ant build`

Testing
-------

To test, try this:
`./java/bin/java -cp build:jar/javax.json-1.0.jar:java/lib/tools.jar traceprinter.InMemory < doc/testfiles/test-input.json`
The expected output is at doc/testfiles/expected-output.json

