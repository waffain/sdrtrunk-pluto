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

import io.github.dsheirer.buffer.FloatNativeBuffer;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.libusb.UsbDeviceInfo;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.manager.UsbDeviceLookup;
import io.github.dsheirer.source.tuner.pluto.iio.IioBuffer;
import io.github.dsheirer.source.tuner.pluto.iio.IioChannel;
import io.github.dsheirer.source.tuner.pluto.iio.IioContext;
import io.github.dsheirer.source.tuner.pluto.iio.IioContextInfo;
import io.github.dsheirer.source.tuner.pluto.iio.IioDevice;
import io.github.dsheirer.source.tuner.pluto.iio.IioException;
import io.github.dsheirer.source.tuner.pluto.iio.LibIio;
import io.github.dsheirer.util.ThreadPool;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlutoSDR (ADALM-Pluto and Pluto-compatible AD936x boards) tuner controller, implemented with libiio.
 *
 * The tuner is discovered with LibUsb at a USB bus and port, like all other USB tuners, however libiio owns the USB
 * device.  The libiio USB context URI is usb:bus.address.interface, so the context is matched by the bus and device
 * address of the device at the discovered port.  A Pluto can also be opened by a network URI (ip:address), such as a
 * board on Ethernet or the USB network link.
 *
 * This controller only receives.  It never opens the transmit sample device (cf-ad9361-dds-core-lpc), and it powers
 * down the transmit local oscillator while the tuner is started, so that the board doesn't leak the LO through an
 * external transmit amplifier.  The previous transmit LO state is restored when the tuner stops.
 *
 * Only the first receive channel (RX1) is used.  Boards with an AD9361 have a second receive channel, but it shares the
 * local oscillator and sample rate with the first, so it couldn't be tuned as an independent tuner.
 */
public class PlutoSdrTunerController extends TunerController
{
    private static final Logger mLog = LoggerFactory.getLogger(PlutoSdrTunerController.class);
    //The AD9361 and AD9364 range.  A stock ADALM-Pluto (AD9363) reports 325 MHz to 3.8 GHz.  The device reports its own
    //range when it's started.
    public static final long MINIMUM_TUNABLE_FREQUENCY_HZ = 70_000_000L;
    public static final long MAXIMUM_TUNABLE_FREQUENCY_HZ = 6_000_000_000L;
    public static final int GAIN_MINIMUM = 0;
    public static final int GAIN_MAXIMUM = 73;
    public static final int GAIN_DEFAULT = 40;
    public static final PlutoSdrGainMode GAIN_MODE_DEFAULT = PlutoSdrGainMode.MANUAL;
    //ADALM-Pluto USB vendor and product ID in normal (not DFU) mode
    public static final int USB_VENDOR_ID = 0x0456;
    public static final int USB_PRODUCT_ID = 0xB673;
    private static final String USB_SCAN_BACKEND = "usb=0456:b673";
    private static final String PHY_DEVICE = "ad9361-phy";
    private static final String RX_DEVICE = "cf-ad9361-lpc";
    //ad9361-phy channels: RX1 settings (input voltage0), RX LO (output altvoltage0), TX LO (output altvoltage1)
    private static final String RX_SETTINGS_CHANNEL = "voltage0";
    private static final String RX_LO_CHANNEL = "altvoltage0";
    private static final String TX_LO_CHANNEL = "altvoltage1";
    //cf-ad9361-lpc channels: RX1 I and Q samples
    private static final String I_CHANNEL = "voltage0";
    private static final String Q_CHANNEL = "voltage1";
    private static final long FREQUENCY_DEFAULT = 101_100_000L;
    //Samples per libiio buffer: 52 ms at 2.5 MHz, 13 ms at 10 MHz.  Larger buffers make fewer, more efficient USB reads.
    private static final int BUFFER_SAMPLE_COUNT = 131_072;
    //Each buffer step holds a 16-bit I and Q sample of RX1
    private static final int BYTES_PER_SAMPLE = 4;
    //12-bit samples, sign-extended to 16 bits
    private static final float SAMPLE_SCALE = 1.0f / 2048.0f;
    private static final ValueLayout.OfShort SAMPLE_LAYOUT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    //Analog filter range of the AD936x
    private static final long RF_BANDWIDTH_MINIMUM = 200_000;
    private static final long RF_BANDWIDTH_MAXIMUM = 56_000_000;
    private static final double USABLE_BANDWIDTH_PERCENT = 0.85;
    //Longer than the libiio timeout (5 seconds) so that a refill that doesn't respond to the cancel times out first
    private static final long STREAM_STOP_TIMEOUT_MS = 6000;

