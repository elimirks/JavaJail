JavaJail: chroot jail and trace printer
=======================================

Based on java_jail by David Pritchard (daveagp@gmail.com), created May 2013
https://github.com/daveagp/java_jail

This is the backend for PCRS-Java
https://mcs.utm.utoronto.ca/~pcrs/java-programming/index.shtml

This directory serves 2 purposes:
- '.' serves as chroot (changed root) for executing Java programs
- './build/traceprinter' contains a Java program to print traces of Java
    programs as they execute, printing the results to the same JSON
    format used by http://pythontutor.com/
    See ./src/traceprinter/README for documentation on that part.

The significant code in this directory is the documentation, and the
contents of ./src/traceprinter. Both are released under the GNU Affero
General Public License, versions 3 or later. See LICENSE or visit:
http://www.gnu.org/licenses/agpl.html

This project would not be possible without the package
com.sun.tools.example.trace, written by Robert Field. The traceprinter
package was initially created from that package.

Setting up a chroot jail for java
---------------------------------

The good news is that java for linux is available as a single self-contained
archive (we used jdk-7u21-linux-x64.gz).

The bad news is that it also uses various shared object files not contained
in there, as well as various pseudo-files. So the chroot jail must also
contain these.

For chroot jail to work, the full contents of this directory should be:

./java/: copy of unzipped java installation
./{etc,lib64}/: necessary libraries
./{dev,proc}/: necessary pseudo-files
./build/: we use this to hold application-specific java classpath stuff

as well as README, LICENSE, and some .git files.

Installation steps
------------------

(0) It is highly recommended to NOT put this directory anywhere accessible
    via http, just for sanity's sake.
(1) Get java. As of the time of writing, a suitable link for wget is:

wget --no-check-certificate --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" http://download.oracle.com/otn-pub/java/jdk/8u20-b26/jdk-8u20-linux-x64.tar.gz

(props to http://stackoverflow.com/questions/10268583)
Extract java with gunzip and tar -xvf.
Rename that jdk1.8.0_20 to "java"
(2) You will need to copy some library files into the jail.
    The simplest thing would be to make a recursive copy of /lib64
    into ./lib64, and likewise with /lib and /usr/lib. This way
    the libraries available in the system are still available
    after you chroot.

    HARD MODE: If you want to copy a minimum number of files, a good start is
    to run "ldd java/bin/java" from within java_jail.
    See ./lib64/.gitignore for what we needed on our dev machine.
      (From Sasha Sirotkin: on Ubuntu, those files might instead need to
       be in lib/x86_64-linux-gnu instead.)
    strace -f can be useful to disgnose obscure linker problems.
(3) copy /etc/ld.so.cache to ./etc
(4) mkdir ./proc, then you will need to mount /proc to ./proc -- use
      EITHER:
      To mount once (until reboot or "umount /path/to/chroot/proc")
        sudo mount --bind -o ro,bind /proc /path/to/chroot/proc
      To mount permanently, add this to /etc/fstab:
        proc  /path/to/chroot/proc    proc    defaults    0 0
      (this is just a modified version of the normal proc mount line.)
(5) Mount necessary devices to /dev
        sudo mknod -m 0666 ./dev/null c 1 3
        sudo mknod -m 0666 ./dev/random c 1 8
        sudo mknod -m 0444 ./dev/urandom c 1 9
(6) File system permissions. At a basic level, it is enough to
    allow everything to be read and executed by everyone, and written by nobody.
(7) iptables considerations. If you are running arbitrary user code,
    you probably want to prevent them from accessing the internet.
    But, the JDI requires access to a local debugging port to
    communicate between the tracing VM and the traced VM. On our system,
    all sandboxed executions run with group id 1000, so we have

to allow JDI:
-A OUTPUT -p tcp -d 127.0.0.1 --dport 32000:65535 -m owner --gid-owner 1000 -j ACCEPT
to deny everything else:
-A OUTPUT -m owner --gid-owner 1000 -j DROP

If you want to allow connections to the outgoing world, particularly in Java,
you will have to copy /etc/resolv.conf into the etc of the jail.

Testing
-------

Assuming you have CEMC safeexec (https://github.com/cemc/safeexec)
installed, here is a way how to test whether things are installed correctly.

0. Run `ant build`
1. Run this command (from java_jail):
    - `./java/bin/java -cp build:jar/javax.json-1.0.jar:java/lib/tools.jar traceprinter.InMemory < doc/testfiles/test-input.json`
    - The expected output is at doc/testfiles/expected-output.json
2. Try with safeexec (from java_jail):
    - `/path/to/safeexec --chroot_dir . --exec_dir / --share_newnet --nproc 50 --mem 3000000 --nfile 30 --env_vars CLASSPATH=/build/:/jar/javax.json-1.0.jar:/java/lib/tools.jar --exec /java/bin/java traceprinter.InMemory < doc/testfiles/test-input.json`

Notes from different machines: on at least one machine, nfile and nproc needed to be set to 100.

Misc
----

I found these links useful at some point:
http://www.cyberciti.biz/faq/howto-run-nginx-in-a-chroot-jail/
http://interreality.org/~reed/java-chroot.html

