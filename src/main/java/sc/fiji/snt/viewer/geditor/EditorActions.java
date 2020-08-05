package sc.fiji.snt.viewer.geditor;

import com.mxgraph.analysis.mxDistanceCostFunction;
import com.mxgraph.analysis.mxGraphAnalysis;
import com.mxgraph.canvas.mxICanvas;
import com.mxgraph.canvas.mxSvgCanvas;
import com.mxgraph.io.mxCodec;
import com.mxgraph.io.mxGdCodec;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;
import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGeometry;
import com.mxgraph.model.mxGraphModel;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.swing.handler.mxConnectionHandler;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.util.mxGraphActions;
import com.mxgraph.swing.view.mxCellEditor;
import com.mxgraph.util.*;
import com.mxgraph.util.mxCellRenderer.CanvasFactory;
import com.mxgraph.util.png.mxPngEncodeParam;
import com.mxgraph.util.png.mxPngImageEncoder;
import com.mxgraph.util.png.mxPngTextDecoder;
import com.mxgraph.view.mxGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.util.ColorRGB;
import org.w3c.dom.Document;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.GraphRecViewerCmd;
import sc.fiji.snt.gui.cmds.NewGraphOptionsCmd;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Copyright (c) 2001-2012, JGraph Ltd
 */
public class EditorActions
{
	/**
	 * 
	 * @param e
	 * @return Returns the graph for the given action event.
	 */
	public static final GraphEditor getEditor(ActionEvent e)
	{
		if (e.getSource() instanceof Component)
		{
			Component component = (Component) e.getSource();

			while (component != null
					&& !(component instanceof GraphEditor))
			{
				component = component.getParent();
			}

			return (GraphEditor) component;
		}

		return null;
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ToggleRulersItem extends JCheckBoxMenuItem
	{
		public ToggleRulersItem(final GraphEditor editor, String name)
		{
			super(name);
			setSelected(editor.getGraphComponent().getColumnHeader() != null);
			addActionListener(e -> {
				mxGraphComponent graphComponent = editor.getGraphComponent();

				if (graphComponent.getColumnHeader() != null) {
					graphComponent.setColumnHeader(null);
					graphComponent.setRowHeader(null);
				} else {
					graphComponent
							.setColumnHeaderView(new EditorRuler(graphComponent, EditorRuler.ORIENTATION_HORIZONTAL));
					graphComponent.setRowHeaderView(new EditorRuler(graphComponent, EditorRuler.ORIENTATION_VERTICAL));
				}
			});
		}
	}

	@SuppressWarnings("serial")
	public static class ToggleBottomPaneItem extends JCheckBoxMenuItem {
		public ToggleBottomPaneItem(final GraphEditor editor, String name) {
			super(name, editor.getGraphOutline().isVisible());
			addItemListener(e -> {
				editor.setBottomPaneVisible(isSelected());
			});
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ExitAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			GraphEditor editor = getEditor(e);

			if (editor != null)
			{
				editor.exit();
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class StylesheetAction extends AbstractAction
	{
		protected String stylesheet;
		protected Color background;

		public StylesheetAction(String stylesheet, final Color background)
		{
			this.stylesheet = stylesheet;
			this.background = background;
		}

		public void actionPerformed(ActionEvent e) {
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e.getSource();
				mxGraph graph = graphComponent.getGraph();
				mxCodec codec = new mxCodec();
				Document doc = mxUtils.loadDocument(EditorActions.class.getResource(stylesheet).toString());
				if (doc != null) {
					try {
						graph.getModel().beginUpdate();
						codec.decode(doc.getDocumentElement(), graph.getStylesheet());
						graphComponent.setPageBackgroundColor(background);
						graphComponent.getViewport().setOpaque(true);
						graphComponent.getViewport().setBackground(background);
					} finally {
						graph.getModel().endUpdate();
						graph.refresh();
						// somehow changes on label spacing don't apply fully with certain styles!?
						// HACK: update manually all the cell sizes
						graph.getModel().beginUpdate();
						try {
							for (Object cell : mxGraphModel.getChildren(graph.getModel(), graph.getDefaultParent()))
								graph.updateCellSize(cell);
						}
						finally {
							graph.getModel().endUpdate();
						}
					}
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ZoomPolicyAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected int zoomPolicy;

		/**
		 * 
		 */
		public ZoomPolicyAction(int zoomPolicy)
		{
			this.zoomPolicy = zoomPolicy;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				graphComponent.setPageVisible(true);
				graphComponent.setZoomPolicy(zoomPolicy);
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class GridStyleAction extends AbstractAction
	{
		private final int style;
		private final EditorMenuBar editorMenuBar;

		public GridStyleAction(EditorMenuBar editorMenuBar, int style)
		{
			this.style = style;
			this.editorMenuBar = editorMenuBar;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				graphComponent.setGridStyle(style);
				editorMenuBar.enableGrid(true);
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class GridColorAction extends AbstractAction
	{

		private final EditorMenuBar editorMenuBar;

		public GridColorAction(EditorMenuBar editorMenuBar)
		{
			this.editorMenuBar = editorMenuBar;
		}

		@Override
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				Color newColor = JColorChooser.showDialog(graphComponent,
						mxResources.get("gridColor"),
						graphComponent.getGridColor());

				if (newColor != null)
				{
					graphComponent.setGridColor(newColor);
					editorMenuBar.enableGrid(true);
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ScaleAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected double scale;

		/**
		 * 
		 */
		public ScaleAction(double scale)
		{
			this.scale = scale;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				double scale = this.scale;

				if (scale == 0)
				{
					String value = (String) JOptionPane.showInputDialog(
							graphComponent, mxResources.get("value"),
							mxResources.get("scale") + " (%)",
							JOptionPane.PLAIN_MESSAGE, null, null, "");

					if (value != null)
					{
						scale = Double.parseDouble(value.replace("%", "")) / 100;
					}
				}

				if (scale > 0)
				{
					graphComponent.zoomTo(scale, graphComponent.isCenterZoom());
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class PageSetupAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				PrinterJob pj = PrinterJob.getPrinterJob();
				PageFormat format = pj.pageDialog(graphComponent
						.getPageFormat());

				if (format != null)
				{
					graphComponent.setPageFormat(format);
					graphComponent.zoomAndCenter();
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class PrintAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				PrinterJob pj = PrinterJob.getPrinterJob();

				if (pj.printDialog())
				{
					PageFormat pf = graphComponent.getPageFormat();
					Paper paper = new Paper();
					double margin = 36;
					paper.setImageableArea(margin, margin, paper.getWidth()
							- margin * 2, paper.getHeight() - margin * 2);
					pf.setPaper(paper);
					pj.setPrintable(graphComponent, pf);

					try
					{
						pj.print();
					}
					catch (PrinterException e2)
					{
						System.out.println(e2);
					}
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class SaveAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected boolean showDialog;

		/**
		 * 
		 */
		protected String lastDir = null;

		/**
		 * 
		 */
		public SaveAction(boolean showDialog)
		{
			this.showDialog = showDialog;
		}

		/**
		 * Saves XML+PNG format.
		 */
		protected void saveXmlPng(GraphEditor editor, String filename,
				Color bg) throws IOException
		{
			mxGraphComponent graphComponent = editor.getGraphComponent();
			mxGraph graph = graphComponent.getGraph();

			// Creates the image for the PNG file
			BufferedImage image = mxCellRenderer.createBufferedImage(graph,
					null, 1, bg, graphComponent.isAntiAlias(), null,
					graphComponent.getCanvas());

			// Creates the URL-encoded XML data
			mxCodec codec = new mxCodec();
			String xml = URLEncoder.encode(
					mxXmlUtils.getXml(codec.encode(graph.getModel())), "UTF-8");
			mxPngEncodeParam param = mxPngEncodeParam
					.getDefaultEncodeParam(image);
			param.setCompressedText(new String[] { "mxGraphModel", xml });

			// Saves as a PNG file
			FileOutputStream outputStream = new FileOutputStream(new File(
					filename));
			try
			{
				mxPngImageEncoder encoder = new mxPngImageEncoder(outputStream,
						param);

				if (image != null)
				{
					encoder.encode(image);

					editor.setModified(false);
					editor.setCurrentFile(new File(filename));
				}
				else
				{
					JOptionPane.showMessageDialog(graphComponent,
							mxResources.get("noImageData"));
				}
			}
			finally
			{
				outputStream.close();
			}
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			GraphEditor editor = getEditor(e);

			if (editor != null)
			{
				mxGraphComponent graphComponent = editor.getGraphComponent();
				mxGraph graph = graphComponent.getGraph();
				FileFilter selectedFilter = null;
				DefaultFileFilter xmlPngFilter = new DefaultFileFilter(".png",
						"PNG+XML " + mxResources.get("file") + " (.png)");
				FileFilter vmlFileFilter = new DefaultFileFilter(".html",
						"VML " + mxResources.get("file") + " (.html)");
				String filename = null;
				boolean dialogShown = false;

				if (showDialog || editor.getCurrentFile() == null)
				{
					String wd;

					if (lastDir != null)
					{
						wd = lastDir;
					}
					else if (editor.getCurrentFile() != null)
					{
						wd = editor.getCurrentFile().getParent();
					}
					else
					{
						wd = System.getProperty("user.dir");
					}

					JFileChooser fc = new JFileChooser(wd);

					// Adds the default file format
					FileFilter defaultFilter = xmlPngFilter;
					fc.addChoosableFileFilter(defaultFilter);

					// Adds special vector graphics formats and HTML
					fc.addChoosableFileFilter(new DefaultFileFilter(".mxe",
							"mxGraph Editor " + mxResources.get("file")
									+ " (.mxe)"));
					fc.addChoosableFileFilter(new DefaultFileFilter(".txt",
							"Graph Drawing " + mxResources.get("file")
									+ " (.txt)"));
					fc.addChoosableFileFilter(new DefaultFileFilter(".svg",
							"SVG " + mxResources.get("file") + " (.svg)"));
					fc.addChoosableFileFilter(vmlFileFilter);
					fc.addChoosableFileFilter(new DefaultFileFilter(".html",
							"HTML " + mxResources.get("file") + " (.html)"));

					// Adds a filter for each supported image format
					Object[] imageFormats = ImageIO.getReaderFormatNames();

					// Finds all distinct extensions
					HashSet<String> formats = new HashSet<String>();

					for (int i = 0; i < imageFormats.length; i++)
					{
						String ext = imageFormats[i].toString().toLowerCase();
						formats.add(ext);
					}

					imageFormats = formats.toArray();

					for (int i = 0; i < imageFormats.length; i++)
					{
						String ext = imageFormats[i].toString();
						fc.addChoosableFileFilter(new DefaultFileFilter("."
								+ ext, ext.toUpperCase() + " "
								+ mxResources.get("file") + " (." + ext + ")"));
					}

					// Adds filter that accepts all supported image formats
					fc.addChoosableFileFilter(new DefaultFileFilter.ImageFileFilter(
							mxResources.get("allImages")));
					fc.setFileFilter(defaultFilter);
					int rc = fc.showDialog(null, mxResources.get("save"));
					dialogShown = true;

					if (rc != JFileChooser.APPROVE_OPTION)
					{
						return;
					}
					else
					{
						lastDir = fc.getSelectedFile().getParent();
					}

					filename = fc.getSelectedFile().getAbsolutePath();
					selectedFilter = fc.getFileFilter();

					if (selectedFilter instanceof DefaultFileFilter)
					{
						String ext = ((DefaultFileFilter) selectedFilter)
								.getExtension();

						if (!filename.toLowerCase().endsWith(ext))
						{
							filename += ext;
						}
					}

					if (new File(filename).exists()
							&& JOptionPane.showConfirmDialog(graphComponent,
									mxResources.get("overwriteExistingFile")) != JOptionPane.YES_OPTION)
					{
						return;
					}
				}
				else
				{
					filename = editor.getCurrentFile().getAbsolutePath();
				}

				try
				{
					String ext = filename
							.substring(filename.lastIndexOf('.') + 1);

					if (ext.equalsIgnoreCase("svg"))
					{
						mxSvgCanvas canvas = (mxSvgCanvas) mxCellRenderer
								.drawCells(graph, null, 1, null,
										new CanvasFactory()
										{
											public mxICanvas createCanvas(
													int width, int height)
											{
												mxSvgCanvas canvas = new mxSvgCanvas(
														mxDomUtils.createSvgDocument(
																width, height));
												canvas.setEmbedded(true);

												return canvas;
											}

										});

						mxUtils.writeFile(mxXmlUtils.getXml(canvas.getDocument()),
								filename);
					}
					else if (selectedFilter == vmlFileFilter)
					{
						mxUtils.writeFile(mxXmlUtils.getXml(mxCellRenderer
								.createVmlDocument(graph, null, 1, null, null)
								.getDocumentElement()), filename);
					}
					else if (ext.equalsIgnoreCase("html"))
					{
						mxUtils.writeFile(mxXmlUtils.getXml(mxCellRenderer
								.createHtmlDocument(graph, null, 1, null, null)
								.getDocumentElement()), filename);
					}
					else if (ext.equalsIgnoreCase("mxe")
							|| ext.equalsIgnoreCase("xml"))
					{
						mxCodec codec = new mxCodec();
						String xml = mxXmlUtils.getXml(codec.encode(graph
								.getModel()));

						mxUtils.writeFile(xml, filename);

						editor.setModified(false);
						editor.setCurrentFile(new File(filename));
					}
					else if (ext.equalsIgnoreCase("txt"))
					{
						String content = mxGdCodec.encode(graph);

						mxUtils.writeFile(content, filename);
					}
					else
					{
						Color bg = null;

						if ((!ext.equalsIgnoreCase("gif") && !ext
								.equalsIgnoreCase("png"))
								|| JOptionPane.showConfirmDialog(
										graphComponent, mxResources
												.get("transparentBackground")) != JOptionPane.YES_OPTION)
						{
							bg = graphComponent.getBackground();
						}

						if (selectedFilter == xmlPngFilter
								|| (editor.getCurrentFile() != null
										&& ext.equalsIgnoreCase("png") && !dialogShown))
						{
							saveXmlPng(editor, filename, bg);
						}
						else
						{
							BufferedImage image = mxCellRenderer
									.createBufferedImage(graph, null, 1, bg,
											graphComponent.isAntiAlias(), null,
											graphComponent.getCanvas());

							if (image != null)
							{
								ImageIO.write(image, ext, new File(filename));
							}
							else
							{
								JOptionPane.showMessageDialog(graphComponent,
										mxResources.get("noImageData"));
							}
						}
					}
				}
				catch (Throwable ex)
				{
					ex.printStackTrace();
					JOptionPane.showMessageDialog(graphComponent,
							ex.toString(), mxResources.get("error"),
							JOptionPane.ERROR_MESSAGE);
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class SelectShortestPathAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected boolean directed;

		/**
		 * 
		 */
		public SelectShortestPathAction(boolean directed)
		{
			this.directed = directed;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				mxGraph graph = graphComponent.getGraph();
				mxIGraphModel model = graph.getModel();

				Object source = null;
				Object target = null;

				Object[] cells = graph.getSelectionCells();

				for (int i = 0; i < cells.length; i++)
				{
					if (model.isVertex(cells[i]))
					{
						if (source == null)
						{
							source = cells[i];
						}
						else if (target == null)
						{
							target = cells[i];
						}
					}

					if (source != null && target != null)
					{
						break;
					}
				}

				if (source != null && target != null)
				{
					int steps = graph.getChildEdges(graph.getDefaultParent()).length;
					Object[] path = mxGraphAnalysis.getInstance()
							.getShortestPath(graph, source, target,
									new mxDistanceCostFunction(), steps,
									directed);
					graph.setSelectionCells(path);
				}
				else
				{
					JOptionPane.showMessageDialog(graphComponent,
							mxResources.get("noSourceAndTargetSelected"));
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class SelectSpanningTreeAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected boolean directed;

		/**
		 * 
		 */
		public SelectSpanningTreeAction(boolean directed)
		{
			this.directed = directed;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				mxGraph graph = graphComponent.getGraph();
				mxIGraphModel model = graph.getModel();

				Object parent = graph.getDefaultParent();
				Object[] cells = graph.getSelectionCells();

				for (int i = 0; i < cells.length; i++)
				{
					if (model.getChildCount(cells[i]) > 0)
					{
						parent = cells[i];
						break;
					}
				}

				Object[] v = graph.getChildVertices(parent);
				Object[] mst = mxGraphAnalysis.getInstance()
						.getMinimumSpanningTree(graph, v,
								new mxDistanceCostFunction(), directed);
				graph.setSelectionCells(mst);
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ToggleConnectModeAction extends JCheckBoxMenuItem
	{
		public ToggleConnectModeAction(final String name, final boolean initialState) {
			super(name, initialState);
		}

		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				mxConnectionHandler handler = graphComponent
						.getConnectionHandler();
				handler.setHandleEnabled(!handler.isHandleEnabled());
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ToggleCreateTargetItem extends JCheckBoxMenuItem
	{
		/**
		 * 
		 */
		public ToggleCreateTargetItem(final GraphEditor editor, String name)
		{
			super(name);
			setSelected(true);

			addActionListener(new ActionListener()
			{
				/**
				 * 
				 */
				public void actionPerformed(ActionEvent e)
				{
					mxGraphComponent graphComponent = editor
							.getGraphComponent();

					if (graphComponent != null)
					{
						mxConnectionHandler handler = graphComponent
								.getConnectionHandler();
						handler.setCreateTarget(!handler.isCreateTarget());
						setSelected(handler.isCreateTarget());
					}
				}
			});
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class PromptPropertyAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected Object target;

		/**
		 * 
		 */
		protected String fieldname, message;

		/**
		 * 
		 */
		public PromptPropertyAction(Object target, String message)
		{
			this(target, message, message);
		}

		/**
		 * 
		 */
		public PromptPropertyAction(Object target, String message,
				String fieldname)
		{
			this.target = target;
			this.message = message;
			this.fieldname = fieldname;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof Component)
			{
				try
				{
					Method getter = target.getClass().getMethod(
							"get" + fieldname);
					Object current = getter.invoke(target);

					// TODO: Support other atomic types
					if (current instanceof Integer)
					{
						Method setter = target.getClass().getMethod(
								"set" + fieldname, int.class);

						String value = (String) JOptionPane.showInputDialog(
								(Component) e.getSource(), "Value", message,
								JOptionPane.PLAIN_MESSAGE, null, null, current);

						if (value != null)
						{
							setter.invoke(target, Integer.parseInt(value));
						}
					}
				}
				catch (Exception ex)
				{
					ex.printStackTrace();
				}
			}

			// Repaints the graph component
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				graphComponent.repaint();
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class TogglePropertyItem extends JCheckBoxMenuItem
	{
		/**
		 * 
		 */
		public TogglePropertyItem(Object target, String name, String fieldname)
		{
			this(target, name, fieldname, false);
		}

		/**
		 * 
		 */
		public TogglePropertyItem(Object target, String name, String fieldname,
				boolean refresh)
		{
			this(target, name, fieldname, refresh, null);
		}

		/**
		 * 
		 */
		public TogglePropertyItem(final Object target, String name,
				final String fieldname, final boolean refresh,
				ActionListener listener)
		{
			super(name);

			// Since action listeners are processed last to first we add the given
			// listener here which means it will be processed after the one below
			if (listener != null)
			{
				addActionListener(listener);
			}

			addActionListener(new ActionListener()
			{
				/**
				 * 
				 */
				public void actionPerformed(ActionEvent e)
				{
					execute(target, fieldname, refresh);
				}
			});

			PropertyChangeListener propertyChangeListener = new PropertyChangeListener()
			{

				/*
				 * (non-Javadoc)
				 * @see java.beans.PropertyChangeListener#propertyChange(java.beans.PropertyChangeEvent)
				 */
				public void propertyChange(PropertyChangeEvent evt)
				{
					if (evt.getPropertyName().equalsIgnoreCase(fieldname))
					{
						update(target, fieldname);
					}
				}
			};

			if (target instanceof mxGraphComponent)
			{
				((mxGraphComponent) target)
						.addPropertyChangeListener(propertyChangeListener);
			}
			else if (target instanceof mxGraph)
			{
				((mxGraph) target)
						.addPropertyChangeListener(propertyChangeListener);
			}

			update(target, fieldname);
		}

		/**
		 * 
		 */
		public void update(Object target, String fieldname)
		{
			if (target != null && fieldname != null)
			{
				try
				{
					Method getter = target.getClass().getMethod(
							"is" + fieldname);

					if (getter != null)
					{
						Object current = getter.invoke(target);

						if (current instanceof Boolean)
						{
							setSelected(((Boolean) current).booleanValue());
						}
					}
				}
				catch (Exception e)
				{
					// ignore
				}
			}
		}

		/**
		 * 
		 */
		public void execute(Object target, String fieldname, boolean refresh)
		{
			if (target != null && fieldname != null)
			{
				try
				{
					Method getter = target.getClass().getMethod(
							"is" + fieldname);
					Method setter = target.getClass().getMethod(
							"set" + fieldname, boolean.class);

					Object current = getter.invoke(target);

					if (current instanceof Boolean)
					{
						boolean value = !((Boolean) current).booleanValue();
						setter.invoke(target, value);
						setSelected(value);
					}

					if (refresh)
					{
						mxGraph graph = null;

						if (target instanceof mxGraph)
						{
							graph = (mxGraph) target;
						}
						else if (target instanceof mxGraphComponent)
						{
							graph = ((mxGraphComponent) target).getGraph();
						}

						graph.refresh();
					}
				}
				catch (Exception e)
				{
					// ignore
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class HistoryAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected boolean undo;

		/**
		 * 
		 */
		public HistoryAction(boolean undo)
		{
			this.undo = undo;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			GraphEditor editor = getEditor(e);

			if (editor != null)
			{
				if (undo)
				{
					editor.getUndoManager().undo();
				}
				else
				{
					editor.getUndoManager().redo();
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class FontStyleAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected boolean bold;

		/**
		 * 
		 */
		public FontStyleAction(boolean bold)
		{
			this.bold = bold;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				Component editorComponent = null;

				if (noCellsError(e, "Font style", graphComponent.getGraph())) {
					return;
				}
				if (graphComponent.getCellEditor() instanceof mxCellEditor)
				{
					editorComponent = ((mxCellEditor) graphComponent
							.getCellEditor()).getEditor();
				}

				if (editorComponent instanceof JEditorPane)
				{
					JEditorPane editorPane = (JEditorPane) editorComponent;
					int start = editorPane.getSelectionStart();
					int ende = editorPane.getSelectionEnd();
					String text = editorPane.getSelectedText();

					if (text == null)
					{
						text = "";
					}

					try
					{
						HTMLEditorKit editorKit = new HTMLEditorKit();
						HTMLDocument document = (HTMLDocument) editorPane
								.getDocument();
						document.remove(start, (ende - start));
						editorKit.insertHTML(document, start, ((bold) ? "<b>"
								: "<i>") + text + ((bold) ? "</b>" : "</i>"),
								0, 0, (bold) ? HTML.Tag.B : HTML.Tag.I);
					}
					catch (Exception ex)
					{
						ex.printStackTrace();
					}

					editorPane.requestFocus();
					editorPane.select(start, ende);
				}
				else
				{
					mxIGraphModel model = graphComponent.getGraph().getModel();
					model.beginUpdate();
					try
					{
						graphComponent.stopEditing(false);
						graphComponent.getGraph().toggleCellStyleFlags(
								mxConstants.STYLE_FONTSTYLE,
								(bold) ? mxConstants.FONT_BOLD
										: mxConstants.FONT_ITALIC);
					}
					finally
					{
						model.endUpdate();
					}
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class WarningAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				Object[] cells = graphComponent.getGraph().getSelectionCells();

				if (cells != null && cells.length > 0)
				{
					String warning = JOptionPane.showInputDialog(mxResources
							.get("enterWarningMessage"));

					for (int i = 0; i < cells.length; i++)
					{
						graphComponent.setCellWarning(cells[i], warning);
					}
				}
				else
				{
					JOptionPane.showMessageDialog(graphComponent,
							mxResources.get("noCellSelected"));
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class NewAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			GraphEditor editor = getEditor(e);

			if (editor != null)
			{
				if (!editor.isModified()
						|| JOptionPane.showConfirmDialog(editor,
								mxResources.get("loseChanges")) == JOptionPane.YES_OPTION)
				{
					mxGraph graph = editor.getGraphComponent().getGraph();

					// Check modified flag and display save dialog
					mxCell root = new mxCell();
					root.insert(new mxCell());
					graph.getModel().setRoot(root);

					editor.setModified(false);
					editor.setCurrentFile(null);
					editor.getGraphComponent().zoomAndCenter();
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class OpenAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String lastDir;

		/**
		 * 
		 */
		protected void resetEditor(GraphEditor editor)
		{
			editor.setModified(false);
			editor.getUndoManager().clear();
			editor.getGraphComponent().zoomAndCenter();
		}

		/**
		 * Reads XML+PNG format.
		 */
		protected void openXmlPng(GraphEditor editor, File file)
				throws IOException
		{
			Map<String, String> text = mxPngTextDecoder
					.decodeCompressedText(new FileInputStream(file));

			if (text != null)
			{
				String value = text.get("mxGraphModel");

				if (value != null)
				{
					Document document = mxXmlUtils.parseXml(URLDecoder.decode(
							value, "UTF-8"));
					mxCodec codec = new mxCodec(document);
					codec.decode(document.getDocumentElement(), editor
							.getGraphComponent().getGraph().getModel());
					editor.setCurrentFile(file);
					resetEditor(editor);

					return;
				}
			}

			JOptionPane.showMessageDialog(editor,
					mxResources.get("imageContainsNoDiagramData"));
		}

		/**
		 * @throws IOException
		 *
		 */
		protected void openGD(GraphEditor editor, File file,
				String gdText)
		{
			mxGraph graph = editor.getGraphComponent().getGraph();

			// Replaces file extension with .mxe
			String filename = file.getName();
			filename = filename.substring(0, filename.length() - 4) + ".mxe";

			if (new File(filename).exists()
					&& JOptionPane.showConfirmDialog(editor,
							mxResources.get("overwriteExistingFile")) != JOptionPane.YES_OPTION)
			{
				return;
			}

			((mxGraphModel) graph.getModel()).clear();
			mxGdCodec.decode(gdText, graph);
			editor.getGraphComponent().zoomAndCenter();
			editor.setCurrentFile(new File(lastDir + "/" + filename));
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			GraphEditor editor = getEditor(e);

			if (editor != null)
			{
				if (!editor.isModified()
						|| JOptionPane.showConfirmDialog(editor,
								mxResources.get("loseChanges")) == JOptionPane.YES_OPTION)
				{
					mxGraph graph = editor.getGraphComponent().getGraph();

					if (graph != null)
					{
						String wd = (lastDir != null) ? lastDir : System
								.getProperty("user.dir");

						JFileChooser fc = new JFileChooser(wd);

						// Adds file filter for supported file format
						DefaultFileFilter defaultFilter = new DefaultFileFilter(
								".mxe", mxResources.get("allSupportedFormats")
										+ " (.mxe, .png, .vdx)")
						{

							public boolean accept(File file)
							{
								String lcase = file.getName().toLowerCase();

								return super.accept(file)
										|| lcase.endsWith(".png")
										|| lcase.endsWith(".vdx");
							}
						};
						fc.addChoosableFileFilter(defaultFilter);

						fc.addChoosableFileFilter(new DefaultFileFilter(".mxe",
								"mxGraph Editor " + mxResources.get("file")
										+ " (.mxe)"));
						fc.addChoosableFileFilter(new DefaultFileFilter(".png",
								"PNG+XML  " + mxResources.get("file")
										+ " (.png)"));

						// Adds file filter for VDX import
						fc.addChoosableFileFilter(new DefaultFileFilter(".vdx",
								"XML Drawing  " + mxResources.get("file")
										+ " (.vdx)"));

						// Adds file filter for GD import
						fc.addChoosableFileFilter(new DefaultFileFilter(".txt",
								"Graph Drawing  " + mxResources.get("file")
										+ " (.txt)"));

						fc.setFileFilter(defaultFilter);

						int rc = fc.showDialog(null,
								mxResources.get("openFile"));

						if (rc == JFileChooser.APPROVE_OPTION)
						{
							lastDir = fc.getSelectedFile().getParent();

							try
							{
								if (fc.getSelectedFile().getAbsolutePath()
										.toLowerCase().endsWith(".png"))
								{
									openXmlPng(editor, fc.getSelectedFile());
								}
								else if (fc.getSelectedFile().getAbsolutePath()
										.toLowerCase().endsWith(".txt"))
								{
									openGD(editor, fc.getSelectedFile(),
											mxUtils.readFile(fc
													.getSelectedFile()
													.getAbsolutePath()));
								}
								else
								{
									Document document = mxXmlUtils
											.parseXml(mxUtils.readFile(fc
													.getSelectedFile()
													.getAbsolutePath()));

									mxCodec codec = new mxCodec(document);
									codec.decode(
											document.getDocumentElement(),
											graph.getModel());
									editor.setCurrentFile(fc
											.getSelectedFile());

									resetEditor(editor);
								}
							}
							catch (IOException ex)
							{
								ex.printStackTrace();
								JOptionPane.showMessageDialog(
										editor.getGraphComponent(),
										ex.toString(),
										mxResources.get("error"),
										JOptionPane.ERROR_MESSAGE);
							}
						}
					}
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ToggleAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String key;

		/**
		 * 
		 */
		protected boolean defaultValue;

		/**
		 * 
		 * @param key
		 */
		public ToggleAction(String key)
		{
			this(key, false);
		}

		/**
		 * 
		 * @param key
		 */
		public ToggleAction(String key, boolean defaultValue)
		{
			this.key = key;
			this.defaultValue = defaultValue;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			mxGraph graph = mxGraphActions.getGraph(e);

			if (graph != null)
			{
				graph.toggleCellStyles(key, defaultValue);
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class SetLabelPositionAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String labelPosition, alignment;

		/**
		 * 
		 * @param labelPosition
		 * @param alignment
		 */
		public SetLabelPositionAction(String labelPosition, String alignment)
		{
			this.labelPosition = labelPosition;
			this.alignment = alignment;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			mxGraph graph = mxGraphActions.getGraph(e);

			if (graph != null && !graph.isSelectionEmpty())
			{
				graph.getModel().beginUpdate();
				try
				{
					// Checks the orientation of the alignment to use the correct constants
					if (labelPosition.equals(mxConstants.ALIGN_LEFT)
							|| labelPosition.equals(mxConstants.ALIGN_CENTER)
							|| labelPosition.equals(mxConstants.ALIGN_RIGHT))
					{
						graph.setCellStyles(mxConstants.STYLE_LABEL_POSITION,
								labelPosition);
						graph.setCellStyles(mxConstants.STYLE_ALIGN, alignment);
					}
					else
					{
						graph.setCellStyles(
								mxConstants.STYLE_VERTICAL_LABEL_POSITION,
								labelPosition);
						graph.setCellStyles(mxConstants.STYLE_VERTICAL_ALIGN,
								alignment);
					}
				}
				finally
				{
					graph.getModel().endUpdate();
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class SetStyleAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String value;

		/**
		 * 
		 * @param value
		 */
		public SetStyleAction(String value)
		{
			this.value = value;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			mxGraph graph = mxGraphActions.getGraph(e);

			if (graph != null && !graph.isSelectionEmpty())
			{
				graph.setCellStyle(value);
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class KeyValueAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String key, value;

		/**
		 * 
		 * @param key
		 */
		public KeyValueAction(String key)
		{
			this(key, null);
		}

		/**
		 * 
		 * @param key
		 */
		public KeyValueAction(String key, String value)
		{
			this.key = key;
			this.value = value;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			mxGraph graph = mxGraphActions.getGraph(e);

			if (graph != null && !noCellsError(e, key, graph))
			{
				graph.setCellStyles(key, value);
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class PromptValueAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String key, message;

		/**
		 * 
		 * @param key
		 */
		public PromptValueAction(String key, String message)
		{
			this.key = key;
			this.message = message;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof Component)
			{
				mxGraph graph = mxGraphActions.getGraph(e);

				if (graph != null && !graph.isSelectionEmpty())
				{
					String value = (String) JOptionPane.showInputDialog(
							(Component) e.getSource(),
							mxResources.get("value"), message,
							JOptionPane.PLAIN_MESSAGE, null, null, "");

					if (value != null)
					{
						if (value.equals(mxConstants.NONE))
						{
							value = null;
						}

						graph.setCellStyles(key, value);
					}
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class AlignCellsAction extends AbstractAction
	{
		/**
		 * 
		 */
		protected String align;

		/**
		 * 
		 * @param align
		 */
		public AlignCellsAction(String align)
		{
			this.align = align;
		}

		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			mxGraph graph = mxGraphActions.getGraph(e);

			if (graph != null && !graph.isSelectionEmpty())
			{
				graph.alignCells(align);
			}
		}
	}

	@SuppressWarnings("serial")
	public static class CenterAction extends AbstractAction {

		public void actionPerformed(final ActionEvent e) {
			if (e.getSource() instanceof mxGraphComponent) {
				final mxGraphComponent component = ((mxGraphComponent) e.getSource());
				final mxGraph graph = component.getGraph();
				// https://stackoverflow.com/a/36947526
				final double widthLayout = component.getLayoutAreaSize().getWidth();
				final double heightLayout = component.getLayoutAreaSize().getHeight();
				final double width = graph.getGraphBounds().getWidth();
				final double height = graph.getGraphBounds().getHeight();
				graph.getModel().setGeometry(graph.getDefaultParent(), new mxGeometry((widthLayout - width) / 2,
						(heightLayout - height) / 2, widthLayout, heightLayout));

			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class AutosizeAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			mxGraph graph = mxGraphActions.getGraph(e);

			if (graph != null && !graph.isSelectionEmpty())
			{
				Object[] cells = graph.getSelectionCells();
				mxIGraphModel model = graph.getModel();

				model.beginUpdate();
				try
				{
					for (int i = 0; i < cells.length; i++)
					{
						graph.updateCellSize(cells[i]);
					}
				}
				finally
				{
					model.endUpdate();
				}
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class ColorAction extends AbstractAction {
		/**
		 *
		 */
		protected String name, key;

		/**
		 * @param key
		 */
		public ColorAction(String name, String key) {
			this.name = name;
			this.key = key;
		}

		/**
		 *
		 */
		public void actionPerformed(ActionEvent e) {
			if (e.getSource() instanceof SNTGraphComponent) {
				SNTGraphComponent graphComponent = (SNTGraphComponent) e
						.getSource();
				if (graphComponent.getGraph() instanceof SNTGraphAdapter) {
					@SuppressWarnings("unchecked")
					SNTGraphAdapter<Object, DefaultWeightedEdge> graph
							= (SNTGraphAdapter<Object, DefaultWeightedEdge>) graphComponent.getGraph();

					if (!noCellsError(e, name, graph)) {
						Color newColor = JColorChooser.showDialog(graphComponent,
								name, null);

						if (newColor != null) {
							graph.setCellStyles(key, mxUtils.hexString(newColor));
							if (key.equals(mxConstants.STYLE_FILLCOLOR)) {
								Object[] selectionCells = graph.getSelectionCells();
								for (Object c : selectionCells) {
									mxCell mxc = (mxCell) c;
									graph.getSourceGraph().setVertexColor(
											graph.getCellToVertexMap().get(mxc),
											new ColorRGB(newColor.getRed(), newColor.getGreen(), newColor.getBlue())
									);
								}
							}
						}
					}
				}
			}
		}

	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class BackgroundImageAction extends AbstractAction
	{
		public void actionPerformed(ActionEvent e) {
			if (e.getSource() instanceof mxGraphComponent) {
				final mxGraphComponent graphComponent = (mxGraphComponent) e.getSource();
				String value = (String) JOptionPane.showInputDialog(graphComponent,
						"Image location (absolute path or URL). Clear field to remove loaded image:",
						mxResources.get("backgroundImage"), JOptionPane.PLAIN_MESSAGE, null, null,
						"http://morphonets.org/static/MorphonetsLogo.png");

				if (value == null) {
					return; // user pressed cancel

				} else if (value.trim().isEmpty()) {
					graphComponent.setBackgroundImage(null);
					graphComponent.getGraph().repaint();
					System.out.println("Background image removed");
					return;
				} else if (!(value.startsWith("http") || value.startsWith("www"))) {
					try {
						value = Paths.get(value).toUri().toURL().toString();
					} catch (final MalformedURLException e1) {
						System.out.println("Not a valid path!");
						return;
					}
				}
				final Image background = mxUtils.loadImage(value);
				if (background == null) {
					// Incorrect URLs will result in no image.
					System.out.println("Image could not be loaded. Invalid path?");
				} else {
					graphComponent.setBackgroundImage(new ImageIcon(background));
					System.out.println("Applied background image: " + value);
				}

				// Forces a repaint of the outline
				graphComponent.getGraph().repaint();
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class BackgroundAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				Color newColor = JColorChooser.showDialog(graphComponent,
						mxResources.get("background"), null);

				if (newColor != null)
				{
					graphComponent.getViewport().setOpaque(true);
					graphComponent.getViewport().setBackground(newColor);
				}

				// Forces a repaint of the outline
				graphComponent.getGraph().repaint();
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class PageBackgroundAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				Color newColor = JColorChooser.showDialog(graphComponent,
						mxResources.get("pageBackground"), null);

				if (newColor != null)
				{
					graphComponent.setPageBackgroundColor(newColor);
				}

				// Forces a repaint of the component
				graphComponent.repaint();
			}
		}
	}

	/**
	 *
	 */
	@SuppressWarnings("serial")
	public static class StyleAction extends AbstractAction
	{
		/**
		 * 
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				mxGraph graph = graphComponent.getGraph();
				String initial = graph.getModel().getStyle(
						graph.getSelectionCell());
				String value = (String) JOptionPane.showInputDialog(
						graphComponent, mxResources.get("style"),
						mxResources.get("style"), JOptionPane.PLAIN_MESSAGE,
						null, null, initial);

				if (value != null)
				{
					graph.setCellStyle(value);
				}
			}
		}
	}

	@SuppressWarnings("serial")
	public static class ChangeVertexShapeAction extends AbstractAction
	{
		private final String[] shapes = {mxConstants.SHAPE_RECTANGLE, mxConstants.SHAPE_DOUBLE_RECTANGLE,
				mxConstants.SHAPE_ELLIPSE, mxConstants.SHAPE_DOUBLE_ELLIPSE, mxConstants.SHAPE_TRIANGLE,
				mxConstants.SHAPE_RHOMBUS, mxConstants.SHAPE_HEXAGON, mxConstants.SHAPE_CYLINDER,
				mxConstants.SHAPE_ACTOR, mxConstants.SHAPE_CLOUD, mxConstants.NONE};
		/**
		 *
		 */
		public void actionPerformed(ActionEvent e)
		{
			if (e.getSource() instanceof mxGraphComponent)
			{
				mxGraphComponent graphComponent = (mxGraphComponent) e
						.getSource();
				mxGraph graph = graphComponent.getGraph();
				JList<String> choices = new JList<>(shapes);
				JOptionPane.showMessageDialog(
						graphComponent, new JScrollPane(choices), "Choose new shape", JOptionPane.PLAIN_MESSAGE,
						null);
				String selectedShape = choices.getSelectedValue();
				if (selectedShape != null) {
					graph.getModel().beginUpdate();
					try {
						graph.getStylesheet().getDefaultVertexStyle().put(mxConstants.STYLE_SHAPE, selectedShape);
					} finally {
						graph.getModel().endUpdate();
						graph.refresh();
					}
				}
			}
		}
	}

	@SuppressWarnings("serial")
	public static class ChangeGraphAction extends AbstractAction {

		private final CommandService cmdService;

		public ChangeGraphAction(CommandService cmdService) {
			this.cmdService = cmdService;
		}

		class NewGraphFromCmd extends SwingWorker<AnnotationGraphAdapter, Void> {

			private final List<Tree> trees;
			private final AnnotationGraphComponent graphComponent;

			public NewGraphFromCmd(final AnnotationGraphComponent graphComponent, final List<Tree> trees) {
				this.graphComponent = graphComponent;
				this.trees = trees;
			}

			@Override
			public AnnotationGraphAdapter doInBackground() {
				try {
					final CommandModule cm = cmdService.run(NewGraphOptionsCmd.class, true).get();
					if (cm.isCanceled()) return null;
					final Map<String, Object> inputs = cm.getInputs();
					final String metric = String.valueOf(inputs.get("metric"));
					final double threshold = Double.parseDouble(String.valueOf(inputs.get("threshold")));
					final int depth = Integer.parseInt(String.valueOf(inputs.get("depth")));
					AnnotationGraph newGraph = new AnnotationGraph(trees, metric, threshold, depth);
					return new AnnotationGraphAdapter(newGraph);
				} catch (final InterruptedException | ExecutionException e1) {
					e1.printStackTrace();
				}
				return null;
			}

			@Override
			protected void done() {
				try {
					AnnotationGraphAdapter adapter = get();
					if (adapter == null) return;
					graphComponent.setGraph(adapter);
					adapter.getModel().beginUpdate();
					try {
						new mxCircleLayout(adapter).execute(adapter.getDefaultParent());
						new mxParallelEdgeLayout(adapter).execute(adapter.getDefaultParent());
					} catch (Exception ex) {
						ex.printStackTrace();
					} finally {
						adapter.getModel().endUpdate();
					}
					graphComponent.refresh();
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			if (e.getSource() instanceof AnnotationGraphComponent) {
				AnnotationGraphComponent graphComponent = (AnnotationGraphComponent) e.getSource();
				AnnotationGraphAdapter graph = (AnnotationGraphAdapter) graphComponent.getGraph();
				List<Tree> trees = graph.getAnnotationGraph().getTrees();
				if (trees == null || trees.isEmpty()) {
					System.out.println("Graph not associated with a Tree Collection.");
					return;
				}
				new NewGraphFromCmd(graphComponent, trees).execute();
			} else {
				System.out.println("This action requires AnnotationGraph.");
			}
		}
	}

	public static class ShowInViewer3DAction extends AbstractAction {

		private final CommandService cmdService;

		public ShowInViewer3DAction(CommandService cmdService) {
			this.cmdService = cmdService;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			if (e.getSource() instanceof AnnotationGraphComponent) {
				final AnnotationGraphAdapter adapter;
				adapter = (AnnotationGraphAdapter) ((AnnotationGraphComponent) e.getSource()).getGraph();
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("adapter", adapter);
				cmdService.run(GraphRecViewerCmd.class, true, inputs);
			}
		}

	}

	public static class ScaleEdgeWeightsAction extends AbstractAction {

		@Override
		public void actionPerformed(ActionEvent e) {
			if (e.getSource() instanceof SNTGraphComponent) {
				String result = JOptionPane.showInputDialog(null, "Scale factor");
				if (result == null) {
					return;
				}
				double scale = Double.parseDouble(result);
				if (Double.isNaN(scale)) {
					GuiUtils.errorPrompt("Invalid scale factor");
					return;
				}
				SNTGraphComponent graphComponent = (SNTGraphComponent) e.getSource();
				@SuppressWarnings("unchecked")
				SNTGraphAdapter<Object, DefaultWeightedEdge> graph
						= (SNTGraphAdapter<Object, DefaultWeightedEdge>) graphComponent.getGraph();
				SNTGraph<Object, DefaultWeightedEdge> sntGraph = graph.getSourceGraph();
				for (DefaultWeightedEdge edge : graph.getEdgeToCellMap().keySet()) {
					double newWeight = sntGraph.getEdgeWeight(edge) * scale;
					sntGraph.setEdgeWeight(edge, newWeight);
				}
				graph.refresh();
			}
		}
	}

	private static boolean noCellsError(final ActionEvent e, final String cmdName, final mxGraph graph) {
		final boolean noCells = graph.isSelectionEmpty();
		if (noCells) {
			final GraphEditor editor = getEditor(e);
			if (editor != null)
				editor.status("No selection exists!", true);
			else
				System.out.println("No selection exists");
		}
		return noCells;
	}

}
