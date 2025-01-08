/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.snt.viewer.geditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintStream;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

class EditorConsole extends JPanel implements ActionListener {

	private static final long serialVersionUID = 1L;
	private final JTextArea ta;
	private final TextAreaOutputStream taStream;
	private final PrintStream sOut;
	private final PrintStream sErr;

	EditorConsole() {
		super(new BorderLayout());
		ta = new JTextArea("Welcome to SNT's Graph Viewer!\n");
		ta.setLineWrap(false);
		sOut = System.out;
		sErr = System.err;
		taStream = new TextAreaOutputStream(ta);
		ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, ta.getFont().getSize()));
		final JScrollPane pane = new JScrollPane(ta);
		pane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		add(pane);
		assignPopupMenu();
	}

	protected JTextArea getTextArea() {
		return ta;
	}

	private void assignPopupMenu() {
		final JPopupMenu pMenu = new JPopupMenu();
		addMenuItem(pMenu, "Cut");
		addMenuItem(pMenu, "Copy");
		pMenu.addSeparator();
		addMenuItem(pMenu, "Clear");
		pMenu.addSeparator();
		final JCheckBoxMenuItem jmi = new JCheckBoxMenuItem("Pause");
		jmi.addItemListener(e -> {
			if (jmi.isSelected()) restore(); else redirect();
		});
		pMenu.add(jmi);
		ta.setComponentPopupMenu(pMenu);
	}

	private void addMenuItem(final JPopupMenu pMenu, final String cmd) {
		final JMenuItem jmi = new JMenuItem(cmd);
		jmi.addActionListener(this);
		pMenu.add(jmi);
	}

	@Override
	public void actionPerformed(final ActionEvent e) {
		switch (e.getActionCommand()) {
		case "Cut":
			ta.cut();
			break;
		case "Copy":
			ta.copy();
			break;
		case "Clear":
			taStream.clear();
			break;
		default:
			throw new IllegalArgumentException("Unrecognized option: " + e);
		}
	}

	protected void setPreferredWidth(final int width) {
		final int cols = Math.max(1, width / 55);
		setPreferredSize(new Dimension(width, (getComponentCount() * 55 / cols) + 30));
		revalidate();
	}

	public void redirect() {
		System.setOut(taStream.getPrintStream());
		System.setErr(taStream.getPrintStream());
	}

	public void restore() {
		System.setOut(sOut);
		System.setErr(sErr);
	}

	public void restoreOut() {
		System.setOut(sOut);
	}

	public void restoreErr() {
		System.setErr(sErr);
	}

	public void clear() {
		taStream.clear();
	}
//
//	public void logAndRestore(final Object obj) {
//		redirect();
//		taStream.log(obj);
//		restore();
//	}
//
//	public void log(final Object obj) {
//		taStream.log(obj);
//	}
}
