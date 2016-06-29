/*****************************************************************************

traceprinter: a Java package to print traces of Java programs
David Pritchard (daveagp@gmail.com), created May 2013

The contents of this directory are released under the GNU Affero
General Public License, versions 3 or later. See LICENSE or visit:
http://www.gnu.org/licenses/agpl.html

See README for documentation on this package.

This file was originally based on
com.sun.tools.example.trace.EventThread, written by Robert Field.

******************************************************************************/

package traceprinter;

import com.sun.jdi.*;
import com.sun.jdi.request.*;
import com.sun.jdi.event.*;

import java.util.*;
import java.io.*;
import javax.json.*;

/*
 * Original author: Robert Field, see
 *
 * This version: David Pritchard (http://dave-pritchard.net)
 */
public class JSONTracingThread extends Thread {
    private final VirtualMachine vm;   // Running VM
    private String[] no_breakpoint_requests = {
        "java.*", "javax.*", "sun.*", "com.sun.*",
        "Stack", "Queue", "ST", // FIXME possibly remove these - legacy
        "jdk.internal.org.objectweb.asm.*" // for creating lambda classes
    };

    private boolean connected = true;  // Connected to VM
    private boolean vmDied = true;     // VMDeath occurred

    private EventRequestManager mgr;

    private JDI2JSON jdi2json;

    static int MAX_STEPS = 256;

    static double MAX_WALLTIME_SECONDS = 5;

    private int steps = 0;

    static int MAX_STACK_SIZE = 16;

    private InMemory im;

    private VMCommander vmc;

    private JsonArrayBuilder output = Json.createArrayBuilder();

    JSONTracingThread(InMemory im) {
        super("event-handler");
        this.vm = im.vm;
        this.im = im;
        mgr = vm.eventRequestManager();
        jdi2json = new JDI2JSON(vm,
                                vm.process().getInputStream(),
                                vm.process().getErrorStream(),
                                im.optionsObject);
        setEventRequests();
    }

    void setEventRequests() {
        ExceptionRequest excReq = mgr.createExceptionRequest(null, true, true);
        excReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        for (String clob : no_breakpoint_requests)
            excReq.addClassExclusionFilter(clob);
        excReq.enable();

        MethodEntryRequest menr = mgr.createMethodEntryRequest();
        for (String clob : no_breakpoint_requests)
            menr.addClassExclusionFilter(clob);
        menr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        menr.enable();

        MethodExitRequest mexr = mgr.createMethodExitRequest();
        for (String clob : no_breakpoint_requests)
            mexr.addClassExclusionFilter(clob);
        mexr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        mexr.enable();

        ThreadDeathRequest tdr = mgr.createThreadDeathRequest();
        tdr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        tdr.enable();

        ClassPrepareRequest cpr = mgr.createClassPrepareRequest();
        for (String clob : no_breakpoint_requests)
            cpr.addClassExclusionFilter(clob);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();
    }

    @Override
    public void run() {
        StepRequest request = null;
        final EventQueue queue = vm.eventQueue();
        while (connected) {
            try {
                final EventSet eventSet = queue.remove();
                for (Event event : new EventSetIterable(eventSet)) {
                    tryHandlingEvent(event);
                    finishRequest(request);

                    boolean isReportableEvent =
                        event instanceof LocatableEvent &&
                        jdi2json.reportEventsAtLocation(((LocatableEvent)event).location());

                    boolean shouldCreateStepRequest = isReportableEvent ||
                        (event.toString().contains("NoopMain"));

                    if (shouldCreateStepRequest) {
                        request = mgr.createStepRequest(((LocatableEvent)event).thread(),
                            StepRequest.STEP_MIN,
                            StepRequest.STEP_INTO);
                        request.addCountFilter(1);  // next step only
                        request.enable();
                    }
                }
                eventSet.resume();
            } catch (InterruptedException exc) {
                exc.printStackTrace();
                // Ignore
            } catch (VMDisconnectedException discExc) {
                handleDisconnectedException();
                break;
            }
        }
        String outputString = createJsonOutputString();
        try {
            PrintStream out = new PrintStream(System.out, true, "UTF-8");
            out.print(outputString);
        } catch (UnsupportedEncodingException e) {
            System.out.print(outputString);
        }
    }