    private final int mBus;
    private final String mPortAddress;
    private final String mUri;
    private IioContext mContext;
    private IioChannel mRxSettings;
    private IioChannel mRxLo;
    private IioChannel mTxLo;
    private String mTxLoPowerdown;
    private volatile boolean mTxLoPoweredDown;
    private IioDevice mRxDevice;
    private IioChannel mIChannel;
    private IioChannel mQChannel;
    private PlutoSdrDeviceInformation mDeviceInfo;
    private List<PlutoSdrSampleRate> mSampleRates = List.of();
    private PlutoSdrSampleRate mSampleRate;
    private long mMinimumTunableFrequency = MINIMUM_TUNABLE_FREQUENCY_HZ;
    private long mMaximumTunableFrequency = MAXIMUM_TUNABLE_FREQUENCY_HZ;
    private PlutoSdrGainMode mGainMode = GAIN_MODE_DEFAULT;
    private int mGain = GAIN_DEFAULT;
    private volatile float mSamplesPerMillisecond = 0.0f;
    private Thread mStreamThread;
    private volatile boolean mStreaming;
    //Buffer that the stream thread is reading, guarded by mBufferLock so that it isn't cancelled while it's destroyed
    private final Object mBufferLock = new Object();
    private IioBuffer mBuffer;

    /**
     * Constructs an instance for a Pluto discovered on USB.
     * @param bus usb
     * @param portAddress usb
     * @param tunerErrorListener to receive error notifications from this controller
     */
    public PlutoSdrTunerController(int bus, String portAddress, ITunerErrorListener tunerErrorListener)
    {
        this(bus, portAddress, null, tunerErrorListener);
    }

    /**
     * Constructs an instance for a Pluto at a libiio URI.
     * @param uri such as ip:192.168.2.1
     * @param tunerErrorListener to receive error notifications from this controller
     */
    public PlutoSdrTunerController(String uri, ITunerErrorListener tunerErrorListener)
    {
        this(0, null, uri, tunerErrorListener);
    }

    private PlutoSdrTunerController(int bus, String portAddress, String uri, ITunerErrorListener tunerErrorListener)
    {
        super(tunerErrorListener);
        mBus = bus;
        mPortAddress = portAddress;
        mUri = uri;
        setMinimumFrequency(MINIMUM_TUNABLE_FREQUENCY_HZ);
        setMaximumFrequency(MAXIMUM_TUNABLE_FREQUENCY_HZ);
        //The AD936x tracks and removes the DC offset, so the center of the spectrum is usable
        setMiddleUnusableHalfBandwidth(0);
        setUsableBandwidthPercentage(USABLE_BANDWIDTH_PERCENT);
    }

    @Override
    public TunerType getTunerType()
    {
        return TunerType.PLUTOSDR;
    }

    @Override
    public int getBufferSampleCount()
    {
        return BUFFER_SAMPLE_COUNT;
    }

