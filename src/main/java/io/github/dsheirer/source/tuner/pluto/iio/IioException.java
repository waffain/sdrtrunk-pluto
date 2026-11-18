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

/**
 * Error from a libiio call.
 */
public class IioException extends Exception
{
    private final int mCode;

    /**
     * Constructs an instance
     * @param message describing the failed call
     * @param code positive errno value, or 0 when there isn't one
     */
    public IioException(String message, int code)
    {
        super(code != 0 ? message + " - " + LibIio.strerror(code) : message);
        mCode = code;
    }

    /**
     * Positive errno value of the failure, or 0 when there isn't one.
     */
    public int code()
    {
        return mCode;
    }
}
