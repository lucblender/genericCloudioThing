/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mqtt.generic.internal.generic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.TypeParser;
import org.eclipse.smarthome.io.transport.mqtt.MqttBrokerConnection;
import org.eclipse.smarthome.io.transport.mqtt.MqttMessageSubscriber;
import org.openhab.binding.mqtt.generic.internal.GenericThingConfiguration;
import org.openhab.binding.mqtt.generic.internal.values.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This object consists of an {@link Value}, which is updated on the respective MQTT topic change.
 * Updates to the value are propagated via the {@link ChannelStateUpdateListener}.
 *
 * @author David Graeff - Initial contribution
 */
@NonNullByDefault
public class ChannelState implements MqttMessageSubscriber {
    private final Logger logger = LoggerFactory.getLogger(ChannelState.class);

    // Immutable channel configuration
    protected final boolean readOnly;
    protected final ChannelUID channelUID;
    protected final ChannelConfig config;

    /** Channel value **/
    protected final Value cachedValue;

    // Runtime variables

    protected @Nullable MqttBrokerConnection connection;
    protected final List<ChannelStateTransformation> transformationsIn = new ArrayList<>();
    protected final List<ChannelStateTransformation> transformationsOut = new ArrayList<>();
    private @Nullable ChannelStateUpdateListener channelStateUpdateListener;
    protected boolean hasSubscribed = false;
    private @Nullable ScheduledFuture<?> scheduledFuture;
    private CompletableFuture<@Nullable Void> future = new CompletableFuture<>();

    /**
     * Creates a new channel state.
     *
     * @param config The channel configuration
     * @param channelUID The channelUID is used for the {@link ChannelStateUpdateListener} to notify
     *            about value changes
     * @param cachedValue MQTT only notifies us once about a value, during the subscribe. The channel
     *            state therefore
     *            needs a cache for the current value.
     * @param channelStateUpdateListener A channel state update listener
     */
    public ChannelState(ChannelConfig config, ChannelUID channelUID, Value cachedValue,
            @Nullable ChannelStateUpdateListener channelStateUpdateListener) {
        this.config = config;
        this.channelStateUpdateListener = channelStateUpdateListener;
        this.channelUID = channelUID;
        this.cachedValue = cachedValue;
        this.readOnly = false;// StringUtils.isBlank(config.commandTopic);
    }

    public boolean isReadOnly() {
        return this.readOnly;
    }

    /**
     * Add a transformation that is applied for each received MQTT topic value.
     * The transformations are executed in order.
     *
     * @param transformation A transformation
     */
    public void addTransformation(ChannelStateTransformation transformation) {
        transformationsIn.add(transformation);
    }

    /**
     * Add a transformation that is applied for each value to be published.
     * The transformations are executed in order.
     *
     * @param transformation A transformation
     */
    public void addTransformationOut(ChannelStateTransformation transformation) {
        transformationsOut.add(transformation);
    }

    /**
     * Clear transformations
     */
    public void clearTransformations() {
        transformationsIn.clear();
        transformationsOut.clear();
    }

    /**
     * Returns the cached value state object of this message subscriber.
     * <p>
     * MQTT only notifies us once about a value, during the subscribe.
     * The channel state therefore needs a cache for the current value.
     * If MQTT has not yet published a value, the cache might still be in UNDEF state.
     * </p>
     */
    public Value getCache() {
        return cachedValue;
    }

    /**
     * Return the channelUID
     */
    public ChannelUID channelUID() {
        return channelUID;
    }

    /**
     * Incoming message from the MqttBrokerConnection
     *
     * @param topic The topic. Is the same as the field stateTopic.
     * @param payload The byte payload. Must be UTF8 encoded text or binary data.
     */
    @Override
    public void processMessage(String topic, byte[] payload) {
        final ChannelStateUpdateListener channelStateUpdateListener = this.channelStateUpdateListener;
        if (channelStateUpdateListener == null) {
            logger.warn("MQTT message received for topic {}, but MessageSubscriber object hasn't been started!", topic);
            return;
        }

        if (cachedValue.isBinary()) {
            cachedValue.update(payload);
            channelStateUpdateListener.updateChannelState(channelUID, cachedValue.getChannelState());
            receivedOrTimeout();
            return;
        }

        // String value: Apply transformations
        String strvalue = new String(payload, StandardCharsets.UTF_8);
        for (ChannelStateTransformation t : transformationsIn) {
            strvalue = t.processValue(strvalue);
        }

        // Is trigger?: Special handling
        if (config.trigger) {
            channelStateUpdateListener.triggerChannel(channelUID, strvalue);
            receivedOrTimeout();
            return;
        }

        Command command = TypeParser.parseCommand(cachedValue.getSupportedCommandTypes(), strvalue);
        if (command == null) {
            logger.warn("Incoming payload '{}' not supported by type '{}'", strvalue,
                    cachedValue.getClass().getSimpleName());
            receivedOrTimeout();
            return;
        }

        Command postOnlyCommand = cachedValue.isPostOnly(command);
        if (postOnlyCommand != null) {
            channelStateUpdateListener.postChannelCommand(channelUID, postOnlyCommand);
            receivedOrTimeout();
            return;
        }

        // Map the string to an ESH command, update the cached value and post the command to the framework
        try {
            cachedValue.update(command);
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.warn("Command '{}' not supported by type '{}': {}", strvalue, cachedValue.getClass().getSimpleName(),
                    e.getMessage());
            receivedOrTimeout();
            return;
        }

        if (config.postCommand) {
            channelStateUpdateListener.postChannelCommand(channelUID, (Command) cachedValue.getChannelState());
        } else {
            channelStateUpdateListener.updateChannelState(channelUID, cachedValue.getChannelState());
        }
        receivedOrTimeout();
    }

