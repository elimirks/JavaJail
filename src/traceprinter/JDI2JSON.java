/*****************************************************************************

traceprinter: a Java package to print traces of Java programs
David Pritchard (daveagp@gmail.com), created May 2013

The contents of this directory are released under the GNU Affero
General Public License, versions 3 or later. See LICENSE or visit:
http://www.gnu.org/licenses/agpl.html

See README for documentation on this package.

 ******************************************************************************/

package traceprinter;

import java.util.*;
import com.sun.jdi.*;
import com.sun.jdi.request.*;
import com.sun.jdi.event.*;

import java.io.*;
import javax.json.*;

public class JDI2JSON {
    private class InputPuller {
        InputStreamReader vm_link;
        StringWriter contents = new java.io.StringWriter();
        String getContents() {
            return contents.toString();
        }
        InputPuller(InputStream ir) {
            try {
                vm_link = new InputStreamReader(ir, "UTF-8");
            }
            catch (UnsupportedEncodingException e) {
                throw new RuntimeException("Encoding error!");
            }
        }
        void pull() {
            int BUFFER_SIZE = 2048;
            char[] cbuf = new char[BUFFER_SIZE];
            int count;
            try {
                while (vm_link.ready()
                        && ((count = vm_link.read(cbuf, 0, BUFFER_SIZE)) >= 0)) {
                    contents.write(cbuf, 0, count);
                        }
            }
            catch(IOException e) {
                throw new RuntimeException("I/O Error!");
            }
        }
    }

    private VirtualMachine vm;
    private InputPuller stdout, stderr;
    private JsonObject last_ep = null;
    private TreeMap<Long, ObjectReference> heap;
    private TreeSet<Long> heap_done;

    private long frame_ticker = 0;

    private List<ReferenceType> staticListable = new ArrayList<>();

    public ReferenceType stdinRT = null;

    public static StringBuilder userlogged;

    public static boolean showVoid = true;

    boolean showStringsAsValues = false;
    boolean showAllFields = false;

    private Value lastReturnValue = null;

    public JDI2JSON(VirtualMachine vm, InputStream vm_stdout,
            InputStream vm_stderr, JsonObject optionsObject) {
        stdout = new InputPuller(vm_stdout);
        stderr = new InputPuller(vm_stderr);
        if (optionsObject.containsKey("showStringsAsValues")) {
            showStringsAsValues =
                optionsObject.getBoolean("showStringsAsValues");
        }
        if (optionsObject.containsKey("showAllFields")) {
            showAllFields = optionsObject.getBoolean("showAllFields");
        }
    }

    public static void userlog(String S) {
        if (userlogged == null) userlogged = new StringBuilder();
        userlogged.append(S).append("\n");
    }

    /**
     * Neatly adds the "line" and "file" parameters to the given json.
     *
     * The "file" parameter will have a ".java" extension.
     */
    private void addLocationLineAndFileToJson(Location loc,
            JsonObjectBuilder json) {
        try {
            json.add("line", loc.lineNumber());
            json.add("file", loc.sourceName());
        } catch (AbsentInformationException ex) {
            // This should never happen, so we crash and burn if it does!
            throw new RuntimeException(ex);
        }
    }

    public void addPotentiallyStaticReference(ReferenceType ref) {
        staticListable.add(ref);
    }