    @Override
    public void start() throws SourceException
    {
        getLock().lock();

        try
        {
            LibIio lib = LibIio.get();
            String uri = mUri != null ? mUri : findUsbUri(lib);
            mContext = lib.open(uri);

            IioDevice phy = require(mContext.findDevice(PHY_DEVICE), "device " + PHY_DEVICE);
            mRxDevice = require(mContext.findDevice(RX_DEVICE), "device " + RX_DEVICE);
            mRxSettings = require(phy.findChannel(RX_SETTINGS_CHANNEL, false), PHY_DEVICE + " RX channel");
            mRxLo = require(phy.findChannel(RX_LO_CHANNEL, true), PHY_DEVICE + " RX LO channel");
            mIChannel = require(mRxDevice.findChannel(I_CHANNEL, false), RX_DEVICE + " I channel");
            mQChannel = require(mRxDevice.findChannel(Q_CHANNEL, false), RX_DEVICE + " Q channel");
            mDeviceInfo = getDeviceInformation(mContext, uri, lib.version());

            powerDownTransmitLo(phy);

            long[] frequencies = readRange(mRxLo, "frequency_available");
            mMinimumTunableFrequency = frequencies != null ? frequencies[0] : MINIMUM_TUNABLE_FREQUENCY_HZ;
            mMaximumTunableFrequency = frequencies != null ? frequencies[1] : MAXIMUM_TUNABLE_FREQUENCY_HZ;
            setFrequencyExtents(mMinimumTunableFrequency, mMaximumTunableFrequency);

            long[] rates = readRange(mRxSettings, "sampling_frequency_available");
            mSampleRates = rates != null ? PlutoSdrSampleRate.getRates(rates[0], rates[1]) :
                    List.of(PlutoSdrSampleRate.values());

            if(mSampleRates.isEmpty())
            {
                throw new SourceException("PlutoSDR sample rate range [" + rates[0] + "-" + rates[1] +
                        "] doesn't include any usable rates");
            }

            mSampleRate = null;
            setSampleRate(PlutoSdrSampleRate.getClosest(mSampleRates, PlutoSdrSampleRate.DEFAULT.getRate()));
            setFrequency(Math.clamp(FREQUENCY_DEFAULT, mMinimumTunableFrequency, mMaximumTunableFrequency));
            setGainMode(GAIN_MODE_DEFAULT);
            setGain(GAIN_DEFAULT);
            mLog.info("PlutoSDR [" + mDeviceInfo.model() + "] serial [" + mDeviceInfo.serial() + "] started on [" +
                    uri + "] - firmware " + mDeviceInfo.firmware() + ", libiio " + mDeviceInfo.library());
        }
        catch(IioException | RuntimeException | LinkageError e)
        {
            closeContext();
            throw new SourceException("Can't start PlutoSDR tuner - " + e.getMessage(), e);
        }
        catch(SourceException se)
        {
            closeContext();
            throw se;
        }
        finally
        {
            getLock().unlock();
        }
    }

    @Override
    public void stop()
    {
        getLock().lock();

        try
        {
            stopStream();
            closeContext();
        }
        finally
        {
            getLock().unlock();
        }

        mNativeBufferBroadcaster.clear();
    }

    /**
     * Restores the transmit LO state and closes the libiio context.
     */
    private void closeContext()
    {
        if(mTxLo != null && mTxLoPowerdown != null && !mTxLoPowerdown.equals("1"))
        {
            try
            {
                mTxLo.write("powerdown", mTxLoPowerdown);
            }
            catch(IioException | RuntimeException e)
            {
                mLog.warn("Can't restore PlutoSDR transmit LO state - " + e.getMessage());
            }
        }

        mTxLo = null;
        mTxLoPowerdown = null;
        mTxLoPoweredDown = false;
        mRxSettings = null;
        mRxLo = null;
        mRxDevice = null;
        mIChannel = null;
        mQChannel = null;

        if(mContext != null)
        {
            try
            {
                mContext.close();
            }
            catch(RuntimeException e)
            {
                mLog.error("Error closing PlutoSDR libiio context", e);
            }

            mContext = null;
        }
    }

    /**
     * Powers down the transmit LO, remembering its state so that it can be restored when the tuner stops.  A failure is
     * logged, since the tuner can still receive.
     * @param phy device
     */
    private void powerDownTransmitLo(IioDevice phy)
    {
        IioChannel txLo = phy.findChannel(TX_LO_CHANNEL, true);

        if(txLo == null)
        {
            return;
        }

        try
        {
            mTxLoPowerdown = txLo.read("powerdown");
            mTxLo = txLo;
            txLo.write("powerdown", "1");
            mTxLoPoweredDown = true;
        }
        catch(IioException e)
        {
            mLog.warn("Can't power down the PlutoSDR transmit LO - " + e.getMessage());
        }
    }

