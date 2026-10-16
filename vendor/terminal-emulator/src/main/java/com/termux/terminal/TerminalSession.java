/*
 * Vendored from termux/termux-app (terminal-emulator), Apache License 2.0.
 * MODIFIED by Walkcode: the local subprocess/JNI pseudo-terminal was replaced with a
 * {@link TerminalTransport} (an SSH channel in Walkcode). The public API used by
 * TerminalView (write, writeCodePoint, updateSize, getEmulator, ...) is unchanged.
 */
package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a {@link TerminalTransport} coupled to a terminal emulator.
 * <p>
 * The transport is opened when the size is made known by a call to {@link #updateSize(int, int, int, int)};
 * threads are then spawned to handle its I/O. All terminal emulation and callback methods are performed on
 * the main thread.
 * <p>
 * NOTE: The terminal session may outlive the view, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Written by the reader thread with remote output, read by the main thread into the emulator. */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** Written by the main thread with user input, drained by the writer thread into the transport. */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private final TerminalTransport mTransport;

    /** True from {@link #initializeEmulator} until the transport ends. */
    private boolean mRunning;
    private int mExitStatus;
    /** Human readable reason the transport ended, or null. */
    private String mExitReason;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(TerminalTransport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the transport of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            if (isRunning()) mTransport.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        synchronized (this) {
            mRunning = true;
        }

        new Thread("TermSessionReader[" + mHandle + "]") {
            @Override
            public void run() {
                int exitCode = -1;
                String reason = null;
                try {
                    mTransport.open(columns, rows, cellWidthPixels, cellHeightPixels);
                    startWriter(mTransport.getOutputStream());
                    InputStream termIn = mTransport.getInputStream();
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) break;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                    exitCode = mTransport.getExitStatus();
                } catch (Exception e) {
                    reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                } finally {
                    mTransport.close();
                }
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, exitCode, 0, reason));
            }
        }.start();
    }

    private void startWriter(final OutputStream termOut) {
        new Thread("TermSessionWriter[" + mHandle + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                        termOut.flush();
                    }
                } catch (IOException e) {
                    // Transport closed; the reader reports the end of the session.
                }
            }
        }.start();
    }

    /** Write data to the remote terminal. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning()) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** End this session by closing the transport (the remote tmux/psmux session keeps running). */
    public void finishIfRunning() {
        if (isRunning()) mTransport.close();
    }

    /** Cleanup resources when the transport ends. */
    void cleanupResources(int exitStatus, String reason) {
        synchronized (this) {
            mRunning = false;
            mExitStatus = exitStatus;
            mExitReason = reason;
        }

        // Stop the reader and writer threads.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Only valid if not {@link #isRunning()}. -1 if unknown. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    /** Why the transport ended (e.g. a connection error), or null for a normal end. */
    public synchronized String getExitReason() {
        return mExitReason;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = msg.arg1;
                String reason = (String) msg.obj;
                cleanupResources(exitCode, reason);

                String exitDescription = reason != null
                    ? "\r\n[Disconnected: " + reason + "]"
                    : "\r\n[Session ended" + (exitCode > 0 ? " (code " + exitCode + ")" : "") + "]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