    // returns null when nothing changed since the last time
    // (or when only event type changed and new value is "step_line")
    public ArrayList<JsonObject> convertExecutionPoint(Event e, Location loc,
            ThreadReference thread) {
        stdout.pull();
        stderr.pull();

        //System.out.println(e);

        ArrayList<JsonObject> results = new ArrayList<>();

        if (loc.method().name().indexOf("access$") >= 0) {
            // don't visualize synthetic access$000 methods
            return results;
        }

        heap_done = new TreeSet<Long>();
        heap = new TreeMap<>();

        JsonValue returnValue = null;

        JsonObjectBuilder result = Json.createObjectBuilder();
        result.add("stdout", stdout.getContents());

        // Used to keep track of objects passing as return values.
        if (lastReturnValue != null) {
            result.add("last_return_value", convertValue(lastReturnValue));
            lastReturnValue = null;
        }

        if (e instanceof MethodExitEvent) {
            Value value = ((MethodExitEvent)e).returnValue();
            returnValue = convertValue(value);
            result.add("event", "return");
            addLocationLineAndFileToJson(loc, result);

            lastReturnValue =
                lastReturnValueFromExitEvent((MethodExitEvent)e, thread);
        } else if (e instanceof BreakpointEvent || e instanceof StepEvent) {
            result.add("event", "step_line");
            addLocationLineAndFileToJson(loc, result);
        } else if (e instanceof ExceptionEvent) {
            // we could compare this with null to see if it was caught.
            // Location katch = ((ExceptionEvent)e).catchLocation();

            // but it turns out we don't care, since either the code
            // keeps going or just halts appropriately anyway.

            result.add("event", "exception");
            result.add("exception_msg", exceptionMessage((ExceptionEvent)e));
            addLocationLineAndFileToJson(loc, result);
        }

        result.add("stack_to_render", generateStackFrameJson(
            thread, returnValue));

        JsonObjectBuilder statics = Json.createObjectBuilder();
        JsonArrayBuilder statics_a = Json.createArrayBuilder();
        for (ReferenceType rt : staticListable) {
            if (rt.isPrepared() && !in_builtin_package(rt.name())) {
                for (Field f : rt.visibleFields()) {
                    if (f.isStatic()) {
                        statics.add(rt.name()+"."+f.name(),
                                convertValue(rt.getValue(f)));
                        statics_a.add(rt.name()+"."+f.name());
                    }
                }
            }
        }
        if (stdinRT != null && stdinRT.isInitialized()) {
            int stdinPosition = ((IntegerValue)stdinRT.getValue(stdinRT.fieldByName("position"))).value();
            result.add("stdinPosition", stdinPosition);
            /*            statics.add("stdin.Position", stdinPosition);
                          statics_a.add("stdin.Position");*/
        }

        result.add("globals", statics);
        result.add("ordered_globals", statics_a);

        result.add("func_name", getFormattedMethodName(loc.method()));
        result.add("heap", convertHeap());

        JsonObject this_ep = result.build();
        if (reallyChanged(last_ep, this_ep)) {
            results.add(this_ep);
            last_ep = this_ep;
        }
        return results;
    }

    /**
     * Intelligently determines the last return value.
     *
     * Normally, constructors "return void", but this grabs the "this" ref.
     */
    private Value lastReturnValueFromExitEvent(MethodExitEvent event,
            ThreadReference thread) {
        Value value = event.returnValue();

        Location location = event.location();
        Method method = location.method();

        if (method.isConstructor()) {
            try {
                StackFrame currentFrame = thread.frame(0);
                return currentFrame.thisObject();
            } catch (IncompatibleThreadStateException ex) {
                // Should not normally happen
                throw new RuntimeException(ex);
            }
        } else if (value instanceof VoidValue) {
            // Reset if a void value is returned.
            return null;
        } else {
            return value;
        }
    }

    /**
     * Generates the stack frame JSON from the give thread.
     */
    private JsonArrayBuilder generateStackFrameJson(ThreadReference thread,
            JsonValue returnValue) {
        JsonArrayBuilder frames = Json.createArrayBuilder();
        StackFrame lastNonUserFrame = null;
        try {
            boolean firstFrame = true;
            for (StackFrame sf : thread.frames()) {
                if ( ! showFramesInLocation(sf.location())) {
                    lastNonUserFrame = sf;
                    continue;
                }

                if (lastNonUserFrame != null) {
                    frame_ticker++;
                    frames.add(convertFrameStub(lastNonUserFrame));
                    lastNonUserFrame = null;
                }

                frame_ticker++;
                frames.add(convertFrame(sf, firstFrame, returnValue));
                firstFrame = false;
                returnValue = null;
            }
        } catch (IncompatibleThreadStateException ex) {
            // thread was not suspended .. should not normally happen
            throw new RuntimeException(ex);
        }
        return frames;
    }

