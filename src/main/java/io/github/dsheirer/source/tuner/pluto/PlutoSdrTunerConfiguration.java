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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;

/**
 * PlutoSDR tuner configuration.  A Pluto that is discovered on USB has no URI.  A Pluto that the user adds by network
 * address has the libiio URI, such as ip:192.168.2.1, and its unique ID is the ID of its discovered tuner.
 */
public class PlutoSdrTunerConfiguration extends TunerConfiguration
{
    private int mSampleRate = PlutoSdrSampleRate.DEFAULT.getRate();
    private int mGain = PlutoSdrTunerController.GAIN_DEFAULT;
    private PlutoSdrGainMode mGainMode = PlutoSdrTunerController.GAIN_MODE_DEFAULT;
    private String mUri;

    /**
     * Default constructor for Jackson
     */
    public PlutoSdrTunerConfiguration()
    {
        super(PlutoSdrTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ, PlutoSdrTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ);
    }

    /**
     * Constructs an instance
     * @param uniqueID for the tuner
     */
    public PlutoSdrTunerConfiguration(String uniqueID)
    {
        super(uniqueID);
    }

    /**
     * Creates a configuration for a Pluto at a network address.
     * @param uri libiio URI, such as ip:192.168.2.1
     * @param frequency to tune initially, in Hertz
     */
    public static PlutoSdrTunerConfiguration create(String uri, long frequency)
    {
        PlutoSdrTunerConfiguration config = new PlutoSdrTunerConfiguration(DiscoveredPlutoSdrTuner.getId(uri));
        config.setMinimumFrequency(PlutoSdrTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ);
        config.setMaximumFrequency(PlutoSdrTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ);
        config.setUri(uri);
        config.setFrequency(frequency);
        return config;
    }

    @Override
    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public TunerType getTunerType()
    {
        return TunerType.PLUTOSDR;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "sample_rate")
    public int getSampleRate()
    {
        return mSampleRate;
    }

    public void setSampleRate(int sampleRate)
    {
        mSampleRate = sampleRate;
    }

    /**
     * Manual receive gain in dB.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "gain")
    public int getGain()
    {
        return mGain;
    }

    public void setGain(int gain)
    {
        mGain = gain;
    }

    /**
     * Manual gain or one of the automatic gain control modes.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "gain_mode")
    public PlutoSdrGainMode getGainMode()
    {
        return mGainMode;
    }

    public void setGainMode(PlutoSdrGainMode gainMode)
    {
        mGainMode = gainMode != null ? gainMode : PlutoSdrTunerController.GAIN_MODE_DEFAULT;
    }

    /**
     * libiio URI of a Pluto that the user added by network address, or null for a Pluto discovered on USB.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "uri")
    public String getUri()
    {
        return mUri;
    }

    public void setUri(String uri)
    {
        mUri = uri;
    }

    /**
     * Indicates if this is the configuration of a Pluto that the user added by network address.
     */
    @JsonIgnore
    public boolean hasUri()
    {
        return mUri != null && !mUri.isBlank();
    }
}
