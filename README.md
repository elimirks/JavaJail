JavaJail: JVM jail and trace printer
====================================

Based on java_jail by David Pritchard (daveagp@gmail.com), created May 2013
https://github.com/daveagp/java_jail

This is the backend for the PCRS-Java visualizer:
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

1. Install JDK (not JRE) if it isn't installed
2. Make sure the `JAVA_HOME` environment variable is set to a JDK path
3. Run `ant build`

Testing
-------

To test, try this:
`./run.sh < doc/testfiles/test-input.json`
If you see a big JSON trace, it works. If you see ugly exceptions, it's broken.

To see a fancy ncurses trace viewer, run:
`./test/generateJailInput.py test/Dog.java | ./run.sh | test/traceStepper.py`

