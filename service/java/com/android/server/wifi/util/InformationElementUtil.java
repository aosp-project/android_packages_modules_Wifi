/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wifi.util;

import static com.android.server.wifi.anqp.Constants.getInteger;

import android.net.wifi.ScanResult.InformationElement;
import android.util.Log;

import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.VenueNameElement;
import com.android.server.wifi.hotspot2.NetworkDetail;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;

public class InformationElementUtil {

    public static InformationElement[] parseInformationElements(byte[] bytes) {
        if (bytes == null) {
            return new InformationElement[0];
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        ArrayList<InformationElement> infoElements = new ArrayList<>();
        boolean found_ssid = false;
        while (data.remaining() > 1) {
            int eid = data.get() & Constants.BYTE_MASK;
            int elementLength = data.get() & Constants.BYTE_MASK;

            if (elementLength > data.remaining() || (eid == InformationElement.EID_SSID
                    && found_ssid)) {
                // APs often pad the data with bytes that happen to match that of the EID_SSID
                // marker.  This is not due to a known issue for APs to incorrectly send the SSID
                // name multiple times.
                break;
            }
            if (eid == InformationElement.EID_SSID) {
                found_ssid = true;
            }

            InformationElement ie = new InformationElement();
            ie.id = eid;
            ie.bytes = new byte[elementLength];
            data.get(ie.bytes);
            infoElements.add(ie);
        }
        return infoElements.toArray(new InformationElement[infoElements.size()]);
    }


    public static class BssLoad {
        public int stationCount = 0;
        public int channelUtilization = 0;
        public int capacity = 0;

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_BSS_LOAD) {
                throw new IllegalArgumentException("Element id is not BSS_LOAD, : " + ie.id);
            }
            if (ie.bytes.length != 5) {
                throw new IllegalArgumentException("BSS Load element length is not 5: "
                                                   + ie.bytes.length);
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            stationCount = data.getShort() & Constants.SHORT_MASK;
            channelUtilization = data.get() & Constants.BYTE_MASK;
            capacity = data.getShort() & Constants.SHORT_MASK;
        }
    }

    public static class HtOperation {
        public int secondChannelOffset = 0;

        public int getChannelWidth() {
            if (secondChannelOffset != 0) {
                return 1;
            } else {
                return 0;
            }
        }

