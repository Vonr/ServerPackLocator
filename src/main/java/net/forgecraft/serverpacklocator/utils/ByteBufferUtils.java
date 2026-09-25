package net.forgecraft.serverpacklocator.utils;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class ByteBufferUtils {
    // Adapted from https://stackoverflow.com/a/6603018 (https://creativecommons.org/licenses/by-sa/3.0/)
    public static class ByteBufferBackedInputStream extends InputStream {
        @NotNull
        final ByteBuffer buf;

        public ByteBufferBackedInputStream(@NotNull ByteBuffer buf) {
            this.buf = buf;
        }

        @Override
        public int read() {
            if (!buf.hasRemaining()) {
                return -1;
            }
            return buf.get() & 0xFF;
        }

        @Override
        public int read(byte @NotNull [] bytes, int off, int len) {
            if (!buf.hasRemaining()) {
                return -1;
            }

            len = Math.min(len, buf.remaining());
            buf.get(bytes, off, len);
            return len;
        }
    }

    @NotNull
    public static InputStream inputStreamFromByteBuffer(@NotNull ByteBuffer buf) {
        return buf.hasArray() ? new ByteArrayInputStream(buf.array()) : new ByteBufferBackedInputStream(buf);
    }
}
