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
package org.eclipse.ditto.signals.commands.things;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.ThingTooLargeException;
import org.eclipse.ditto.signals.commands.base.AbstractCommandSizeValidator;

/**
 * Command size validator for thing commands.
 */
public final class ThingCommandSizeValidator extends AbstractCommandSizeValidator<ThingTooLargeException> {

    /**
     * System property name of the property defining the max Thing size in bytes.
     */
    public static final String DITTO_LIMITS_THINGS_MAX_SIZE_BYTES = "ditto.limits.things.max-size";

    @Nullable private static ThingCommandSizeValidator instance;

    private ThingCommandSizeValidator(@Nullable final Long maxSize) {
        super(maxSize);
    }

    @Override
    protected ThingTooLargeException newInvalidSizeException(final long maxSize, final long actualSize,
            final DittoHeaders headers) {
        return ThingTooLargeException.newBuilder(actualSize, maxSize).dittoHeaders(headers).build();
    }

    /**
     * The singleton instance for this command size validator. Will be initialized at first call.
     *
     * @return Singleton instance
     */
    public static ThingCommandSizeValidator getInstance() {
        if (null == instance) {
            final long maxSize =
                    Long.parseLong(System.getProperty(DITTO_LIMITS_THINGS_MAX_SIZE_BYTES, DEFAULT_LIMIT));
            instance = (maxSize > 0) ? new ThingCommandSizeValidator(maxSize) : new ThingCommandSizeValidator(null);
        }
        return instance;
    }

}