    /**
     * Finds the libiio USB context of the device at this controller's bus and port.
     */
    private String findUsbUri(LibIio lib) throws SourceException, IioException
    {
        UsbDeviceInfo device = UsbDeviceLookup.find(mBus, mPortAddress);
        return selectUsbContext(lib.scan(USB_SCAN_BACKEND), device.busNumber(), device.address());
    }

    /**
     * Selects the libiio USB context at the bus and device address, or the only Pluto when none matches, which can happen
     * if libiio numbers the USB devices differently.
     * @param contexts found by a libiio USB scan
     * @param bus number of the USB device
     * @param address of the USB device on the bus
     * @return context URI
     * @throws SourceException if there isn't a match
     */
    static String selectUsbContext(List<IioContextInfo> contexts, int bus, int address) throws SourceException
    {
        String prefix = "usb:" + bus + "." + address + ".";

        for(IioContextInfo context : contexts)
        {
            if(context.uri() != null && context.uri().startsWith(prefix))
            {
                return context.uri();
            }
        }

        if(contexts.size() == 1 && contexts.getFirst().uri() != null)
        {
            mLog.info("libiio reports the PlutoSDR at [" + contexts.getFirst().uri() + "] rather than USB bus [" + bus +
                    "] address [" + address + "] - using it since it's the only PlutoSDR");
            return contexts.getFirst().uri();
        }

        if(contexts.isEmpty())
        {
            throw new SourceException("libiio can't access the PlutoSDR on USB - if using windows, install the " +
                    "PlutoSDR USB driver (PlutoSDR-M2k-USB-Drivers.exe).  If using linux, install the PlutoSDR udev " +
                    "rules (53-adi-plutosdr-usb.rules)");
        }

        throw new SourceException("Can't find the libiio context for the PlutoSDR at USB bus [" + bus +
                "] address [" + address + "] among " + contexts.size() + " PlutoSDR devices");
    }

    /**
     * Reads the device details from the context attributes that the Pluto firmware provides.
     */
    private static PlutoSdrDeviceInformation getDeviceInformation(IioContext context, String uri, String library)
    {
        Map<String,String> attributes = context.attributes();
        String server;

        try
        {
            server = context.version();
        }
        catch(IioException e)
        {
            server = "unknown";
        }

        return new PlutoSdrDeviceInformation(getSerial(attributes, uri),
                attributes.getOrDefault("hw_model", "PlutoSDR"),
                attributes.getOrDefault("ad9361-phy,model", "ad936x"),
                attributes.getOrDefault("fw_version", "unknown"), uri, server, library);
    }

    /**
     * Board serial number from the context attributes, or the URI when the board doesn't report one.
     */
    static String getSerial(Map<String,String> attributes, String uri)
    {
        for(String attribute : List.of("hw_serial", "usb,serial"))
        {
            String serial = attributes.get(attribute);

            if(serial != null && !serial.isBlank())
            {
                return serial.trim();
            }
        }

        return uri;
    }

