#!/usr/bin/env python3

# Pipe output from JavaJail in here to analyze it in the shell
# This is used for developing JavaJail - doesn't have much use otherwise

import os
import sys
import json
import curses

def displayStep(stdscr, scrollOffset, stepNumber, steps):
    '''Displays a single step on the console.

    Params:
        stdscr: A curses screen to print to.
        scrollOffset: How far down to scroll (for large outputs).
        stepNumber: The trace index of the given step
        step: The step object.
    '''
    step = steps[stepNumber]

    stepHeader = 'Step {0:->3}/{1:->3}:'.format(stepNumber, len(steps) - 1)
    offsetHeader = '(Scroll offset: {0})'.format(scrollOffset)
    stdscr.addstr(2, 0, stepHeader)
    stdscr.addstr(2, 14, offsetHeader)

    jsonStr = json.dumps(step, indent=4)
    lines = jsonStr.splitlines()

    displayOffsetY = 4
    maxY, maxX = stdscr.getmaxyx()
    maxY -= displayOffsetY

    begin = max(0, scrollOffset)
    end = begin + maxY
    lines = lines[begin:end]

    stdscr.addstr(displayOffsetY, 0, '\n'.join(lines))

def startTraceWindow(steps):
    stdscr = curses.initscr()
    curses.cbreak()
    curses.curs_set(0)
    stdscr.keypad(1)

    stdscr.refresh()

    stepNumber = 0
    scrollOffset = 0
    key = ''
    while key != ord('q'):
        stdscr.clear()
        headerStr = (
            'Whack "q" to quit. '
            'left/right arrows to step. '
            'up/down/PGUP/PGDOWN to scroll.'
        )
        stdscr.addstr(0, 0, headerStr)
        displayStep(stdscr, scrollOffset, stepNumber, steps)

        key = stdscr.getch()

        # Handle changing steps
        if key == curses.KEY_RIGHT and stepNumber < len(steps) - 1:
            stepNumber += 1
        elif key == curses.KEY_LEFT and stepNumber > 0:
            stepNumber -= 1
        # Handle scrolling (for large steps)
        elif key == curses.KEY_UP and scrollOffset > 0:
            scrollOffset -= 1
        elif key == curses.KEY_DOWN:
            scrollOffset += 1
        elif key == curses.KEY_PPAGE:
            scrollOffset = max(0, scrollOffset - 10)
        elif key == curses.KEY_NPAGE:
            scrollOffset += 10

    curses.endwin()


if __name__ == '__main__':
    stdin = sys.stdin.readline()
    # Reset stdin to the terminal, since we intend to pipe a big string in here
    sys.stdin = open('/dev/tty')
    os.dup2(sys.stdin.fileno(), 0)

    try:
        jsonInput = json.loads(stdin)

        if 'trace' in jsonInput:
            startTraceWindow(jsonInput['trace'])
        else:
            json.dumps(jsonInput, indent=4)
    except ValueError as e: # Probably a JSON parse error
        print("Recieved stdin: ")
        print(stdin)
        print("Error: ")
        print(e)