    private String createJsonOutputString() {
        if (vmc == null) {
            return JDI2JSON.compileErrorOutput("Internal error: " +
                " there was an error starting the debuggee VM.").toString();
        }

        try {
            vmc.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if ( ! vmc.wasSuccessful()) {
            return JDI2JSON.compileErrorOutput(vmc.errorMessage).toString();
        }
        return JDI2JSON.output(output.build()).toString();
    }

    private void tryHandlingEvent(Event event) {
        boolean hasExceededTimeLimit = System.currentTimeMillis() >
            MAX_WALLTIME_SECONDS * 1000 + InMemory.startTime;
        if (hasExceededTimeLimit) {
            exitDueToTimeLimit();
        }

        if (event instanceof ClassPrepareEvent) {
            classPrepareEvent((ClassPrepareEvent)event);
        } else if (event instanceof VMDeathEvent) {
            vmDeathEvent((VMDeathEvent)event);
        } else if (event instanceof VMDisconnectEvent) {
            vmDisconnectEvent((VMDisconnectEvent)event);
        } else if (event instanceof LocatableEvent) {
            handleLocatableEvent((LocatableEvent)event);
        }
    }

    private void finishRequest(StepRequest request) {
        if (request != null) {
            if (request.isEnabled()) {
                request.disable();
            }

            mgr.deleteEventRequest(request);
            request = null;
        }
    }

    private void exitDueToTimeLimit() {
        output.add(Json.createObjectBuilder()
                   .add("exception_msg", "<exceeded max visualizer time limit>")
                   .add("event", "instruction_limit_reached"));

        try {
            PrintStream out = new PrintStream(System.out, true, "UTF-8");
            String outputString = JDI2JSON.output(output.build()).toString();
            out.print(outputString);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        } finally {
            System.exit(0);
            vm.exit(0); // might take a long time
        }
    }

    /**
     * Handle the given event during VM runtime.
     *
     * The VM should be running on a single thread, or else we
     * may get garbled output.
     */
    private void handleLocatableEvent(LocatableEvent event) {
        tryInitVMCommander(event);

        Location loc = event.location();

        boolean isExceptionEvent = event instanceof ExceptionEvent &&
            ((ExceptionEvent)event).catchLocation() == null;
        boolean isReportableEvent = steps < MAX_STEPS &&
            jdi2json.reportEventsAtLocation(loc);

        if (isReportableEvent) {
            addLocatableEventInfoToOutput(event);
        } else if (isExceptionEvent) {
            addLocatableEventInfoToOutput(event);
            vm.exit(0);
        }
    }

    /**
     * Iterates through the new steps for the given event.
     *
     * @see JDI2JSON.convertExecutionPoint
     */
    private void addLocatableEventInfoToOutput(LocatableEvent event) {
        Location loc = event.location();
        ThreadReference thread = event.thread();

        for (JsonObject e : jdi2json.convertExecutionPoint(event, loc, thread)) {
            addExecutionPointToOutput(e);
        }
    }

    /**
     * Adds a single execution point to the output.
     *
     * @see JDI2JSON.convertExecutionPoint for JSON format
     */
    private void addExecutionPointToOutput(JsonObject execPoint) {
        output.add(execPoint);
        steps++;
        int stackSize = ((JsonArray)execPoint.get("stack_to_render")).size();

        if (stackSize >= MAX_STACK_SIZE) {
            output.add(Json.createObjectBuilder()
                .add("exception_msg", "<exceeded max visualizer stack size>")
                .add("event", "instruction_limit_reached"));
            vm.exit(0);
        } else if (steps == MAX_STEPS) {
            output.add(Json.createObjectBuilder()
                .add("exception_msg", "<exceeded max visualizer step limit>")
                .add("event", "instruction_limit_reached"));
            vm.exit(0);
        }
    }

    /**
     * Attempt to initialize the VMCommander once a VM thread is created.
     *
     * This must be done once the VM events start firing to grab a thread.
     */
    private void tryInitVMCommander(LocatableEvent event) {
        // The vmc already exists - we don't need to init.
        if (vmc != null) return;

        try {
            if (event.location().sourceName().equals("NoopMain.java")) {
                steps++;
                vmc = new VMCommander(im, event.thread());
                vmc.start();
            }
        } catch (AbsentInformationException e) {
            throw new RuntimeException(e);
        }
    }

    /***
     * A VMDisconnectedException has happened while dealing with
     * another event. We need to flush the event queue, dealing only
     * with exit events (VMDeath, VMDisconnect) so that we terminate
     * correctly.
     */
    synchronized void handleDisconnectedException() {
        EventQueue queue = vm.eventQueue();
        while (connected) {
            try {
                EventSet eventSet = queue.remove();
                EventIterator iter = eventSet.eventIterator();
                while (iter.hasNext()) {
                    Event event = iter.nextEvent();
                    if (event instanceof VMDeathEvent) {
                        vmDeathEvent((VMDeathEvent)event);
                    } else if (event instanceof VMDisconnectEvent) {
                        vmDisconnectEvent((VMDisconnectEvent)event);
                    }
                }
                eventSet.resume(); // Resume the VM
            } catch (InterruptedException exc) {
                // ignore
            }
        }
    }

    private void classPrepareEvent(ClassPrepareEvent event)  {
        //System.out.println("CPE!");
        ReferenceType rt = event.referenceType();

        if (!rt.name().equals("traceprinter.shoelace.NoopMain")) {
            if (rt.name().equals("StdIn"))
                jdi2json.stdinRT = rt;

            if (jdi2json.in_builtin_package(rt.name()))
                return;
        }

        jdi2json.staticListable.add(rt);

        //System.out.println(rt.name());
        try {
            for (Location loc : rt.allLineLocations()) {
                BreakpointRequest br = mgr.createBreakpointRequest(loc);
                br.enable();
            }
        }
        catch (AbsentInformationException e) {
            if (!rt.name().contains("$Lambda$"))
                System.out.println("AIE!" + rt.name());
        }
    }

    public void vmDeathEvent(VMDeathEvent event) {
        vmDied = true;
    }

    public void vmDisconnectEvent(VMDisconnectEvent event) {
        connected = false;
    }


    /**
     * Provides an iterator to the EventSet class.
     */
    private class EventSetIterable implements Iterable<Event> {
        private EventSet set;

        public EventSetIterable(EventSet set) {
            this.set = set;
        }

        @Override
        public Iterator<Event> iterator() {
            return set.eventIterator();
        }
    }
}

