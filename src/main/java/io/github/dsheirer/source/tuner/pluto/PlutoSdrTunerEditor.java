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

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.source.tuner.ui.TunerEditor;
import java.io.Serial;
import java.util.List;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.SpinnerNumberModel;

/**
 * PlutoSDR tuner editor
 */
public class PlutoSdrTunerEditor extends TunerEditor<PlutoSdrTuner, PlutoSdrTunerConfiguration>
{
    @Serial
    private static final long serialVersionUID = 1L;
    private static final Logger mLog = LoggerFactory.getLogger(PlutoSdrTunerEditor.class);
    private JButton mTunerInfoButton;
    private JComboBox<PlutoSdrSampleRate> mSampleRateCombo;
    private JComboBox<PlutoSdrGainMode> mGainModeCombo;
    private JSlider mGainSlider;
    private JLabel mGainValueLabel;

    /**
     * Constructs an instance
     * @param userPreferences for wide-band recordings
     * @param tunerManager for saving tuner configuration
     * @param discoveredTuner for the optionally usable PlutoSDR tuner and controller
     */
    public PlutoSdrTunerEditor(UserPreferences userPreferences, TunerManager tunerManager, DiscoveredTuner discoveredTuner)
    {
        super(userPreferences, tunerManager, discoveredTuner);
        init();
        tunerStatusUpdated();
    }

    @Override
    public long getMinimumTunableFrequency()
    {
        return hasTuner() ? getTuner().getController().getMinimumTunableFrequency() :
                PlutoSdrTunerController.MINIMUM_TUNABLE_FREQUENCY_HZ;
    }

    @Override
    public long getMaximumTunableFrequency()
    {
        return hasTuner() ? getTuner().getController().getMaximumTunableFrequency() :
                PlutoSdrTunerController.MAXIMUM_TUNABLE_FREQUENCY_HZ;
    }

    private void init()
    {
        setLayout(new MigLayout("fill,wrap 3", "[right][grow,fill][fill]", "[][][][][][][][][][][grow]"));

        add(new JLabel("Tuner:"));
        add(getTunerIdLabel());
        add(getTunerInfoButton());

        add(new JLabel("Status:"));
        add(getTunerStatusLabel(), "wrap");

        add(getButtonPanel(), "span,align left");

        add(new JSeparator(), "span,growx");

        add(new JLabel("Frequency (MHz):"));
        add(getFrequencyPanel(), "wrap");

        add(new JLabel("Sample Rate:"));
        add(getSampleRateCombo(), "wrap");

        add(new JSeparator(), "span,growx");

        add(new JLabel("Gain Mode:"));
        add(getGainModeCombo(), "wrap");

        add(new JLabel("Gain:"));
        add(getGainSlider());
        add(getGainValueLabel());

        //Empty row that takes all extra vertical space so that the controls stay stacked at the top of the editor.
        add(new JLabel(), "span,pushy");
    }

    @Override
    protected void tunerStatusUpdated()
    {
        setLoading(true);

        boolean started = hasStartedTuner();
        getTunerIdLabel().setText(started ? getTuner().getPreferredName() : getDiscoveredTuner().getId());

        String status = getDiscoveredTuner().getTunerStatus().toString();
        if(getDiscoveredTuner().hasErrorMessage())
        {
            status += " - " + getDiscoveredTuner().getErrorMessage();
        }
        getTunerStatusLabel().setText(status);
        getButtonPanel().updateControls();
        getFrequencyPanel().updateControls();
        getTunerInfoButton().setEnabled(started);

        if(started)
        {
            PlutoSdrTunerController controller = getTuner().getController();
            List<PlutoSdrSampleRate> rates = controller.getSampleRates();
            getSampleRateCombo().setModel(new DefaultComboBoxModel<>(rates.toArray(new PlutoSdrSampleRate[0])));
            getSampleRateCombo().setSelectedItem(controller.getPlutoSdrSampleRate());
            getSampleRateCombo().setEnabled(!controller.isLockedSampleRate());

            getGainModeCombo().setSelectedItem(controller.getGainMode());
            getGainModeCombo().setEnabled(true);

            getGainSlider().setValue(controller.getGain());
        }
        else
        {
            getSampleRateCombo().setModel(new DefaultComboBoxModel<>());
            getSampleRateCombo().setEnabled(false);
            getGainModeCombo().setEnabled(false);
        }

        updateGainControls();
        setLoading(false);
    }

