/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import bdv.tools.boundingbox.TransformedBoxEditor;
import bdv.util.BdvHandle;
import bdv.viewer.AbstractViewerPanel;
import bdv.viewer.ConverterSetups;

import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

/**
 * {@link RegionSelector} for {@link Bdv}: a draggable box overlaid on the
 * viewer, see {@link BoxRegionSelector}
 *
 * @author Tiago Ferreira
 */
public class BdvRegionSelector extends BoxRegionSelector {

	private final Bdv bdv;

	BdvRegionSelector(final Bdv bdv) {
		super(bdv);
		this.bdv = bdv;
	}

	@Override
	AbstractViewerPanel viewerPanel() {
		return bdv.getViewerPanel();
	}

	@Override
	ConverterSetups converterSetups() {
		final BdvHandle handle = bdv.getBdvHandle();
		return (handle == null) ? null : handle.getConverterSetups();
	}

	@Override
	TriggerBehaviourBindings triggerBindings() {
		final BdvHandle handle = bdv.getBdvHandle();
		return (handle == null) ? null : handle.getTriggerbindings();
	}

	@Override
	void configureEditor(final TransformedBoxEditor editor) {
		editor.setPerspective(1, 1000); // same values as BDV's own selection dialog
	}

}
