/*****************************************************************************

traceprinter: a Java package to print traces of Java programs
David Pritchard (daveagp@gmail.com), created May 2013

The contents of this directory are released under the GNU Affero
General Public License, versions 3 or later. See LICENSE or visit:
http://www.gnu.org/licenses/agpl.html

See README for documentation on this package.

This file was originally based on
com.sun.tools.example.trace.Trace, written by Robert Field.

******************************************************************************/

package traceprinter;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;

import java.util.regex.*;
import java.util.*;
import java.io.*;

import javax.tools.*;

import traceprinter.ramtools.*;

import javax.json.*;

public class InMemory {
    private List<FakeFile> sourceFiles = new ArrayList<>();

    JsonObject optionsObject;
    JsonArray argsArray;
    String givenStdin;
    String mainClass;
    VirtualMachine vm;
    static String stdin;
    Map<String, byte[]> bytecode;

    public final static long startTime = System.currentTimeMillis();

    public static String getFileContents(String filename) {
        StringBuilder result = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(filename));
            String line = null;
            while ((line = br.readLine()) != null) {
                result.append(line).append("\n");
            }
        } catch (Exception e) {
            throw new RuntimeException("getFileContents " + filename + " failed: " + e.toString());
        }
        return result.toString();
    }


    public static void main(String[] args) {

        JDI2JSON.userlog("Debugger VM maxMemory: " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "M");

        // just a sanity check, can the debugger VM see this NoopMain?
        traceprinter.shoelace.NoopMain.main(null);
        // however, the debuggee might or might not be able to.
        // use the CLASSPATH environment variable so that it includes
        // the parent directory of traceprinter; using -cp does not
        // reliably pass on to the debuggee.

        try {
            new InMemory(Json.createReader(
                new InputStreamReader(System.in, "UTF-8")).readObject());
        } catch (IOException e) {
            String message = "Internal IOException in php->java";
            System.out.print(JDI2JSON.compileErrorOutput(message));
        }
    }

    void printUTF8String(String str) {
        try {
            PrintStream out = new PrintStream(System.out, true, "UTF-8");
            out.print(str);
        } catch (UnsupportedEncodingException e) { //fallback
            System.out.print(str);
        }
    }

    // Convenience methods for JDI2JSON methods
    void printCompileError(String msg, String file, long row, long col) {
        JsonObject json = JDI2JSON.compileErrorOutput(msg, file, row, col);
        printUTF8String(json.toString());
    }
    void printCompileError(String msg) {
        JsonObject json = JDI2JSON.compileErrorOutput(msg);
        printUTF8String(json.toString());
    }

    // figure out the class name, then compile and run main([])
    InMemory(JsonObject frontend_data) {
        this.optionsObject = frontend_data.getJsonObject("options");
        this.argsArray = frontend_data.getJsonArray("args");
        this.givenStdin = frontend_data.getJsonString("stdin").getString();
        stdin = this.givenStdin;
        try {
            this.sourceFiles = FakeFile.parseJsonFiles(
                frontend_data.getJsonArray("files"));
        } catch (FakeFile.NameException ex) {
            printCompileError(ex.getMessage());
            System.exit(0);
        }
        // FIXME kind of a hack for now
        this.mainClass = sourceFiles.get(0).getName();

        boolean hasVisualizerArgs =
            frontend_data.containsKey("visualizer_args") &&
            ( ! frontend_data.isNull("visualizer_args"));
        if (hasVisualizerArgs) {
            JsonObject args = frontend_data.getJsonObject("visualizer_args");
            setupVisualizerArgs(args);
        }

        CompileToBytes c2b = new CompileToBytes();

        c2b.compilerOutput = new StringWriter();
        //,"-classpath",System.getProperty("java.class.path"));
        c2b.options = Arrays.asList("-g","-Xmaxerrs","1");

        DiagnosticCollector<JavaFileObject> errorCollector = new DiagnosticCollector<>();
        c2b.diagnosticListener = errorCollector;

        String[][] fileInfo = FakeFile.fakeFileListToPairArray(this.sourceFiles);
        bytecode = c2b.compileFiles(fileInfo);

        if (bytecode == null) {
            exitWithErrorCollector(errorCollector);
        } else {
            startDebuggerVM();
        }
    }

    private void setupVisualizerArgs(JsonObject args) {
        if (args.getJsonNumber("MAX_STEPS") != null) {
            JSONTracingThread.MAX_STEPS = args.getJsonNumber(
                "MAX_STEPS").intValue();
        }
        if (args.getJsonNumber("MAX_STACK_SIZE") != null) {
            JSONTracingThread.MAX_STACK_SIZE = args.getJsonNumber(
                "MAX_STACK_SIZE").intValue();
        }
        if (args.getJsonNumber("MAX_WALLTIME_SECONDS") != null) {
            JSONTracingThread.MAX_WALLTIME_SECONDS = args.getJsonNumber(
                "MAX_WALLTIME_SECONDS").intValue();
        }
    }

    private void startDebuggerVM() {
        vm = launchVM("traceprinter.shoelace.NoopMain");
        vm.setDebugTraceMode(0);

        JSONTracingThread tt = new JSONTracingThread(this);
        tt.start();

        vm.resume();
    }

    private void exitWithErrorCollector(
            DiagnosticCollector<JavaFileObject> collector) {

        for (Diagnostic<? extends JavaFileObject> err :
                collector.getDiagnostics()) {
            if (err.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }

            String message = "Error: " + err.getMessage(null);
            String fileName = err.getSource().toString();
            long lineNumber = Math.max(0, err.getLineNumber());
            long columnNumber = Math.max(0, err.getColumnNumber());

            printCompileError(message, fileName, lineNumber, columnNumber);
            System.exit(0);
        }
        printCompileError("Compiler did not work, but reported no ERROR?!?!");
        System.exit(0);
    }

    VirtualMachine launchVM(String className) {
        LaunchingConnector connector = theCommandLineLaunchConnector();
        try {

            java.util.Map<String, Connector.Argument> args
                = connector.defaultArguments();

            /* what are the other options? on my system,

            for (java.util.Map.Entry<String, Connector.Argument> arg: args.entrySet()) {
                System.out.print(arg.getKey()+" ");
                System.out.print("["+arg.getValue().value()+"]: ");
                System.out.println(arg.getValue().description());
            }

            prints out:

home [/java/jre]: Home directory of the SDK or runtime environment used to launch the application
options []: Launched VM options
main []: Main class and arguments, or if -jar is an option, the main jar file and arguments
suspend [true]: All threads will be suspended before execution of main
quote ["]: Character used to combine space-delimited text into a single command line argument
vmexec [java]: Name of the Java VM launcher

            For more info, see
http://docs.oracle.com/javase/7/docs/jdk/api/jpda/jdi/com/sun/jdi/connect/Connector.Argument.html
            */

            ((Connector.Argument)(args.get("main"))).setValue(className);

            String options = "";

            // inherit the classpath. if it were not for this, the CLASSPATH environment
            // variable would be inherited, but the -cp command-line option would not.
            // note that -cp overrides CLASSPATH.

            options += "-cp " + System.getProperty("java.class.path") + " ";

            // set a memory limit

            options += "-Xmx512M ";

            options += "-Dfile.encoding=UTF-8 ";

            options += "-Djava.awt.headless=true ";
            options += "-Djava.security.manager ";
            options += "-Djava.security.policy=src/user_code.policy ";

            ((Connector.Argument)(args.get("options"))).setValue(options);

            //	    System.out.println("About to call LaunchingConnector.launch...");
	    VirtualMachine result = connector.launch(args);
	    //System.out.println("...done");
            return result;
        } catch (VMStartException exc) {
	    System.out.println("Hoeyx!");
            System.out.println("Failed in launchTarget: " + exc.getMessage());
            exc.printStackTrace();
	    byte[] b = new byte[100000];
	    System.out.println(exc.process().exitValue());
	    try {
		BufferedReader in = new BufferedReader(new InputStreamReader(exc.process().getErrorStream()));
		String inputLine;
		while ((inputLine = in.readLine()) != null)
		    System.out.println(inputLine);
		in = new BufferedReader(new InputStreamReader(exc.process().getInputStream()));
		while ((inputLine = in.readLine()) != null)
		    System.out.println(inputLine);

	    }
	    catch (java.io.IOException excx) {
		System.out.println("Crud");
	    }
        } catch (java.io.IOException exc) {
            System.out.println("Failed in launchTarget: " + exc.getMessage());
            exc.printStackTrace();
        } catch (IllegalConnectorArgumentsException exc) {
	    System.out.println("Hoeyy!");
            for (String S : exc.argumentNames()) {
                System.out.println(S);
            }
            System.out.println(exc);
        }
        return null; // when caught
    }

    LaunchingConnector theCommandLineLaunchConnector() {
        for (Connector connector :
                 Bootstrap.virtualMachineManager().allConnectors())
            if (connector.name().equals("com.sun.jdi.CommandLineLaunch"))
                return (LaunchingConnector)connector;
        throw new Error("No launching connector");
    }
}

