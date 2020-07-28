package sc.fiji.snt.viewer.geditor;
import java.awt.EventQueue;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.JComponent;

//https://stackoverflow.com/a/27737289
public class JComponentOutputStream extends OutputStream {

    // *************************************************************************************************
    // INSTANCE MEMBERS
    // *************************************************************************************************

    private byte[] oneByte; // array for write(int val);
    private Appender appender; // most recent action
    private final PrintStream con;

    private Lock jcosLock = new ReentrantLock();

    public JComponentOutputStream(JComponent txtara, JComponentHandler handler) {
        this(txtara, 1000, handler);
    }

    public JComponentOutputStream(JComponent txtara, int maxlin, JComponentHandler handler) {
        if (maxlin < 1) {
            throw new IllegalArgumentException("JComponentOutputStream maximum lines must be positive (value=" + maxlin + ")");
        }
        oneByte = new byte[1];
        appender = new Appender(txtara, maxlin, handler);
        con = new PrintStream(this);
    }

    /** Clear the current console text area. */
    public void clear() {
        jcosLock.lock();
        try {
            if (appender != null) {
                appender.clear();
            }
        } finally {
            jcosLock.unlock();
        }
    }

    public void close() {
        jcosLock.lock();
        try {
            appender = null;
        } finally {
            jcosLock.unlock();
        }
    }

    public void flush() {
        // sstosLock.lock();
        // try {
        // // TODO: Add necessary code here...
        // } finally {
        // sstosLock.unlock();
        // }
    }

    public void write(int val) {
        jcosLock.lock();
        try {
            oneByte[0] = (byte) val;
            write(oneByte, 0, 1);
        } finally {
            jcosLock.unlock();
        }
    }

    public void write(byte[] ba) {
        jcosLock.lock();
        try {
            write(ba, 0, ba.length);
        } finally {
            jcosLock.unlock();
        }
    }

    public void write(byte[] ba, int str, int len) {
        jcosLock.lock();
        try {
            if (appender != null) {
                appender.append(bytesToString(ba, str, len));
            }
        } finally {
            jcosLock.unlock();
        }
    }

    static private String bytesToString(byte[] ba, int str, int len) {
        try {
            return new String(ba, str, len, "UTF-8");
        } catch (UnsupportedEncodingException thr) {
            return new String(ba, str, len);
        } // all JVMs are required to support UTF-8
    }

    // *************************************************************************************************
    // STATIC MEMBERS
    // *************************************************************************************************

    static class Appender implements Runnable {
        private final JComponent swingComponent;
        private final int maxLines; // maximum lines allowed in text area
        private final LinkedList<Integer> lengths; // length of lines within
                                                    // text area
        private final List<String> values; // values waiting to be appended

        private int curLength; // length of current line
        private boolean clear;
        private boolean queue;

        private Lock appenderLock;

        private JComponentHandler handler;

        Appender(JComponent cpt, int maxlin, JComponentHandler hndlr) {
            appenderLock = new ReentrantLock();

            swingComponent = cpt;
            maxLines = maxlin;
            lengths = new LinkedList<Integer>();
            values = new ArrayList<String>();

            curLength = 0;
            clear = false;
            queue = true;

            handler = hndlr;
        }

        void append(String val) {
            appenderLock.lock();
            try {
                values.add(val);
                if (queue) {
                    queue = false;
                    EventQueue.invokeLater(this);
                }
            } finally {
                appenderLock.unlock();
            }
        }

        void clear() {
            appenderLock.lock();
            try {

                clear = true;
                curLength = 0;
                lengths.clear();
                values.clear();
                if (queue) {
                    queue = false;
                    EventQueue.invokeLater(this);
                }
            } finally {
                appenderLock.unlock();
            }
        }

        // MUST BE THE ONLY METHOD THAT TOUCHES the JComponent!
        public void run() {
            appenderLock.lock();
            try {
                if (clear) {
                    handler.setText(swingComponent, "");
                }
                for (String val : values) {
                    curLength += val.length();
                    if (val.endsWith(EOL1) || val.endsWith(EOL2)) {
                        if (lengths.size() >= maxLines) {
                            handler.replaceRange(swingComponent, "", 0, lengths.removeFirst());
                        }
                        lengths.addLast(curLength);
                        curLength = 0;
                    }
                    handler.append(swingComponent, val);
                }

                values.clear();
                clear = false;
                queue = true;
            } finally {
                appenderLock.unlock();
            }
        }

        static private final String EOL1 = "\n";
        static private final String EOL2 = System.getProperty("line.separator", EOL1);
    }

    public interface JComponentHandler {
        void setText(JComponent swingComponent, String text);

        void replaceRange(JComponent swingComponent, String text, int start, int end);

        void append(JComponent swingComponent, String text);
    }

	public PrintStream getPrintStream() {
		return con;
	}

} /* END PUBLIC CLASS */