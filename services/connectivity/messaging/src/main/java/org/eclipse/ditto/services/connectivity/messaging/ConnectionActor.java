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
package org.eclipse.ditto.services.connectivity.messaging;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;
import static org.eclipse.ditto.services.models.connectivity.ConnectivityMessagingConstants.CLUSTER_ROLE;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.WithDittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionConfigurationInvalidException;
import org.eclipse.ditto.model.connectivity.ConnectionStatus;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.connectivity.Topic;
import org.eclipse.ditto.services.connectivity.messaging.persistence.ConnectionMongoSnapshotAdapter;
import org.eclipse.ditto.services.connectivity.messaging.validation.CompoundConnectivityCommandInterceptor;
import org.eclipse.ditto.services.connectivity.messaging.validation.DittoConnectivityCommandValidator;
import org.eclipse.ditto.services.connectivity.util.ConfigKeys;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.services.utils.akka.controlflow.WithSender;
import org.eclipse.ditto.services.utils.persistence.SnapshotAdapter;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.connectivity.AggregatedConnectivityCommandResponse;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityCommand;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityCommandResponse;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityErrorResponse;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionConflictException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionFailedException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionNotAccessibleException;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.ModifyConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.ModifyConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.OpenConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.OpenConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.TestConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.TestConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnection;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionMetrics;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionStatus;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionStatusResponse;
import org.eclipse.ditto.signals.events.base.Event;
import org.eclipse.ditto.signals.events.connectivity.ConnectionClosed;
import org.eclipse.ditto.signals.events.connectivity.ConnectionCreated;
import org.eclipse.ditto.signals.events.connectivity.ConnectionDeleted;
import org.eclipse.ditto.signals.events.connectivity.ConnectionModified;
import org.eclipse.ditto.signals.events.connectivity.ConnectionOpened;

import com.typesafe.config.Config;

import akka.ConfigurationException;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.ReceiveTimeout;
import akka.actor.Status;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.PatternsCS;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.RecoveryCompleted;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import akka.routing.Broadcast;
import akka.routing.RoundRobinPool;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

/**
 * Handles {@code *Connection} commands and manages the persistence of connection. The actual connection handling to the
 * remote server is delegated to a child actor that uses a specific client (AMQP 1.0 or 0.9.1).
 */
public final class ConnectionActor extends AbstractPersistentActor {

    private static final String PERSISTENCE_ID_PREFIX = "connection:";

    private static final String JOURNAL_PLUGIN_ID = "akka-contrib-mongodb-persistence-connection-journal";
    private static final String SNAPSHOT_PLUGIN_ID = "akka-contrib-mongodb-persistence-connection-snapshots";

    /**
     * Default timeout for flushing pending responses.
     *
     * @see ConfigKeys.Connection#FLUSH_PENDING_RESPONSES_TIMEOUT
     */
    private static java.time.Duration DEFAULT_FLUSH_PENDING_RESPONSES_TIMEOUT = java.time.Duration.ofSeconds(5L);

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private static final String PUB_SUB_GROUP_PREFIX = "connection:";

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final String connectionId;
    private final ActorRef pubSubMediator;
    private final ActorRef conciergeForwarder;
    private final long snapshotThreshold;
    private final SnapshotAdapter<Connection> snapshotAdapter;
    private final ConnectionActorPropsFactory propsFactory;
    private final Consumer<ConnectivityCommand<?>> commandValidator;
    private final Receive connectionCreatedBehaviour;

    @Nullable private ActorRef clientActor;
    @Nullable private Connection connection;

    private long lastSnapshotSequenceNr = -1L;
    private boolean snapshotInProgress = false;
    private final PlaceholderFilter placeholdersFilter = new PlaceholderFilter();

    private Set<Topic> uniqueTopicPaths = Collections.emptySet();

    private final FiniteDuration flushPendingResponsesTimeout;
    private Queue<WithSender<ConnectivityCommandResponse>> pendingResponses = new LinkedList<>();
    @Nullable private Cancellable flushPendingResponsesTrigger;

