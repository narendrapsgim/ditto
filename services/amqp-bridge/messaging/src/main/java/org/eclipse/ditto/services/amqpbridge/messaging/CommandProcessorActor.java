/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.amqpbridge.messaging;


import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;
import static org.eclipse.ditto.services.models.amqpbridge.AmqpBridgeMessagingConstants.GATEWAY_PROXY_ACTOR_PATH;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.ditto.model.amqpbridge.InternalMessage;
import org.eclipse.ditto.model.amqpbridge.MappingContext;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.protocoladapter.Adaptable;
import org.eclipse.ditto.protocoladapter.DittoProtocolAdapter;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.ConverterTraceWrapper;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.DittoMessageMapper;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.MessageMapper;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.MessageMapperConfiguration;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.MessageMapperFactory;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.MessageMapperRegistry;
import org.eclipse.ditto.services.amqpbridge.mapping.mapper.MessageMappers;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.things.ThingErrorResponse;

import com.google.common.base.Converter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ExtendedActorSystem;
import akka.actor.Props;
import akka.actor.Status;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import kamon.Kamon;
import kamon.trace.TraceContext;
import scala.Option;

/**
 * This Actor processes incoming {@link Command}s and dispatches them via {@link DistributedPubSubMediator} to a
 * consumer actor.
 */
public final class CommandProcessorActor extends AbstractActor {

    /**
     * The name of this Actor in the ActorSystem.
     */
    public static final String ACTOR_NAME_PREFIX = "amqpCommandProcessor-";

    private static final DittoProtocolAdapter PROTOCOL_ADAPTER = DittoProtocolAdapter.newInstance();

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final ActorRef pubSubMediator;
    private final AuthorizationSubject authorizationSubject;
    private final Cache<String, TraceContext> traces;

    private final MessageMapperRegistry registry;

