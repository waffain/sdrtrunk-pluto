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

package io.github.dsheirer.source.tuner.pluto;

/**
 * AD936x receive gain control modes, as named by the ad9361-phy gain_control_mode attribute.
 */
public enum PlutoSdrGainMode
{
    MANUAL("manual", "Manual"),
    SLOW_ATTACK("slow_attack", "AGC - Slow Attack"),
    FAST_ATTACK("fast_attack", "AGC - Fast Attack"),
    HYBRID("hybrid", "AGC - Hybrid");

    private final String mValue;
    private final String mLabel;

    PlutoSdrGainMode(String value, String label)
    {
        mValue = value;
        mLabel = label;
    }

    /**
     * Value of the gain_control_mode attribute
     */
    public String getValue()
    {
        return mValue;
    }

    /**
     * Indicates if the gain is set manually, rather than by the automatic gain control.
     */
    public boolean isManual()
    {
        return this == MANUAL;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }

    /**
     * Finds the mode for a gain_control_mode attribute value.
     * @param value of the attribute
     * @return mode, or MANUAL when the value isn't recognized
     */
    public static PlutoSdrGainMode fromValue(String value)
    {
        for(PlutoSdrGainMode mode : values())
        {
            if(mode.getValue().equalsIgnoreCase(value))
            {
                return mode;
            }
        }

        return MANUAL;
    }
}