        public int getCenterFreq0(int primaryFrequency) {
            //40 MHz
            if (secondChannelOffset != 0) {
                if (secondChannelOffset == 1) {
                    return primaryFrequency + 10;
                } else if (secondChannelOffset == 3) {
                    return primaryFrequency - 10;
                } else {
                    Log.e("HtOperation", "Error on secondChannelOffset: " + secondChannelOffset);
                    return 0;
                }
            } else {
                return 0;
            }
        }

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_HT_OPERATION) {
                throw new IllegalArgumentException("Element id is not HT_OPERATION, : " + ie.id);
            }
            secondChannelOffset = ie.bytes[1] & 0x3;
        }
    }

    public static class VhtOperation {
        public int channelMode = 0;
        public int centerFreqIndex1 = 0;
        public int centerFreqIndex2 = 0;

        public boolean isValid() {
            return channelMode != 0;
        }

        public int getChannelWidth() {
            return channelMode + 1;
        }

        public int getCenterFreq0() {
            //convert channel index to frequency in MHz, channel 36 is 5180MHz
            return (centerFreqIndex1 - 36) * 5 + 5180;
        }

        public int getCenterFreq1() {
            if (channelMode > 1) { //160MHz
                return (centerFreqIndex2 - 36) * 5 + 5180;
            } else {
                return 0;
            }
        }

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_VHT_OPERATION) {
                throw new IllegalArgumentException("Element id is not VHT_OPERATION, : " + ie.id);
            }
            channelMode = ie.bytes[0] & Constants.BYTE_MASK;
            centerFreqIndex1 = ie.bytes[1] & Constants.BYTE_MASK;
            centerFreqIndex2 = ie.bytes[2] & Constants.BYTE_MASK;
        }
    }

    public static class Interworking {
        public NetworkDetail.Ant ant = null;
        public boolean internet = false;
        public VenueNameElement.VenueGroup venueGroup = null;
        public VenueNameElement.VenueType venueType = null;
        public long hessid = 0L;

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_INTERWORKING) {
                throw new IllegalArgumentException("Element id is not INTERWORKING, : " + ie.id);
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            int anOptions = data.get() & Constants.BYTE_MASK;
            ant = NetworkDetail.Ant.values()[anOptions & 0x0f];
            internet = (anOptions & 0x10) != 0;
            // Len 1 none, 3 venue-info, 7 HESSID, 9 venue-info & HESSID
            if (ie.bytes.length == 3 || ie.bytes.length == 9) {
                try {
                    ByteBuffer vinfo = data.duplicate();
                    vinfo.limit(vinfo.position() + 2);
                    VenueNameElement vne = new VenueNameElement(
                            Constants.ANQPElementType.ANQPVenueName, vinfo);
                    venueGroup = vne.getGroup();
                    venueType = vne.getType();
                } catch (ProtocolException pe) {
                    /*Cannot happen*/
                }
            } else if (ie.bytes.length != 1 && ie.bytes.length != 7) {
                throw new IllegalArgumentException("Bad Interworking element length: "
                        + ie.bytes.length);
            }
            if (ie.bytes.length == 7 || ie.bytes.length == 9) {
                hessid = getInteger(data, ByteOrder.BIG_ENDIAN, 6);
            }
        }
    }

    public static class RoamingConsortium {
        public int anqpOICount = 0;
        public long[] roamingConsortiums = null;

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_ROAMING_CONSORTIUM) {
                throw new IllegalArgumentException("Element id is not ROAMING_CONSORTIUM, : "
                        + ie.id);
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            anqpOICount = data.get() & Constants.BYTE_MASK;

            int oi12Length = data.get() & Constants.BYTE_MASK;
            int oi1Length = oi12Length & Constants.NIBBLE_MASK;
            int oi2Length = (oi12Length >>> 4) & Constants.NIBBLE_MASK;
            int oi3Length = ie.bytes.length - 2 - oi1Length - oi2Length;
            int oiCount = 0;
            if (oi1Length > 0) {
                oiCount++;
                if (oi2Length > 0) {
                    oiCount++;
                    if (oi3Length > 0) {
                        oiCount++;
                    }
                }
            }
            roamingConsortiums = new long[oiCount];
            if (oi1Length > 0 && roamingConsortiums.length > 0) {
                roamingConsortiums[0] =
                        getInteger(data, ByteOrder.BIG_ENDIAN, oi1Length);
            }
            if (oi2Length > 0 && roamingConsortiums.length > 1) {
                roamingConsortiums[1] =
                        getInteger(data, ByteOrder.BIG_ENDIAN, oi2Length);
            }
            if (oi3Length > 0 && roamingConsortiums.length > 2) {
                roamingConsortiums[2] =
                        getInteger(data, ByteOrder.BIG_ENDIAN, oi3Length);
            }
        }
    }

    public static class Vsa {
        private static final int ANQP_DOMID_BIT = 0x04;

        public NetworkDetail.HSRelease hsRelease = null;
        public int anqpDomainID = 0;    // No domain ID treated the same as a 0; unique info per AP.

        public void from(InformationElement ie) {
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (ie.bytes.length >= 5 && data.getInt() == Constants.HS20_FRAME_PREFIX) {
                int hsConf = data.get() & Constants.BYTE_MASK;
                switch ((hsConf >> 4) & Constants.NIBBLE_MASK) {
                    case 0:
                        hsRelease = NetworkDetail.HSRelease.R1;
                        break;
                    case 1:
                        hsRelease = NetworkDetail.HSRelease.R2;
                        break;
                    default:
                        hsRelease = NetworkDetail.HSRelease.Unknown;
                        break;
                }
                if ((hsConf & ANQP_DOMID_BIT) != 0) {
                    if (ie.bytes.length < 7) {
                        throw new IllegalArgumentException(
                                "HS20 indication element too short: " + ie.bytes.length);
                    }
                    anqpDomainID = data.getShort() & Constants.SHORT_MASK;
                }
            }
        }
    }

    public static class ExtendedCapabilities {
        private static final int RTT_RESP_ENABLE_BIT = 70;
        private static final long SSID_UTF8_BIT = 0x0001000000000000L;

        public Long extendedCapabilities = null;
        public boolean is80211McRTTResponder = false;

        public ExtendedCapabilities() {
        }

        public ExtendedCapabilities(ExtendedCapabilities other) {
            extendedCapabilities = other.extendedCapabilities;
            is80211McRTTResponder = other.is80211McRTTResponder;
        }

        public boolean isStrictUtf8() {
            return extendedCapabilities != null && (extendedCapabilities & SSID_UTF8_BIT) != 0;
        }

        public void from(InformationElement ie) {
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            extendedCapabilities =
                    Constants.getInteger(data, ByteOrder.LITTLE_ENDIAN, ie.bytes.length);

            int index = RTT_RESP_ENABLE_BIT / 8;
            byte offset = RTT_RESP_ENABLE_BIT % 8;
            if (ie.bytes.length < index + 1) {
                is80211McRTTResponder = false;
            } else {
                is80211McRTTResponder = (ie.bytes[index] & ((byte) 0x1 << offset)) != 0;
            }
        }
    }

    /**
     * parse beacon to build the capabilities
     *
     * This class is used to build the capabilities string of the scan results coming
     * from HAL. It parses the ieee beacon's capability field, WPA and RSNE IE as per spec,
     * and builds the ScanResult.capabilities String in a way that mirrors the values returned
     * by wpa_supplicant.
     */
    public static class Capabilities {
        private static final int CAP_PRIVACY_BIT_OFFSET = 4;

        private static final int WPA_VENDOR_OUI_TYPE_ONE = 0x01f25000;
        private static final short WPA_VENDOR_OUI_VERSION = 0x0001;
        private static final short RSNE_VERSION = 0x0001;

        private static final int WPA_AKM_EAP = 0x01f25000;
        private static final int WPA_AKM_PSK = 0x02f25000;

        private static final int WPA2_AKM_EAP = 0x01ac0f00;
        private static final int WPA2_AKM_PSK = 0x02ac0f00;
        private static final int WPA2_AKM_FT_EAP = 0x03ac0f00;
        private static final int WPA2_AKM_FT_PSK = 0x04ac0f00;
        private static final int WPA2_AKM_EAP_SHA256 = 0x05ac0f00;
        private static final int WPA2_AKM_PSK_SHA256 = 0x06ac0f00;

        public Capabilities() {
        }

        // RSNE format (size unit: byte)
        //
        // | Element ID | Length | Version | Group Data Cipher Suite |
        //      1           1         2                 4
        // | Pairwise Cipher Suite Count | Pairwise Cipher Suite List |
        //              2                            4 * m
        // | AKM Suite Count | AKM Suite List | RSN Capabilities |
        //          2               4 * n               2
        // | PMKID Count | PMKID List | Group Management Cipher Suite |
        //        2          16 * s                 4
        //
        // Note: InformationElement.bytes has 'Element ID' and 'Length'
        //       stripped off already
        private static String parseRsnElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            try {
                // version
                if (buf.getShort() != RSNE_VERSION) {
                    // incorrect version
                    return null;
                }

                // group data cipher suite
                // here we simply advance the buffer position
                buf.getInt();

                // found the RSNE IE, hence start building the capability string
                String security = "[WPA2";

                // pairwise cipher suite count
                short cipherCount = buf.getShort();

                // pairwise cipher suite list
                for (int i = 0; i < cipherCount; i++) {
                    // here we simply advance the buffer position
                    buf.getInt();
                }

                // AKM
                // AKM suite count
                short akmCount = buf.getShort();

                // parse AKM suite list
                if (akmCount == 0) {
                    security += "-EAP"; //default AKM
                }
                boolean found = false;
                for (int i = 0; i < akmCount; i++) {
                    int akm = buf.getInt();
                    switch (akm) {
                        case WPA2_AKM_EAP:
                            security += (found ? "+" : "-") + "EAP";
                            found = true;
                            break;
                        case WPA2_AKM_PSK:
                            security += (found ? "+" : "-") + "PSK";
                            found = true;
                            break;
                        case WPA2_AKM_FT_EAP:
                            security += (found ? "+" : "-") + "FT/EAP";
                            found = true;
                            break;
                        case WPA2_AKM_FT_PSK:
                            security += (found ? "+" : "-") + "FT/PSK";
                            found = true;
                            break;
                        case WPA2_AKM_EAP_SHA256:
                            security += (found ? "+" : "-") + "EAP-SHA256";
                            found = true;
                            break;
                        case WPA2_AKM_PSK_SHA256:
                            security += (found ? "+" : "-") + "PSK-SHA256";
                            found = true;
                            break;
                        default:
                            // do nothing
                            break;
                    }
                }

                // we parsed what we want at this point
                security += "]";
                return security;
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse RSNE, buffer underflow");
                return null;
            }
        }

        private static boolean isWpaOneElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            try {
                // WPA OUI and type
                return (buf.getInt() == WPA_VENDOR_OUI_TYPE_ONE);
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse VSA IE, buffer underflow");
                return false;
            }
        }

        // WPA type 1 format (size unit: byte)
        //
        // | Element ID | Length | OUI | Type | Version |
        //      1           1       3     1        2
        // | Pairwise Cipher Suite Count | Pairwise Cipher Suite List |
        //              2                            4 * m
        // | AKM Suite Count | AKM Suite List |
        //          2               4 * n
        //
        // Note: InformationElement.bytes has 'Element ID' and 'Length'
        //       stripped off already
        //
        private static String parseWpaOneElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            try {
                // skip WPA OUI and type parsing. isWpaOneElement() should have
                // been called for verification before we reach here.
                buf.getInt();

                // start building the string
                String security = "[WPA";

                // version
                if (buf.getShort() != WPA_VENDOR_OUI_VERSION)  {
                    // incorrect version
                    return null;
                }

                // group data cipher suite
                // here we simply advance buffer position
                buf.getInt();

                // pairwise cipher suite count
                short cipherCount = buf.getShort();

                // pairwise chipher suite list
                for (int i = 0; i < cipherCount; i++) {
                    // here we simply advance buffer position
                    buf.getInt();
                }

                // AKM
                // AKM suite count
                short akmCount = buf.getShort();

                // AKM suite list
                if (akmCount == 0) {
                    security += "-EAP"; //default AKM
                }
                boolean found = false;
                for (int i = 0; i < akmCount; i++) {
                    int akm = buf.getInt();
                    switch (akm) {
                        case WPA_AKM_EAP:
                            security += (found ? "+" : "-") + "EAP";
                            found = true;
                            break;
                        case WPA_AKM_PSK:
                            security += (found ? "+" : "-") + "PSK";
                            found = true;
                            break;
                        default:
                            // do nothing
                            break;
                    }
                }

                // we parsed what we want at this point
                security += "]";
                return security;
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse type 1 WPA, buffer underflow");
                return null;
            }
        }

        /**
         * Parse the Information Element and the 16-bit Capability Information field
         * to build the ScanResult.capabilities String.
         *
         * @param ies -- Information Element array
         * @param beaconCap -- 16-bit Beacon Capability Information field
         * @return security string that mirrors what wpa_supplicant generates
         */
        public static String buildCapabilities(InformationElement[] ies, BitSet beaconCap) {
            String capabilities = "";
            boolean rsneFound = false;
            boolean wpaFound = false;

            if (ies == null || beaconCap == null) {
                return capabilities;
            }

            boolean privacy = beaconCap.get(CAP_PRIVACY_BIT_OFFSET);

            for (InformationElement ie : ies) {
                if (ie.id == InformationElement.EID_RSN) {
                    rsneFound = true;
                    capabilities += parseRsnElement(ie);
                }

                if (ie.id == InformationElement.EID_VSA) {
                    if (isWpaOneElement(ie)) {
                        wpaFound = true;
                        capabilities += parseWpaOneElement(ie);
                    }
                }
            }

            if (!rsneFound && !wpaFound && privacy) {
                //private Beacon without an RSNE or WPA IE, hence WEP0
                capabilities += "[WEP]";
            }

            return capabilities;
        }
    }
}
