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
package org.traccar.model;

import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.traccar.helper.AdvancedConnection;
import org.traccar.helper.DriverDelegate;
import org.traccar.helper.Log;
import org.traccar.helper.NamedParameterStatement;
import org.xml.sax.InputSource;

import java.net.URISyntaxException;
import java.net.URL;
import java.net.URI;
import java.net.URLEncoder;
import java.net.MalformedURLException; 
import java.io.UnsupportedEncodingException;



/**
 * Database abstraction class
 */
public class DatabaseDataManager implements DataManager {

    public DatabaseDataManager(Properties properties) throws Exception {
        initDatabase(properties);
    }

    /**
     * Database statements
     */
    private NamedParameterStatement queryGetDevices;
    private NamedParameterStatement queryAddPosition;
    private NamedParameterStatement queryUpdateLatestPosition;
    private NamedParameterStatement queryAddDevice;

    /**
     * Initialize database
     */
    private void initDatabase(Properties properties) throws Exception {

        // Load driver
        String driver = properties.getProperty("database.driver");
        if (driver != null) {
            String driverFile = properties.getProperty("database.driverFile");

            if (driverFile != null) {
                URL url = new URL("jar:file:" + new File(driverFile).getAbsolutePath() + "!/");
                URLClassLoader cl = new URLClassLoader(new URL[] { url });
                Driver d = (Driver) Class.forName(driver, true, cl).newInstance();
                DriverManager.registerDriver(new DriverDelegate(d));
            } else {
                Class.forName(driver);
            }
        }

        // Refresh delay
        String refreshDelay = properties.getProperty("database.refreshDelay");
        if (refreshDelay != null) {
            devicesRefreshDelay = Long.valueOf(refreshDelay) * 1000;
        } else {
            devicesRefreshDelay = new Long(300) * 1000; // Magic number
        }

        // Connect database
        String url = properties.getProperty("database.url");
        String user = properties.getProperty("database.user");
        String password = properties.getProperty("database.password");
        AdvancedConnection connection = new AdvancedConnection(url, user, password);

        // Load statements from configuration
        String query;

        query = properties.getProperty("database.selectDevice");
        if (query != null) {
            queryGetDevices = new NamedParameterStatement(connection, query);
        }

        query = properties.getProperty("database.insertPosition");
        if (query != null) {
            queryAddPosition = new NamedParameterStatement(connection, query);
        }

        query = properties.getProperty("database.updateLatestPosition");
        if (query != null) {
            queryUpdateLatestPosition = new NamedParameterStatement(connection, query);
        }

        //add device
        query = properties.getProperty("database.addDevice");
        if (query != null) {
            queryAddDevice = new NamedParameterStatement(connection, query);
        }

    }

    @Override
    public synchronized List<Device> getDevices() throws SQLException {

        List<Device> deviceList = new LinkedList<Device>();

        if (queryGetDevices != null) {
            queryGetDevices.prepare();
            ResultSet result = queryGetDevices.executeQuery();
            while (result.next()) {
                Device device = new Device();
                device.setId(result.getLong("id"));
                device.setImei(result.getString("imei"));
                deviceList.add(device);
            }
        }




        return deviceList;
    }

    /**
     * Devices cache
     */
    private Map<String, Device> devices;
    private Calendar devicesLastUpdate;
    private Long devicesRefreshDelay;

    @Override
    public Device getDeviceByImei(String imei) throws SQLException {

        if ((devices == null) || (Calendar.getInstance().getTimeInMillis() - devicesLastUpdate.getTimeInMillis() > devicesRefreshDelay)) {
            List<Device> list = getDevices();
            devices = new HashMap<String, Device>();
            for (Device device: list) {
                devices.put(device.getImei(), device);
            }
            devicesLastUpdate = Calendar.getInstance();
        }

        return devices.get(imei);
    }

