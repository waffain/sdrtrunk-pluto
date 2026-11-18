/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.source.tuner.pluto.iio;

import java.lang.foreign.MemorySegment;

/**
 * libiio sample buffer.  Samples of the enabled channels are interleaved, one sample of each channel per step.
 *
 * The buffer is used from one thread, except for cancel(), which can be called from another thread to stop a refill
 * that is waiting for samples.
 */
public final class IioBuffer implements AutoCloseable
{
    private final LibIio mLib;
    private MemorySegment mBuffer;

    IioBuffer(LibIio lib, MemorySegment buffer)
    {
        mLib = lib;
        mBuffer = buffer;
    }

    /**
     * Fetches the next block of samples, waiting until they're available.
     * @return samples, valid until the next refill or until the buffer is closed
     * @throws IioException if the refill fails, times out or is cancelled
     */
    public MemorySegment refill() throws IioException
    {
        long result = mLib.bufferRefill(buffer());

        if(result < 0)
        {
            throw new IioException("libiio buffer refill failed", (int)-result);
        }

        MemorySegment start = mLib.bufferStart(mBuffer);
        long length = mLib.bufferEnd(mBuffer).address() - start.address();
        return start.reinterpret(Math.min(length, result));
    }

    /**
     * Bytes from one sample of each enabled channel to the next.
     */
    public long step()
    {
        return mLib.bufferStep(buffer());
    }

    /**
     * Makes a refill that is waiting, and any later refill, fail.  Can be called from any thread.
     */
    public void cancel()
    {
        MemorySegment buffer = mBuffer;

        if(buffer != null)
        {
            mLib.bufferCancel(buffer);
        }
    }

    private MemorySegment buffer()
    {
        if(mBuffer == null)
        {
            throw new IllegalStateException("libiio buffer is closed");
        }

        return mBuffer;
    }

    @Override
    public void close()
    {
        if(mBuffer != null)
        {
            MemorySegment buffer = mBuffer;
            mBuffer = null;
            mLib.bufferDestroy(buffer);
        }
    }
}
