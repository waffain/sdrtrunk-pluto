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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.dsp.filter.channelizer.ComplexPolyphaseChannelizerM2;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.pluto.iio.IioContextInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the parts of the PlutoSDR tuner that don't require a device.
 */
class PlutoSdrTunerTest
{
    private static final IioContextInfo PLUTO_A = new IioContextInfo("usb:1.5.5",
            "0456:b673 (Analog Devices Inc. PlutoSDR (ADALM-PLUTO)), serial=104473023196000BF5FF1A00AAE12C3CA8");
    private static final IioContextInfo PLUTO_B = new IioContextInfo("usb:2.12.5",
            "0456:b673 (Analog Devices Inc. PlutoSDR (ADALM-PLUTO)), serial=1044730a199700191f001800f3647e1a2b");

    @Test
    void lookupFindsThePluto()
    {
        assertEquals(TunerClass.PLUTOSDR, TunerClass.lookup((short)0x0456, (short)0xB673));
        assertEquals(TunerClass.PLUTOSDR, TunerClass.lookup((short)PlutoSdrTunerController.USB_VENDOR_ID,
                (short)PlutoSdrTunerController.USB_PRODUCT_ID));
        //DFU mode isn't a tuner
        assertEquals(TunerClass.UNKNOWN, TunerClass.lookup((short)0x0456, (short)0xB674));
        assertTrue(TunerClass.PLUTOSDR.isSupportedUsbTuner());
        assertTrue(TunerType.PLUTOSDR.isSupportedUsbTuner());
    }

    @Test
    void samplesAreScaledFrom12Bits()
    {
        short[] samples = {0, 2047, -2048, 1024, -1};
        assertArrayEquals(new float[]{0.0f, 2047.0f / 2048.0f, -1.0f, 0.5f, -1.0f / 2048.0f},
                PlutoSdrTunerController.scale(samples));
    }

    @Test
    void sampleRatesAreLimitedToTheDeviceRange()
    {
        //AD936x range without a custom FIR filter
        assertEquals(List.of(PlutoSdrSampleRate.values()), PlutoSdrSampleRate.getRates(2_083_333, 61_440_000));
        assertEquals(PlutoSdrSampleRate.RATE_5_0, PlutoSdrSampleRate.getRates(2_083_333, 5_000_000).getLast());
        assertTrue(PlutoSdrSampleRate.getRates(20_000_000, 61_440_000).isEmpty());
    }

    @Test
    void closestRateDoesNotExceedTheRequest()
    {
        List<PlutoSdrSampleRate> rates = List.of(PlutoSdrSampleRate.values());
        assertEquals(PlutoSdrSampleRate.RATE_3_0, PlutoSdrSampleRate.getClosest(rates, 3_500_000));
        assertEquals(PlutoSdrSampleRate.RATE_10_0, PlutoSdrSampleRate.getClosest(rates, 61_440_000));
        assertEquals(PlutoSdrSampleRate.RATE_2_5, PlutoSdrSampleRate.getClosest(rates, 1));
    }

    @Test
    void sampleRatesDivideIntoPolyphaseChannels()
    {
        for(PlutoSdrSampleRate rate : PlutoSdrSampleRate.values())
        {
            int channelCount = ComplexPolyphaseChannelizerM2.getChannelCount(rate.getRate());
            assertEquals(0, channelCount % 2, rate.toString());
            assertTrue((double)rate.getRate() / channelCount >= 25_000.0, rate.toString());
        }
    }

    @Test
    void rangeAttributesAreParsed()
    {
        assertArrayEquals(new long[]{70_000_000L, 6_000_000_000L}, PlutoSdrTunerController.parseRange("[70000000 1 6000000000]"));
        assertArrayEquals(new long[]{2_083_333L, 61_440_000L}, PlutoSdrTunerController.parseRange("[2083333 1 61440000]"));
        assertArrayEquals(new long[]{-3L, 71L}, PlutoSdrTunerController.parseRange(" [-3 1 71] "));
        assertNull(PlutoSdrTunerController.parseRange("manual fast_attack slow_attack hybrid"));
        assertNull(PlutoSdrTunerController.parseRange("[71 1 -3]"));
        assertNull(PlutoSdrTunerController.parseRange(null));
    }

