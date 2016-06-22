package traceprinter;

import javax.json.JsonArray;
import javax.json.JsonObject;
import java.util.List;
import java.util.ArrayList;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Container and parser "fake" files from JSON.
 */
public class FakeFile {
    private String name;
    private String code;

    /**
     * Instantiates from the given JSON.
     *
     * @param json A JSON object with "code" and "name" parameter.
     *             If "name" is null, the public class of the code is used.
     */
    public FakeFile(JsonObject json) throws NameException {
        code = json.getJsonString("code").toString();
        name = (! json.containsKey("name")) || json.isNull("name")
            ? extractPublicClassName(code)
            : json.getJsonString("name").toString();
    }

    /**
     * Instantiates from already known name and code.
     */
    public FakeFile(String name, String code) {
        this.name = name;
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * @return The file name (with ".java" omitted
     */
    public String getName() {
        return name;
    }

    /**
     * @return A String pair with the name first and the code second.
     */
    public String[] getNameCodePair() {
        return new String[] { name, code };
    }

    /**
     * Generates a fake file list from the given JSON array.
     *
     * @param json A json list of files. Format is explained in FakeFile()
     * @return A list of fake files from the given json.
     * @see FakeFile(JsonObject)
     */
    public static List<FakeFile> parseJsonFiles(JsonArray json)
            throws NameException {
        List<FakeFile> list = new ArrayList<FakeFile>();
        for (int i = 0; i < json.size(); i++) {
            FakeFile file = new FakeFile(json.getJsonObject(i));
            list.add(file);
        }
        return list;
    }

    /**
     * Turns a fake file list into a pair array.
     *
     * This format is used to compile files into bytes in memory.
     * @see parseJsonFiles
     */
    public static String[][] fakeFileListToPairArray(List<FakeFile> files) {
        String[][] pairs = new String[files.size()][];
        for (int i = 0; i < files.size(); i++) {
            pairs[i] = files.get(i).getNameCodePair();
        }
        return pairs;
    }

    /**
     * Extract the public class from the given code.
     *
     * @param code The code to strip the class name from.
     * @return The public class name from the given code.
     */
    private String extractPublicClassName(String code) throws NameException {
        // Not 100% accurate if people have multiple top-level classes.
        Pattern p = Pattern.compile("public\\s+class\\s+([a-zA-Z0-9_]+)\\b");
        Matcher m = p.matcher(code);

        if ( ! m.find()) {
            String message = "Error: Make sure your code includes " +
                "'public class \u00ABClassName\u00BB'";
            throw new NameException(message);
        }

        return m.group(1);
    }


    /**
     * Thrown when an invalid file name is given.
     */
    public class NameException extends Exception {
        public NameException(String message) {
            super(message);
        }
    }
}

