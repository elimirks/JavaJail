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
    /**
     * Runs the main method in the users code.
     *
     * @param className The "main class" of the user code.
     * @param args The args to pass to the users main method.
     * @param stdin The STDIN to stream into the user program.
     * @return An error message, or null if everything worked
     */
    public String runMain(String className, String[] args, String stdin) {
        try {
            Class<?> target = targetFromClassName(className);
            Method main = mainMethodFromClass(target, className);
            setStdin(stdin);

            // Instance param is null since it is a static method
            main.invoke(null, new Object[] {
                args
            });
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
        } catch (CodeStagingException e) {
             return e.getMessage();
        }
    }

    /**
     * Finds the user class with the given name.
     */
    private Class<?> targetFromClassName(String className)
            throws CodeStagingException {
        Class<?> target;
        try {
            target = ByteClassLoader.publicFindClass(className);
        } catch (ClassNotFoundException e) {
            throw new CodeStagingException(String.format(
                "Main class '%s' not found.",
                className));
        }

        int classMod = target.getModifiers();
        if ((classMod & Modifier.PUBLIC) != Modifier.PUBLIC) {
            throw new CodeStagingException(String.format(
                "Class '%s' must be public.",
                className));
        } else if ((classMod & Modifier.INTERFACE) == Modifier.INTERFACE) {
            throw new CodeStagingException(String.format(
                "'%s' must be a class, not an interface.",
                className));
        }
        return target;
    }

    /**
     * Finds the main method, ready to invoke, from the given class.
     */
    private Method mainMethodFromClass(Class<?> target, String className)
            throws CodeStagingException {
        Method main;
        try {
            main = target.getDeclaredMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            throw new CodeStagingException(String.format(
                "Class '%s' requires a method with signature " +
                    "'public static void main(String[])'.",
                className));
        }

        int mainModifiers = main.getModifiers();
        if ((mainModifiers & Modifier.PUBLIC) != Modifier.PUBLIC) {
            throw new CodeStagingException(String.format(
                "The 'main' method in '%s' must be public.",
                className));
        } else if ((mainModifiers & Modifier.STATIC) != Modifier.STATIC) {
            throw new CodeStagingException(String.format(
                "The 'main' method in '%s' must be static.",
                className));
        }

        return main;
    }

    /**
     * Sets user code stdin (inside the VM).
     */
    private void setStdin(String stdin) throws CodeStagingException {
        if (stdin == null) return;

        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes("UTF-8")));
        } catch (SecurityException | UnsupportedEncodingException e) {
            throw new CodeStagingException("Internal error: can't set STDIN");
        }
    }

    /**
     * Exception class used to signify user code misconfiguration.
     *
     * For instance, a missing main() method.
     */
    private class CodeStagingException extends Exception {
        public CodeStagingException(String message) {
            super(message);
        }
    }
}