    /**
     * Returns the cloudIOObject
     */
    public String getCloudioObject() {
        return config.cloudioObject;
    }

    /**
     * Returns the cloudIOAttribute
     */
    public String getCloudioAttribute() {
        return config.cloudioAttribute;
    }

    /**
     * Returns the state topic. Might be an empty string if this is a stateless channel (TRIGGER kind channel).
     */
    public String getStateTopic() {
        return config.stateTopic;
    }

    /**
     * Return the command topic. Might be an empty string, if this is a read-only channel.
     */
    /*
     * public String getCommandTopic() {
     * return config.commandTopic;
     * }
     */

    /**
     * Returns the channelType ID which also happens to be an item-type
     */
    public String getItemType() {
        return cachedValue.getItemType();
    }

    /**
     * Returns true if this is a stateful channel.
     */
    public boolean isStateful() {
        return config.retained;
    }

    /**
     * Removes the subscription to the state topic and resets the channelStateUpdateListener.
     *
     * @return A future that completes with true if unsubscribing from the state topic succeeded.
     *         It completes with false if no connection is established and completes exceptionally otherwise.
     */
    public CompletableFuture<@Nullable Void> stop() {
        final MqttBrokerConnection connection = this.connection;
        if (connection != null && StringUtils.isNotBlank(config.stateTopic)) {
            return connection.unsubscribe(config.stateTopic, this).thenRun(this::internalStop);
        } else {
            internalStop();
            return CompletableFuture.completedFuture(null);
        }
    }

    private void internalStop() {
        this.connection = null;
        this.channelStateUpdateListener = null;
        hasSubscribed = false;
        cachedValue.resetState();
    }

