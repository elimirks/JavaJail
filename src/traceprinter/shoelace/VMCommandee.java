package traceprinter.shoelace;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.*;

/**
 * Receives commands from traceprinter.VMCommander telling what user code
 * should be run.
 *
 * Note that VMCommander is in the debugger JVM, and
 * VMCommandee is in the debugee.
 */
public class VMCommandee {
    private static final String MAIN_CLASS_NOT_FOUND_MESSAGE =
        "Internal error: main class %s not found";
    private static final String MAIN_METHOD_NOT_FOUND_MESSAGE =
        "Class '%s' requires a 'public static void main' method";

    // Returns null if everything worked
    // else, returns an error message
    public String runMain(String className, String[] args, String stdin) {
        Class<?> target;
        try {
            target = ByteClassLoader.publicFindClass(className);
        } catch (ClassNotFoundException e) {
            return String.format(MAIN_CLASS_NOT_FOUND_MESSAGE, className);
        }

        Method main;
        try {
            main = target.getMethod("main", new Class[]{String[].class});
        } catch (NoSuchMethodException e) {
            return String.format(MAIN_METHOD_NOT_FOUND_MESSAGE, className);
        }

        if (stdin != null) {
            try {
                System.setIn(new ByteArrayInputStream(stdin.getBytes("UTF-8")));
            } catch (SecurityException | UnsupportedEncodingException e) {
                return "Internal error: can't setIn";
            }
        }

        int modifiers = main.getModifiers();
        if (modifiers != (Modifier.PUBLIC | Modifier.STATIC)) {
            return String.format(MAIN_METHOD_NOT_FOUND_MESSAGE, className);
        }

        try {
            // First is null since it is a static method
            main.invoke(null, new Object[]{args});
            return null;
        } catch (IllegalAccessException e) {
            return "Internal error invoking main";
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException)
                throw (RuntimeException)(e.getTargetException());


            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.getTargetException().printStackTrace(pw);

            return "Internal error handling error " +
                e.getTargetException() + sw.toString();

            //if (e.getTargetException() instanceof Error)
            //  throw (Error)(e.getTargetException());
        }
    }
}

