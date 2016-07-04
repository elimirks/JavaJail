Original Format
===============
The I/O format is nearly hte same as in OnlinePythonTutor:
https://github.com/pgbovine/OnlinePythonTutor/blob/master/v3/docs/opt-trace-format.md

Differences
===========

Input
-----
- Code must be passed in as a file list

For instance:

```javascript
{
    "files": [
        {
            "name": null,
            "code": "public class Test { public static void main(String[] args) { int x = 3; x += x; Hai h = new Hai(); } }"
        },
        {
            "name": "Foobar",
            "code": "class Hai {\npublic Hai() {}\n}"
        }
    ],
    "options": {},
    "args": [],
    "stdin": ""
}
```

The first file in the list will be considered the main file (where main() is located).
If the "name" property is omitted or null, it will use the public class name.

Output
------
- The "code" parameter is omitted.
- A "file" parameter is output alongside the "line"
- A "last_return_value" parameter may be shown when a non-void function returns.