    /**
     * Reads a range attribute, formatted as [minimum step maximum] by the AD936x driver.
     * @param channel to read
     * @param attribute name
     * @return minimum and maximum, or null if the attribute can't be read or parsed
     */
    private static long[] readRange(IioChannel channel, String attribute)
    {
        try
        {
            return parseRange(channel.read(attribute));
        }
        catch(IioException e)
        {
            mLog.debug("Can't read PlutoSDR attribute [" + attribute + "] - " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a range attribute value, such as [70000000 1 6000000000] or [-3 1 71].
     * @param value to parse
     * @return minimum and maximum, or null if the value isn't a range
     */
    static long[] parseRange(String value)
    {
        if(value == null)
        {
            return null;
        }

        String[] parts = value.replace("[", " ").replace("]", " ").trim().split("\\s+");

        if(parts.length != 3)
        {
            return null;
        }

        try
        {
            long minimum = Long.parseLong(parts[0]);
            long maximum = Long.parseLong(parts[2]);
            return minimum <= maximum ? new long[]{minimum, maximum} : null;
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * Throws a source exception when a device or channel is missing.
     */
    private static <T> T require(T value, String description) throws SourceException
    {
        if(value == null)
        {
            throw new SourceException("PlutoSDR libiio context doesn't have the " + description +
                    " - is this an AD936x (Pluto) device?");
        }

        return value;
    }

    @Override
    public void apply(TunerConfiguration tunerConfiguration) throws SourceException
    {
        if(!(tunerConfiguration instanceof PlutoSdrTunerConfiguration config))
        {
            throw new IllegalArgumentException("Invalid tuner config:" + tunerConfiguration.getClass());
        }

        //Saved limits can be wider than this device, since the defaults are for the AD9361.
        config.setMinimumFrequency(Math.max(config.getMinimumFrequency(), mMinimumTunableFrequency));
        config.setMaximumFrequency(Math.min(config.getMaximumFrequency() > 0 ? config.getMaximumFrequency() :
                mMaximumTunableFrequency, mMaximumTunableFrequency));

        PlutoSdrSampleRate rate = PlutoSdrSampleRate.getClosest(mSampleRates, config.getSampleRate());
        setSampleRate(rate);
        config.setSampleRate(rate.getRate());

        //Invoke super for frequency, frequency correction and autoPPM
        super.apply(config);

        setGainMode(config.getGainMode());
        int gain = Math.clamp(config.getGain(), GAIN_MINIMUM, GAIN_MAXIMUM);
        setGain(gain);
        config.setGain(gain);
    }

    @Override
    public long getTunedFrequency() throws SourceException
    {
        return mFrequencyController.getTunedFrequency();
    }

    @Override
    public void setTunedFrequency(long frequency) throws SourceException
    {
        getLock().lock();

        try
        {
            if(mRxLo != null)
            {
                mRxLo.writeLong("frequency", frequency);
            }
        }
        catch(IioException e)
        {
            throw new SourceException("Can't tune PlutoSDR to [" + frequency + "] - " + e.getMessage(), e);
        }
        finally
        {
            getLock().unlock();
        }
    }

    @Override
    public double getCurrentSampleRate()
    {
        return mSampleRate != null ? mSampleRate.getRate() : 0;
    }

    /**
     * Sample rates supported by the device.
     */
    public List<PlutoSdrSampleRate> getSampleRates()
    {
        return mSampleRates;
    }

    /**
     * Current sample rate.
     */
    public PlutoSdrSampleRate getPlutoSdrSampleRate()
    {
        return mSampleRate;
    }

    /**
     * Sets the sample rate and the matching analog filter bandwidth.  The sample stream keeps running.
     * @param rate to set
     * @throws SourceException if the sample rate is locked or can't be set
     */
    public void setSampleRate(PlutoSdrSampleRate rate) throws SourceException
    {
        getLock().lock();

        try
        {
            if(rate != mSampleRate && mRxSettings != null)
            {
                if(isLockedSampleRate())
                {
                    throw new SourceException("Can't change PlutoSDR sample rate while channels are allocated");
                }

                mRxSettings.writeLong("sampling_frequency", rate.getRate());
                mRxSettings.writeLong("rf_bandwidth", Math.clamp(rate.getRate(), RF_BANDWIDTH_MINIMUM,
                        RF_BANDWIDTH_MAXIMUM));
                mSampleRate = rate;
                mSamplesPerMillisecond = rate.getRate() / 1000.0f;
                mFrequencyController.setSampleRate(rate.getRate());
            }
        }
        catch(IioException e)
        {
            throw new SourceException("Can't set PlutoSDR sample rate to [" + rate + "] - " + e.getMessage(), e);
        }
        finally
        {
            getLock().unlock();
        }
    }

    /**
     * Lowest frequency that the device can tune.
     */
    public long getMinimumTunableFrequency()
    {
        return mMinimumTunableFrequency;
    }

    /**
     * Highest frequency that the device can tune.
     */
    public long getMaximumTunableFrequency()
    {
        return mMaximumTunableFrequency;
    }

    /**
     * Current gain control mode.
     */
    public PlutoSdrGainMode getGainMode()
    {
        return mGainMode;
    }

    /**
     * Sets manual gain or one of the automatic gain control modes.  In manual mode, the manual gain is reapplied.
     * @param mode to set
     * @throws SourceException if the mode can't be set
     */
    public void setGainMode(PlutoSdrGainMode mode) throws SourceException
    {
        getLock().lock();

        try
        {
            mGainMode = mode;

            if(mRxSettings != null)
            {
                mRxSettings.write("gain_control_mode", mode.getValue());

                if(mode.isManual())
                {
                    writeGain(mGain);
                }
            }
        }
        catch(IioException e)
        {
            throw new SourceException("Can't set PlutoSDR gain mode to [" + mode + "] - " + e.getMessage(), e);
        }
        finally
        {
            getLock().unlock();
        }
    }

    /**
     * Current manual gain in dB.
     */
    public int getGain()
    {
        return mGain;
    }

    /**
     * Sets the manual receive gain.  The gain is applied when the gain mode is manual.
     * @param gain in dB
     * @throws SourceException if the gain can't be set
     */
    public void setGain(int gain) throws SourceException
    {
        getLock().lock();

        try
        {
            mGain = gain;

            if(mRxSettings != null && mGainMode.isManual())
            {
                writeGain(gain);
            }
        }
        catch(IioException e)
        {
            throw new SourceException("Can't set PlutoSDR gain to [" + gain + "] - " + e.getMessage(), e);
        }
        finally
        {
            getLock().unlock();
        }
    }

    /**
     * Writes the gain, limited to the range that the AD936x supports at the current frequency (-1 to 73 dB below
     * 1.3 GHz, less above that).
     */
    private void writeGain(int gain) throws IioException
    {
        long[] range = readRange(mRxSettings, "hardwaregain_available");
        long limited = range != null ? Math.clamp(gain, range[0], range[1]) : gain;
        mRxSettings.writeLong("hardwaregain", limited);
    }

    /**
     * Indicates if the transmit LO was powered down when the tuner started.
     */
    public boolean isTransmitLoPoweredDown()
    {
        return mTxLoPoweredDown;
    }

    /**
     * Device information, available once the tuner has been started.
     */
    public PlutoSdrDeviceInformation getDeviceInfo()
    {
        return mDeviceInfo;
    }

    /**
     * Adds the IQ buffer listener and automatically starts streaming, if not already started.
     */
    @Override
    public void addBufferListener(Listener<INativeBuffer> listener)
    {
        getLock().lock();

        try
        {
            if(mContext != null)
            {
                super.addBufferListener(listener);

                if(!mStreaming)
                {
                    startStream();
                }
            }
        }
        catch(SourceException se)
        {
            mLog.error("Error starting streaming on PlutoSDR tuner", se);
        }
        finally
        {
            getLock().unlock();
        }
    }

    /**
     * Removes the IQ buffer listener and stops streaming if there are no more listeners.
     */
    @Override
    public void removeBufferListener(Listener<INativeBuffer> listener)
    {
        getLock().lock();

        try
        {
            super.removeBufferListener(listener);

            if(!hasBufferListeners())
            {
                stopStream();
            }
        }
        finally
        {
            getLock().unlock();
        }
    }

    /**
     * Enables the RX1 I and Q channels, creates the sample buffer and starts the stream thread.
     */
    private void startStream() throws SourceException
    {
        IioBuffer buffer = null;

        try
        {
            mIChannel.setEnabled(true);
            mQChannel.setEnabled(true);
            buffer = mRxDevice.createBuffer(BUFFER_SAMPLE_COUNT);

            if(buffer.step() != BYTES_PER_SAMPLE)
            {
                throw new SourceException("PlutoSDR sample format isn't 16-bit I/Q - buffer step is [" +
                        buffer.step() + "] bytes");
            }
        }
        catch(IioException | RuntimeException e)
        {
            if(buffer != null)
            {
                buffer.close();
            }

            throw new SourceException("Can't start PlutoSDR sample stream - " + e.getMessage(), e);
        }
        catch(SourceException se)
        {
            buffer.close();
            throw se;
        }

        synchronized(mBufferLock)
        {
            mBuffer = buffer;
        }

        mStreaming = true;
        IioBuffer streamBuffer = buffer;
        mStreamThread = new Thread(() -> stream(streamBuffer), "sdrtrunk PlutoSDR " + mDeviceInfo.serial());
        mStreamThread.setDaemon(true);
        mStreamThread.setPriority(Thread.MAX_PRIORITY);
        mStreamThread.start();
    }

    /**
     * Stops the stream thread and waits for it to finish.  The thread finishes after its current refill, which takes
     * a buffer's duration.  A cancelled refill makes libiio log errors to the console, so the refill is only cancelled
     * when it doesn't finish in time, such as when the device stops sending samples.
     */
    private void stopStream()
    {
        Thread thread = mStreamThread;
        mStreamThread = null;
        mStreaming = false;

        if(thread == null || thread == Thread.currentThread())
        {
            return;
        }

        double rate = getCurrentSampleRate() > 0 ? getCurrentSampleRate() : PlutoSdrSampleRate.RATE_2_5.getRate();
        join(thread, (long)(2000.0 * BUFFER_SAMPLE_COUNT / rate) + 250);

        if(thread.isAlive())
        {
            synchronized(mBufferLock)
            {
                if(mBuffer != null)
                {
                    mBuffer.cancel();
                }
            }

            join(thread, STREAM_STOP_TIMEOUT_MS);

            if(thread.isAlive())
            {
                mLog.error("PlutoSDR sample stream thread didn't stop");
            }
        }
    }

    private static void join(Thread thread, long milliseconds)
    {
        try
        {
            thread.join(milliseconds);
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads sample buffers on the stream thread and dispatches them to the buffer listeners, until the stream is
     * stopped or fails.  The buffer is destroyed when the stream ends.
     * @param buffer to read
     */
    private void stream(IioBuffer buffer)
    {
        short[] samples = new short[0];
        String error = null;

        try
        {
            while(mStreaming)
            {
                MemorySegment data = buffer.refill();
                int length = (int)(data.byteSize() / Short.BYTES);

                if(samples.length != length)
                {
                    samples = new short[length];
                }

                MemorySegment.copy(data, SAMPLE_LAYOUT, 0, samples, 0, length);
                broadcast(new FloatNativeBuffer(scale(samples), System.currentTimeMillis(), mSamplesPerMillisecond));
            }
        }
        catch(IioException e)
        {
            //A cancelled refill fails, which is expected when the stream is stopped
            if(mStreaming)
            {
                error = "PlutoSDR sample stream stopped - " + e.getMessage();
            }
        }
        catch(RuntimeException e)
        {
            mLog.error("Error dispatching PlutoSDR sample buffer", e);
            error = "PlutoSDR sample stream stopped - " + e.getMessage();
        }
        finally
        {
            synchronized(mBufferLock)
            {
                if(mBuffer == buffer)
                {
                    mBuffer = null;
                }

                buffer.close();
            }
        }

        if(error != null)
        {
            mStreaming = false;
            String message = error;
            mLog.error(message);
            //Reported on another thread, since it stops the tuner, which waits for this thread to finish
            ThreadPool.CACHED.execute(() -> setErrorMessage(message));
        }
    }

    /**
     * Scales 12-bit samples to the range -1 to 1.
     * @param samples interleaved complex samples
     * @return scaled interleaved complex samples
     */
    static float[] scale(short[] samples)
    {
        float[] scaled = new float[samples.length];

        for(int x = 0; x < samples.length; x++)
        {
            scaled[x] = samples[x] * SAMPLE_SCALE;
        }

        return scaled;
    }
}
