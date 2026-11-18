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
 * libiio channel: a set of attributes, such as the receive gain, and optionally a stream of samples.
 */
public final class IioChannel
{
    private final LibIio mLib;
    private final MemorySegment mChannel;
    private final String mName;

    IioChannel(LibIio lib, MemorySegment channel, String name)
    {
        mLib = lib;
        mChannel = channel;
        mName = name;
    }

    /**
     * Name that the channel was found by.
     */
    public String name()
    {
        return mName;
    }

    /**
     * Reads an attribute as text.
     * @param attribute name
     * @return value, without leading or trailing white space
     * @throws IioException if the attribute can't be read
     */
    public String read(String attribute) throws IioException
    {
        return mLib.channelAttrRead(mChannel, attribute);
    }

    /**
     * Reads an integer attribute.
     * @param attribute name
     * @return value
     * @throws IioException if the attribute can't be read or isn't an integer
     */
    public long readLong(String attribute) throws IioException
    {
        return mLib.channelAttrReadLong(mChannel, attribute);
    }

    /**
     * Writes an attribute as text.
     * @param attribute name
     * @param value to write
     * @throws IioException if the attribute can't be written, such as when the value is out of range
     */
    public void write(String attribute, String value) throws IioException
    {
        mLib.channelAttrWrite(mChannel, attribute, value);
    }

    /**
     * Writes an integer attribute.
     * @param attribute name
     * @param value to write
     * @throws IioException if the attribute can't be written, such as when the value is out of range
     */
    public void writeLong(String attribute, long value) throws IioException
    {
        mLib.channelAttrWriteLong(mChannel, attribute, value);
    }

    /**
     * Enables or disables the channel's samples in buffers created afterward.
     */
    public void setEnabled(boolean enabled)
    {
        mLib.channelEnable(mChannel, enabled);
    }
}
