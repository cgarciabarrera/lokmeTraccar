/*
 * Copyright 2012 - 2013 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import java.util.Calendar;
import java.util.TimeZone;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.ServerManager;
import org.traccar.helper.Crc;
import org.traccar.helper.Log;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

public class Gt06ProtocolDecoder extends BaseProtocolDecoder {

    private Long deviceId;

    public Gt06ProtocolDecoder(ServerManager serverManager) {
        super(serverManager);
    }

    private String readImei(ChannelBuffer buf) {
        int b = buf.readUnsignedByte();
        StringBuilder imei = new StringBuilder();
        imei.append(b & 0x0F);
        for (int i = 0; i < 7; i++) {
            b = buf.readUnsignedByte();
            imei.append((b & 0xF0) >> 4);
            imei.append(b & 0x0F);
        }
        return imei.toString();
    }

    private static final int MSG_LOGIN = 0x01;
    private static final int MSG_GPS = 0x10;
    private static final int MSG_LBS = 0x11;
    private static final int MSG_GPS_LBS = 0x12;
    private static final int MSG_STATUS = 0x13;
    private static final int MSG_SATELLITE = 0x14;
    private static final int MSG_STRING = 0x15;
    private static final int MSG_GPS_LBS_STATUS = 0x16;
    private static final int MSG_LBS_PHONE = 0x17;
    private static final int MSG_LBS_EXTEND = 0x18;
    private static final int MSG_LBS_STATUS = 0x19;
    private static final int MSG_GPS_PHONE = 0x1A;

    private static void sendResponse(Channel channel, int type, int index) {
        if (channel != null) {
            ChannelBuffer response = ChannelBuffers.directBuffer(10);
            response.writeByte(0x78); response.writeByte(0x78); // header
            response.writeByte(0x05); // size
            response.writeByte(type);
            response.writeShort(index);
            response.writeShort(Crc.crc16Ccitt(response.toByteBuffer(2, 4)));
            response.writeByte(0x0D); response.writeByte(0x0A); // ending
            channel.write(response);
        }
    }

    /**
     * Decode message
     */
    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        buf.skipBytes(2); // header
        int length = buf.readByte(); // size
        int dataLength = length - 5;

        int type = buf.readUnsignedByte();
        
        if (type == MSG_LOGIN) {
            String imei = readImei(buf);
            try {
                deviceId = getDataManager().getDeviceByImei(imei).getId();
                buf.skipBytes(dataLength - 8);
                sendResponse(channel, type, buf.readUnsignedShort());
            } catch(Exception error) {
                Log.warning("Unknown device - " + imei + " creating new...");
                getDataManager().addDevice(imei);
                Log.warning("Created device - " + imei + " OK");
                deviceId = getDataManager().getDeviceByImei(imei).getId();
                buf.skipBytes(dataLength - 8);
                sendResponse(channel, type, buf.readUnsignedShort());


            }
        }

        else if (type == MSG_GPS ||
                 type == MSG_GPS_LBS ||
                 type == MSG_GPS_LBS_STATUS ||
                 type == MSG_GPS_PHONE) {

            // Create new position
            Position position = new Position();
            position.setDeviceId(deviceId);
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter("gt06");

            // Date and time
            Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            time.clear();
            time.set(Calendar.YEAR, 2000 + buf.readUnsignedByte());
            time.set(Calendar.MONTH, buf.readUnsignedByte() - 1);
            time.set(Calendar.DAY_OF_MONTH, buf.readUnsignedByte());
            time.set(Calendar.HOUR, buf.readUnsignedByte());
            time.set(Calendar.MINUTE, buf.readUnsignedByte());
            time.set(Calendar.SECOND, buf.readUnsignedByte());
            position.setTime(time.getTime());

            // GPS length and Satellites count
            int gpsLength = buf.readUnsignedByte();
            extendedInfo.set("satellites", gpsLength & 0xf);
            gpsLength >>= 4;

            // Latitude
            double latitude = buf.readUnsignedInt() / (60.0 * 30000.0);

            // Longitude
            double longitude = buf.readUnsignedInt() / (60.0 * 30000.0);

            // Speed
            position.setSpeed(buf.readUnsignedByte() * 0.539957);

            // Course and flags
            int union = buf.readUnsignedShort();
            position.setCourse((double) (union & 0x03FF));
            position.setValid((union & 0x1000) != 0);
            if ((union & 0x0400) == 0) latitude = -latitude;
            if ((union & 0x0800) != 0) longitude = -longitude;

            position.setLatitude(latitude);
            position.setLongitude(longitude);
            position.setAltitude(0.0);

            buf.skipBytes(gpsLength - 12); // skip reserved

            if (type == MSG_GPS_LBS || type == MSG_GPS_LBS_STATUS) {

                int lbsLength = 0;
                if (type == MSG_GPS_LBS_STATUS) {
                    lbsLength = buf.readUnsignedByte();
                }

                // Cell information
                extendedInfo.set("mcc", buf.readUnsignedShort());
                extendedInfo.set("mnc", buf.readUnsignedByte());
                extendedInfo.set("lac", buf.readUnsignedShort());
                extendedInfo.set("cell", buf.readUnsignedShort() << 8 + buf.readUnsignedByte());
                buf.skipBytes(lbsLength - 9);

                // Status
                if (type == MSG_GPS_LBS_STATUS) {
                    int flags = buf.readUnsignedByte(); // TODO parse flags
                    extendedInfo.set("alarm", true);

                    // Voltage
                    extendedInfo.set("power", buf.readUnsignedByte());

                    // GSM signal
                    extendedInfo.set("gsm", buf.readUnsignedByte());
                }
            }

            // Index
            if (buf.readableBytes() > 6) {
                buf.skipBytes(buf.readableBytes() - 6);
            }
            int index = buf.readUnsignedShort();
            extendedInfo.set("index", index);
            sendResponse(channel, type, index);

            position.setExtendedInfo(extendedInfo.toString());
            return position;
        }

        else {
            buf.skipBytes(dataLength);
            sendResponse(channel, type, buf.readUnsignedShort());
        }

        return null;
    }

}
