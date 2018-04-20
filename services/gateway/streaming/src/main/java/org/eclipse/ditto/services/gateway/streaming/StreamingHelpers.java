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
package org.eclipse.ditto.services.gateway.streaming;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.eclipse.ditto.model.things.Feature;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingBuilder;
import org.eclipse.ditto.signals.events.things.AclEntryCreated;
import org.eclipse.ditto.signals.events.things.AclEntryDeleted;
import org.eclipse.ditto.signals.events.things.AclEntryModified;
import org.eclipse.ditto.signals.events.things.AclModified;
import org.eclipse.ditto.signals.events.things.AttributeCreated;
import org.eclipse.ditto.signals.events.things.AttributeDeleted;
import org.eclipse.ditto.signals.events.things.AttributeModified;
import org.eclipse.ditto.signals.events.things.AttributesCreated;
import org.eclipse.ditto.signals.events.things.AttributesDeleted;
import org.eclipse.ditto.signals.events.things.AttributesModified;
import org.eclipse.ditto.signals.events.things.FeatureCreated;
import org.eclipse.ditto.signals.events.things.FeatureDeleted;
import org.eclipse.ditto.signals.events.things.FeatureModified;
import org.eclipse.ditto.signals.events.things.FeaturePropertiesCreated;
import org.eclipse.ditto.signals.events.things.FeaturePropertiesDeleted;
import org.eclipse.ditto.signals.events.things.FeaturePropertiesModified;
import org.eclipse.ditto.signals.events.things.FeaturePropertyCreated;
import org.eclipse.ditto.signals.events.things.FeaturePropertyDeleted;
import org.eclipse.ditto.signals.events.things.FeaturePropertyModified;
import org.eclipse.ditto.signals.events.things.FeaturesCreated;
import org.eclipse.ditto.signals.events.things.FeaturesDeleted;
import org.eclipse.ditto.signals.events.things.FeaturesModified;
import org.eclipse.ditto.signals.events.things.PolicyIdCreated;
import org.eclipse.ditto.signals.events.things.PolicyIdModified;
import org.eclipse.ditto.signals.events.things.ThingCreated;
import org.eclipse.ditto.signals.events.things.ThingDeleted;
import org.eclipse.ditto.signals.events.things.ThingEvent;
import org.eclipse.ditto.signals.events.things.ThingModified;

/**
 * Helpers and utils for streaming based stuff.
 */
public final class StreamingHelpers {

    private static final Map<Class<?>, BiFunction<ThingEvent, ThingBuilder.FromScratch, Thing>> EVENT_TO_THING_MAPPERS =
            createEventToThingMappers();

    private StreamingHelpers() {
        throw new AssertionError();
    }

    /**
     * Creates a Thing from the passed ThingEvent.
     *
     * @param thingEvent the ThingEvent to extract the correlating Thing from
     * @return the Thing represented by the passed in ThingEvent
     */
    public static Optional<Thing> thingEventToThing(final ThingEvent thingEvent) {

        return Optional.ofNullable(EVENT_TO_THING_MAPPERS.get(thingEvent.getClass()))
                .map(eventToThingMapper -> {
                    final ThingBuilder.FromScratch thingBuilder = Thing.newBuilder()
                            .setId(thingEvent.getThingId())
                            .setRevision(thingEvent.getRevision());
                    return eventToThingMapper.apply(thingEvent, thingBuilder);
                });
    }

