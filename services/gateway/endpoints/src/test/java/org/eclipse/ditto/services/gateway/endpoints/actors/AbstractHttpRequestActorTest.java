/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.gateway.endpoints.actors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.auth.AuthorizationModelFactory;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.auth.DittoAuthorizationContextType;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.headers.DittoHeaderDefinition;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.messages.Message;
import org.eclipse.ditto.model.messages.MessageDirection;
import org.eclipse.ditto.model.messages.MessageHeaderDefinition;
import org.eclipse.ditto.model.messages.MessageHeaders;
import org.eclipse.ditto.model.policies.SubjectId;
import org.eclipse.ditto.model.policies.SubjectIssuer;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.protocoladapter.HeaderTranslator;
import org.eclipse.ditto.services.gateway.endpoints.routes.whoami.DefaultUserInformation;
import org.eclipse.ditto.services.gateway.endpoints.routes.whoami.UserInformation;
import org.eclipse.ditto.services.gateway.endpoints.routes.whoami.Whoami;
import org.eclipse.ditto.services.gateway.util.config.DittoGatewayConfig;
import org.eclipse.ditto.services.gateway.util.config.GatewayConfig;
import org.eclipse.ditto.services.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.signals.commands.messages.SendThingMessage;
import org.eclipse.ditto.signals.commands.messages.SendThingMessageResponse;
import org.eclipse.ditto.signals.commands.things.modify.ModifyAttribute;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import com.typesafe.config.ConfigFactory;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ResponseEntity;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;

/**
 * Abstract class to setup the infrastructure to test HttpRequestActor.
 */
public abstract class AbstractHttpRequestActorTest {

    static final HeaderTranslator HEADER_TRANSLATOR = HeaderTranslator.of(
            DittoHeaderDefinition.values(),
            MessageHeaderDefinition.values());

    static ActorSystem system;
    static GatewayConfig gatewayConfig;

    @BeforeClass
    public static void beforeClass() {
        system = ActorSystem.create();
        final DefaultScopedConfig dittoConfig = DefaultScopedConfig.dittoScoped(ConfigFactory.load("test.conf"));
        gatewayConfig = DittoGatewayConfig.of(dittoConfig);
    }

    @AfterClass
    public static void afterClass() {
        if (null != system) {
            TestKit.shutdownActorSystem(system);
        }
    }

