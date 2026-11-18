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
 * libiio device, such as ad9361-phy (the radio's controls) or cf-ad9361-lpc (its receive sample stream).
 */
public final class IioDevice
{
    private final LibIio mLib;
    private final MemorySegment mDevice;
    private final String mName;

    IioDevice(LibIio lib, MemorySegment device, String name)
    {
        mLib = lib;
        mDevice = device;
        mName = name;
    }

    /**
     * Name that the device was found by.
     */
    public String name()
    {
        return mName;
    }

    /**
     * Finds a channel by name or ID.
     * @param name such as voltage0 or altvoltage0
     * @param output true for an output channel, false for an input channel
     * @return channel, or null if the device doesn't have it
     */
    public IioChannel findChannel(String name, boolean output)
    {
        MemorySegment channel = mLib.deviceFindChannel(mDevice, name, output);
        return channel.equals(MemorySegment.NULL) ? null : new IioChannel(mLib, channel, name);
    }

    /**
     * Sets the number of buffers that the kernel (or iiod) queues for a buffer created on this device.
     * @param count of buffers
     * @throws IioException if the count can't be set
     */
    public void setKernelBuffersCount(int count) throws IioException
    {
        mLib.deviceSetKernelBuffersCount(mDevice, count);
    }

    /**
     * Creates a non-cyclic buffer for the enabled channels.  Enable the channels first.
     * @param samples per buffer
     * @return buffer, which must be closed before the context
     * @throws IioException if the buffer can't be created
     */
    public IioBuffer createBuffer(long samples) throws IioException
    {
        return new IioBuffer(mLib, mLib.deviceCreateBuffer(mDevice, samples));
    }
}