    private static Map<Class<?>, BiFunction<ThingEvent, ThingBuilder.FromScratch, Thing>> createEventToThingMappers() {
        final Map<Class<?>, BiFunction<ThingEvent, ThingBuilder.FromScratch, Thing>> mappers = new HashMap<>();

        mappers.put(ThingCreated.class,
                (te, tb) -> ((ThingCreated) te).getThing().toBuilder().setRevision(te.getRevision()).build());
        mappers.put(ThingModified.class,
                (te, tb) -> ((ThingModified) te).getThing().toBuilder().setRevision(te.getRevision()).build());
        mappers.put(ThingDeleted.class,
                (te, tb) -> tb.build());

        mappers.put(AclModified.class,
                (te, tb) -> tb.setPermissions(((AclModified) te).getAccessControlList()).build());
        mappers.put(AclEntryCreated.class,
                (te, tb) -> tb.setPermissions(((AclEntryCreated) te).getAclEntry()).build());
        mappers.put(AclEntryModified.class,
                (te, tb) -> tb.setPermissions(((AclEntryModified) te).getAclEntry()).build());
        mappers.put(AclEntryDeleted.class,
                (te, tb) -> tb.build());

        mappers.put(PolicyIdCreated.class,
                (te, tb) -> tb.setPolicyId(((PolicyIdCreated) te).getPolicyId()).build());
        mappers.put(PolicyIdModified.class,
                (te, tb) -> tb.setPolicyId(((PolicyIdModified) te).getPolicyId()).build());

        mappers.put(AttributesCreated.class,
                (te, tb) -> tb.setAttributes(((AttributesCreated) te).getCreatedAttributes()).build());
        mappers.put(AttributesModified.class,
                (te, tb) -> tb.setAttributes(((AttributesModified) te).getModifiedAttributes()).build());
        mappers.put(AttributesDeleted.class, (te, tb) -> tb.build());
        mappers.put(AttributeCreated.class, (te, tb) -> tb.setAttribute(((AttributeCreated) te).getAttributePointer(),
                ((AttributeCreated) te).getAttributeValue()).build());
        mappers.put(AttributeModified.class, (te, tb) -> tb.setAttribute(((AttributeModified) te).getAttributePointer(),
                ((AttributeModified) te).getAttributeValue()).build());
        mappers.put(AttributeDeleted.class, (te, tb) -> tb.build());

        mappers.put(FeaturesCreated.class, (te, tb) -> tb.setFeatures(((FeaturesCreated) te).getFeatures()).build());
        mappers.put(FeaturesModified.class, (te, tb) -> tb.setFeatures(((FeaturesModified) te).getFeatures()).build());
        mappers.put(FeaturesDeleted.class, (te, tb) -> tb.build());
        mappers.put(FeatureCreated.class, (te, tb) -> tb.setFeature(((FeatureCreated) te).getFeature()).build());
        mappers.put(FeatureModified.class, (te, tb) -> tb.setFeature(((FeatureModified) te).getFeature()).build());
        mappers.put(FeatureDeleted.class, (te, tb) -> tb.build());

        mappers.put(FeaturePropertiesCreated.class, (te, tb) -> tb.setFeature(Feature.newBuilder()
                .properties(((FeaturePropertiesCreated) te).getProperties())
                .withId(((FeaturePropertiesCreated) te).getFeatureId())
                .build()).build());
        mappers.put(FeaturePropertiesModified.class, (te, tb) -> tb.setFeature(Feature.newBuilder()
                .properties(((FeaturePropertiesModified) te).getProperties())
                .withId(((FeaturePropertiesModified) te).getFeatureId())
                .build()).build());
        mappers.put(FeaturePropertiesDeleted.class, (te, tb) -> tb.build());
        mappers.put(FeaturePropertyCreated.class, (te, tb) ->
                tb.setFeatureProperty(((FeaturePropertyCreated) te).getFeatureId(),
                        ((FeaturePropertyCreated) te).getPropertyPointer(),
                        ((FeaturePropertyCreated) te).getPropertyValue()).build());
        mappers.put(FeaturePropertyModified.class, (te, tb) ->
                tb.setFeatureProperty(((FeaturePropertyModified) te).getFeatureId(),
                        ((FeaturePropertyModified) te).getPropertyPointer(),
                        ((FeaturePropertyModified) te).getPropertyValue()).build());
        mappers.put(FeaturePropertyDeleted.class, (te, tb) -> tb.build());

        return mappers;
    }
}
