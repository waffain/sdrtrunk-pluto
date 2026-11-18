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

import java.util.ArrayList;
import java.util.List;

/**
 * PlutoSDR sample rates offered to the user.  The AD936x supports any rate from about 2.08 MHz to 61.44 MHz without
 * loading a custom FIR filter, but the Pluto streams 4 bytes per sample over USB 2.0 (or its USB network link), which
 * sustains about 4 to 6 MHz.  These are rates that divide evenly into polyphase channelizer channels.
 */
public enum PlutoSdrSampleRate
{
    RATE_2_5(2_500_000, "2.50 MHz"),
    RATE_3_0(3_000_000, "3.00 MHz"),
    RATE_4_0(4_000_000, "4.00 MHz"),
    RATE_5_0(5_000_000, "5.00 MHz"),
    RATE_6_0(6_000_000, "6.00 MHz"),
    RATE_8_0(8_000_000, "8.00 MHz"),
    RATE_10_0(10_000_000, "10.00 MHz");

    public static final PlutoSdrSampleRate DEFAULT = RATE_2_5;
    private final int mRate;
    private final String mLabel;

    PlutoSdrSampleRate(int rate, String label)
    {
        mRate = rate;
        mLabel = label;
    }

    /**
     * Sample rate in Hertz
     */
    public int getRate()
    {
        return mRate;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }

    /**
     * Sample rates within the range that the device reports.
     * @param minimumRate in Hertz
     * @param maximumRate in Hertz
     * @return rates, lowest to highest
     */
    public static List<PlutoSdrSampleRate> getRates(long minimumRate, long maximumRate)
    {
        List<PlutoSdrSampleRate> rates = new ArrayList<>();

        for(PlutoSdrSampleRate rate : values())
        {
            if(rate.getRate() >= minimumRate && rate.getRate() <= maximumRate)
            {
                rates.add(rate);
            }
        }

        return rates;
    }

    /**
     * Finds the highest rate from the list that doesn't exceed the requested rate.
     * @param rates to search, lowest to highest
     * @param requested rate in Hertz
     * @return closest rate, or the lowest rate when the requested rate is below all of them.
     */
    public static PlutoSdrSampleRate getClosest(List<PlutoSdrSampleRate> rates, long requested)
    {
        PlutoSdrSampleRate closest = rates.getFirst();

        for(PlutoSdrSampleRate rate : rates)
        {
            if(rate.getRate() <= requested)
            {
                closest = rate;
            }
        }

        return closest;
    }
}
