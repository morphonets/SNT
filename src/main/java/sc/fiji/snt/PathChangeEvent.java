/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

package sc.fiji.snt;

import java.util.EventObject;

/**
 * @author Cameron Arshadi
 */
class PathChangeEvent extends EventObject {

	private static final long serialVersionUID = 4237091433859122738L;

	enum EventType {NAME_CHANGED, ID_CHANGED}

    private final EventType eventType;
    private final Object[] args;

    /**
     * Constructs a prototypical Event.
     *
     * @param source The object on which the Event initially occurred.
     * @throws IllegalArgumentException if source is null.
     */
    PathChangeEvent(final Object source, final EventType eventType, Object... args) {
        super(source);
        this.eventType = eventType;
        this.args = args;
    }

    EventType getEventType() {
        return eventType;
    }

    Object[] getArgs() {
        return args;
    }

}
