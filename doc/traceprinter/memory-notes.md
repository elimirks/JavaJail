These are notes from when I tried to try to figure out how much
memory is needed to run the java visualizer within a safeexec jail.
I created these notes in summer 2013 but didn't post them until
January 2014. Read them at your discretion. -- Dave Pritchard

Memory usage, part 1
--------------------
By default, java may use a lot of memory. You can, sort of, try to
control it. Here are the defaults on my machine:

./java -XX:+PrintFlagsFinal -version | pcregrep "\b(MaxHeapSize|InitialHeapSize|ThreadStackSize)"

prints the output

    uintx InitialHeapSize                          := 130770560       {product}
    uintx MaxHeapSize                              := 2092957696      {product}
     intx ThreadStackSize                           = 1024            {pd product}

which means that the heap uses 124M initially and 2G at maximum.

You can control MaxHeapSize with "java -Xmx" (this is the most
important for us) and the other two above with -Xms, -Xss.

How much memory does Java actually use? How much can the user
actually access? There is naturally some overhead between these two
numbers, for the stuff running the VM, and for the default class
files and stuff inside the VM. You can write

    long maxBytes = Runtime.getRuntime().maxMemory();
    System.out.println("Max memory: " + maxBytes / 1024 / 1024 + "M");

I am not sure which of the controlling and reporting numbers
correspond to user or user+VM memory. But as an example, for me, if
I run java with -Xmx1024M, then the above reports "Max memory:
910M", while the largest int[] that Java will let me allocate is
about new int[175_000_000] (or about 7*10^8 bytes ~ 667M).

Memory usage, part 2
--------------------
The way that traceprinter works, we actually have two VMs. One is
the debugger, which is started first, and whose -Xmx setting is
done by the command-line (or whoever calls java
traceprinter.InMemory). The second, debugee, VM has its -Xmx set
according to the options string used by LaunchingConnector.launch()
in InMemory.launchVM.

The -Xmx used for one VM affects only that machine, and does not
affect the -Xmx for the other machine, as far as I can tell from
testing.

What is a reasonable setting for the maximum memory of our two
machines? My system default, 2GB, seems a little excessive. All of
the visualizer examples run with -Xmx128M on both VMs, so I am
using this for now. (This gives the user about 80M of usable space,
using the new int[] test.)

Memory usage, part 3
--------------------
Now let's consider the fact that we want to run all of this under
safeexec. On my machine, it appears that the limits enforced by
safeexec successfully limit the sum of the memories used by both
VMs. So the --mem option for safeexec must be at least 256\*1024 K.
However, there seems to be more overhead somewhere along the line,
as,

safeexec --mem 300000 gives "Could not create the Java Virtual Machine"

safeexec --mem 400000 gives "# There is insufficient memory for the Java Runtime Environment to continue"
                            (on safeexec's stdout)
                      and/or "Command terminated by signal (6: SIGABRT)"
                            (on safeexec's stderr)

while

safeexec --mem 500000 seems to work without any problems.
