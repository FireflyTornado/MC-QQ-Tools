package cn.nightbugclub.mcqqauthcapture;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

final class ConsoleOutput implements AutoCloseable {
    private final PrintStream stdout = System.out;
    private final PrintStream stderr = System.err;
    private final PrintStream wrappedOut = wrapCurrent(stdout, false);
    private final PrintStream wrappedErr = wrapCurrent(stderr, true);

    static ConsoleOutput install() {
        var console = new ConsoleOutput();
        try {
            if (console.stdout != console.wrappedOut) {
                System.setOut(console.wrappedOut);
            }
            if (console.stderr != console.wrappedErr) {
                System.setErr(console.wrappedErr);
            }
            return console;
        } catch (SecurityException denied) {
            console.close();
            throw denied;
        }
    }

    @Override
    public void close() {
        if (System.out == wrappedOut && stdout != wrappedOut) {
            System.setOut(stdout);
        }
        if (System.err == wrappedErr && stderr != wrappedErr) {
            System.setErr(stderr);
        }
    }

    private static PrintStream wrapCurrent(PrintStream stream, boolean error) {
        String type = stream.getClass().getName();
        boolean forwardsLines = type.equals("net.minecraft.server.LoggedPrintStream")
                || type.equals("net.minecraft.server.DebugLoggedPrintStream");
        if (!forwardsLines && stream.getClass() != PrintStream.class) {
            // Another mod owns an unknown PrintStream implementation and may override its behavior.
            return stream;
        }
        Charset charset = Charset.defaultCharset();
        if (!forwardsLines) {
            String encoding = System.getProperty(error ? "sun.stderr.encoding" : "sun.stdout.encoding");
            if (encoding != null && Charset.isSupported(encoding)) {
                charset = Charset.forName(encoding);
            }
        }
        return wrap(stream, error, charset, forwardsLines);
    }

    static PrintStream wrap(PrintStream stream, boolean error, Charset charset, boolean forwardsLines) {
        return new PrintStream(new Sink(stream, error, charset), true, charset) {
            @Override
            public void println(String text) {
                if (forwardsLines) {
                    stream.println(text);
                } else {
                    super.println(text);
                }
            }

            @Override
            public void println(Object value) {
                if (forwardsLines) {
                    stream.println(value);
                } else {
                    super.println(value);
                }
            }

            @Override
            public boolean checkError() {
                return stream.checkError() || super.checkError();
            }
        };
    }

    private static final class Sink extends OutputStream {
        private final PrintStream original;
        private final boolean error;
        private final Charset charset;

        Sink(PrintStream original, boolean error, Charset charset) {
            this.original = original;
            this.error = error;
            this.charset = charset;
        }

        @Override
        public void write(int value) {
            original.write(value);
            byte[] bytes = {(byte) value};
            OutputCapture.consoleBytes(error, bytes, 0, 1, charset);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            original.write(bytes, offset, length);
            OutputCapture.consoleBytes(error, bytes, offset, length, charset);
        }

        @Override
        public void flush() {
            original.flush();
        }

        @Override
        public void close() {
            original.close();
        }
    }
}
