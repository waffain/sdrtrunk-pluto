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
import java.util.Map;

/**
 * Open libiio context.  Its devices, channels and buffers are only valid until it's closed.
 */
public final class IioContext implements AutoCloseable
{
    private final LibIio mLib;
    private final String mUri;
    private MemorySegment mContext;

    IioContext(LibIio lib, MemorySegment context, String uri)
    {
        mLib = lib;
        mContext = context;
        mUri = uri;
    }

    /**
     * URI that the context was opened with.
     */
    public String uri()
    {
        return mUri;
    }

    /**
     * Backend name, such as usb, network or local.
     */
    public String name()
    {
        return mLib.contextName(context());
    }

    /**
     * Description, such as the remote kernel version and board model.
     */
    public String description()
    {
        return mLib.contextDescription(context());
    }

    /**
     * Version of the remote iiod server, or of the library for a local context.
     * @throws IioException if the version can't be read
     */
    public String version() throws IioException
    {
        return mLib.contextVersion(context());
    }

    /**
     * Context attributes, such as hw_model, hw_serial and fw_version on a Pluto.
     */
    public Map<String,String> attributes()
    {
        return mLib.contextAttributes(context());
    }

    /**
     * Sets the timeout for attribute and buffer operations.
     * @param milliseconds timeout, or 0 to wait forever
     * @throws IioException if the timeout can't be set
     */
    public void setTimeout(int milliseconds) throws IioException
    {
        mLib.contextSetTimeout(context(), milliseconds);
    }

    /**
     * Finds a device by name or ID.
     * @param name such as ad9361-phy
     * @return device, or null if the context doesn't have it
     */
    public IioDevice findDevice(String name)
    {
        MemorySegment device = mLib.contextFindDevice(context(), name);
        return device.equals(MemorySegment.NULL) ? null : new IioDevice(mLib, device, name);
    }

    private MemorySegment context()
    {
        if(mContext == null)
        {
            throw new IllegalStateException("libiio context [" + mUri + "] is closed");
        }

        return mContext;
    }

    @Override
    public void close()
    {
        if(mContext != null)
        {
            mLib.contextDestroy(mContext);
            mContext = null;
        }
    }
}
