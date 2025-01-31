package de.schnippsche.solarreader.backend.utils;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.ModbusIOException;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.net.AbstractSerialConnection;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import de.schnippsche.solarreader.backend.configuration.ConfigDevice;
import de.schnippsche.solarreader.backend.configuration.ConfigDeviceField;
import de.schnippsche.solarreader.backend.fields.*;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ModbusWrapper
{
  private final AbstractModbusMaster modbusMaster;
  private final int unitId;
  private final String infoText;
  private final NumericHelper numericHelper;

  public ModbusWrapper(ConfigDevice configDevice)
  {
    unitId = configDevice.getIntParamOrDefault(ConfigDeviceField.DEVICE_ADDRESS, 1);
    numericHelper = new NumericHelper();
    if (configDevice.isParamEnabled(ConfigDeviceField.HF2211_ENABLED))
    {
      String ip = configDevice.getParamOrDefault(ConfigDeviceField.HF2211_IP, "127.0.0.1");
      int port = configDevice.getIntParamOrDefault(ConfigDeviceField.HF2211_PORT, 502);
      modbusMaster = new ModbusTCPMaster(ip, port);
      infoText = String.format("url %s, port %s", ip, port);
    } else if (configDevice.containsField(ConfigDeviceField.COM_PORT) && !configDevice.getParam(ConfigDeviceField.COM_PORT)
                                                                                      .isEmpty())
    {
      SerialParameters serialParameters = createParameter(configDevice);
      Logger.debug(serialParameters);
      modbusMaster = new ModbusSerialMaster(serialParameters);
      infoText = String.format("portName %s", serialParameters.getPortName());
    } else if (configDevice.containsField(ConfigDeviceField.DEVICE_IP) && configDevice.containsField(ConfigDeviceField.DEVICE_PORT))
    {
      String ip = configDevice.getParam(ConfigDeviceField.DEVICE_IP);
      int port = configDevice.getIntParamOrDefault(ConfigDeviceField.DEVICE_PORT, 502);
      modbusMaster = new ModbusTCPMaster(ip, port);
      infoText = String.format("url %s, port %s", ip, port);
    } else
    {
      modbusMaster = null;
      infoText = "unkown connection";
      Logger.error("incorrect configuration, missing {} or {}", ConfigDeviceField.HF2211_IP, ConfigDeviceField.COM_PORT);
    }
  }

  private SerialParameters createParameter(ConfigDevice configDevice)
  {
    SerialParameters serialParameters = new SerialParameters();
    serialParameters.setBaudRate(configDevice.getIntParamOrDefault(ConfigDeviceField.BAUDRATE, 9600));
    serialParameters.setDatabits(configDevice.getIntParamOrDefault(ConfigDeviceField.DATABITS, 8)); // 8
    serialParameters.setParity(configDevice.getIntParamOrDefault(ConfigDeviceField.PARITY, AbstractSerialConnection.NO_PARITY)); // NO PARITY
    serialParameters.setStopbits(configDevice.getIntParamOrDefault(ConfigDeviceField.STOPBITS, AbstractSerialConnection.ONE_STOP_BIT)); // 1
    serialParameters.setEcho(configDevice.isParamEnabled((ConfigDeviceField.ECHO)));
    serialParameters.setOpenDelay(configDevice.getIntParamOrDefault(ConfigDeviceField.OPENDELAY, AbstractSerialConnection.OPEN_DELAY));
    serialParameters.setFlowControlIn(configDevice.getIntParamOrDefault(ConfigDeviceField.FLOWCONTROLIN, AbstractSerialConnection.FLOW_CONTROL_DISABLED));
    serialParameters.setFlowControlOut(configDevice.getIntParamOrDefault(ConfigDeviceField.FLOWCONTROLOUT, AbstractSerialConnection.FLOW_CONTROL_DISABLED));
    serialParameters.setEncoding(configDevice.getParamOrDefault(ConfigDeviceField.ENCODING, Modbus.SERIAL_ENCODING_RTU));
    serialParameters.setPortName(configDevice.getParamOrDefault(ConfigDeviceField.COM_PORT, "ttyUSB0"));
    return serialParameters;
  }

  public AbstractModbusMaster getModbusMaster()
  {
    return modbusMaster;
  }

  public String getInfoText()
  {
    return infoText;
  }

  public List<ResultField> readFields(List<DeviceField> deviceFieldList)
  {
    List<ResultField> resultFields = new ArrayList<>();
    int errorCounter = 0;
    int maxErrors = deviceFieldList.size() / 10 + 1;
    for (DeviceField deviceField : deviceFieldList)
    {
      ResultField resultField = readField(deviceField);
      resultFields.add(resultField);
      if (resultField.getStatus() == ResultFieldStatus.READERROR)
      {
        errorCounter++;
      }
      if (errorCounter > maxErrors)
      {
        Logger.error("Too many read errors {}, maximum error counter = {}, stop reading", errorCounter, maxErrors);
        break;
      }
    }
    return resultFields;
  }

  public ResultField readField(DeviceField deviceField)
  {
    try
    {
      InputRegister[] register = readRegister(deviceField);
      if (register == null || register.length == 0)
      {
        throw new ModbusIOException("register is empty");
      }

      byte[] result = convertRegisterToByteArray(register);
      Object value = numericHelper.convertByteArray(result, deviceField.getType());
      return deviceField.createResultField(value);

    } catch (NumberFormatException e)
    {
      Logger.error("Can't convert bytes into type {}", deviceField.getType());
    } catch (Exception e)
    {
      Logger.error("Can't read device Field {}: {}", deviceField.getName(), e.getMessage());
    }
    return new ResultField(deviceField, ResultFieldStatus.READERROR, null);
  }

  public List<ResultField> readFieldBlocks(List<DeviceFieldBlock> deviceFieldBlockList)
  {
    List<ResultField> resultFields = new ArrayList<>();
    for (DeviceFieldBlock deviceFieldBlock : deviceFieldBlockList)
    {
      resultFields.addAll(readDeviceFieldBlock(deviceFieldBlock));
    }
    return resultFields;
  }

  public List<ResultField> readDeviceFieldBlock(DeviceFieldBlock deviceFieldBlock)
  {
    List<ResultField> resultFields = new ArrayList<>();
    Logger.debug(" readDeviceFieldBlock with {} devicefields", deviceFieldBlock.getOriginalDeviceFields().size());
    try
    {
      InputRegister[] register = readRegister(deviceFieldBlock.getBlockDeviceField());
      if (register == null || register.length == 0)
      {
        throw new ModbusIOException("registerblock is empty");
      }
      resultFields = deviceFieldBlock.convertToResultFields(register, numericHelper);
    } catch (Exception e)
    {
      Logger.error("Can't read device fieldblock : {}", e.getMessage());
    }
    Logger.debug("end of readDevicefieldBlock with {} resultfields", resultFields.size());
    return resultFields;
  }

  /**
   * read all device fields from device and store the result in resultfield list
   *
   * @param deviceFields List of device fields
   * @return List of resultfields
   */
  public List<ResultField> readAllFields(List<DeviceField> deviceFields)
  {
    if (modbusMaster == null || deviceFields == null)
    {
      return Collections.emptyList();
    }
    List<ResultField> resultFields = new ArrayList<>(deviceFields.size());
    try
    {
      Logger.info("try to connect to {}", getInfoText());
      modbusMaster.connect();
      Logger.info("connected");

      resultFields.addAll(readFields(deviceFields));
      // log for debugging
      for (ResultField field : resultFields)
      {
        Logger.debug(field);
      }
    } catch (Exception e)
    {
      Logger.error(e);
      return Collections.emptyList();

    } finally
    {
      modbusMaster.disconnect();
      Logger.debug("disconnected from {}", getInfoText());
    }

    return resultFields;
  }

  /**
   * read all device field blocks from device and store the result in resultfield list
   *
   * @param deviceFieldBlocks List of device fields
   * @return List of resultfields
   */
  public List<ResultField> readAllBlocks(List<DeviceFieldBlock> deviceFieldBlocks)
  {
    if (modbusMaster == null)
    {
      return Collections.emptyList();
    }
    List<ResultField> resultFields;
    try
    {
      Logger.info("try to connect to {}", getInfoText());
      modbusMaster.connect();
      Logger.info("connected");
      resultFields = readFieldBlocks(deviceFieldBlocks);
      // Ausgeben
      for (ResultField field : resultFields)
      {
        Logger.debug(field);
      }
    } catch (Exception e)
    {
      Logger.error(e);
      return Collections.emptyList();

    } finally
    {
      modbusMaster.disconnect();
      Logger.debug("disconnected from {}", getInfoText());
    }

    return resultFields;
  }

  /**
   * convert an array of InputRegister into a string and trims all whitespaces und zero chars
   *
   * @param register array of InputRegister
   * @return trimmed string
   */
  public String registerToString(InputRegister[] register)
  {
    byte[] bytes = convertRegisterToByteArray(register);
    return String.valueOf(numericHelper.convertByteArray(bytes, FieldType.STRING));
  }

  public byte[] convertRegisterToByteArray(InputRegister[] inputRegisters)
  {
    byte[] result = new byte[inputRegisters.length * 2];
    for (int i = 0; i < inputRegisters.length; i++)
    {
      byte[] source = inputRegisters[i].toBytes();
      result[i * 2] = source[0];
      result[i * 2 + 1] = source[1];
    }
    return result;
  }

  public InputRegister[] readRegister(int functionCode, int offset, int count) throws ModbusException
  {
    Logger.debug("read Register, Funktion code {}, offset {}, count {}", functionCode, offset, count);
    if (functionCode == 3)
    {
      return modbusMaster.readMultipleRegisters(unitId, offset, count);
    }
    if (functionCode == 4)
    {
      return modbusMaster.readInputRegisters(unitId, offset, count);
    }
    throw new ModbusException("unsupported function code " + functionCode);
  }

  public InputRegister[] readRegister(DeviceField deviceField) throws ModbusException
  {
    return readRegister(deviceField.getRegister(), deviceField.getOffset(), deviceField.getCount());
  }

  public String readRegisterAsString(int functionCode, int offset, int count) throws ModbusException
  {
    return registerToString(readRegister(functionCode, offset, count));
  }

}
