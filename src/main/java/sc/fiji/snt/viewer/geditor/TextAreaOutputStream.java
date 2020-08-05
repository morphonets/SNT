package sc.fiji.snt.viewer.geditor;

import java.awt.EventQueue;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JTextArea;

//https://stackoverflow.com/a/27737289
//https://stackoverflow.com/a/343007
public class TextAreaOutputStream extends OutputStream {

// *************************************************************************************************
// INSTANCE MEMBERS
// *************************************************************************************************

	private final byte[] oneByte; // array for write(int val);
	private Appender appender; // most recent action
	private final PrintStream con;

	public TextAreaOutputStream(final JTextArea txtara) {
		this(txtara, 1000);
	}

	public TextAreaOutputStream(final JTextArea txtara, final int maxlin) {
		if (maxlin < 1) {
			throw new IllegalArgumentException(
					"TextAreaOutputStream maximum lines must be positive (value=" + maxlin + ")");
		}
		oneByte = new byte[1];
		appender = new Appender(txtara, maxlin);
		con = new PrintStream(this);

	}

	/** Clear the current console text area. */
	public synchronized void clear() {
		if (appender != null) {
			appender.clear();
		}
	}

	public synchronized void close() {
		appender = null;
	}

	public synchronized void flush() {
	}

	public synchronized void write(final int val) {
		oneByte[0] = (byte) val;
		write(oneByte, 0, 1);
	}

	public synchronized void write(final byte[] ba) {
		write(ba, 0, ba.length);
	}

	public synchronized void write(final byte[] ba, final int str, final int len) {
		if (appender != null) {
			appender.append(bytesToString(ba, str, len));
		}
	}

	static private String bytesToString(final byte[] ba, final int str, final int len) {
		try {
			return new String(ba, str, len, "UTF-8");
		} catch (final UnsupportedEncodingException thr) {
			return new String(ba, str, len);
		} // all JVMs are required to support UTF-8
	}

// *************************************************************************************************
// STATIC MEMBERS
// *************************************************************************************************

	static class Appender implements Runnable {
		private final JTextArea textArea;
		private final int maxLines; // maximum lines allowed in text area
		private final LinkedList<Integer> lengths; // length of lines within text area
		private final List<String> values; // values waiting to be appended

		private int curLength; // length of current line
		private boolean clear;
		private boolean queue;

		Appender(final JTextArea txtara, final int maxlin) {
			textArea = txtara;
			maxLines = maxlin;
			lengths = new LinkedList<Integer>();
			values = new ArrayList<String>();

			curLength = 0;
			clear = false;
			queue = true;
		}

		synchronized void append(final String val) {
			values.add(val);
			if (queue) {
				queue = false;
				EventQueue.invokeLater(this);
			}
		}

		synchronized void clear() {
			clear = true;
			curLength = 0;
			lengths.clear();
			values.clear();
			if (queue) {
				queue = false;
				EventQueue.invokeLater(this);
			}
		}

		// MUST BE THE ONLY METHOD THAT TOUCHES textArea!
		public synchronized void run() {
			if (clear) {
				textArea.setText("");
			}
			for (final String val : values) {
				curLength += val.length();
				if (val.endsWith(EOL1) || val.endsWith(EOL2)) {
					if (lengths.size() >= maxLines) {
						textArea.replaceRange("", 0, lengths.removeFirst());
					}
					lengths.addLast(curLength);
					curLength = 0;
				}
				textArea.append(val);
				// SCROLL TO BOTTOM:
				textArea.setCaretPosition(textArea.getDocument().getLength());
			}
			values.clear();
			clear = false;
			queue = true;
		}

		static private final String EOL1 = "\n";
		static private final String EOL2 = System.getProperty("line.separator", EOL1);
	}

	public PrintStream getPrintStream() {
		return con;
	}

} /* END PUBLIC CLASS */