    // input format: [package.]ClassName:lineno or [package.]ClassName
    public boolean in_builtin_package(String line) {
        final String[] builtin_packages = {"java", "javax", "sun", "com.sun", "traceprinter"};

        line = line.split(":")[0];
        for (String badPrefix : builtin_packages) {
            if (line.startsWith(badPrefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean showFramesInLocation(Location loc) {
        return ( ! in_builtin_package(loc.toString())
                && ! loc.method().name().contains("$access"));
        // skip synthetic accessor methods
    }

    private boolean showGuts(ReferenceType rt) {
        return (rt.name().matches("(^|\\.)Point") ||
                ! in_builtin_package(rt.name()));
    }

    public boolean reportEventsAtLocation(Location loc) {
        if (in_builtin_package(loc.toString())) {
            return false;
        }

        if (loc.toString().contains("$$Lambda$")) {
            return false;
        }

        if (loc.lineNumber() <= 0) {
            userlog(loc.toString());
            return true;
        }

        return true;
    }

    // issue: the frontend uses persistent frame ids but JDI doesn't provide them
    // approach 1, trying to compute them, seems intractable (esp. w/ callbacks)
    // approach 2, using an id based on stack depth, does not work w/ frontend
    // approach 3, just give each frame at each execution point a unique id,
    // is what we do. but we also want to skip animating e.p.'s where nothing changed,
    // and if only the frame ids changed, we should treat it as if nothing changed
    private boolean reallyChanged(JsonObject old_ep, JsonObject new_ep) {
        if (old_ep == null) return true;
        return !stripFrameIDs(new_ep).equals(stripFrameIDs(old_ep));
    }

    private JsonObject stripFrameIDs(JsonObject ep) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        for (JsonValue frame : (JsonArray)(ep.get("stack_to_render"))) {
            result.add(jsonModifiedObject
                    (jsonModifiedObject( (JsonObject)frame,
                                         "unique_hash",
                                         jsonString("")),
                     "frame_id",
                     jsonInt(0)));
        }
        return jsonModifiedObject(ep, "stack_to_render", result.build());
    }

    private JsonObjectBuilder convertFrame(StackFrame sf, boolean highlight, JsonValue returnValue) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        JsonArrayBuilder result_ordered = Json.createArrayBuilder();
        if (sf.thisObject() != null) {
            result.add("this", convertValue(sf.thisObject()));
            result_ordered.add("this");
        }

        // list args first
        /* KNOWN ISSUE:
           .arguments() gets the args which have names in LocalVariableTable,
           but if there are none, we get an IllegalArgExc, and can use .getArgumentValues()
           However, sometimes some args have names but not all. Such as within synthetic
           lambda methods like "lambda$inc$0". For an unknown reason, trying .arguments()
           causes a JDWP error in such frames. So sadly, those frames are incomplete. */

        boolean JDWPerror = false;
        try {
            sf.getArgumentValues();
        }
        catch (com.sun.jdi.InternalException e) {
            if (e.toString().contains("Unexpected JDWP Error: 35")) // expect JDWP error 35
                JDWPerror = true;
            else {
                throw e;
            }
        }

        List<LocalVariable> frame_vars = null, frame_args = null;
        boolean completed_args = false;
        try {
            // args make sense to show first
            frame_args = sf.location().method().arguments(); //throwing statement
            completed_args = ! JDWPerror && frame_args.size() == sf.getArgumentValues().size();
            for (LocalVariable lv : frame_args) {
                //System.out.println(sf.location().method().getClass());
                if (lv.name().equals("args")) {
                    Value v = sf.getValue(lv);

                    if (v instanceof ArrayReference && ((ArrayReference)v).length() == 0) {
                        continue;
                    }
                }

                result.add(lv.name(), convertValue(sf.getValue(lv)));
                result_ordered.add(lv.name());
            }
        } catch (AbsentInformationException e) {
        }
        // args did not have names, like a functional interface call...
        // although hopefully a future Java version will give them names!
        if ( ! completed_args && ! JDWPerror) {
            try {
                List<Value> anon_args = sf.getArgumentValues();
                for (int i=0; i<anon_args.size(); i++) {
                    result.add("param#"+i, convertValue(anon_args.get(i)));
                    result_ordered.add("param#"+i);
                }
            }
            catch (InvalidStackFrameException e) {
            }
        }

        if (JDWPerror) {
            // hack since number-literal is just html
            result.add("&hellip;?",
                jsonArray("NUMBER-LITERAL", jsonString("&hellip;?")));
            result_ordered.add("&hellip;?");
        }

        // now non-args
        try {
            /* We're using the fact that the hashCode tells us something
               about the variable's position (which is subject to change)
               to compensate for that the natural order of variables()
               is often different from the declaration order (see LinkedList.java) */
            frame_vars = sf.location().method().variables(); //throwing statement
            TreeMap<Integer, String> orderByHash = null;
            int offset = 0;
            for (LocalVariable lv : frame_vars)
                if (!lv.isArgument())
                    if (showAllFields || !lv.name().endsWith("$")) { // skip for-loop synthetics (exists in Java 7, but not 8)
                        try {
                            result.add(lv.name(),
                                    convertValue(sf.getValue(lv)));
                            if (orderByHash == null) {
                                offset = lv.hashCode();
                                orderByHash = new TreeMap<>();
                            }
                            orderByHash.put(lv.hashCode() - offset, lv.name());
                        }
                        catch (IllegalArgumentException exc) {
                            // variable not yet defined, don't list it
                        }
                    }
            if (orderByHash != null) // maybe no local vars
                for (Map.Entry<Integer,String> me : orderByHash.entrySet())
                    result_ordered.add(me.getValue());
        } catch (AbsentInformationException ex) {
            //System.out.println("AIE: can't list variables in " + sf.location());
        }
        if (returnValue != null && (showVoid || returnValue != convertVoid)) {
            result.add("__return__", returnValue);
            result_ordered.add("__return__");
        }
        String methodName = getFormattedMethodName(sf.location().method());
        return Json.createObjectBuilder()
            .add("func_name", methodName + ":" + sf.location().lineNumber())
            .add("encoded_locals", result)
            .add("ordered_varnames", result_ordered)
            .add("parent_frame_id_list", Json.createArrayBuilder())
            .add("is_highlighted", highlight)
            .add("is_zombie", false)
            .add("is_parent", false)
            .add("unique_hash", ""+frame_ticker)
            .add("frame_id", frame_ticker);
    }

    // used to show a single non-user frame when there is
    // non-user code running between two user frames
    private JsonObjectBuilder convertFrameStub(StackFrame sf) {
        Location location = sf.location();
        String methodName = getFormattedMethodName(location.method());
        String functionName = String.format("\u22EE\n%s.%s",
            location.declaringType().name(), methodName);

        return Json.createObjectBuilder()
            .add("func_name", functionName)
            .add("encoded_locals", Json.createObjectBuilder())
            .add("ordered_varnames", Json.createArrayBuilder())
            .add("parent_frame_id_list", Json.createArrayBuilder())
            .add("is_highlighted", false)
            .add("is_zombie", false)
            .add("is_parent", false)
            .add("unique_hash", ""+frame_ticker)
            .add("frame_id", frame_ticker);
    }

    JsonObjectBuilder convertHeap() {
        heap_done = new java.util.TreeSet<>();

        JsonObjectBuilder result = Json.createObjectBuilder();
        while ( ! heap.isEmpty()) {
            Map.Entry<Long, ObjectReference> first = heap.firstEntry();
            ObjectReference obj = first.getValue();
            long id = first.getKey();
            heap.remove(id);
            if (heap_done.contains(id)) {
                continue;
            }
            heap_done.add(id);
            result.add("" + id, convertObject(obj, true));
        }
        return result;
    }

    List<String> wrapperTypes =
        new ArrayList<String>
        (Arrays.asList
         ("Byte Short Integer Long Float Double Character Boolean".split(" ")));

    private JsonValue convertObject(ObjectReference obj, boolean fullVersion) {
        if (showStringsAsValues && obj.referenceType().name().startsWith("java.lang.")
                && wrapperTypes.contains(obj.referenceType().name().substring(10))) {
            return convertValue(obj.getValue(obj.referenceType().fieldByName("value")));
                }

        // abbreviated versions are for references to objects
        if ( ! fullVersion) {
            heap.put(obj.uniqueID(), obj);
            return Json.createArrayBuilder()
                .add("REF")
                .add(obj.uniqueID())
                .build();
        // full versions are for describing the objects themselves,
        // in the heap
        } else if (obj instanceof ArrayReference) {
            heap_done.add(obj.uniqueID());
            return convertArray((ArrayReference)obj);
        } else if (obj instanceof StringReference) {
            return Json.createArrayBuilder()
                .add("HEAP_PRIMITIVE")
                .add("String")
                .add(jsonString(((StringReference)obj).value()))
                .build();
        }
        // do we need special cases for ClassObjectReference, ThreadReference,.... ?
        // stack and queue handling code by Will Gwozdz
        else {
            JsonArrayBuilder result = Json.createArrayBuilder();
            // now deal with Objects.
            heap_done.add(obj.uniqueID());
            result.add("INSTANCE");
            if (obj.referenceType().name().startsWith("java.lang.")
                    && wrapperTypes.contains(obj.referenceType().name().substring(10))) {
                result.add(obj.referenceType().name().substring(10));
                result.add(jsonArray("___NO_LABEL!___",//jsonArray("NO-LABEL"), // don't show a label or label cell for wrapper instance field
                            convertValue(obj.getValue(obj.referenceType().fieldByName("value")))));
            } else {
                String fullName = obj.referenceType().name();
                if (fullName.indexOf("$") > 0) {
                    // inner, local, anonymous or lambda class
                    if (fullName.contains("$$Lambda")) {
                        fullName = "&lambda;" + fullName.substring(fullName.indexOf("$$Lambda")+9); // skip $$lambda$
                        try {
                            String interf = ((ClassType)obj.referenceType()).interfaces().get(0).name();
                            if (interf.startsWith("java.util.function."))
                                interf = interf.substring(19);

                            fullName += " ["+interf+"]";
                        } catch (Exception e) {}
                    // more cases here?
                    } else {
                        fullName=fullName.substring(1+fullName.indexOf('$'));
                        if (fullName.matches("[0-9]+")) {
                            fullName = "anonymous class " + fullName;
                        } else if (fullName.substring(0, 1).matches("[0-9]+")) {
                            fullName = "local class " + fullName.substring(1);
                        }
                    }
                }
                result.add(fullName);
            }
            if (showGuts(obj.referenceType())) {
                // fields: -inherited -hidden +synthetic
                // visibleFields: +inherited -hidden +synthetic
                // allFields: +inherited +hidden +repeated_synthetic
                for (Map.Entry<Field,Value> me : obj.getValues(showAllFields ?
                         obj.referenceType().allFields() :
                         obj.referenceType().visibleFields())
                        .entrySet()) {
                    if ( ! me.getKey().isStatic() && (showAllFields || !me.getKey().isSynthetic())) {
                        String name = (showAllFields
                            ? me.getKey().declaringType().name() + "."
                            : ""
                        ) + me.getKey().name();

                        result.add(Json.createArrayBuilder()
                            .add(name)
                            .add(convertValue(me.getValue())));
                    }
                }
            }
            return result.build();
        }
    }

    /**
     * Convert the given array reference into a JSON array representation.
     */
    private JsonArray convertArray(ArrayReference arr) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        result.add("LIST");
        for (int i = 0; i < arr.length(); i++) {
            // hack for markov
            if ( ! isZeroIntegerValue(arr.getValue(i))) {
                result.add(convertValue(arr.getValue(i)));
                continue;
            }

            // j is the next nonzero after i
            int j = i + 1;
            while (j < arr.length() && isZeroIntegerValue(arr.getValue(j))) {
                j++;
            }
            if (j - i >= 4) {
                result.add(convertValue(arr.getValue(i)));
                result.add(Json.createArrayBuilder().add("ELIDE").add(j - i - 2));
                result.add(convertValue(arr.getValue(j-1)));
            } else {
                for (int k = i; k < j; k++) {
                    result.add(convertValue(arr.getValue(k)));
                }
            }
            i = j - 1; // don't redo them all
        }
        return result.build();
    }

    private JsonArray convertVoid = jsonArray("VOID");

    private JsonArray jsonArray(Object... args) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        for (Object o : args) {
            if (o instanceof JsonValue) {
                result.add((JsonValue)o);
            } else if (o instanceof String) {
                result.add((String)o);
            } else {
                throw new RuntimeException(
                    "Add more cases to JDI2JSON.jsonArray(Object...)");
            }
        }
        return result.build();
    }

