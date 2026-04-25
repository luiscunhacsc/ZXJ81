This folder stores `.P` files (ZX81 memory snapshots) used by the emulator.

Supported flows:

1) Start with a preload from the command line:

   `zx81.exe FLIGHT.P`

2) From BASIC inside the emulator:

   `LOAD "FLIGHT"`
   `SAVE "MYPROG"`

The emulator resolves BASIC names inside `tapes/` and appends `.P` when no extension is supplied.
Tape activity is logged to `tape_log.txt` in the project root.
