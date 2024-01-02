/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.filter;

import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.Computers;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.type.NativeType;

/**
 * This class is taken directly from
 * <a href=https://github.com/saalfeldlab/hot-knife/blob/master/src/main/java/org/janelia/saalfeldlab/hotknife/ops/UnaryComputerOpCellLoader.java>https://github.com/saalfeldlab/hot-knife</a>
 */
public class UnaryComputerOpCellLoader<T, S extends NativeType<S>, R extends RandomAccessibleInterval<T>>
        implements CellLoader<S>
{

    private final R source;

    private final UnaryComputerOp<R, RandomAccessibleInterval<S>> op;

    public UnaryComputerOpCellLoader(final R source, final UnaryComputerOp<R, RandomAccessibleInterval<S>> op) {
        this.source = source;
        this.op = op;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public UnaryComputerOpCellLoader(
            final R source,
            final OpService opService,
            final Class<? extends Op> opClass,
            final Object[] args)
    {

        this.source = source;

        op = Computers.unary(
                opService,
                opClass,
                (RandomAccessibleInterval<S>) new SingleCellArrayImg(source.numDimensions()),
                source,
                args);
    }

    @Override
    public void load(final SingleCellArrayImg<S, ?> cell) throws Exception {
        op.compute(source, cell);
    }
}
