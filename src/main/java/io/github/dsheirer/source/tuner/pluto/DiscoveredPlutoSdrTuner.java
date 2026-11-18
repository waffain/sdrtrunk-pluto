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

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlutoSDR that the user added by its libiio network URI, such as ip:192.168.2.1, rather than one discovered on USB.
 * Like the other network tuners, it's disabled when the application starts, since the address may not be reachable.
 */
public class DiscoveredPlutoSdrTuner extends DiscoveredTuner
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveredPlutoSdrTuner.class);
    private final ChannelizerType mChannelizerType;

    /**
     * Constructs an instance
     * @param configuration with the libiio URI of the Pluto
     * @param channelizerType to use with the tuner
     */
    public DiscoveredPlutoSdrTuner(PlutoSdrTunerConfiguration configuration, ChannelizerType channelizerType)
    {
        mChannelizerType = channelizerType;
        setTunerConfiguration(configuration);
        setEnabled(false);
    }

    /**
     * ID of the discovered tuner for a Pluto at the URI, which is also the unique ID of its configuration.
     * @param uri libiio URI
     */
    public static String getId(String uri)
    {
        return "PlutoSDR " + uri;
    }

    /**
     * libiio URI of the Pluto
     */
    public String getUri()
    {
        return ((PlutoSdrTunerConfiguration)getTunerConfiguration()).getUri();
    }

    @Override
    public TunerClass getTunerClass()
    {
        return TunerClass.PLUTOSDR;
    }

    @Override
    public String getId()
    {
        return getId(getUri());
    }

    /**
     * Enables or disables the tuner.  The tuner manager starts an enabled tuner when it's notified of the status change,
     * so that a failed start leaves the tuner in error status.  A tuner that the user just added isn't monitored by
     * the tuner manager, so it's started here.
     */
    @Override
    public void setEnabled(boolean enabled)
    {
        super.setEnabled(enabled);

        if(enabled && isAvailable() && !hasTuner())
        {
            start();
        }
    }

    @Override
    public void start()
    {
        if(isAvailable() && !hasTuner())
        {
            try
            {
                mTuner = new PlutoSdrTuner(new PlutoSdrTunerController(getUri(), this), this, mChannelizerType);
                mTuner.start();

                //Applies the configuration, which the tuner manager doesn't do for a tuner that the user just added
                setTunerConfiguration(getTunerConfiguration());
            }
            catch(SourceException se)
            {
                //Set error message to flag this tuner with error status and invoke stop()
                setErrorMessage(se.getMessage());
                mLog.error("Unable to start PlutoSDR tuner [" + getUri() + "] - error: " + getErrorMessage());
            }
        }
    }

    @Override
    public String toString()
    {
        return "Network Tuner - " + getId();
    }
}