    @Test
    void usbContextIsMatchedByBusAndAddress() throws Exception
    {
        assertEquals("usb:2.12.5", PlutoSdrTunerController.selectUsbContext(List.of(PLUTO_A, PLUTO_B), 2, 12));
        assertEquals("usb:1.5.5", PlutoSdrTunerController.selectUsbContext(List.of(PLUTO_A, PLUTO_B), 1, 5));
        //Address 1 must not match address 12
        assertThrows(SourceException.class, () -> PlutoSdrTunerController.selectUsbContext(List.of(PLUTO_A, PLUTO_B), 2, 1));
    }

    @Test
    void onlyPlutoIsUsedWhenTheAddressDoesNotMatch() throws Exception
    {
        assertEquals("usb:1.5.5", PlutoSdrTunerController.selectUsbContext(List.of(PLUTO_A), 3, 7));
        assertThrows(SourceException.class, () -> PlutoSdrTunerController.selectUsbContext(List.of(), 1, 5));
    }

    @Test
    void serialComesFromTheContextAttributes()
    {
        assertEquals("104473023196000BF5FF1A00AAE12C3CA8", PlutoSdrTunerController.getSerial(
                Map.of("hw_serial", "104473023196000BF5FF1A00AAE12C3CA8", "usb,serial", "other"), "usb:1.5.5"));
        assertEquals("abc", PlutoSdrTunerController.getSerial(Map.of("usb,serial", "abc"), "usb:1.5.5"));
        assertEquals("ip:192.168.3.1", PlutoSdrTunerController.getSerial(Map.of(), "ip:192.168.3.1"));
    }

    @Test
    void gainModesMatchTheDriverValues()
    {
        assertEquals(PlutoSdrGainMode.SLOW_ATTACK, PlutoSdrGainMode.fromValue("slow_attack"));
        assertEquals(PlutoSdrGainMode.MANUAL, PlutoSdrGainMode.fromValue("unknown"));
        assertTrue(PlutoSdrGainMode.MANUAL.isManual());
        assertFalse(PlutoSdrGainMode.HYBRID.isManual());
    }

    @Test
    void configurationSurvivesSerialization() throws Exception
    {
        PlutoSdrTunerConfiguration configuration = PlutoSdrTunerConfiguration.create("ip:192.168.3.1", 851_012_500L);
        configuration.setSampleRate(PlutoSdrSampleRate.RATE_5_0.getRate());
        configuration.setGain(55);
        configuration.setGainMode(PlutoSdrGainMode.SLOW_ATTACK);

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String json = mapper.writeValueAsString(configuration);
        TunerConfiguration restored = mapper.readValue(json, TunerConfiguration.class);

        PlutoSdrTunerConfiguration pluto = assertInstanceOf(PlutoSdrTunerConfiguration.class, restored);
        assertEquals(TunerType.PLUTOSDR, pluto.getTunerType());
        assertEquals(PlutoSdrSampleRate.RATE_5_0.getRate(), pluto.getSampleRate());
        assertEquals(55, pluto.getGain());
        assertEquals(PlutoSdrGainMode.SLOW_ATTACK, pluto.getGainMode());
        assertEquals("ip:192.168.3.1", pluto.getUri());
        assertTrue(pluto.hasUri());
        assertEquals(851_012_500L, pluto.getFrequency());
        assertEquals(DiscoveredPlutoSdrTuner.getId("ip:192.168.3.1"), pluto.getUniqueID());
    }

    @Test
    void usbConfigurationHasNoUri()
    {
        PlutoSdrTunerConfiguration configuration = assertInstanceOf(PlutoSdrTunerConfiguration.class,
                TunerFactory.getTunerConfiguration(TunerType.PLUTOSDR, "PlutoSDR USB Bus:1 Port:3"));
        assertFalse(configuration.hasUri());
        assertEquals(PlutoSdrGainMode.MANUAL, configuration.getGainMode());
    }

    @Test
    void channelsAvoidTheCenterFrequency()
    {
        PlutoSdrTunerController controller = new PlutoSdrTunerController("ip:192.168.2.1", null);
        assertTrue(controller.hasMiddleUnusableBandwidth());
        assertEquals(PlutoSdrTunerController.DC_HALF_BANDWIDTH, controller.getMiddleUnusableHalfBandwidth());
    }

    @Test
    void maximumUsbRateDoesNotOverflow()
    {
        assertTrue(PlutoSdrSampleRate.RATE_10_0.getRate() * 32L <= Integer.MAX_VALUE);
    }
}