    /**
     * Indicates if the tuner has finished starting, since a status update can arrive while it's starting or restarting.
     */
    private boolean hasStartedTuner()
    {
        return hasTuner() && getTuner().getController().getPlutoSdrSampleRate() != null;
    }

    /**
     * Enables the gain slider in manual gain mode and updates its label.
     */
    private void updateGainControls()
    {
        boolean manual = hasStartedTuner() && getTuner().getController().getGainMode().isManual();
        getGainSlider().setEnabled(manual);
        getGainValueLabel().setEnabled(manual);
        getGainValueLabel().setText(manual || !hasStartedTuner() ? getGainSlider().getValue() + " dB" : "AGC");
    }

    /**
     * Button that provides tuner information
     */
    private JButton getTunerInfoButton()
    {
        if(mTunerInfoButton == null)
        {
            mTunerInfoButton = new JButton("Info");
            mTunerInfoButton.setEnabled(false);
            mTunerInfoButton.addActionListener(_ -> JOptionPane.showMessageDialog(PlutoSdrTunerEditor.this,
                    getTunerInfo(), "Tuner Info", JOptionPane.INFORMATION_MESSAGE));
        }

        return mTunerInfoButton;
    }

    private JComboBox<PlutoSdrSampleRate> getSampleRateCombo()
    {
        if(mSampleRateCombo == null)
        {
            mSampleRateCombo = new JComboBox<>();
            mSampleRateCombo.setEnabled(false);
            mSampleRateCombo.addActionListener(_ ->
            {
                if(hasTuner() && !isLoading())
                {
                    PlutoSdrSampleRate rate = (PlutoSdrSampleRate)mSampleRateCombo.getSelectedItem();

                    try
                    {
                        getTuner().getController().setSampleRate(rate);

                        //Adjust the min/max values for the sample rate.
                        adjustForSampleRate(rate.getRate());

                        save();
                    }
                    catch(Exception e1)
                    {
                        mLog.error("Error setting PlutoSDR sample rate", e1);
                        JOptionPane.showMessageDialog(PlutoSdrTunerEditor.this, "Couldn't set sample rate to " + rate);
                    }
                }
            });
        }

        return mSampleRateCombo;
    }

    private JComboBox<PlutoSdrGainMode> getGainModeCombo()
    {
        if(mGainModeCombo == null)
        {
            mGainModeCombo = new JComboBox<>(PlutoSdrGainMode.values());
            mGainModeCombo.setEnabled(false);
            mGainModeCombo.setToolTipText("Manual gain, or the AD936x automatic gain control.  Manual gain is " +
                    "recommended for decoding trunked systems.");
            mGainModeCombo.addActionListener(_ ->
            {
                if(hasTuner() && !isLoading())
                {
                    PlutoSdrGainMode mode = (PlutoSdrGainMode)mGainModeCombo.getSelectedItem();

                    try
                    {
                        getTuner().getController().setGainMode(mode);
                        save();
                    }
                    catch(Exception e1)
                    {
                        mLog.error("Error setting PlutoSDR gain mode", e1);
                        JOptionPane.showMessageDialog(mGainModeCombo, "Couldn't set gain mode to " + mode);
                    }

                    updateGainControls();
                }
            });
        }

        return mGainModeCombo;
    }

