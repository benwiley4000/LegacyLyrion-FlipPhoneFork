package com.h33ted.legacylyrion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * How the player's SlimProto conversation with LMS travels, when it is not the plain TCP connection
 * that SlimProtoService makes itself: today the LyrionJS bridge (a WebSocket on the web port).
 * The protocol code only ever sees the two streams; what differs lives here.
 */
interface PlayerLink {
    /** Connects, and returns the stream LMS's frames arrive on. */
    InputStream open() throws IOException;

    /** Where the player's frames go: each flush() is one frame. Valid after open(). */
    OutputStream out();

    void close();

    /** "http(s)://host[:port]" that audio streams from when a command names no address of its own. */
    String streamBase();

    /** What to show after a failure, and how long to wait before trying again. */
    Failure failure(Exception e);

    final class Failure {
        final String status;
        final long retryMs;

        Failure(String status, long retryMs) {
            this.status = status;
            this.retryMs = retryMs;
        }
    }
}