    private JsonValue convertValue(Value v) {
        if (v instanceof BooleanValue) {
            if (((BooleanValue)v).value() == true) {
                return JsonValue.TRUE;
            } else {
                return JsonValue.FALSE;
            }
        } else if (v instanceof ByteValue) {
            return jsonInt(((ByteValue)v).value());
        } else if (v instanceof ShortValue) {
            return jsonInt(((ShortValue)v).value());
        } else if (v instanceof IntegerValue) {
            return jsonInt(((IntegerValue)v).value());
            /*
             * Some longs can't be represented as JSON doubles.
             * They won't survive the JSON conversion.
             */
        } else if (v instanceof LongValue) {
            return jsonArray("NUMBER-LITERAL",
                jsonString("" + ((LongValue)v).value()));
            /*
             * Floats that hold integer values will end up as integers
             * after json conversion. Also, this lets us pass "Infinity" and
             * other IEEE non-numbers
             */
        } else if (v instanceof FloatValue) {
            return jsonArray("NUMBER-LITERAL",
                jsonString("" + ((FloatValue)v).value()));
        } else if (v instanceof DoubleValue) {
            return jsonArray("NUMBER-LITERAL",
                jsonString("" + ((DoubleValue)v).value()));
        } else if (v instanceof CharValue) {
            return jsonArray("CHAR-LITERAL",
                jsonString("" + ((CharValue)v).value()));
        } else if (v instanceof VoidValue) {
            return convertVoid;
        } else if (!(v instanceof ObjectReference)) {
            return JsonValue.NULL; //not a hack
        } else if (showStringsAsValues && v instanceof StringReference) {
            return jsonString(((StringReference)v).value());
        } else {
            ObjectReference obj = (ObjectReference)v;
            heap.put(obj.uniqueID(), obj);
            return convertObject(obj, false);
        }
    }

