What does this package do and what does it contain?
---------------------------------------------------

traceprinter.InMemory: the main class. It takes java file source code
                       from standard input. Then it outputs a text (JSON)
                       version of what the program didwhen executed.

The output format is in opt-trace-format.md
This allows us to use the javascript frontend from OnlinePythonTutor
to visualize Java instead of Python.

traceprinter.ramtools: compiles the java files to bytecode in memory.

Then, InMemory uses the JDI to start a debuggee JVM, load the bytecode into
it, and then execute it.

traceprinter.VMCommander: drives injection and execution into debuggee.

traceprinter.shoelace: contains all fixed code run by the debugee JVM.

traceprinter.JSONTracingThread: event handling loop.

traceprinter.JDI2JSON: used to convert everything to text output.

Flow of execution
-----------------

InMemory will get things started, and passes the ball briefly to
JSONTracingThread. But once the debuggee takes it first step, the
VMCommander drives the execution of the debuggee VM. Again, the
code it causes to run, will drive the event loop in JSONTracingThread
some more, which in turn uses JDI2JSON to output things nicely.
