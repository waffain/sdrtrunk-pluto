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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.pluto.iio.LibIio;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jtransforms.fft.FloatFFT_1D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Receives samples from a real PlutoSDR, or from a test double of its iiod server.  Skipped unless the PLUTO_URI
 * environment variable names the libiio URI, such as usb: (the only Pluto on USB), usb:1.5.5 or ip:192.168.2.1.
 *
 * Optional environment variables:
 * LIBIIO_LIBRARY_PATH - libiio file or directory, when libiio isn't installed
 * PLUTO_EXPECT_TONE_BIN - the test double's tone, in bins of a 131072-sample buffer, to check the spectrum
 *
 * The tuner only receives.  It powers down the transmit LO while it runs and restores it when it stops.
 */
@EnabledIfEnvironmentVariable(named = "PLUTO_URI", matches = ".+")
class PlutoSdrLiveTest
{
    private static final int FFT_SIZE = 8192;
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void streamsSamplesAndFollowsSettings() throws Exception
    {
        String libiio = System.getenv("LIBIIO_LIBRARY_PATH");

        if(libiio != null && !libiio.isBlank())
        {
            System.setProperty(LibIio.LIBRARY_PATH_PROPERTY, libiio);
        }

        List<String> errors = new CopyOnWriteArrayList<>();
        ITunerErrorListener errorListener = new ITunerErrorListener()
        {
            @Override
            public void setErrorMessage(String errorMessage)
            {
                errors.add(errorMessage);
            }

            @Override
            public void tunerRemoved()
            {
                errors.add("removed");
            }
        };

        //Plutos that libiio can open on USB - none means the USB driver (windows) or udev rules (linux) are missing
        System.out.println("libiio " + LibIio.get().version() + " from " + LibIio.get().location() + " - USB Plutos: " +
                LibIio.get().scan("usb=0456:b673"));

        PlutoSdrTunerController controller = new PlutoSdrTunerController(System.getenv("PLUTO_URI"), errorListener);
        controller.start();

        try
        {
            PlutoSdrDeviceInformation info = controller.getDeviceInfo();
            assertNotNull(info);
            System.out.println("PlutoSDR: " + info);
            System.out.println("Sample rates: " + controller.getSampleRates() + " frequency range: " +
                    controller.getMinimumTunableFrequency() + " - " + controller.getMaximumTunableFrequency());
            assertTrue(controller.isTransmitLoPoweredDown(), "transmit LO should be powered down");

            PlutoSdrTunerConfiguration config = new PlutoSdrTunerConfiguration("live-test");
            config.setFrequency(FREQUENCY);
            config.setSampleRate(PlutoSdrSampleRate.RATE_2_5.getRate());
            config.setGainMode(PlutoSdrGainMode.MANUAL);
            config.setGain(30);
            controller.apply(config);
            assertEquals(FREQUENCY, controller.getFrequency());
            assertEquals(PlutoSdrSampleRate.RATE_2_5, controller.getPlutoSdrSampleRate());

            LinkedBlockingQueue<INativeBuffer> buffers = new LinkedBlockingQueue<>();
            Listener<INativeBuffer> listener = buffers::offer;
            controller.addBufferListener(listener);

            try
            {
                checkStream(buffers, PlutoSdrSampleRate.RATE_2_5);

                //Changing the rate while streaming
                controller.setSampleRate(PlutoSdrSampleRate.RATE_5_0);
                buffers.clear();
                checkStream(buffers, PlutoSdrSampleRate.RATE_5_0);

                //Tuning and gain while streaming
                controller.setFrequency(FREQUENCY + 1_000_000);
                controller.setGainMode(PlutoSdrGainMode.SLOW_ATTACK);
                controller.setGainMode(PlutoSdrGainMode.MANUAL);
                controller.setGain(50);
                assertTrue(buffers.poll(5, TimeUnit.SECONDS) != null, "stream stopped after changing settings");
            }
            finally
            {
                controller.removeBufferListener(listener);
            }
        }
        finally
        {
            controller.stop();
        }

        assertTrue(errors.isEmpty(), "tuner errors: " + errors);
    }

    /**
     * Collects a second of buffers, checks the sample count against the rate, and logs (or checks) the spectrum peak.
     */
    private static void checkStream(LinkedBlockingQueue<INativeBuffer> buffers, PlutoSdrSampleRate rate) throws Exception
    {
        //Skip the buffers from before the change
        Thread.sleep(300);
        buffers.clear();

        long start = System.nanoTime();
        long samples = 0;
        INativeBuffer last = null;

        while(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
        {
            INativeBuffer buffer = buffers.poll(5, TimeUnit.SECONDS);
            assertNotNull(buffer, "no samples received at " + rate);
            samples += buffer.sampleCount();
            last = buffer;
        }

        double seconds = (System.nanoTime() - start) / 1e9;
        double measuredRate = samples / seconds;
        System.out.printf("%s: received %d samples in %.2f s = %.3f MS/s%n", rate, samples, seconds, measuredRate / 1e6);
        assertTrue(measuredRate > rate.getRate() * 0.8, "sample rate too low: " + measuredRate);

        float[] fft = new float[FFT_SIZE * 2];
        Iterator<InterleavedComplexSamples> iterator = last.iteratorInterleaved();
        int filled = 0;

        while(iterator.hasNext() && filled < fft.length)
        {
            float[] chunk = iterator.next().samples();
            int count = Math.min(chunk.length, fft.length - filled);
            System.arraycopy(chunk, 0, fft, filled, count);
            filled += count;
        }

        float rms = 0;

        for(int x = 0; x < filled; x++)
        {
            rms += fft[x] * fft[x];
        }

        rms = (float)Math.sqrt(rms / Math.max(1, filled));

        new FloatFFT_1D(FFT_SIZE).complexForward(fft);
        int peak = 0;
        double peakPower = 0;

        for(int bin = 0; bin < FFT_SIZE; bin++)
        {
            double power = fft[bin * 2] * fft[bin * 2] + fft[bin * 2 + 1] * fft[bin * 2 + 1];

            if(power > peakPower)
            {
                peakPower = power;
                peak = bin;
            }
        }

        double binWidth = (double)rate.getRate() / FFT_SIZE;
        double peakFrequency = (peak < FFT_SIZE / 2 ? peak : peak - FFT_SIZE) * binWidth;
        System.out.printf("%s: RMS %.4f full scale, spectrum peak at %+.0f Hz from center%n", rate, rms, peakFrequency);

        String toneBin = System.getenv("PLUTO_EXPECT_TONE_BIN");

        if(toneBin != null && !toneBin.isBlank())
        {
            double expected = Integer.parseInt(toneBin.trim()) * (double)rate.getRate() / 131_072;
            assertEquals(expected, peakFrequency, binWidth * 2, "tone frequency");
        }
    }
}