    private void receivedOrTimeout() {
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) { // Cancel timeout
            scheduledFuture.cancel(false);
            this.scheduledFuture = null;
        }
        future.complete(null);
    }

    private @Nullable Void subscribeFail(Throwable e) {
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) { // Cancel timeout
            scheduledFuture.cancel(false);
            this.scheduledFuture = null;
        }
        future.completeExceptionally(e);
        return null;
    }

    /**
     * Subscribes to the state topic on the given connection and informs about updates on the given listener.
     *
     * @param connection A broker connection
     * @param scheduler A scheduler to realize the timeout
     * @param timeout A timeout in milliseconds. Can be 0 to disable the timeout and let the future
     *            return earlier.
     * @param channelStateUpdateListener An update listener
     * @return A future that completes with true if the subscribing worked, with false if the stateTopic is not set
     *         and exceptionally otherwise.
     */
    public CompletableFuture<@Nullable Void> start(MqttBrokerConnection connection, ScheduledExecutorService scheduler,
            int timeout) {
        if (hasSubscribed) {
            return CompletableFuture.completedFuture(null);
        }

        this.connection = connection;

        if (StringUtils.isBlank(config.stateTopic)) {
            return CompletableFuture.completedFuture(null);
        }

        this.future = new CompletableFuture<>();
        connection.subscribe(config.stateTopic, this).thenRun(() -> {
            hasSubscribed = true;
            logger.debug("Subscribed channel {} to topic: {}", this.channelUID, config.stateTopic);
            if (timeout > 0 && !future.isDone()) {
                this.scheduledFuture = scheduler.schedule(this::receivedOrTimeout, timeout, TimeUnit.MILLISECONDS);
            } else {
                receivedOrTimeout();
            }
        }).exceptionally(this::subscribeFail);
        return future;
    }

    /**
     * Return true if this channel has subscribed to its MQTT topics.
     * You need to call {@link #start(MqttBrokerConnection, ScheduledExecutorService, int)} and
     * have a stateTopic set, to subscribe this channel.
     */
    public boolean hasSubscribed() {
        return this.hasSubscribed;
    }

    /**
     * Publishes a value on MQTT. A command topic needs to be set in the configuration.
     *
     * @param command The command to send
     * @return A future that completes with true if the publishing worked and false and/or exceptionally otherwise.
     */
    public CompletableFuture<@Nullable Void> publishValue(Command command) {
        cachedValue.update(command);

        String mqttCommandValue = cachedValue.getMQTTpublishValue();

        final MqttBrokerConnection connection = this.connection;

        if (!readOnly && connection != null) {
            // Formatter: Applied before the channel state value is published to the MQTT broker.
            if (config.formatBeforePublish.length() > 0) {
                try (Formatter formatter = new Formatter()) {
                    Formatter format = formatter.format(config.formatBeforePublish, mqttCommandValue);
                    mqttCommandValue = format.toString();
                } catch (IllegalFormatException e) {
                    logger.debug("Format pattern incorrect for {}", channelUID, e);
                }
            }
            // Outgoing transformations
            for (ChannelStateTransformation t : transformationsOut) {
                mqttCommandValue = t.processValue(mqttCommandValue);
            }
            // Send retained messages if this is a stateful channel
            // TODO here we publish
            return connection
                    .publish("/" + config.cloudioObject + "/titi/tata", mqttCommandValue.getBytes(), 1, config.retained)
                    .thenRun(() -> {
                    });
        } else {
            CompletableFuture<@Nullable Void> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("No connection or readOnly channel!"));
            return f;
        }
    }

    /**
     * Publishes a value on MQTT. A command topic needs to be set in the configuration.
     *
     * @param command The command to send
     * @return A future that completes with true if the publishing worked and false and/or exceptionally otherwise.
     */
    public CompletableFuture<@Nullable Void> publishValueConfig(GenericThingConfiguration brokerConfig,
            @Nullable ChannelTypeUID channelTypeUID, Command command) {
        cachedValue.update(command);

        String mqttCommandValue = cachedValue.getMQTTpublishValue();

        final MqttBrokerConnection connection = this.connection;

        if (!readOnly && connection != null) {
            // Formatter: Applied before the channel state value is published to the MQTT broker.
            if (config.formatBeforePublish.length() > 0) {
                try (Formatter formatter = new Formatter()) {
                    Formatter format = formatter.format(config.formatBeforePublish, mqttCommandValue);
                    mqttCommandValue = format.toString();
                } catch (IllegalFormatException e) {
                    logger.debug("Format pattern incorrect for {}", channelUID, e);
                }
            }
            // Outgoing transformations
            for (ChannelStateTransformation t : transformationsOut) {
                mqttCommandValue = t.processValue(mqttCommandValue);
            }
            // Send retained messages if this is a stateful channel
            // TODO here we publish
            String topic = String.format("@update/%s/nodes/%s/objects/%s/attributes/%s", brokerConfig.cloudioEndpoint,
                    brokerConfig.cloudioNode, config.cloudioObject, config.cloudioAttribute);

            String epochTimeMilli = String.format("%.3f", System.currentTimeMillis() / 1000.0);
            String type = "";
            if (channelTypeUID != null) {
                System.out.println("\n" + channelTypeUID.toString() + "\n");
                switch (channelTypeUID.toString()) {
                    case "mqtt:dimmer":
                        type = "Number";
                        break;
                    case "mqtt:switch":
                        type = "Bool";
                        if (mqttCommandValue.equals("ON")) {
                            mqttCommandValue = "true";
                        } else if (mqttCommandValue.equals("OFF")) {
                            mqttCommandValue = "false";
                        } else {
                            mqttCommandValue = "true";
                            logger.warn("Unknown format value for mqtt:switch");
                        }
                        break;
                    case "mqtt:string":
                    case "mqtt:location":
                    case "mqtt:colorRGB":
                    case "mqtt:colorHSB":
                    case "mqtt:datetime":
                        type = "String";
                        break;
                    case "mqtt:number":
                        type = "Integer";
                        break;

                    default:
                        logger.warn("Channel type: " + channelTypeUID.toString()
                                + " is not fully implemented for cloud.Io channel");
                        break;
                }
            }
            String content;
            if (type.equals("String")) {
                content = String.format("{\n" + "\"type\": \"%s\",\n" + "\"constraint\": \"%s\",\n"
                        + "\"timestamp\": %s,\n" + "\"value\": \"%s\"\n" + "}", type, "Measure", epochTimeMilli,
                        mqttCommandValue);
            } else {
                content = String.format("{\n" + "\"type\": \"%s\",\n" + "\"constraint\": \"%s\",\n"
                        + "\"timestamp\": %s,\n" + "\"value\": %s\n" + "}", type, "Measure", epochTimeMilli,
                        mqttCommandValue);
            }

            System.out.println(content);

            return connection.publish(topic, content.getBytes(), 1, config.retained).thenRun(() -> {
            });
        } else {
            CompletableFuture<@Nullable Void> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("No connection or readOnly channel!"));
            return f;
        }
    }

    /**
     * @return The channelStateUpdateListener
     */
    public @Nullable ChannelStateUpdateListener getChannelStateUpdateListener() {
        return channelStateUpdateListener;
    }

    /**
     * @param channelStateUpdateListener The channelStateUpdateListener to set
     */
    public void setChannelStateUpdateListener(ChannelStateUpdateListener channelStateUpdateListener) {
        this.channelStateUpdateListener = channelStateUpdateListener;
    }

}
