/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.utils.persistence.mongo;

import java.util.function.Predicate;

import org.bson.BsonValue;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonParseException;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.FieldType;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.signals.events.base.Event;
import org.eclipse.ditto.signals.events.base.EventRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.actor.ExtendedActorSystem;
import akka.persistence.journal.EventAdapter;
import akka.persistence.journal.EventSeq;

/**
 * Abstract event adapter for {@link org.eclipse.ditto.signals.events.base.Event}.
 */
public abstract class AbstractMongoEventAdapter<T extends Event> implements EventAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMongoEventAdapter.class);
    private static final Predicate<JsonField> IS_REVISION = field -> field.getDefinition()
            .filter(Event.JsonFields.REVISION::equals)
            .isPresent();

    private final ExtendedActorSystem system;
    private final EventRegistry<T> eventRegistry;

    protected AbstractMongoEventAdapter(final ExtendedActorSystem system, final EventRegistry<T> eventRegistry) {
        this.system = system;
        this.eventRegistry = eventRegistry;
    }

    @Override
    public String manifest(final Object event) {
        if (event instanceof Event) {
            return ((Event) event).getType();
        } else {
            throw new IllegalArgumentException(
                    "Unable to create manifest for a non-'Event' object! Was: " + event.getClass());
        }
    }

    @Override
    public Object toJournal(final Object event) {
        if (event instanceof Event) {
            final Event<?> theEvent = (Event) event;
            final JsonSchemaVersion schemaVersion = theEvent.getImplementedSchemaVersion();
            final JsonObject jsonObject =
                    theEvent.toJson(schemaVersion, IS_REVISION.negate().and(FieldType.regularOrSpecial()));
            return DittoBsonJson.getInstance().parse(jsonObject);
        } else {
            throw new IllegalArgumentException("Unable to toJournal a non-'Event' object! Was: " + event.getClass());
        }
    }

    @Override
    public EventSeq fromJournal(final Object event, final String manifest) {
        if (event instanceof BsonValue) {
            return EventSeq.single(tryParseEvent(DittoBsonJson.getInstance().serialize((BsonValue) event)));
        } else {
            throw new IllegalArgumentException(
                    "Unable to fromJournal a non-'BsonValue' object! Was: " + event.getClass());
        }
    }

    private T tryParseEvent(final JsonValue jsonValue) {
        try {
            return parseEvent(jsonValue);
        } catch (final JsonParseException | DittoRuntimeException e) {
            if (system != null) {
                system.log().error(e, "Could not deserialize Event JSON: '{}'", jsonValue);
            } else {
                LOGGER.error("Could not deserialize Event JSON: '{}': {}", jsonValue, e.getMessage());
            }
            return null;
        }
    }

    private T parseEvent(final JsonValue jsonValue) {
        final JsonObject jsonObject = jsonValue.asObject()
                .setValue(Event.JsonFields.REVISION.getPointer(), Event.DEFAULT_REVISION);
        return eventRegistry.parse(jsonObject, DittoHeaders.empty());
    }

}
