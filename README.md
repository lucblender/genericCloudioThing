#  Generic cloud.iO Thing, *adapted version of MQTT Generic Thing Binding for cloud.iO*

This binding is used to communicate with [cloud.iO](http://cloudio.hevs.ch) IoT Solution. The main documentation of this binding is taken back from the MQTT Generic Thing Binding.

> MQTT is a machine-to-machine (M2M)/"Internet of Things" connectivity protocol. It was designed as an extremely lightweight publish/subscribe messaging transport.

This binding allows to link cloud.iO Endpoint to Things and cloud.iO Attributes to channels.

## Supported Things

### Generic cloud.iO Thing

A generic cloud.iO Thing has two parameters as configuration linked to the cloud.iO object model. You can then add channel to it to go deeper in the cloud.iO Object Model.

### Common Generic Cloud.iO Thing Configuration Parameters

* __cloudioEndpoint__: Cloud.iO Endpoint used for the MQTT publishing (from the cloud.iO object model)
* __cloudioNode__: Cloud.iO Node used for the MQTT publishing (from the cloud.iO object model)

#### Supported Channels

* **string**: This channel can show the received text on the given topic and can send text to a given topic.
* **number**: This channel can show the received number on the given topic and can send a number to a given topic. It can have a min, max and step values.
* **dimmer**: This channel handles numeric values as percentages. It can have min, max and step values.
* **contact**: This channel represents a open/close state of a given topic.
* **switch**: This channel represents a on/off state of a given topic and can send an on/off value to a given topic.
* **colorRGB**: This channel handles color values in RGB format.
* **colorHSB**: This channel handles color values in HSB format.
* **location**: This channel handles a location.    
* **datetime**: This channel handles date/time values.

#### Still in Developpement Channels  (Data format not compatible with cloud.iO)
* **image**: This channel handles binary images in common java supported formats (bmp,jpg,png). * 
* **rollershutter**: This channel is for rollershutters.  

## Thing and Channel Configuration 

All things require a configured broker.

### Common Channel Configuration Parameters

* __cloudioObject__: Cloud.iO Object used for the MQTT publishing (from the cloud.iO object model)
* __cloudioAttribute__: Cloud.iO attribute used for the MQTT publishing (from the cloud.iO object model)
* __stateTopic__: The MQTT topic that represents the state of the thing. This can be empty, the thing channel will be a state-less trigger then. You can use a wildcard topic like "sensors/+/event" to retrieve state from multiple MQTT topics. 
* __transformationPattern__: An optional transformation pattern like [JSONPath](http://goessner.net/articles/JsonPath/index.html#e2) that is applied to all incoming MQTT values.
* __transformationPatternOut__: An optional transformation pattern like [JSONPath](http://goessner.net/articles/JsonPath/index.html#e2) that is applied before publishing a value to MQTT.
* __formatBeforePublish__: Format a value before it is published to the MQTT broker. The default is to just pass the channel/item state. If you want to apply a prefix, say "MYCOLOR,", you would use "MYCOLOR,%s". If you want to adjust the precision of a number to for example 4 digits, you would use "%.4f".
* __postCommand__: If `true`, the received MQTT value will not only update the state of linked items, but command it.
  The default is `false`.
  You usually need this to be `true` if your item is also linked to another channel, say a KNX actor, and you want a received MQTT payload to command that KNX actor. 
* __retained__: The value will be published to the command topic as retained message. A retained value stays on the broker and can even be seen by MQTT clients that are subscribing at a later point in time. 
* __trigger__: If `true`, the state topic will not update a state, but trigger a channel instead.

### Channel Type "string"

* __allowedStates__: An optional comma separated list of allowed states. Example: "ONE,TWO,THREE"

You can connect this channel to a String item.

### Channel Type "number"

* __min__: An optional minimum value.
* __max__: An optional maximum value.
* __step__: For decrease, increase commands the step needs to be known

A decimal value (like 0.2) is send to the MQTT topic if the number has a fractional part.
If you always require an integer, please use the formatter.

You can connect this channel to a Number item.

### Channel Type "dimmer"
 
* __on__: A optional string (like "ON"/"Open") that is recognized as minimum.
* __off__: A optional string (like "OFF"/"Close") that is recognized as maximum.
* __min__: A required minimum value.
* __max__: A required maximum value.
* __step__: For decrease, increase commands the step needs to be known

The value is internally stored as a percentage for a value between **min** and **max**.

The channel will publish a value between `min` and `max`.

You can connect this channel to a Rollershutter or Dimmer item.

### Channel Type "contact", "switch"

* __on__: A optional number (like 1, 10) or a string (like "ON"/"Open") that is recognized as on/open state.
* __off__: A optional number (like 0, -10) or a string (like "OFF"/"Close") that is recognized as off/closed state.

The contact channel by default recognizes `"OPEN"` and `"CLOSED"`. You can connect this channel to a Contact item.
The switch channel by default recognizes `"ON"` and `"OFF"`. You can connect this channel to a Switch item.

If **on** and **off** are not configured it publishes the strings mentioned before respectively.

You can connect this channel to a Contact or Switch item.

### Channel Type "colorRGB", "colorHSB"

* __on__: An optional string (like "BRIGHT") that is recognized as on state. (ON will always be recognized.)
* __off__: An optional string (like "DARK") that is recognized as off state. (OFF will always be recognized.)
* __onBrightness__: If you connect this channel to a Switch item and turn it on,
color and saturation are preserved from the last state, but
the brightness will be set to this configured initial brightness (default: 10%).

You can connect this channel to a Color, Dimmer and Switch item.

This channel will publish the color as comma separated list to the MQTT broker,
e.g. "112,54,123" for an RGB channel (0-255 per component) and "360,100,100" for a HSB channel (0-359 for hue and 0-100 for saturation and brightness).

The channel expects values on the corresponding MQTT topic to be in this format as well.

### Channel Type "location"

You can connect this channel to a Location item.

The channel will publish the location as comma separated list to the MQTT broker,
e.g. "112,54,123" for latitude, longitude, altitude. The altitude is optional. 

The channel expects values on the corresponding MQTT topic to be in this format as well. 

### Channel Type "image"

You can connect this channel to an Image item. This is a read-only channel.

The channel expects values on the corresponding MQTT topic to contain the binary
data of a bmp, jpg, png or any other format that the installed java runtime supports. 

### Channel Type "datetime"

You can connect this channel to a DateTime item.

The channel will publish the date/time in the format "yyyy-MM-dd'T'HH:mm"
for example 2018-01-01T12:14:00. If you require another format, please use the formatter.

The channel expects values on the corresponding MQTT topic to be in this format as well. 

### Channel Type "rollershutter"

* __on__: An optional string (like "Open") that is recognized as UP state.
* __off__: An optional string (like "Close") that is recognized as DOWN state.
* __stop__: An optional string (like "Stop") that is recognized as STOP state.

You can connect this channel to a Rollershutter or Dimmer item.

### A broker Thing with a Generic cloud.iO Thing and a few channels 

Example 1 Map an Z-Wave thing to a cloud.iO Thing:

```xtend
rule "Temperture link"
when
  Item zwave_device_sensor_temperature changed or
  System started
then
  mqtt_topic_temperatureReport.sendCommand(
      zwave_device_sensor_temperature.state.toString)
end
```

Example 2 Map an action from Z-Wave Thing to a cloud.iO Thing:

```xtend
rule "Heater Switch"
when
  Item zwave_device_sensor_temperature changed
then
  if(zwave_device_sensor_temperature.state < 21){
    mqtt_topic_HeaterValve.sendCommand(ON)
  }
  else
  {
    mqtt_topic_HeaterValve.sendCommand(OFF)
  }
end
```
