/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.policies.persistence.actors.strategies.events;

import org.eclipse.ditto.model.policies.PolicyBuilder;
import org.eclipse.ditto.signals.events.policies.ResourceDeleted;

/**
 * This strategy handles {@link org.eclipse.ditto.signals.events.policies.ResourceDeleted} events.
 */
final class ResourceDeletedStrategy extends AbstractPolicyEventStrategy<ResourceDeleted> {

    @Override
    protected PolicyBuilder applyEvent(final ResourceDeleted rd, final PolicyBuilder policyBuilder) {
        return policyBuilder.removeResourceFor(rd.getLabel(), rd.getResourceKey());
    }

}
