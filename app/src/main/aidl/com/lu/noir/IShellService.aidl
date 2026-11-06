package com.lu.noir;

// Runs in the Shizuku server process as shell (uid 2000) / root (uid 0).
// destroy() must keep transaction code 16777114, as defined by Shizuku.
interface IShellService {
    void destroy() = 16777114;

    // Run one shell command, return "exit=<code>\n<stdout>\n---STDERR---\n<stderr>".
    String runCommand(String command) = 1;

    String whoami() = 2;
}