    private ConnectionActor(final String connectionId, final ActorRef pubSubMediator,
            final ActorRef conciergeForwarder, final ConnectionActorPropsFactory propsFactory,
            @Nullable final Consumer<ConnectivityCommand<?>> customCommandValidator) {
        this.connectionId = connectionId;
        this.pubSubMediator = pubSubMediator;
        this.conciergeForwarder = conciergeForwarder;
        this.propsFactory = propsFactory;
        final DittoConnectivityCommandValidator
                dittoCommandValidator = new DittoConnectivityCommandValidator(propsFactory, conciergeForwarder);
        if (customCommandValidator != null) {
            this.commandValidator =
                    new CompoundConnectivityCommandInterceptor(dittoCommandValidator, customCommandValidator);
        } else {
            this.commandValidator = dittoCommandValidator;
        }

        final Config config = getContext().system().settings().config();
        snapshotThreshold = config.getLong(ConfigKeys.Connection.SNAPSHOT_THRESHOLD);
        if (snapshotThreshold < 0) {
            throw new ConfigurationException(String.format("Config setting '%s' must be positive, but is: %d.",
                    ConfigKeys.Connection.SNAPSHOT_THRESHOLD, snapshotThreshold));
        }
        snapshotAdapter = new ConnectionMongoSnapshotAdapter();
        connectionCreatedBehaviour = createConnectionCreatedBehaviour();

        final java.time.Duration javaFlushTimeout =
                config.hasPath(ConfigKeys.Connection.FLUSH_PENDING_RESPONSES_TIMEOUT)
                        ? config.getDuration(ConfigKeys.Connection.FLUSH_PENDING_RESPONSES_TIMEOUT)
                        : DEFAULT_FLUSH_PENDING_RESPONSES_TIMEOUT;
        flushPendingResponsesTimeout = Duration.create(javaFlushTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @return the Akka configuration Props object
     */
    public static Props props(final String connectionId, final ActorRef pubSubMediator,
            final ActorRef conciergeForwarder, final ConnectionActorPropsFactory propsFactory,
            @Nullable final Consumer<ConnectivityCommand<?>> commandValidator) {
        return Props.create(ConnectionActor.class, new Creator<ConnectionActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ConnectionActor create() {
                return new ConnectionActor(connectionId, pubSubMediator, conciergeForwarder, propsFactory,
                        commandValidator);
            }
        });
    }

    @Override
    public String persistenceId() {
        return PERSISTENCE_ID_PREFIX + connectionId;
    }

    @Override
    public String journalPluginId() {
        return JOURNAL_PLUGIN_ID;
    }

    @Override
    public String snapshotPluginId() {
        return SNAPSHOT_PLUGIN_ID;
    }

    @Override
    public void postStop() {
        log.info("stopped connection <{}>", connectionId);
        flushPendingResponses();
        super.postStop();
    }

    @Override
    public Receive createReceiveRecover() {
        return ReceiveBuilder.create()
                // # Snapshot handling
                .match(SnapshotOffer.class, ss -> {
                    final Connection fromSnapshotStore = snapshotAdapter.fromSnapshotStore(ss);
                    log.info("Received SnapshotOffer containing connection: <{}>", fromSnapshotStore);
                    if (fromSnapshotStore != null) {
                        connection = fromSnapshotStore;
                    }
                    lastSnapshotSequenceNr = ss.metadata().sequenceNr();
                })
                .match(ConnectionCreated.class, event -> {
                    connection = event.getConnection();
                })
                .match(ConnectionModified.class, event -> {
                    connection = event.getConnection();
                })
                .match(ConnectionOpened.class, event -> connection = connection != null ? connection.toBuilder()
                        .connectionStatus(ConnectionStatus.OPEN).build() : null)
                .match(ConnectionClosed.class, event -> connection = connection != null ? connection.toBuilder()
                        .connectionStatus(ConnectionStatus.CLOSED).build() : null)
                .match(ConnectionDeleted.class, event -> {
                    connection = null;
                })
                .match(RecoveryCompleted.class, rc -> {
                    log.info("Connection '{}' was recovered: {}", connectionId, connection);
                    if (connection != null) {
                        if (ConnectionStatus.OPEN.equals(connection.getConnectionStatus())) {
                            log.debug("Opening connection {} after recovery.", connectionId);

                            final CreateConnection connect = CreateConnection.of(connection, DittoHeaders.empty());

                            final ActorRef origin = getSender();
                            askClientActor(connect,
                                    response -> log.info("CreateConnection result: {}", response),
                                    error -> handleException("recovery-connect", origin, error)
                            );
                            subscribeForEvents();
                        }
                        getContext().become(connectionCreatedBehaviour);
                    }

                    getContext().getParent().tell(ConnectionSupervisorActor.ManualReset.getInstance(), getSelf());
                })
                .matchAny(m -> log.warning("Unknown recover message: {}", m))
                .build();
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(TestConnection.class, testConnection -> validateAndForward(testConnection, this::testConnection))
                .match(CreateConnection.class,
                        createConnection -> validateAndForward(createConnection, this::createConnection))
                .match(ConnectivityCommand.class, this::handleCommandDuringInitialization)
                .match(Status.Failure.class, f -> log.warning("Got failure in initial behaviour with cause {}: {}",
                        f.cause().getClass().getSimpleName(), f.cause().getMessage()))
                .match(PerformTask.class, this::performTask)
                .matchAny(m -> {
                    log.warning("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private Receive createConnectionCreatedBehaviour() {
        return ReceiveBuilder.create()
                .match(TestConnection.class, testConnection ->
                        getSender().tell(
                                TestConnectionResponse.alreadyCreated(testConnection.getConnectionId(),
                                        testConnection.getDittoHeaders()),
                                getSelf()))
                .match(CreateConnection.class, createConnection -> {
                    enhanceLogUtil(createConnection);
                    log.info("Connection <{}> already exists, responding with conflict", createConnection.getId());
                    final ConnectionConflictException conflictException =
                            ConnectionConflictException.newBuilder(createConnection.getId())
                                    .dittoHeaders(createConnection.getDittoHeaders())
                                    .build();
                    getSender().tell(conflictException, getSelf());
                })
                .match(ModifyConnection.class,
                        modifyConnection -> validateAndForward(modifyConnection, this::modifyConnection))
                .match(OpenConnection.class, this::openConnection)
                .match(CloseConnection.class, this::closeConnection)
                .match(DeleteConnection.class, this::deleteConnection)
                .match(RetrieveConnection.class, this::retrieveConnection)
                .match(RetrieveConnectionStatus.class, this::retrieveConnectionStatus)
                .match(RetrieveConnectionMetrics.class, this::retrieveConnectionMetrics)
                .match(Signal.class, this::handleSignal)
                .match(DistributedPubSubMediator.SubscribeAck.class, this::handleSubscribeAck)
                .match(DistributedPubSubMediator.UnsubscribeAck.class, this::handleUnsubscribeAck)
                .match(SaveSnapshotSuccess.class, this::handleSnapshotSuccess)
                .match(Status.Failure.class, f -> log.warning("Got failure in connectionCreated behaviour with " +
                        "cause {}: {}", f.cause().getClass().getSimpleName(), f.cause().getMessage()))
                .match(PerformTask.class, this::performTask)
                .matchAny(m -> {
                    log.warning("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private void enhanceLogUtil(final WithDittoHeaders<?> createConnection) {
        LogUtil.enhanceLogWithCorrelationId(log, createConnection);
        LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
    }

    private void performTask(final PerformTask performTask) {
        log.info("Running <{}>", performTask);
        performTask.run(this);
    }

    private void handleSignal(final Signal<?> signal) {
        // since a signal arrives, event subscription works in the absence of race conditions (which are not handled).
        // flush pending responses regardless whether the signal is forwarded or not.
        flushPendingResponses();

        enhanceLogUtil(signal);
        if (clientActor == null) {
            log.debug("Signal dropped: Client actor not ready.");
            return;
        }
        if (connection == null) {
            log.debug("Signal dropped: No Connection configuration available.");
            return;
        }
        if (uniqueTopicPaths.isEmpty()) {
            log.debug("Signal dropped: No topic paths present.");
            return;
        }
        if (connectionId.equals(signal.getDittoHeaders().getOrigin().orElse(null))) {
            log.debug("Signal dropped: was sent by myself.");
            return;
        }

        final Set<Target> subscribedAndAuthorizedTargets = SignalFilter.filter(connection, signal);
        if (subscribedAndAuthorizedTargets.isEmpty()) {
            log.debug("Signal dropped: No subscribed and authorized targets present");
            return;
        }

        // forward to client actor if topic was subscribed and there are targets that are authorized to read
        final Set<Target> filteredTargets =
                placeholdersFilter.filterTargets(subscribedAndAuthorizedTargets, signal.getId());
        if (subscribedAndAuthorizedTargets.size() != filteredTargets.size()) {
            log.warning("Failed to substitute placeholders in all targets, some targets were dropped.");
        }

        log.debug("Forwarding signal <{}> to client actor with targets: {}.", signal.getType(), filteredTargets);

        final OutboundSignal outbound = new UnmappedOutboundSignal(signal, filteredTargets);
        clientActor.tell(outbound, getSelf());
    }

    private void testConnection(final TestConnection command) {
        final ActorRef origin = getSender();
        final ActorRef self = getSelf();
        connection = command.getConnection();

        final PerformTask stopSelfTask = new PerformTask("stop self after test", ConnectionActor::stopSelf);

        askClientActor(command, response -> {
            origin.tell(
                    TestConnectionResponse.success(command.getConnectionId(), response.toString(),
                            command.getDittoHeaders()),
                    self);
            // terminate this actor's supervisor after a connection test again:
            self.tell(stopSelfTask, ActorRef.noSender());
        }, error -> {
            handleException("test", origin, error);
            // terminate this actor's supervisor after a connection test again:
            self.tell(stopSelfTask, ActorRef.noSender());
        });
    }

    private <T extends ConnectivityCommand> void validateAndForward(final T command, final Consumer<T> target) {
        final ActorRef origin = getSender();
        try {
            commandValidator.accept(command);
            target.accept(command);
        } catch (final Exception e) {
            handleException(command.getType(), origin, e);
            stopSelf();
        }
    }

    private void createConnection(final CreateConnection command) {
        final ActorRef origin = getSender();
        final ActorRef self = getSelf();
        final ActorRef parent = getContext().getParent();

        final ConnectionCreated connectionCreated =
                ConnectionCreated.of(command.getConnection(), command.getDittoHeaders());

        persistEvent(connectionCreated, persistedEvent -> {
            connection = persistedEvent.getConnection();
            getContext().become(connectionCreatedBehaviour);

            if (ConnectionStatus.OPEN.equals(connection.getConnectionStatus())) {
                log.debug("Connection <{}> has status <{}> and will therefore be opened.", connection.getId(),
                        connection.getConnectionStatus().getName());
                askClientActor(command,
                        response -> {
                            final ConnectivityCommandResponse commandResponse =
                                    CreateConnectionResponse.of(connection, command.getDittoHeaders());
                            final PerformTask performTask =
                                    new PerformTask("subscribe for events and schedule CreateConnectionResponse",
                                            subscribeForEventsAndScheduleResponse(commandResponse, origin));
                            parent.tell(ConnectionSupervisorActor.ManualReset.getInstance(), self);
                            self.tell(performTask, ActorRef.noSender());
                        },
                        error -> {
                            // log error but send response anyway
                            handleException("connect", origin, error, false);
                            respondWithCreateConnectionResponse(connection, command, origin);
                        }
                );
            } else {
                log.debug("Connection <{}> has status <{}> and will therefore stay closed.", connection.getId(),
                        connection.getConnectionStatus().getName());
                this.respondWithCreateConnectionResponse(connection, command, origin);
            }
        });
    }

    private void respondWithCreateConnectionResponse(final Connection connection, final CreateConnection command,
            final ActorRef origin) {
        origin.tell(CreateConnectionResponse.of(connection, command.getDittoHeaders()), getSelf());
        getContext().getParent().tell(ConnectionSupervisorActor.ManualReset.getInstance(), getSelf());

    }

    private void modifyConnection(final ModifyConnection command) {
        final ActorRef origin = getSender();
        final ActorRef parent = getContext().getParent();
        final ActorRef self = getSelf();

        if (connection != null && !connection.getConnectionType().equals(command.getConnection().getConnectionType())) {
            handleException("modify", origin, ConnectionConfigurationInvalidException
                    .newBuilder("ConnectionType '" + connection.getConnectionType().getName() +
                            "' of existing connection '" + connectionId + "' cannot be changed.")
                    .dittoHeaders(command.getDittoHeaders())
                    .build()
            );
            return;
        }

        final ConnectionModified connectionModified =
                ConnectionModified.of(command.getConnection(), command.getDittoHeaders());

        persistEvent(connectionModified, persistedEvent -> {
            connection = persistedEvent.getConnection();
            getContext().become(connectionCreatedBehaviour);
            if (persistedEvent.getConnection().getClientCount() != connection.getClientCount()) {
                log.info("Client count changed: old client count: <{}>, new client count: <{}>. " +
                                "Stopping client actor to apply new config.",
                        connection.getClientCount(), persistedEvent.getConnection().getClientCount());
                // send an artificial CloseConnection command to gracefully disconnect and stop the child actors
                askClientActor(CloseConnection.of(connectionId, DittoHeaders.empty()),
                        onSuccess -> {
                            log.info("Connection closed, now stopping client actor.");
                            final PerformTask performTask = new PerformTask(
                                    "stop client actor and handle modify connection",
                                    connectionActor -> {
                                        connectionActor.stopClientActor();
                                        connectionActor.handleModifyConnection(command, persistedEvent, origin,
                                                parent, self);
                                    });
                            self.tell(performTask, ActorRef.noSender());
                        },
                        error -> handleException("connect-after-modify", origin, error));
            } else {
                handleModifyConnection(command, persistedEvent, origin, parent, self);
            }
        });
    }

    /*
     * NOT thread-safe.
     */
    private void handleModifyConnection(final ModifyConnection command, final ConnectionModified persistedEvent,
            final ActorRef origin, final ActorRef parent, final ActorRef self) {
        askClientActor(command,
                response -> {
                    final ConnectivityCommandResponse commandResponse =
                            ModifyConnectionResponse.modified(connectionId, command.getDittoHeaders());
                    final PerformTask performTask =
                            new PerformTask("subscribe for events and schedule ModifyConnectionResponse",
                                    subscribeForEventsAndScheduleResponse(commandResponse, origin));
                    parent.tell(ConnectionSupervisorActor.ManualReset.getInstance(), self);
                    self.tell(performTask, ActorRef.noSender());
                },
                error -> handleException("connect-after-modify", origin, error)
        );
    }

    private void openConnection(final OpenConnection command) {
        checkNotNull(connection, "Connection");

        final ConnectionOpened connectionOpened =
                ConnectionOpened.of(command.getConnectionId(), command.getDittoHeaders());
        final ActorRef origin = getSender();
        final ActorRef self = getSelf();

        persistEvent(connectionOpened, persistedEvent -> {
            connection = connection.toBuilder().connectionStatus(ConnectionStatus.OPEN).build();
            askClientActor(command, response -> {
                        final ConnectivityCommandResponse commandResponse =
                                OpenConnectionResponse.of(connectionId, command.getDittoHeaders());
                        final PerformTask performTask = new PerformTask("open connection",
                                subscribeForEventsAndScheduleResponse(commandResponse, origin));
                        self.tell(performTask, ActorRef.noSender());
                    },
                    error -> handleException("open-connection", origin, error)
            );
        });
    }

    private void closeConnection(final CloseConnection command) {

        final ConnectionClosed connectionClosed =
                ConnectionClosed.of(command.getConnectionId(), command.getDittoHeaders());
        final ActorRef origin = getSender();
        final ActorRef self = getSelf();

        persistEvent(connectionClosed, persistedEvent -> {
            if (connection != null) {
                connection = connection.toBuilder().connectionStatus(ConnectionStatus.CLOSED).build();
            }
            askClientActor(command,
                    response -> {
                        origin.tell(CloseConnectionResponse.of(connectionId, command.getDittoHeaders()),
                                getSelf());
                        final PerformTask performTask =
                                new PerformTask("unsubscribe from events on connection closed",
                                        ConnectionActor::unsubscribeFromEvents);
                        self.tell(performTask, ActorRef.noSender());
                    },
                    error -> handleException("disconnect", origin, error)
            );
        });
    }

    private void deleteConnection(final DeleteConnection command) {

        final ConnectionDeleted connectionDeleted =
                ConnectionDeleted.of(command.getConnectionId(), command.getDittoHeaders());
        final ActorRef origin = getSender();
        final ActorRef self = getSelf();

        final PerformTask deleteSucceededTask = new PerformTask("connection deleted", connectionActor -> {
            connectionActor.stopClientActor();
            origin.tell(DeleteConnectionResponse.of(connectionId, command.getDittoHeaders()), self);
            connectionActor.stopSelf();
            // All subscriptions stop automatically once this actor stops.
            // connectionActor.unsubscribeFromEvents();
        });


        persistEvent(connectionDeleted, persistedEvent ->
                askClientActor(command,
                        response -> self.tell(deleteSucceededTask, ActorRef.noSender()),
                        // we can safely ignore this error and do the same as in the "success" case
                        error -> self.tell(deleteSucceededTask, ActorRef.noSender())
                )
        );
    }

    /*
     * NOT thread-safe.
     */
    private void askClientActor(final Command<?> cmd, final Consumer<Object> onSuccess,
            final Consumer<Throwable> onError) {

        startClientActorIfRequired();
        final long timeout = Optional.ofNullable(cmd.getDittoHeaders().get("timeout"))
                .map(Long::parseLong)
                .orElse(DEFAULT_TIMEOUT_MS);
        // wrap in Broadcast message because these management messages must be delivered to each client actor
        if (clientActor != null && connection != null) {
            final ActorRef aggregationActor = getContext().actorOf(
                    AggregateActor.props(connectionId, clientActor, connection.getClientCount(), timeout));
            PatternsCS.ask(aggregationActor, cmd, timeout)
                    .whenComplete((response, exception) -> {
                        log.debug("Got response to {}: {}", cmd.getType(), exception == null ? response : exception);
                        if (exception != null) {
                            onError.accept(exception);
                        } else if (response instanceof Status.Failure) {
                            final Throwable cause = ((Status.Failure) response).cause();
                            onError.accept(cause);
                        } else if (response instanceof DittoRuntimeException) {
                            onError.accept((DittoRuntimeException) response);
                        } else {
                            onSuccess.accept(response);
                        }
                    });
        } else {
            final String message =
                    MessageFormat.format(
                            "NOT asking client actor <{}> for connection <{}> because one of them is null.",
                            clientActor, connection);
            final NullPointerException nullPointerException = new NullPointerException(message);
            log.error(message);
            onError.accept(nullPointerException);
        }
    }

    /*
     * Thread-safe because Actor.getSelf() is thread-safe.
     */
    private void handleException(final String action, final ActorRef origin, final Throwable exception) {
        this.handleException(action, origin, exception, true);
    }

    private void handleException(final String action, final ActorRef origin, final Throwable exception,
            final boolean sendExceptionResponse) {
        final DittoRuntimeException dre;
        if (exception instanceof DittoRuntimeException) {
            dre = (DittoRuntimeException) exception;
        } else {
            dre = ConnectionFailedException.newBuilder(connectionId)
                    .description(exception.getMessage())
                    .cause(exception)
                    .build();
        }

        if (sendExceptionResponse) {
            origin.tell(dre, getSelf());
        }
        log.warning("Operation <{}> on connection <{}> failed due to {}: {}.", action, connectionId,
                dre.getClass().getSimpleName(), dre.getMessage());
    }

    private void retrieveConnection(final RetrieveConnection command) {
        checkNotNull(connection, "Connection");
        getSender().tell(RetrieveConnectionResponse.of(connection, command.getDittoHeaders()), getSelf());
    }

    private void retrieveConnectionStatus(final RetrieveConnectionStatus command) {
        checkNotNull(connection, "Connection");
        getSender().tell(RetrieveConnectionStatusResponse.of(connectionId, connection.getConnectionStatus(),
                command.getDittoHeaders()), getSelf());
    }

    private void retrieveConnectionMetrics(final RetrieveConnectionMetrics command) {
        checkNotNull(connection, "Connection");

        final ActorRef origin = getSender();
        askClientActor(command,
                response -> origin.tell(response, getSelf()),
                error -> handleException("retrieve-metrics", origin, error));
    }

    private void subscribeForEvents() {
        checkNotNull(connection, "Connection");

        // unsubscribe to previously subscribed topics
        unsubscribeFromEvents();

        uniqueTopicPaths = connection.getTargets().stream()
                .flatMap(target -> target.getTopics().stream())
                .collect(Collectors.toSet());

        forEachPubSubTopicDo(pubSubTopic -> {
            final DistributedPubSubMediator.Subscribe subscribe =
                    new DistributedPubSubMediator.Subscribe(pubSubTopic, PUB_SUB_GROUP_PREFIX + connectionId,
                            getSelf());
            log.debug("Subscribing to pubsub topic '{}' for connection '{}'.", pubSubTopic, connectionId);
            pubSubMediator.tell(subscribe, getSelf());
        });
    }

    private void unsubscribeFromEvents() {
        forEachPubSubTopicDo(pubSubTopic -> {
            log.debug("Unsubscribing from pubsub topic '{}' for connection '{}'.", pubSubTopic, connectionId);
            final DistributedPubSubMediator.Unsubscribe unsubscribe =
                    new DistributedPubSubMediator.Unsubscribe(pubSubTopic, PUB_SUB_GROUP_PREFIX + connectionId,
                            getSelf());
            pubSubMediator.tell(unsubscribe, getSelf());
        });
    }

    private void forEachPubSubTopicDo(final Consumer<String> topicConsumer) {
        uniqueTopicPaths.stream()
                .map(Topic::getPubSubTopic)
                .forEach(topicConsumer);
    }

    private void handleCommandDuringInitialization(final ConnectivityCommand command) {
        log.debug("Unexpected command during initialization of actor received: {} - "
                        + "Terminating this actor and sending 'ConnectionNotAccessibleException' to requester..",
                command.getType());
        getSender().tell(ConnectionNotAccessibleException.newBuilder(command.getId())
                .dittoHeaders(command.getDittoHeaders())
                .build(), getSelf());
    }

    private <E extends Event> void persistEvent(final E event, final Consumer<E> consumer) {
        persist(event, persistedEvent -> {
            log.debug("Successfully persisted Event '{}'", persistedEvent.getType());
            consumer.accept(persistedEvent);
            pubSubMediator.tell(new DistributedPubSubMediator.Publish(event.getType(), event, true), getSelf());

            // save a snapshot if there were too many changes since the last snapshot
            if ((lastSequenceNr() - lastSnapshotSequenceNr) > snapshotThreshold) {
                doSaveSnapshot();
            }
        });
    }

    private void doSaveSnapshot() {
        if (snapshotInProgress) {
            log.debug("Already requested taking a Snapshot - not doing it again");
        } else if (connection != null) {
            snapshotInProgress = true;
            log.info("Attempting to save Snapshot for Connection: <{}> ..", connection);
            // save a snapshot
            final Object snapshotToStore = snapshotAdapter.toSnapshotStore(connection);
            saveSnapshot(snapshotToStore);
        } else {
            log.warning("Connection and MappingContext must not be null when taking snapshot.");
        }
    }

    private void startClientActorIfRequired() {
        checkNotNull(connectionId, "connectionId");
        checkNotNull(connection, "connection");
        if (clientActor == null) {
            final int clientCount = connection.getClientCount();
            log.info("Starting ClientActor for connection <{}> with <{}> clients.", connectionId, clientCount);
            final Props props = propsFactory.getActorPropsForType(connection, conciergeForwarder);
            final ClusterRouterPoolSettings clusterRouterPoolSettings =
                    new ClusterRouterPoolSettings(clientCount, 1, true,
                            Collections.singleton(CLUSTER_ROLE));
            final RoundRobinPool roundRobinPool = new RoundRobinPool(clientCount);
            final Props clusterRouterPoolProps =
                    new ClusterRouterPool(roundRobinPool, clusterRouterPoolSettings).props(props);

            // start client actor without name so it does not conflict with its previous incarnation
            clientActor = getContext().actorOf(clusterRouterPoolProps);
        } else {
            log.debug("ClientActor already started.");
        }
    }

    private void stopClientActor() {
        if (clientActor != null) {
            log.debug("Stopping the client actor.");
            stopChildActor(clientActor);
            clientActor = null;
        }
    }

    private void stopChildActor(final ActorRef actor) {
        log.debug("Stopping child actor '{}'", actor.path());
        getContext().stop(actor);
    }

    private void stopSelf() {
        log.debug("Shutting down");
        // stop the supervisor (otherwise it'd restart this actor) which causes this actor to stop, too.
        getContext().getParent().tell(PoisonPill.getInstance(), getSelf());
    }

    private void handleSubscribeAck(final DistributedPubSubMediator.SubscribeAck subscribeAck) {
        log.debug("Successfully subscribed to distributed pub/sub on topic '{}'", subscribeAck.subscribe().topic());
    }

    private void handleUnsubscribeAck(final DistributedPubSubMediator.UnsubscribeAck unsubscribeAck) {
        log.debug("Successfully unsubscribed from distributed pub/sub on topic '{}'",
                unsubscribeAck.unsubscribe().topic());
    }

    private void handleSnapshotSuccess(final SaveSnapshotSuccess sss) {
        log.debug("Snapshot was saved successfully: {}", sss);
    }

    private void schedulePendingResponse(final ConnectivityCommandResponse response, final ActorRef sender) {
        // flush previous responses before scheduling the next flush
        flushPendingResponses();
        pendingResponses.add(WithSender.of(response, sender));
        final PerformTask flushPendingResponses =
                new PerformTask("flush pending responses", ConnectionActor::flushPendingResponses);
        flushPendingResponsesTrigger = getContext().system().scheduler()
                .scheduleOnce(flushPendingResponsesTimeout,
                        getSelf(),
                        flushPendingResponses,
                        getContext().dispatcher(),
                        ActorRef.noSender());
    }

    private static Consumer<ConnectionActor> subscribeForEventsAndScheduleResponse(
            final ConnectivityCommandResponse response,
            final ActorRef sender) {

        return connectionActor -> {
            connectionActor.subscribeForEvents();
            connectionActor.schedulePendingResponse(response, sender);
        };
    }

    /**
     * Send all pending responses to their senders and cancel response flushing trigger.
     */
    private void flushPendingResponses() {
        cancelFlushPendingResponsesTrigger();
        while (!pendingResponses.isEmpty()) {
            final WithSender<ConnectivityCommandResponse> withSender = pendingResponses.remove();
            withSender.getSender().tell(withSender.getMessage(), getSelf());
        }
    }

    private void cancelFlushPendingResponsesTrigger() {
        if (flushPendingResponsesTrigger != null) {
            flushPendingResponsesTrigger.cancel();
            flushPendingResponsesTrigger = null;
        }
    }

    /**
     * Self-message for future tasks to run synchronously in actor's thread.
     * Minimal wrapping of thread-unsafe operations so that they do not corrupt actor state.
     * The results of such operations are not guaranteed to make sense.
     */
    private static final class PerformTask {

        final String description;
        final Consumer<ConnectionActor> task;

        private PerformTask(final String description, final Consumer<ConnectionActor> task) {
            this.description = description;
            this.task = task;
        }

        private void run(final ConnectionActor thisActor) {
            task.accept(thisActor);
        }

        @Override
        public final String toString() {
            return String.format("PerformTask(%s)", description);
        }
    }

    /**
     * Local helper-actor which is started for aggregating several CommandResponses sent back by potentially several
     * {@code clientActors} (behind a cluster Router running on different cluster nodes).
     */
    private static class AggregateActor extends AbstractActor {

        private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

        private final List<CommandResponse<?>> aggregatedResults;
        private final Map<String, Status.Status> aggregatedStatus;

        private final String connectionId;
        private final ActorRef clientActor;
        private final int expectedResponses;
        private final long timeout;

        private int responseCount = 0;
        @Nullable private ActorRef origin;
        @Nullable private DittoHeaders originHeaders;

        /**
         * Creates Akka configuration object for this actor.
         *
         * @return the Akka configuration Props object
         */
        static Props props(final String connectionId, final ActorRef clientActor, final int expectedResponses,
                final long timeout) {
            return Props.create(AggregateActor.class, connectionId, clientActor, expectedResponses, timeout);
        }

        private AggregateActor(final String connectionId, final ActorRef clientActor, final int expectedResponses,
                final long timeout) {
            this.connectionId = connectionId;
            this.clientActor = clientActor;
            this.expectedResponses = expectedResponses;
            this.timeout = timeout;
            aggregatedResults = new ArrayList<>();
            aggregatedStatus = new HashMap<>();
        }

        @Override
        public Receive createReceive() {
            return ReceiveBuilder.create()
                    .match(Command.class, command -> {
                        clientActor.tell(new Broadcast(command), getSelf());
                        originHeaders = command.getDittoHeaders();
                        origin = getSender();
                        getContext().setReceiveTimeout(Duration.create(timeout / 2.0, TimeUnit.MILLISECONDS));
                    })
                    .match(ReceiveTimeout.class, timeout -> {
                        // send back (partially) gathered responses
                        sendBackAggregatedResults();
                    })
                    .matchAny(any -> {
                        if (any instanceof CommandResponse) {
                            aggregatedResults.add((CommandResponse<?>) any);
                        } else if (any instanceof Status.Status) {
                            aggregatedStatus.put(getSender().path().address().hostPort(), (Status.Status) any);
                        } else if (any instanceof DittoRuntimeException) {
                            aggregatedResults.add(ConnectivityErrorResponse.of((DittoRuntimeException) any));
                        } else {
                            log.error("Could not handle non-Jsonifiable non-Status response: {}", any);
                        }
                        responseCount++;
                        if (expectedResponses == responseCount) {
                            // send back all gathered responses
                            sendBackAggregatedResults();
                        }
                    })
                    .build();
        }

        private void sendBackAggregatedResults() {
            if (origin != null && originHeaders != null && !aggregatedResults.isEmpty()) {
                final String responseType = aggregatedResults.get(0).getType();
                final AggregatedConnectivityCommandResponse response =
                        AggregatedConnectivityCommandResponse.of(connectionId, aggregatedResults, responseType,
                                HttpStatusCode.OK, originHeaders);
                log.debug("Aggregated response: {}", response);
                origin.tell(response, getSelf());
            } else if (origin != null && originHeaders != null && !aggregatedStatus.isEmpty()) {
                log.debug("Aggregated stati: {}", this.aggregatedStatus);
                final Optional<Status.Status> failure = this.aggregatedStatus.entrySet().stream()
                        .filter(s -> s.getValue() instanceof Status.Failure)
                        .map(Map.Entry::getValue)
                        .findFirst();
                if (failure.isPresent()) {
                    origin.tell(failure.get(), getSelf());
                } else {
                    final String aggregatedStatusStr = this.aggregatedStatus.entrySet().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining(","));
                    origin.tell(new Status.Success(aggregatedStatusStr), getSelf());
                }
            } else {
                log.warning("No origin was present or results were empty in order to send back aggregated results to");
            }
            getContext().stop(getSelf());
        }
    }

}
