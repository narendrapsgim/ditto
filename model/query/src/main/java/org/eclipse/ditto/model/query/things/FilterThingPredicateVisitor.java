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
package org.eclipse.ditto.model.query.things;

import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.query.model.expression.FilterFieldExpression;
import org.eclipse.ditto.model.query.model.expression.visitors.FilterFieldExpressionVisitor;

/**
 * FilterFieldExpressionVisitor for Java {@link Predicate}s of {@link Thing}s.
 */
public final class FilterThingPredicateVisitor implements FilterFieldExpressionVisitor<Predicate<Thing>> {

    private final Function<String, Predicate<Thing>> predicateFunction;

    private FilterThingPredicateVisitor(final Function<String, Predicate<Thing>> predicateFunction) {
        this.predicateFunction = predicateFunction;
    }

    public static Predicate<Thing> apply(final FilterFieldExpression expression,
            final Function<String, Predicate<Thing>> predicateFunction) {
        return expression.acceptFilterVisitor(new FilterThingPredicateVisitor(predicateFunction));
    }

    @Override
    public Predicate<Thing> visitAttribute(final String key) {

        return predicateFunction.apply("/attributes/" + key);
    }

    @Override
    public Predicate<Thing> visitFeatureIdProperty(final String featureId, final String property) {

        return predicateFunction.apply("/features/" + featureId + "/properties/" + property);
    }

    @Override
    public Predicate<Thing> visitFeatureProperty(final String property) {
        return thing -> thing.getFeatures()
                .map(features -> features.stream()
                        .anyMatch(feature -> feature.getProperty(property).isPresent())
                )
                .orElse(false);
    }

    @Override
    public Predicate<Thing> visitSimple(final String fieldName) {
        return predicateFunction.apply(fieldName);
    }

    @Override
    public Predicate<Thing> visitAcl() {
        return predicateFunction.apply("/acl");
    }

    @Override
    public Predicate<Thing> visitGlobalReads() {
        return thing -> true; // TODO what about them?
    }
}