    private CommandProcessorActor(final ActorRef pubSubMediator, final AuthorizationSubject authorizationSubject,
            final List<MappingContext> mappingContexts) {
        this.pubSubMediator = pubSubMediator;
        this.authorizationSubject = authorizationSubject;
        traces = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .removalListener((RemovalListener<String, TraceContext>) notification
                        -> log.info("Trace for {} expired.", notification.getKey()))
                .build();

        final MessageMapperFactory mapperFactory = new MessageMapperFactory(
                (ExtendedActorSystem) getContext().getSystem(), MessageMappers.class, log);
        registry = mapperFactory.loadRegistry(createDefaultMapper(), mappingContexts);

        log.info("Configured for processing messages with the following content types: {}",
                registry.stream().map(MessageMapper::getContentType).collect(Collectors.toList()));

        final MessageMapper defaultMapper = registry.getDefaultMapper();
        if (Objects.nonNull(defaultMapper)) {
            log.info("Interpreting messages with missing content type as '{}'",
                    defaultMapper.getContentType());
        } else {
            log.warning("No default mapper configured!");
        }
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param pubSubMediator the akka pubsub mediator actor.
     * @param authorizationSubject the authorized subject that are set in command headers.
     * @return the Akka configuration Props object
     */
    static Props props(final ActorRef pubSubMediator, final AuthorizationSubject authorizationSubject,
            final List<MappingContext> mappingContexts) {

        return Props.create(CommandProcessorActor.class, new Creator<CommandProcessorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public CommandProcessorActor create() {
                return new CommandProcessorActor(pubSubMediator, authorizationSubject, mappingContexts);
            }
        });
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(InternalMessage.class, this::handle)
                .match(CommandResponse.class, this::handleCommandResponse)
                .match(DittoRuntimeException.class, this::handleDittoRuntimeException)
                .match(Status.Failure.class, f -> log.error(f.cause(), "Got an unexpected failure."))
                .matchAny(m -> {
                    log.debug("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private void handle(final InternalMessage m) {

        final String correlationId = DittoHeaders.of(m.getHeaders()).getCorrelationId().orElse("no-correlation-id");
        LogUtil.enhanceLogWithCorrelationId(log, correlationId);
        final TraceContext traceContext = Kamon.tracer().newContext("commandProcessor", Option.apply(correlationId));

        try {
            final Command<?> command = parseMessage(m, traceContext);
            traceContext.finish();
            traceCommand(command);
            log.info("Publishing '{}' to '{}'", command.getType(), GATEWAY_PROXY_ACTOR_PATH);
            pubSubMediator.tell(new DistributedPubSubMediator.Send(GATEWAY_PROXY_ACTOR_PATH, command, true),
                    getSelf());
        } catch (Exception e) {
            traceContext.finishWithError(e);
            log.info(e.getMessage());
        }
    }

    private void handleDittoRuntimeException(final DittoRuntimeException exception) {
        final ThingErrorResponse errorResponse = ThingErrorResponse.of(exception);

        logDittoRuntimeException(exception);
        handleCommandResponse(errorResponse);
    }

    private void logDittoRuntimeException(final DittoRuntimeException exception) {
        LogUtil.enhanceLogWithCorrelationId(log, exception);

        final String msgTemplate = "Got DittoRuntimeException '{}' when command via AMQP was processed: {}";
        log.info(msgTemplate, exception.getErrorCode(), exception.getMessage());
    }

    private void handleCommandResponse(final CommandResponse response) {
        LogUtil.enhanceLogWithCorrelationId(log, response);

        // TODO map back

        if (response.getStatusCodeValue() < HttpStatusCode.BAD_REQUEST.toInt()) {
            log.debug("Received response: {}", response);
        } else {
            log.info("Received error response: {}", response);
        }

        response.getDittoHeaders().getCorrelationId().ifPresent(cid -> {
            final TraceContext traceContext = traces.getIfPresent(cid);
            traces.invalidate(cid);
            if (traceContext != null) {
                traceContext.finish();
            } else {
                log.info("Trace missing for response: '{}'", response);
            }
        });
    }

    private void traceCommand(final Command<?> command) {
        command.getDittoHeaders().getCorrelationId().ifPresent(correlationId -> {
            final Option<String> token = Option.apply(correlationId);
            final TraceContext traceContext = Kamon.tracer().newContext("roundtrip.amqp_" + command.getType(), token);
            traceContext.addMetadata("command", command.getType());
            traces.put(correlationId, traceContext);
        });
    }

    @SuppressWarnings("ConstantConditions") // when message is non null, a converter always returns a non null value
    private Command<?> parseMessage(final InternalMessage message, final TraceContext traceContext) {
        checkNotNull(message);
        checkNotNull(traceContext);

        DittoHeaders headers = DittoHeaders.of(message.getHeaders());

        try {
            final Adaptable adaptable = getMapper(message, traceContext).convert(message);
            headers = adaptable.getHeaders().orElse(headers);
            headers.getCorrelationId().ifPresent(s -> LogUtil.enhanceLogWithCorrelationId(log, s));
            return getTracedAdaptableCommandConverter(traceContext).convert(adaptable).setDittoHeaders(headers);
        } catch (Exception e) {
            throw new IllegalArgumentException("Parsing message failed: " + e.getMessage(), e);
        }
    }

    private Converter<InternalMessage, Adaptable> getMapper(final InternalMessage message,
            final TraceContext traceContext) {
        final MessageMapper mapper = registry.selectMapper(message)
                .orElseThrow(() -> new IllegalArgumentException("No mapper found for message: " + message));
        return ConverterTraceWrapper.wrap(mapper,
                () -> traceContext.startSegment("mapping", "payload-mapping", "commandProcessor"));
    }


    private static final Converter<Adaptable, Command<?>> ADAPTABLE_COMMAND_CONVERTER = Converter.from(
            a -> (Command<?>) PROTOCOL_ADAPTER.fromAdaptable(a),
            PROTOCOL_ADAPTER::toAdaptable
    );

    private static Converter<Adaptable, Command<?>> getTracedAdaptableCommandConverter(
            final TraceContext traceContext) {
        return ConverterTraceWrapper.wrap(ADAPTABLE_COMMAND_CONVERTER,
                () -> traceContext.startSegment("protocoladapter", "payload-mapping", "commandProcessor")
        );
    }

    private static MessageMapper createDefaultMapper() {
        MessageMapperConfiguration cfg = MessageMapperConfiguration.from(
                Collections.singletonMap(MessageMapper.OPT_CONTENT_TYPE_REQUIRED, String.valueOf(false)));
        return new DittoMessageMapper(cfg);
    }
}
