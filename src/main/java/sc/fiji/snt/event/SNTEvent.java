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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.event;

public class SNTEvent {

	/** Event type indicating no specific event */
	public final static int NO_EVENT = 0;
	/** Event type indicating a quit/exit event */
	public final static int QUIT = 1;
	/** Event type indicating data should be sent to TrakEM2 */
	public final static int SEND_TO_TRAKEM2 = 2;

	protected int type;

	/**
	 * Constructs a new SNT event with the specified type.
	 *
	 * @param type the event type (e.g., {@link #NO_EVENT}, {@link #QUIT}, {@link #SEND_TO_TRAKEM2})
	 */
	public SNTEvent(final int type) {
		this.type = type;
	}

	/**
	 * Gets the type of this SNT event.
	 *
	 * @return the event type
	 */
	public int getType() {
		return type;
	}

}
