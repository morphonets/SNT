/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxGraphLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;
import com.mxgraph.model.mxCell;
import com.mxgraph.shape.mxITextShape;
import com.mxgraph.swing.view.mxInteractiveCanvas;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxCellState;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.Context;

import java.awt.*;
import java.util.Map;

public class SNTPseudographComponent extends SNTGraphComponent{

    private static final long serialVersionUID = 1L;
    private mxGraphLayout layout;

    public SNTPseudographComponent(SNTPseudographAdapter<?, ? extends DefaultWeightedEdge> adapter, Context context) {
        super(adapter, context);
        layout = new mxCircleLayout(adapter);
        layout.execute(adapter.getDefaultParent());
        new mxParallelEdgeLayout(adapter).execute(adapter.getDefaultParent());
    }

    @Override
    //https://stackoverflow.com/a/36182812
    public mxInteractiveCanvas createCanvas()
    {
        return new mxInteractiveCanvas() {
            @Override
            public Object drawLabel(String text, mxCellState state, boolean html) {
                Map<String, Object> style = state.getStyle();
                mxITextShape shape = getTextShape(style, html);
                if (g != null && shape != null && drawLabels && text != null && text.length() > 0) {
                    // Creates a temporary graphics instance for drawing this shape
                    float opacity = mxUtils.getFloat(style, mxConstants.STYLE_TEXT_OPACITY, 100);
                    Graphics2D previousGraphics = g;
                    if (((mxCell) state.getCell()).isVertex()) {
                        g = createTemporaryGraphics(style, opacity, null);
                    } else {
                        // quadrant will be true if the edge is NE or SW
                        Object target = ((mxCell) state.getCell()).getTarget();
                        Object source = ((mxCell) state.getCell()).getSource();
                        boolean quadrant = false;
                        if (((mxCell) target).getGeometry().getCenterX() < ((mxCell) source).getGeometry()
                                .getCenterX()) {
                            if (((mxCell) target).getGeometry().getCenterY() > ((mxCell) source).getGeometry()
                                    .getCenterY()) {
                                quadrant = true;
                            }
                        }
                        if (((mxCell) target).getGeometry().getCenterX() > ((mxCell) source).getGeometry()
                                .getCenterX()) {
                            if (((mxCell) target).getGeometry().getCenterY() < ((mxCell) source).getGeometry()
                                    .getCenterY()) {
                                quadrant = true;
                            }
                        }
                        g = createTemporaryGraphics(style, opacity, state, state.getLabelBounds(), quadrant);
                    }

                    // Draws the label background and border
                    Color bg = mxUtils.getColor(style, mxConstants.STYLE_LABEL_BACKGROUNDCOLOR);
                    Color border = mxUtils.getColor(style, mxConstants.STYLE_LABEL_BORDERCOLOR);
                    paintRectangle(state.getLabelBounds().getRectangle(), bg, border);

                    // Paints the label and restores the graphics object
                    shape.paintShape(this, text, state, style);
                    g.dispose();
                    g = previousGraphics;
                }

                return shape;
            }

            public Graphics2D createTemporaryGraphics(Map<String, Object> style, float opacity, mxRectangle bounds,
                                                      mxRectangle labelbounds, boolean quad) {
                Graphics2D temporaryGraphics = (Graphics2D) g.create();

                // Applies the default translate
                try {
                    temporaryGraphics.translate(translate.getX(), translate.getY());
                } catch (java.lang.NoSuchFieldError ignored) {
                    // do nothing!?
                }

                // setup the rotation for the label
                double angle = java.lang.Math.atan(bounds.getHeight() / bounds.getWidth());
                double rotation = Math.toDegrees(angle);
                if (quad) {
                    rotation = -rotation;
                }
                // get the translation needed
                double diff = labelbounds.getHeight() * (1 - Math.cos(angle));
                double plusy = diff * Math.sin(angle);
                double plusx = diff * Math.cos(angle);
                // Applies the rotation and translation on the graphics object
                if (bounds != null) {
                    if (rotation != 0) {
                        temporaryGraphics.rotate(Math.toRadians(rotation), labelbounds.getCenterX(),
                                labelbounds.getCenterY());
                        temporaryGraphics.translate(-plusx, plusy);
                    }
                }

                // Applies the opacity to the graphics object
                if (opacity != 100) {
                    temporaryGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity / 100));
                }

                return temporaryGraphics;
            }
        };
    }

}
