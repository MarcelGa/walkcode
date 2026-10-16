/*
 * Walkcode addition to the vendored Termux terminal emulator (Apache License 2.0).
 * Replaces the local pseudo-terminal process with a pluggable byte transport
 * (for Walkcode: an SSH channel with a remote PTY).
 */
package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A bidirectional byte stream to a remote terminal, opened once the terminal size is known. */
public interface TerminalTransport {

    /**
     * Open the remote terminal. Called once, on a background thread, with the initial size.
     * Blocks until the streams are ready or throws.
     */
    void open(int columns, int rows, int cellWidthPixels, int cellHeightPixels) throws IOException;

    /** Output of the remote terminal. Valid after {@link #open}. Returns -1 at end of stream. */
    InputStream getInputStream();

    /** Input to the remote terminal. Valid after {@link #open}. */
    OutputStream getOutputStream();

    /** Report a new terminal size. Called on the main thread; must not block on the network. */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Exit status of the remote command if known, otherwise -1. Called after the input stream ended. */
    int getExitStatus();

    /** Close the transport. Safe to call more than once and from any thread. */
    void close();
}
