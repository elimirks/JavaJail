#!/usr/bin/env python3

'''
Used to generate input for the JavaJail.
Pipe output from here into run.sh to generate a stack trace.
'''

import sys
import json
import re

if len(sys.argv) == 1:
    print("Usage: %s File1.java File2.java ..." % sys.argv[0])

filePaths = sys.argv[1:]

objectifiedFiles = []

for path in filePaths:
    objectFile = {}

    # Strip out just the base name - we don't include the ".java"
    match = re.search('([A-Za-z0-9\._]+)\.java$', path)

    if not match:
        print('Invalid file name: ' + path)
        sys.exit(1)

    objectFile['name'] = match.group(1)

    with open(path) as f:
        objectFile['code'] = f.read()

    objectifiedFiles.append(objectFile)

data = {
    'files': objectifiedFiles,
    'options': {},
    'args': [],
    'stdin': '',
}

print(json.dumps(data, indent=4))