    @Override
    public synchronized Long addPosition(Position position) throws SQLException {

        if (queryAddPosition != null) {
            queryAddPosition.prepare(Statement.RETURN_GENERATED_KEYS);

            queryAddPosition.setLong("device_id", position.getDeviceId());
            queryAddPosition.setTimestamp("time", position.getTime());
            queryAddPosition.setBoolean("valid", position.getValid());
            queryAddPosition.setDouble("altitude", position.getAltitude());
            queryAddPosition.setDouble("latitude", position.getLatitude());
            queryAddPosition.setDouble("longitude", position.getLongitude());
            queryAddPosition.setDouble("speed", position.getSpeed());
            queryAddPosition.setDouble("course", position.getCourse());
            queryAddPosition.setString("address", position.getAddress());
            queryAddPosition.setString("extended_info", position.getExtendedInfo());
            
            // DELME: Temporary compatibility support
            XPath xpath = XPathFactory.newInstance().newXPath();
            try {
                InputSource source = new InputSource(new StringReader(position.getExtendedInfo()));
                String index = xpath.evaluate("/info/index", source);
                if (!index.isEmpty()) {
                    queryAddPosition.setLong("id", Long.valueOf(index));
                } else {
                    queryAddPosition.setLong("id", null);
                }
                source = new InputSource(new StringReader(position.getExtendedInfo()));
                String power = xpath.evaluate("/info/power", source);
                if (!power.isEmpty()) {
                    queryAddPosition.setDouble("power", Double.valueOf(power));
                } else {
                    queryAddPosition.setLong("power", null);
                }
            } catch (XPathExpressionException e) {
                Log.warning("Error in XML: " + position.getExtendedInfo(), e);
                queryAddPosition.setLong("id", null);
                queryAddPosition.setLong("power", null);
            }

            //queryAddPosition.executeUpdate();

            //aqui mandar al webservice.



            //String urlParameters = "&latitude=" + position.getLatitude() + "&longitude=" + position.getLongitude() + "&imei=" + position.getDeviceId() + "&accuracy=0";
            //String url = "http://www.lokusapp.com/points/manual/?speed=" + position.getSpeed() + "&altitude=" + position.getAltitude() +  "&course=" + position.getCourse() +  "&latitude=" + position.getLatitude() + "&longitude=" + position.getLongitude() + "&imei=" + position.getDeviceIMEI()  + "&accuracy=0";
            String htt = "http";
            String dom = "www.lokusapp.com";
            String dire = "/points/manual";
            String params_send = "latitude=" + position.getLatitude() ;
            params_send = params_send + "&longitude=" + position.getLongitude(); 
            params_send = params_send + "&imei=" + position.getDeviceIMEI()  ;
            params_send = params_send + "&altitude=" + position.getAltitude() ;
            params_send = params_send + "&course=" + position.getCourse() ;
            params_send = params_send + "&extended=" + position.getExtendedInfo();  
            params_send = params_send + "&speed=" + position.getSpeed()   ;
            params_send = params_send + "&datetime=" + String.valueOf(position.getTime().getTime() / 1000);  
            params_send = params_send + "&accuracy=0";
            
            
            String htt2 = "http";
            String dom2 = "new.lokusapp.com";
            String dire2 = "/devices/new_point";
            String params_send2 = "latitude=" + position.getLatitude() ;
            params_send2 = params_send2 + "&longitude=" + position.getLongitude(); 
            params_send2 = params_send2 + "&imei=" + position.getDeviceIMEI()  ;
            params_send2 = params_send2 + "&altitude=" + position.getAltitude() ;
            params_send2 = params_send2 + "&course=" + position.getCourse() ;
            params_send2 = params_send2 + "&extended=" + position.getExtendedInfo();  
            params_send2 = params_send2 + "&speed=" + position.getSpeed()   ;
            params_send2 = params_send2 + "&datetime=" + String.valueOf(position.getTime().getTime() / 1000);  
            params_send2 = params_send2 + "&accuracy=0"; 
            
            
            Log.info("POSITION alt: " + position.getAltitude().toString());
            Log.info("POSITION ext: " + position.getExtendedInfo().toString());
            Log.info("POSITION course: " + position.getCourse().toString());
            Log.info("POSITION speed: " + position.getSpeed().toString());
            
            URI uri =null;
            String request =null;
            
            
			try {
				uri = new URI(
				        htt, 
				        dom, 
				        dire,
				        params_send,
				        null);
			} catch (URISyntaxException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
            request = uri.toASCIIString();
            
            try {
            	Log.info("YO mando :  " + request);
                sendGet(request);
                Log.info("OK al envio de url:  "+ request);
            } catch (Exception e) {
            	Log.error("ha dado error al mandar la URL: " + request);
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }

            // al nuevo 
            uri =null;
            request =null;
			try {
				uri = new URI(
				        htt2, 
				        dom2, 
				        dire2,
				        params_send2,
				        null);
			} catch (URISyntaxException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
            request = uri.toASCIIString();
            
            try {
            	Log.info("YO mando :  " + request);
                sendGet(request);
                Log.info("OK al envio de url:  "+ request);
            } catch (Exception e) {
            	Log.error("ha dado error al mandar la URL: " + request);
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }

            ResultSet result = queryAddPosition.getGeneratedKeys();
            if (result != null && result.next()) {
                return result.getLong(1);
            }
        }

        return null;
    }


    private void sendGet(String url) throws Exception {

        //String url = "http://www.google.com/search?q=mkyong";

        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();

        // optional default is GET
        con.setRequestMethod("GET");

        //add request header
        con.setRequestProperty("User-Agent", "123");

        int responseCode = con.getResponseCode();
        //System.out.println("\nSending 'GET' request to URL : " + url);
        //System.out.println("Response Code : " + responseCode);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        //print result
        //System.out.println(response.toString());

    }


    //@Override
    public synchronized Device addDevice(String imei) throws SQLException {

        if (queryAddDevice != null) {
            queryAddDevice.prepare(Statement.RETURN_GENERATED_KEYS);

            queryAddDevice.setString("imei", imei);
            queryAddDevice.setString("name", "Nombre");


            queryAddDevice.executeUpdate();

            ResultSet result = queryAddDevice.getGeneratedKeys();
            if (result != null && result.next()) {
                //return result.getLong(1);
            }
        }

        return null;
    }



    @Override
    public void updateLatestPosition(Long deviceId, Long positionId) throws SQLException {
        
        if (queryUpdateLatestPosition != null) {
            queryUpdateLatestPosition.prepare();

            queryUpdateLatestPosition.setLong("device_id", deviceId);
            queryUpdateLatestPosition.setLong("id", positionId);

            queryUpdateLatestPosition.executeUpdate();
        }
    }

}
