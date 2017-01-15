package com.eniacdevelopment.EniacHome.Serial;

import com.eniacdevelopment.EniacHome.Business.Contracts.Utils.AlarmCalculator;
import com.eniacdevelopment.EniacHome.DataModel.Configuration.SerialConfiguration;
import com.eniacdevelopment.EniacHome.DataModel.Sensor.SensorStatus;
import com.eniacdevelopment.EniacHome.Repositories.Shared.ConfigurationRepository;
import com.eniacdevelopment.EniacHome.Repositories.Shared.SensorStatusRepository;
import com.eniacdevelopment.EniacHome.Serial.EventListenerObservers.EventListenerObserver;
import com.eniacdevelopment.EniacHome.Serial.Objects.SensorNotification;
import com.eniacdevelopment.EniacHome.Serial.PacketParsers.PacketParser;
import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.fazecast.jSerialComm.SerialPortPacketListener;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by larsg on 9/16/2016.
 * A DataListener that can be plugged into JSerialComm.SerialPort for packet handling.
 */
public class SerialSubject implements SerialPortPacketListener {
    private final List<EventListenerObserver> eventListenerObservers =
            Collections.synchronizedList(new ArrayList<EventListenerObserver>());

    private final ConfigurationRepository<SerialConfiguration> configurationRepository;
    private final SensorStatusRepository sensorStatusRepository;
    private final AlarmCalculator alarmCalculator;
    private final PacketParser packetParser;
    private SerialPort serialPortInstance;

    @Inject
    public SerialSubject(ConfigurationRepository<SerialConfiguration> configurationRepository, SensorStatusRepository sensorStatusRepository, AlarmCalculator alarmCalculator, PacketParser packetParser) {
        this.configurationRepository = configurationRepository;
        this.sensorStatusRepository = sensorStatusRepository;
        this.alarmCalculator = alarmCalculator;
        this.packetParser = packetParser;
    }

    public void addObserver(EventListenerObserver packetListenerObserver) {
        synchronized (this.eventListenerObservers) {
            this.eventListenerObservers.add(packetListenerObserver);
        }
    }

    public void initializeSerial(){
        SerialConfiguration serialConfiguration = this.configurationRepository.getActiveConfiguration();
        //Default Configuration
        if(serialConfiguration == null) {
            serialConfiguration = new SerialConfiguration() {{
                BaudRate = 9600;
                DataBits = 8;
                Parity = 0;
                StopBits = 1;
                PortDescriptor = "COM3";
            }};
        }

        if(this.serialPortInstance != null) {
            this.serialPortInstance.closePort();
            this.serialPortInstance = null;
        }
        this.serialPortInstance = SerialPort.getCommPort(serialConfiguration.PortDescriptor);
        this.serialPortInstance.addDataListener(this);
        this.serialPortInstance.setComPortParameters(
                serialConfiguration.BaudRate,
                serialConfiguration.DataBits,
                serialConfiguration.StopBits,
                serialConfiguration.Parity);
        this.serialPortInstance.openPort();
    }

    public void triggerSensorEvent(String sensorId) {
        SensorStatus sensorStatus = this.sensorStatusRepository.get(sensorId);

        if (sensorStatus.Alarmed) {
            this.serialPortInstance.writeBytes(new byte[]{0}, 1);
        } else {
            this.serialPortInstance.writeBytes(new byte[]{1}, 1);
        }

        synchronized (this.eventListenerObservers) {
            for (EventListenerObserver eventListenerObserver : this.eventListenerObservers) {
                eventListenerObserver.eventNotify(sensorId);
            }
        }
    }

    @Override
    public int getPacketSize() {
        return 2;
    }

    @Override
    public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
    }

    @Override
    public void serialEvent(SerialPortEvent serialPortEvent) {
        byte[] packet = serialPortEvent.getReceivedData();

        // Create SensorNotification for all observers
        SensorNotification notification = this.packetParser.parse(packet, serialPortEvent);
        Boolean alarmed = this.alarmCalculator.calculate(notification);

        SensorStatus sensorStatus = new SensorStatus() {{
            Value = notification.Value;
            Date = notification.Date;
            Alarmed = alarmed;
        }};
        this.sensorStatusRepository.put(notification.Id, sensorStatus);

        this.triggerSensorEvent(notification.Id);
    }
}