    private JSlider getGainSlider()
    {
        if(mGainSlider == null)
        {
            mGainSlider = new JSlider(JSlider.HORIZONTAL, PlutoSdrTunerController.GAIN_MINIMUM,
                    PlutoSdrTunerController.GAIN_MAXIMUM, PlutoSdrTunerController.GAIN_DEFAULT);
            mGainSlider.setEnabled(false);
            mGainSlider.setMajorTickSpacing(5);
            mGainSlider.setPaintTicks(true);
            mGainSlider.addChangeListener(_ ->
            {
                int gain = mGainSlider.getValue();

                if(hasTuner() && !isLoading())
                {
                    try
                    {
                        getTuner().getController().setGain(gain);
                        save();
                    }
                    catch(Exception e)
                    {
                        mLog.error("Couldn't set PlutoSDR gain to:" + gain, e);
                        JOptionPane.showMessageDialog(mGainSlider, "Couldn't set gain value to " + gain);
                    }
                }

                updateGainControls();
            });
        }

        return mGainSlider;
    }

    private JLabel getGainValueLabel()
    {
        if(mGainValueLabel == null)
        {
            mGainValueLabel = new JLabel("0 dB");
            mGainValueLabel.setEnabled(false);
        }

        return mGainValueLabel;
    }

    @Override
    public void save()
    {
        if(hasConfiguration() && !isLoading())
        {
            getConfiguration().setFrequency(getFrequencyControl().getFrequency());
            getConfiguration().setMinimumFrequency(getMinimumFrequencyTextField().getFrequency());
            getConfiguration().setMaximumFrequency(getMaximumFrequencyTextField().getFrequency());
            double value = ((SpinnerNumberModel)getFrequencyCorrectionSpinner().getModel()).getNumber().doubleValue();
            getConfiguration().setFrequencyCorrection(value);
            getConfiguration().setAutoPPMCorrectionEnabled(getAutoPPMCheckBox().isSelected());

            if(getSampleRateCombo().getSelectedItem() instanceof PlutoSdrSampleRate rate)
            {
                getConfiguration().setSampleRate(rate.getRate());
            }

            if(getGainModeCombo().getSelectedItem() instanceof PlutoSdrGainMode mode)
            {
                getConfiguration().setGainMode(mode);
            }

            getConfiguration().setGain(getGainSlider().getValue());
            saveConfiguration();
        }
    }

    @Override
    public void setTunerLockState(boolean locked)
    {
        getFrequencyPanel().updateControls();
        getSampleRateCombo().setEnabled(!locked);
        getSampleRateCombo().setToolTipText(locked ? "Sample Rate is locked.  Disable decoding channels to unlock." :
                "Select a sample rate for the tuner");
    }

    private String getTunerInfo()
    {
        if(getDiscoveredTuner().getTunerStatus() == TunerStatus.ERROR)
        {
            return getDiscoveredTuner().getErrorMessage();
        }

        if(hasTuner())
        {
            PlutoSdrDeviceInformation info = getTuner().getController().getDeviceInfo();
            StringBuilder sb = new StringBuilder();
            sb.append("<html><h3>PlutoSDR Tuner</h3>");
            sb.append("<b>Model: </b>").append(info.model()).append("<br>");
            sb.append("<b>Radio Chip: </b>").append(info.chip()).append("<br>");
            sb.append("<b>Serial: </b>").append(info.serial()).append("<br>");
            sb.append("<b>Firmware: </b>").append(info.firmware()).append("<br>");
            sb.append("<b>Connection: </b>").append(info.uri()).append("<br>");
            sb.append("<b>iiod: </b>").append(info.server()).append("<br>");
            sb.append("<b>libiio: </b>").append(info.library()).append("<br>");
            sb.append("<b>Transmit: </b>never used - transmit LO ")
                    .append(getTuner().getController().isTransmitLoPoweredDown() ? "powered down" : "state unchanged")
                    .append("<br>");
            return sb.toString();
        }

        return null;
    }
}
