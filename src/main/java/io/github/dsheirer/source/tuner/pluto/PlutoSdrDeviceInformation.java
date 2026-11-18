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
 * PlutoSDR device details for display.
 * @param serial board serial number
 * @param model board model, such as: Analog Devices PlutoSDR Rev.C (Z7010-AD9363A)
 * @param chip radio chip model reported by the driver, such as ad9361 or ad9364
 * @param firmware version
 * @param uri libiio context URI, such as usb:1.5.5 or ip:192.168.2.1
 * @param server version of the iiod server on the board
 * @param library version of libiio on this computer
 */
public record PlutoSdrDeviceInformation(String serial, String model, String chip, String firmware, String uri,
                                        String server, String library)
{
}