    static JsonObject compileErrorOutput(String errmsg) {
        return compileErrorOutput(errmsg, "", 0, 0);
    }

    static JsonObject compileErrorOutput(String errmsg, String fileName,
            long row, long col) {
        return output(Json.createArrayBuilder().add(
                    Json.createObjectBuilder()
                    .add("line", ""+row)
                    .add("file", fileName)
                    .add("offset", ""+col)
                    .add("event", "uncaught_exception")
                    .add("exception_msg", errmsg)
                    ).build());
    }

    static JsonObject output(JsonArray trace) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        result.add("stdin", InMemory.stdin)
            .add("trace", trace);
        if (userlogged != null) {
            result.add("userlog", userlogged.toString());
        }
        return result.build();
    }

    String exceptionMessage(ExceptionEvent event) {
        ObjectReference exc = event.exception();
        ReferenceType excType = exc.referenceType();
        try {
            /* this is the logical approach, but
             * gives "Unexpected JDWP Error: 502" in invokeMethod
             * even if we suspend-and-resume the thread t
             */
            /*ThreadReference t = event.thread();
              Method mm = excType.methodsByName("getMessage").get(0);
              t.suspend();
              Value v = exc.invokeMethod(t, mm, new ArrayList<Value>(), 0);
              t.resume();
              StringReference sr = (StringReference) v;
              String detail = sr.value();*/

            // so instead we just look for the longest detailMessage
            String detail = "";
            for (Field ff: excType.allFields()) {
                if (ff.name().equals("detailMessage")) {
                    StringReference sr = (StringReference) exc.getValue(ff);
                    String thisMsg = sr == null ? null : sr.value();
                    if (thisMsg != null && thisMsg.length() > detail.length())
                        detail = thisMsg;
                }
            }

            if (detail.equals("")) {
                // NullPointerException has no detail msg
                return excType.name();
            }

            return excType.name() + ": " + detail;
        } catch (Exception e) {
            System.err.println("Failed to convert exception");
            System.err.println(e);
            e.printStackTrace(System.err);
            for (Field ff : excType.visibleFields()) {
                System.err.println(ff);
            }
            return "fail dynamic message lookup";
        }
    }

    /* JSON utility methods */

    static JsonValue jsonInt(long l) {
        return Json.createArrayBuilder().add(l).build().getJsonNumber(0);
    }

    static JsonValue jsonReal(double d) {
        return Json.createArrayBuilder().add(d).build().getJsonNumber(0);
    }

    static JsonValue jsonString(String S) {
        return Json.createArrayBuilder().add(S).build().getJsonString(0);
    }

    static JsonObject jsonModifiedObject(JsonObject obj, String S, JsonValue v) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        result.add(S, v);
        for (Map.Entry<String, JsonValue> me : obj.entrySet()) {
            if (!S.equals(me.getKey()))
                result.add(me.getKey(), me.getValue());
        }
        return result.build();
    }

    // add at specified position, or end if -1
    static JsonArray jsonModifiedArray(JsonArray arr, int tgt, JsonValue v) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        int i = 0;
        for (JsonValue w : arr) {
            if (i == tgt) {
                result.add(v);
            } else {
                result.add(w);
            }
            i++;
        }
        if (tgt == -1) {
            result.add(v);
        }
        return result.build();
    }

    /**
     * Determine if the given JDI value is a zerod integer.
     */
    boolean isZeroIntegerValue(Value v) {
        return v instanceof IntegerValue && ((IntegerValue)v).intValue() == 0;
    }

    /**
     * Neatly formats the name for the given method.
     *
     * @param method The method to grab the name from.
     */
    private String getFormattedMethodName(Method method) {
        // Don't show the method name as "<init>" (which is the default)
        if (method.isConstructor()) {
            // Show the class/constructor name
            ReferenceType type = method.declaringType();
            return type.name();
        }
        return method.name();
    }
}