    void testThingModifyCommand(final ThingId thingId,
            final String attributeName,
            final JsonPointer attributePointer,
            final DittoHeaders dittoHeaders,
            final DittoHeaders expectedHeaders,
            @Nullable final Object probeResponse,
            final StatusCode expectedHttpStatusCode,
            final boolean expectHttpResponseHeaders) throws InterruptedException, ExecutionException {

        final TestProbe proxyActorProbe = TestProbe.apply(system);

        final ModifyAttribute modifyAttribute = ModifyAttribute.of(
                thingId, attributePointer, JsonValue.of("bar"), dittoHeaders);

        final HttpRequest request =
                HttpRequest.PUT("/api/2/things/" + thingId.toString() + "/attributes/" + attributeName);
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

        final ActorRef underTest = createHttpRequestActor(proxyActorProbe.ref(), request, responseFuture);
        underTest.tell(modifyAttribute, ActorRef.noSender());

        final ModifyAttribute expectedModifyAttribute = modifyAttribute.setDittoHeaders(expectedHeaders);
        proxyActorProbe.expectMsg(expectedModifyAttribute);

        if (null != probeResponse) {
            proxyActorProbe.reply(probeResponse);
        }

        final HttpResponse response = responseFuture.get();
        assertThat(response.status()).isEqualTo(expectedHttpStatusCode);
        if (expectHttpResponseHeaders) {
            final List<HttpHeader> expectedHttpResponseHeaders = HEADER_TRANSLATOR.toExternalHeaders(expectedHeaders)
                    .entrySet()
                    .stream()
                    .map(e -> RawHeader.create(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            assertThat(response.getHeaders()).isEqualTo(expectedHttpResponseHeaders);
        }
    }

    void testMessageCommand(final ThingId thingId,
            final String messageSubject,
            final DittoHeaders dittoHeaders,
            final DittoHeaders expectedHeaders,
            @Nullable final SendThingMessageResponse<?> probeResponse,
            final StatusCode expectedHttpStatusCode,
            final boolean expectHttpResponseHeaders,
            @Nullable final ResponseEntity expectedHttpResponseEntity) throws InterruptedException, ExecutionException {

        HttpResponse expectedHttpResponse = HttpResponse.create().withStatus(expectedHttpStatusCode);
        if (expectHttpResponseHeaders) {
            final List<HttpHeader> expectedHttpResponseHeaders = HEADER_TRANSLATOR.toExternalHeaders(expectedHeaders)
                    .entrySet()
                    .stream()
                    .map(e -> RawHeader.create(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            expectedHttpResponse = expectedHttpResponse.withHeaders(expectedHttpResponseHeaders);
        }
        if (null != expectedHttpResponseEntity) {
            expectedHttpResponse = expectedHttpResponse.withEntity(expectedHttpResponseEntity);
        }
        assertThat(testMessageCommand(thingId, messageSubject, dittoHeaders, expectedHeaders, probeResponse))
                .isEqualTo(expectedHttpResponse);
    }

    HttpResponse testMessageCommand(final ThingId thingId,
            final String messageSubject,
            final DittoHeaders dittoHeaders,
            final DittoHeaders expectedHeaders,
            @Nullable final SendThingMessageResponse<?> probeResponse) throws InterruptedException, ExecutionException {

        final TestProbe proxyActorProbe = TestProbe.apply(system);

        final Message<?> message = Message.newBuilder(
                MessageHeaders.newBuilder(MessageDirection.TO, thingId, messageSubject)
                        .contentType("application/json")
                        .build()
        )
                .payload(JsonValue.of("ping"))
                .build();
        final SendThingMessage<?> sendThingMessage = SendThingMessage.of(thingId, message, dittoHeaders);

        final HttpRequest request =
                HttpRequest.PUT("/api/2/things/" + thingId.toString() + "/inbox/messages/" + messageSubject);
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

        final ActorRef underTest = createHttpRequestActor(proxyActorProbe.ref(), request, responseFuture);
        underTest.tell(sendThingMessage, ActorRef.noSender());

        final SendThingMessage<?> expectedSendThingMessage = sendThingMessage.setDittoHeaders(expectedHeaders);
        proxyActorProbe.expectMsg(expectedSendThingMessage);

        if (null != probeResponse) {
            proxyActorProbe.reply(probeResponse);
        }

        return responseFuture.get();
    }

    SendThingMessageResponse<?> buildSendThingMessageResponse(final ThingId thingId,
            final String messageSubject,
            final DittoHeaders expectedHeaders,
            final String contentType,
            final JsonValue responsePayload) {
        final Message<?> responseMessage = Message.newBuilder(
                MessageHeaders.newBuilder(MessageDirection.TO, thingId, messageSubject)
                        .statusCode(HttpStatusCode.IMUSED)
                        .contentType(contentType)
                        .build()
        )
                .payload(responsePayload)
                .build();
        return SendThingMessageResponse.of(thingId, responseMessage, HttpStatusCode.IMUSED, expectedHeaders);
    }

    ActorRef createHttpRequestActor(final ActorRef proxyActorRef, final HttpRequest request,
            final CompletableFuture<HttpResponse> response) {
        return system.actorOf(HttpRequestActor.props(
                proxyActorRef,
                HEADER_TRANSLATOR,
                request,
                response,
                gatewayConfig.getHttpConfig(),
                gatewayConfig.getCommandConfig()
        ));
    }

    DittoHeaders createAuthorizedHeaders() {
        final AuthorizationContext context = AuthorizationContext.newInstance(DittoAuthorizationContextType.JWT,
                AuthorizationSubject.newInstance(SubjectId.newInstance(SubjectIssuer.GOOGLE, "any-google-user")),
                AuthorizationSubject.newInstance(
                        SubjectId.newInstance(SubjectIssuer.INTEGRATION, "any-integration-subject")));
        return DittoHeaders.newBuilder()
                .randomCorrelationId()
                .authorizationContext(context)
                .build();
    }

    HttpResponse createExpectedWhoamiResponse(final Whoami whoami) {
        final AuthorizationContext authContext =
                getAuthContextWithPrefixedSubjectsFromHeaders(whoami.getDittoHeaders());
        final UserInformation userInformation = DefaultUserInformation.fromAuthorizationContext(authContext);
        final List<HttpHeader> expectedHeaders = HEADER_TRANSLATOR.toExternalHeaders(whoami.getDittoHeaders())
                .entrySet()
                .stream()
                .map(e -> RawHeader.create(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        return HttpResponse.create().withStatus(StatusCodes.OK)
                .withEntity(ContentTypes.APPLICATION_JSON, ByteString.fromString(userInformation.toJsonString()))
                .withHeaders(expectedHeaders);
    }

    static AuthorizationContext getAuthContextWithPrefixedSubjectsFromHeaders(final DittoHeaders headers) {
        final String authContextString = headers.get(DittoHeaderDefinition.AUTHORIZATION_CONTEXT.getKey());
        final JsonObject authContextJson = authContextString == null ?
                JsonObject.empty() :
                JsonObject.of(authContextString);
        return AuthorizationModelFactory.newAuthContext(authContextJson);
    }

}
