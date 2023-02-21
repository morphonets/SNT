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

package sc.fiji.snt.tracing;

class PathResult {

	protected float[] pathPoints;
	protected float[] numberOfPoints;
	protected String errorMessage;
	protected boolean succeeded;

	public float[] getPath() {
		return pathPoints;
	}

	public int getNumberOfPoints() {
		return pathPoints.length / 4;
	}

	public void setPath(final float[] pathPoints) {
		this.pathPoints = pathPoints;
	}

	public void setErrorMessage(final String message) {
		this.errorMessage = message;
	}

	public String getErrorMessage() {
		return this.errorMessage;
	}

	public void setSuccess(final boolean succeeded) {
		this.succeeded = succeeded;
	}

	public boolean getSuccess() {
		return this.succeeded;
	}

}
