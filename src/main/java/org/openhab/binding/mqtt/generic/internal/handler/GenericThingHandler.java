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
package org.openhab.binding.mqtt.generic.internal.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.StateDescription;
import org.eclipse.smarthome.core.types.UnDefType;
import org.eclipse.smarthome.io.transport.mqtt.MqttBrokerConnection;
import org.openhab.binding.mqtt.generic.internal.GenericThingConfiguration;
import org.openhab.binding.mqtt.generic.internal.generic.ChannelConfig;
import org.openhab.binding.mqtt.generic.internal.generic.ChannelState;
import org.openhab.binding.mqtt.generic.internal.generic.ChannelStateTransformation;
import org.openhab.binding.mqtt.generic.internal.generic.ChannelStateUpdateListener;
import org.openhab.binding.mqtt.generic.internal.generic.MqttChannelStateDescriptionProvider;
import org.openhab.binding.mqtt.generic.internal.generic.TransformationServiceProvider;
import org.openhab.binding.mqtt.generic.internal.values.Value;
import org.openhab.binding.mqtt.generic.internal.values.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This handler manages manual created Things with manually added channels to link to MQTT topics.
 *
 * @author David Graeff - Initial contribution
 */
@NonNullByDefault
public class GenericThingHandler extends AbstractMQTTThingHandler implements ChannelStateUpdateListener {
    private final Logger logger = LoggerFactory.getLogger(GenericThingHandler.class);
    final Map<ChannelUID, ChannelState> channelStateByChannelUID = new HashMap<>();
    protected final MqttChannelStateDescriptionProvider stateDescProvider;
    protected final TransformationServiceProvider transformationServiceProvider;

    protected GenericThingConfiguration config = new GenericThingConfiguration();

    /**
     * Creates a new Thing handler for generic MQTT channels.
     *
     * @param thing The thing of this handler
     * @param stateDescProvider A channel state provider
     * @param transformationServiceProvider The transformation service provider
     * @param subscribeTimeout The subscribe timeout
     */
    public GenericThingHandler(Thing thing, MqttChannelStateDescriptionProvider stateDescProvider,
            TransformationServiceProvider transformationServiceProvider, int subscribeTimeout) {
        super(thing, subscribeTimeout);
        this.stateDescProvider = stateDescProvider;
        this.transformationServiceProvider = transformationServiceProvider;
    }

    @Override
    public @Nullable ChannelState getChannelState(ChannelUID channelUID) {
        return channelStateByChannelUID.get(channelUID);
    }

    /**
     * Subscribe on all channel static topics on all {@link ChannelState}s.
     * If subscribing on all channels worked, the thing is put ONLINE, else OFFLINE.
     *
     * @param connection A started broker connection
     */
    @Override
    protected CompletableFuture<@Nullable Void> start(MqttBrokerConnection connection) {
        List<CompletableFuture<@Nullable Void>> futures = channelStateByChannelUID.values().stream()
                .map(c -> c.start(connection, scheduler, 0)).collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).thenRun(() -> {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
        });
    }

    @Override
    protected void stop() {
        channelStateByChannelUID.values().forEach(c -> c.getCache().resetState());
    }

    @Override
    public void dispose() {
        // Stop all MQTT subscriptions
        try {
            channelStateByChannelUID.values().stream().map(e -> e.stop())
                    .reduce(CompletableFuture.completedFuture(null), (a, v) -> a.thenCompose(b -> v))
                    .get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException ignore) {
        }
        // Remove all state descriptions of this handler
        channelStateByChannelUID.forEach((uid, state) -> stateDescProvider.remove(uid));
        connection = null;
        channelStateByChannelUID.clear();
        super.dispose();
    }

    /**
     * For every Thing channel there exists a corresponding {@link ChannelState}. It consists of the MQTT state
     * and MQTT command topic, the ChannelUID and a value state.
     *
     * @param channelConfig The channel configuration that contains MQTT state and command topic and multiple other
     *            configurations.
     * @param channelUID The channel UID
     * @param valueState The channel value state
     * @return
     */
    protected ChannelState createChannelState(ChannelConfig channelConfig, ChannelUID channelUID, Value valueState) {
        ChannelState state = new ChannelState(channelConfig, channelUID, valueState, this);
        String[] transformations;

        // Incoming value transformations
        transformations = channelConfig.transformationPattern.split("∩");
        Stream.of(transformations).filter(t -> StringUtils.isNotBlank(t))
                .map(t -> new ChannelStateTransformation(t, transformationServiceProvider))
                .forEach(t -> state.addTransformation(t));

        // Outgoing value transformations
        transformations = channelConfig.transformationPatternOut.split("∩");
        Stream.of(transformations).filter(t -> StringUtils.isNotBlank(t))
                .map(t -> new ChannelStateTransformation(t, transformationServiceProvider))
                .forEach(t -> state.addTransformationOut(t));

        return state;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (connection == null) {
            return;
        }

        final @Nullable ChannelState data = getChannelState(channelUID);

        if (data == null) {
            logger.warn("Channel {} not supported", channelUID.getId());
            if (command instanceof RefreshType) {
                updateState(channelUID.getId(), UnDefType.UNDEF);
            }
            return;
        }

        if (command instanceof RefreshType || data.isReadOnly()) {
            updateState(channelUID.getId(), data.getCache().getChannelState());
            return;
        }

        ChannelTypeUID channelTypeUID = null;
        // for all channel in the thing test the ID and retrieve the channel type
        for (Channel channel : thing.getChannels()) {
            if (channel.getUID().toString().equals(channelUID.toString())) {
                channelTypeUID = channel.getChannelTypeUID();
            }
        }

        // TODO here we go for the publish
        final CompletableFuture<@Nullable Void> future = data.publishValueConfig(config, channelTypeUID, command);
        future.exceptionally(e -> {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getLocalizedMessage());
            return null;
        }).thenRun(() -> logger.debug("Successfully published value {} to topic {}", command, data.getStateTopic()));
    }

    @Override
    public void initialize() {

        config = getConfigAs(GenericThingConfiguration.class);
        List<ChannelUID> configErrors = new ArrayList<>();
        for (Channel channel : thing.getChannels()) {
            final ChannelTypeUID channelTypeUID = channel.getChannelTypeUID();
            if (channelTypeUID == null) {
                logger.warn("Channel {} has no type", channel.getLabel());
                continue;
            }
            final ChannelConfig channelConfig = channel.getConfiguration().as(ChannelConfig.class);
            try {
                Value value = ValueFactory.createValueState(channelConfig, channelTypeUID.getId());
                ChannelState channelState = createChannelState(channelConfig, channel.getUID(), value);
                channelStateByChannelUID.put(channel.getUID(), channelState);
                StateDescription description = value.createStateDescription(channelConfig.unit, false);// StringUtils.isBlank(channelConfig.commandTopic));
                stateDescProvider.setDescription(channel.getUID(), description);
            } catch (IllegalArgumentException e) {
                logger.warn("Channel configuration error", e);
                configErrors.add(channel.getUID());
            }
        }

        // If some channels could not start up, put the entire thing offline and display the channels
        // in question to the user.
        if (configErrors.isEmpty()) {
            super.initialize();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Remove and recreate: "
                    + configErrors.stream().map(e -> e.getAsString()).collect(Collectors.joining(",")));
        }
    }
}
