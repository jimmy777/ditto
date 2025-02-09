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
package org.eclipse.ditto.connectivity.service.messaging.httppush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.eclipse.ditto.base.model.acks.AcknowledgementLabel;
import org.eclipse.ditto.base.model.acks.AcknowledgementRequest;
import org.eclipse.ditto.base.model.acks.DittoAcknowledgementLabel;
import org.eclipse.ditto.base.model.common.DittoConstants;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.Signal;
import org.eclipse.ditto.base.model.signals.acks.Acknowledgement;
import org.eclipse.ditto.base.model.signals.acks.Acknowledgements;
import org.eclipse.ditto.connectivity.api.ExternalMessage;
import org.eclipse.ditto.connectivity.api.ExternalMessageFactory;
import org.eclipse.ditto.connectivity.api.OutboundSignal;
import org.eclipse.ditto.connectivity.api.OutboundSignalFactory;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.ConnectivityModelFactory;
import org.eclipse.ditto.connectivity.model.HmacCredentials;
import org.eclipse.ditto.connectivity.model.Target;
import org.eclipse.ditto.connectivity.model.Topic;
import org.eclipse.ditto.connectivity.service.messaging.AbstractPublisherActorTest;
import org.eclipse.ditto.connectivity.service.messaging.ConnectivityStatusResolver;
import org.eclipse.ditto.connectivity.service.messaging.TestConstants;
import org.eclipse.ditto.internal.utils.metrics.instruments.timer.PreparedTimer;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.messages.model.Message;
import org.eclipse.ditto.messages.model.MessageDirection;
import org.eclipse.ditto.messages.model.MessageHeaders;
import org.eclipse.ditto.messages.model.signals.commands.SendFeatureMessageResponse;
import org.eclipse.ditto.messages.model.signals.commands.SendThingMessage;
import org.eclipse.ditto.messages.model.signals.commands.SendThingMessageResponse;
import org.eclipse.ditto.protocol.Adaptable;
import org.eclipse.ditto.protocol.ProtocolFactory;
import org.eclipse.ditto.protocol.adapter.DittoProtocolAdapter;
import org.eclipse.ditto.things.model.ThingId;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.event.LoggingAdapter;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.Uri;
import akka.japi.Pair;
import akka.stream.Attributes;
import akka.stream.SystemMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;
import scala.util.Try;

/**
 * Tests {@link HttpPublisherActor}.
 */
public final class HttpPublisherActorTest extends AbstractPublisherActorTest {

    private static final String CONTENT_TYPE = "APPLICATION/VND.ECLIPSE.DITTO+JSON ; PARAM_NAME=PARAM_VALUE";
    private static final String CUSTOM_HEADER_NAME = "my-custom-header";
    private static final String CUSTOM_HEADER_VALUE = "bumlux";
    private static final String BODY = "[\"The quick brown fox jumps over the lazy dog.\"]";

    private HttpPushFactory httpPushFactory;
    private final BlockingQueue<HttpRequest> received = new LinkedBlockingQueue<>();

    @Override
    protected String getOutboundAddress() {
        return "PATCH:/the/quick/brown/fox/jumps/over/the/lazy/dog?someQuery=foo&entity={{ entity:id }}";
    }

    @Override
    protected void setupMocks(final TestProbe probe) {
        httpPushFactory = mockHttpPushFactory(CONTENT_TYPE, HttpStatus.OK, BODY);

        // activate debug log to show responses
        actorSystem.eventStream().setLogLevel(Attributes.logLevelDebug());
    }

    @Override
    protected Props getPublisherActorProps() {
        return HttpPublisherActor.props(TestConstants.createConnection(), httpPushFactory, "clientId",
                mock(ConnectivityStatusResolver.class));
    }

    @Override
    protected Target decorateTarget(final Target target) {
        return target;
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Override
    protected void verifyPublishedMessage() throws Exception {
        final HttpRequest request = received.take();
        assertThat(received).isEmpty();

        // method
        assertThat(request.method()).isEqualTo(HttpMethods.PATCH);

        // uri
        assertThat(request.getUri().host().address()).isEqualTo("8.8.4.4");
        assertThat(request.getUri().query().get("entity")).contains(TestConstants.Things.THING_ID.toString());
        assertThat(request.getUri().port()).isEqualTo(12345);

        // headers
        assertThat(request.getHeader("thing_id").get().value()).isEqualTo(TestConstants.Things.THING_ID.toString());
        assertThat(request.getHeader("suffixed_thing_id").get().value())
                .contains(TestConstants.Things.THING_ID + ".some.suffix");
        assertThat(request.getHeader("prefixed_thing_id").get().value())
                .isEqualTo("some.prefix." + TestConstants.Things.THING_ID);
        assertThat(request.getHeader("eclipse").get().value()).isEqualTo("ditto");
        assertThat(request.getHeader("device_id").get().value()).isEqualTo(TestConstants.Things.THING_ID.toString());

        final HttpEntity.Strict entity = request.entity()
                .toStrict(60_000L, SystemMaterializer.get(actorSystem).materializer())
                .toCompletableFuture()
                .join();
        assertThat(entity.getData().utf8String()).isEqualTo("payload");
        if (!entity.getContentType().toString().equals(DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE)) {
            // Ditto protocol content type is parsed as binary for some reason
            assertThat(entity.getContentType().binary()).isFalse();
        }
    }

    @Override
    protected void verifyAcknowledgements(final Supplier<Acknowledgements> ackSupplier) {
        final Acknowledgements acks = ackSupplier.get();
        assertThat(acks.getSize()).describedAs("Expect 1 acknowledgement in: " + acks).isEqualTo(1);
        final Acknowledgement ack = acks.stream().findAny().orElseThrow();
        assertThat(ack.getLabel().toString()).describedAs("Ack label").isEqualTo("please-verify");
        assertThat(ack.getHttpStatus()).describedAs("Ack status").isEqualTo(HttpStatus.OK);
        assertThat(ack.getEntity()).contains(JsonFactory.readFrom(BODY));
        assertThat(ack.getDittoHeaders()).containsAllEntriesOf(
                Map.of("content-type", "application/vnd.eclipse.ditto+json; PARAM_NAME=PARAM_VALUE", CUSTOM_HEADER_NAME,
                        CUSTOM_HEADER_VALUE));
    }

    @Test
    public void testPlainTextAck() {
        new TestKit(actorSystem) {{
            httpPushFactory = mockHttpPushFactory("text/plain", HttpStatus.OK, "hello!");

            final AcknowledgementLabel label = AcknowledgementLabel.of("please-verify");
            final Target target = decorateTarget(createTestTarget(label));

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            publisherActor.tell(newMultiMappedWithContentType(target, getRef()), getRef());
            final Acknowledgements acks = expectMsgClass(Acknowledgements.class);
            assertThat(acks.getAcknowledgement(label)).isNotEmpty();
            final Acknowledgement ack = acks.getAcknowledgement(label).orElseThrow();
            assertThat(ack.getDittoHeaders()).containsAllEntriesOf(
                    Map.of("content-type", "text/plain", CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE));
            assertThat(ack.getEntity()).contains(JsonValue.of("hello!"));
        }};
    }

    @Test
    public void testBinaryAck() {
        new TestKit(actorSystem) {{
            httpPushFactory = mockHttpPushFactory("application/octet-stream", HttpStatus.OK, "hello!");

            final AcknowledgementLabel label = AcknowledgementLabel.of("please-verify");
            final Target target = decorateTarget(createTestTarget(label));

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            publisherActor.tell(newMultiMappedWithContentType(target, getRef()), getRef());
            final Acknowledgements acks = expectMsgClass(Acknowledgements.class);
            assertThat(acks.getAcknowledgement(label)).isNotEmpty();
            final Acknowledgement ack = acks.getAcknowledgement(label).orElseThrow();
            assertThat(ack.getDittoHeaders()).containsAllEntriesOf(
                    Map.of("content-type", "application/octet-stream", CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE));
            assertThat(ack.getEntity()).contains(JsonValue.of("aGVsbG8h"));
        }};
    }

    @Test
    public void testMessageCommandHttpPushCreatesCommandResponse() {
        new TestKit(actorSystem) {{
            final String customContentType = "application/vnd.org.eclipse.ditto.foobar+json";
            final var httpStatus = HttpStatus.IM_A_TEAPOT;
            final JsonValue jsonResponse = JsonFactory.readFrom("{ \"foo\": true }");
            httpPushFactory = mockHttpPushFactory(customContentType, httpStatus, jsonResponse.toString());

            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address(getOutboundAddress())
                    .originalAddress(getOutboundAddress())
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .headerMapping(TestConstants.HEADER_MAPPING)
                    .issuedAcknowledgementLabel(DittoAcknowledgementLabel.LIVE_RESPONSE)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            final MessageDirection messageDirection = MessageDirection.FROM;
            final String messageSubject = "please-respond";
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(messageDirection, TestConstants.Things.THING_ID, messageSubject)
                            .build()
            ).build();
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder()
                    .correlationId(TestConstants.CORRELATION_ID)
                    .putHeader("device_id", "ditto:thing")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, dittoHeaders);
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            final SendThingMessageResponse<JsonValue> sendThingMessageResponse =
                    expectMsgClass(SendThingMessageResponse.class);
            assertThat((CharSequence) sendThingMessageResponse.getEntityId()).isEqualTo(TestConstants.Things.THING_ID);
            assertThat(sendThingMessageResponse.getHttpStatus()).isEqualTo(httpStatus);
            assertThat(sendThingMessageResponse.getDittoHeaders().getCorrelationId())
                    .contains(TestConstants.CORRELATION_ID);

            final Message<JsonValue> responseMessage = sendThingMessageResponse.getMessage();
            assertThat(responseMessage.getContentType()).contains(customContentType);
            assertThat(responseMessage.getSubject()).isEqualTo(messageSubject);
            assertThat(responseMessage.getDirection()).isEqualTo(messageDirection);
            assertThat(responseMessage.getHttpStatus()).hasValue(httpStatus);
            assertThat(responseMessage.getPayload()).contains(jsonResponse);

            final MessageHeaders responseMessageHeaders = responseMessage.getHeaders();
            assertThat(responseMessageHeaders.get(CUSTOM_HEADER_NAME)).isEqualTo(CUSTOM_HEADER_VALUE);
        }};
    }

    @Test
    public void testMessageCommandHttpPushWithNonLiveResponseIssuedAcknowledgement() {
        new TestKit(actorSystem) {{
            final String contentType = "application/json";
            final var httpStatus = HttpStatus.IM_A_TEAPOT;
            final JsonValue jsonResponse = JsonFactory.readFrom("{ \"foo\": true }");
            httpPushFactory = mockHttpPushFactory(contentType, httpStatus, jsonResponse.toString());
            final AcknowledgementLabel autoAckLabel = AcknowledgementLabel.of("foo:bar");
            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address(getOutboundAddress())
                    .originalAddress(getOutboundAddress())
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .headerMapping(TestConstants.HEADER_MAPPING)
                    .issuedAcknowledgementLabel(autoAckLabel)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            final MessageDirection messageDirection = MessageDirection.FROM;
            final String messageSubject = "please-respond";
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(messageDirection, TestConstants.Things.THING_ID, messageSubject)
                            .build()
            ).build();
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder()
                    .correlationId(TestConstants.CORRELATION_ID)
                    .putHeader("device_id", "ditto:thing")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE),
                            AcknowledgementRequest.of(autoAckLabel))
                    .build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, dittoHeaders);
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            final Acknowledgements acknowledgements = expectMsgClass(Acknowledgements.class);
            assertThat(acknowledgements).hasSize(1);
            final Acknowledgement acknowledgement = acknowledgements.getAcknowledgement(autoAckLabel).get();
            assertThat(acknowledgement).isNotNull();
            assertThat(acknowledgement.getHttpStatus()).isEqualTo(httpStatus);
            assertThat(acknowledgement.getEntityId().toString()).hasToString(TestConstants.Things.THING_ID.toString());
        }};
    }

    @Test
    public void testMessageCommandHttpPushCreatesCommandResponseFromProtocolMessage() {
        new TestKit(actorSystem) {{
            final String customContentType = "application/vnd.org.eclipse.ditto.foobar+json";
            final String messageSubject = "please-respond";
            final String contentType = DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE;
            final var httpStatus = HttpStatus.IM_A_TEAPOT;
            final MessageHeaders messageHeaders =
                    MessageHeaders.newBuilder(MessageDirection.FROM, TestConstants.Things.THING_ID, messageSubject)
                            .contentType(customContentType)
                            .correlationId(TestConstants.CORRELATION_ID)
                            .putHeader(CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE)
                            .build();
            final JsonValue jsonResponse = JsonFactory.readFrom("{ \"foo\": true }");
            final Message<JsonValue> response =
                    Message.<JsonValue>newBuilder(messageHeaders).payload(jsonResponse).build();
            final SendThingMessageResponse<JsonValue> sendMessageResponse =
                    SendThingMessageResponse.of(TestConstants.Things.THING_ID, response, httpStatus,
                            messageHeaders);
            final Adaptable messageResponseAdaptable =
                    DittoProtocolAdapter.newInstance().toAdaptable(sendMessageResponse);
            final JsonObject messageResponseJson =
                    ProtocolFactory.wrapAsJsonifiableAdaptable(messageResponseAdaptable).toJson();
            httpPushFactory = mockHttpPushFactory(contentType, httpStatus, messageResponseJson.toString());

            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address(getOutboundAddress())
                    .originalAddress(getOutboundAddress())
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .headerMapping(TestConstants.HEADER_MAPPING)
                    .issuedAcknowledgementLabel(DittoAcknowledgementLabel.LIVE_RESPONSE)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            final MessageDirection messageDirection = MessageDirection.FROM;
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(messageDirection, TestConstants.Things.THING_ID, messageSubject)
                            .build()
            ).build();
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder()
                    .correlationId(TestConstants.CORRELATION_ID)
                    .putHeader("device_id", "ditto:thing")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, dittoHeaders);
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            final SendThingMessageResponse<JsonValue> sendThingMessageResponse =
                    expectMsgClass(SendThingMessageResponse.class);
            assertThat((CharSequence) sendThingMessageResponse.getEntityId()).isEqualTo(TestConstants.Things.THING_ID);
            assertThat(sendThingMessageResponse.getHttpStatus()).isEqualTo(httpStatus);
            assertThat(sendThingMessageResponse.getDittoHeaders().getCorrelationId())
                    .contains(TestConstants.CORRELATION_ID);

            final Message<JsonValue> responseMessage = sendThingMessageResponse.getMessage();
            assertThat(responseMessage.getContentType()).contains(customContentType);
            assertThat(responseMessage.getSubject()).isEqualTo(messageSubject);
            assertThat(responseMessage.getDirection()).isEqualTo(messageDirection);
            assertThat(responseMessage.getHttpStatus()).hasValue(httpStatus);
            assertThat(responseMessage.getPayload()).contains(jsonResponse);

            final MessageHeaders responseMessageHeaders = responseMessage.getHeaders();
            assertThat(responseMessageHeaders.get(CUSTOM_HEADER_NAME)).isEqualTo(CUSTOM_HEADER_VALUE);
        }};
    }

    @Test
    public void sendingLiveResponseWithWrongCorrelationIdDoesNotWork() {
        new TestKit(actorSystem) {{
            final String customContentType = "application/vnd.org.eclipse.ditto.foobar+json";
            final String messageSubject = "please-respond";
            final String contentType = DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE;
            final var httpStatus = HttpStatus.IM_A_TEAPOT;
            final MessageHeaders messageHeaders =
                    MessageHeaders.newBuilder(MessageDirection.FROM, TestConstants.Things.THING_ID, messageSubject)
                            .contentType(customContentType)
                            .correlationId("otherID")
                            .putHeader(CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE)
                            .build();
            final JsonValue jsonResponse = JsonFactory.readFrom("{ \"foo\": true }");
            final Message<JsonValue> response =
                    Message.<JsonValue>newBuilder(messageHeaders).payload(jsonResponse).build();
            final SendThingMessageResponse<JsonValue> sendMessageResponse =
                    SendThingMessageResponse.of(TestConstants.Things.THING_ID, response, httpStatus, messageHeaders);
            final Adaptable messageResponseAdaptable =
                    DittoProtocolAdapter.newInstance().toAdaptable(sendMessageResponse);
            final JsonObject messageResponseJson =
                    ProtocolFactory.wrapAsJsonifiableAdaptable(messageResponseAdaptable).toJson();
            httpPushFactory = mockHttpPushFactory(contentType, httpStatus, messageResponseJson.toString());

            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address(getOutboundAddress())
                    .originalAddress(getOutboundAddress())
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .headerMapping(TestConstants.HEADER_MAPPING)
                    .issuedAcknowledgementLabel(DittoAcknowledgementLabel.LIVE_RESPONSE)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            final MessageDirection messageDirection = MessageDirection.FROM;
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(messageDirection, TestConstants.Things.THING_ID, messageSubject)
                            .build()
            ).build();
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder()
                    .correlationId(TestConstants.CORRELATION_ID)
                    .putHeader("device_id", "ditto:thing")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, dittoHeaders);
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            final Acknowledgements acknowledgements = expectMsgClass(Acknowledgements.class);
            assertThat((CharSequence) acknowledgements.getEntityId()).isEqualTo(TestConstants.Things.THING_ID);
            assertThat(acknowledgements.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(acknowledgements.getDittoHeaders().getCorrelationId())
                    .contains(TestConstants.CORRELATION_ID);
            assertThat(acknowledgements.getSize()).isOne();
            final Optional<Acknowledgement> acknowledgement =
                    acknowledgements.getAcknowledgement(DittoAcknowledgementLabel.LIVE_RESPONSE);
            assertThat(acknowledgement).isPresent();
            assertThat(acknowledgement.get().toJson().toString())
                    .contains("Correlation ID of response <otherID> does not match correlation ID of message " +
                            "command <cid>");
        }};
    }

    @Test
    public void sendingLiveResponseToDifferentThingIdDoesNotWork() {
        new TestKit(actorSystem) {{
            final String customContentType = "application/vnd.org.eclipse.ditto.foobar+json";
            final String messageSubject = "please-respond";
            final String contentType = DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE;
            final var httpStatus = HttpStatus.IM_A_TEAPOT;
            final ThingId wrongThingId = ThingId.of("namespace:wrongthing");
            final MessageHeaders messageHeaders =
                    MessageHeaders.newBuilder(MessageDirection.FROM, wrongThingId, messageSubject)
                            .contentType(customContentType)
                            .correlationId(TestConstants.CORRELATION_ID)
                            .putHeader(CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE)
                            .build();
            final JsonValue jsonResponse = JsonFactory.readFrom("{ \"foo\": true }");
            final Message<JsonValue> response =
                    Message.<JsonValue>newBuilder(messageHeaders).payload(jsonResponse).build();
            final SendThingMessageResponse<JsonValue> sendMessageResponse =
                    SendThingMessageResponse.of(wrongThingId, response, httpStatus, messageHeaders);
            final Adaptable messageResponseAdaptable =
                    DittoProtocolAdapter.newInstance().toAdaptable(sendMessageResponse);
            final JsonObject messageResponseJson =
                    ProtocolFactory.wrapAsJsonifiableAdaptable(messageResponseAdaptable).toJson();
            httpPushFactory = mockHttpPushFactory(contentType, httpStatus, messageResponseJson.toString());

            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address(getOutboundAddress())
                    .originalAddress(getOutboundAddress())
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .headerMapping(TestConstants.HEADER_MAPPING)
                    .issuedAcknowledgementLabel(DittoAcknowledgementLabel.LIVE_RESPONSE)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            final MessageDirection messageDirection = MessageDirection.FROM;
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(messageDirection, TestConstants.Things.THING_ID, messageSubject)
                            .build()
            ).build();
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder()
                    .correlationId(TestConstants.CORRELATION_ID)
                    .putHeader("device_id", "ditto:thing")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, dittoHeaders);
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            final Acknowledgements acknowledgements = expectMsgClass(Acknowledgements.class);
            assertThat((CharSequence) acknowledgements.getEntityId()).isEqualTo(TestConstants.Things.THING_ID);
            assertThat(acknowledgements.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(acknowledgements.getDittoHeaders().getCorrelationId())
                    .contains(TestConstants.CORRELATION_ID);
            assertThat(acknowledgements.getSize()).isOne();
            final Optional<Acknowledgement> acknowledgement =
                    acknowledgements.getAcknowledgement(DittoAcknowledgementLabel.LIVE_RESPONSE);
            assertThat(acknowledgement).isPresent();
            assertThat(acknowledgement.get().toJson().toString())
                    .contains("Live response does not target the correct thing. Expected thing ID <ditto:thing>, " +
                            "but was <namespace:wrongthing>.");
        }};
    }

    @Test
    public void sendingWrongResponseTypeDoesNotWork() {
        new TestKit(actorSystem) {{
            final String customContentType = "application/vnd.org.eclipse.ditto.foobar+json";
            final String messageSubject = "please-respond";
            final String contentType = DittoConstants.DITTO_PROTOCOL_CONTENT_TYPE;
            final var httpStatus = HttpStatus.IM_A_TEAPOT;
            final MessageHeaders messageHeaders =
                    MessageHeaders.newBuilder(MessageDirection.FROM, TestConstants.Things.THING_ID, messageSubject)
                            .contentType(customContentType)
                            .correlationId(TestConstants.CORRELATION_ID)
                            .putHeader(CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE)
                            .featureId("wrongId")
                            .build();
            final JsonValue jsonResponse = JsonFactory.readFrom("{ \"foo\": true }");
            final Message<JsonValue> response =
                    Message.<JsonValue>newBuilder(messageHeaders).payload(jsonResponse).build();
            final SendFeatureMessageResponse<JsonValue> sendMessageResponse = SendFeatureMessageResponse.of(
                    TestConstants.Things.THING_ID,
                    "wrongId",
                    response,
                    httpStatus,
                    messageHeaders);
            final Adaptable messageResponseAdaptable =
                    DittoProtocolAdapter.newInstance().toAdaptable(sendMessageResponse);
            final JsonObject messageResponseJson =
                    ProtocolFactory.wrapAsJsonifiableAdaptable(messageResponseAdaptable).toJson();
            httpPushFactory = mockHttpPushFactory(contentType, httpStatus, messageResponseJson.toString());

            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address(getOutboundAddress())
                    .originalAddress(getOutboundAddress())
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .headerMapping(TestConstants.HEADER_MAPPING)
                    .issuedAcknowledgementLabel(DittoAcknowledgementLabel.LIVE_RESPONSE)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            final MessageDirection messageDirection = MessageDirection.FROM;
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(messageDirection, TestConstants.Things.THING_ID, messageSubject)
                            .build()
            ).build();
            final DittoHeaders dittoHeaders = DittoHeaders.newBuilder()
                    .correlationId(TestConstants.CORRELATION_ID)
                    .putHeader("device_id", "ditto:thing")
                    .acknowledgementRequest(AcknowledgementRequest.of(DittoAcknowledgementLabel.LIVE_RESPONSE))
                    .build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, dittoHeaders);
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            final Acknowledgements acknowledgements = expectMsgClass(Acknowledgements.class);
            assertThat((CharSequence) acknowledgements.getEntityId()).isEqualTo(TestConstants.Things.THING_ID);
            assertThat(acknowledgements.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(acknowledgements.getDittoHeaders().getCorrelationId())
                    .contains(TestConstants.CORRELATION_ID);
            assertThat(acknowledgements.getSize()).isOne();
            final Optional<Acknowledgement> acknowledgement =
                    acknowledgements.getAcknowledgement(DittoAcknowledgementLabel.LIVE_RESPONSE);
            assertThat(acknowledgement).isPresent();
            assertThat(acknowledgement.get().toJson().toString())
                    .contains("Live response of type <messages.responses:featureResponseMessage> is not of " +
                            "expected type <messages.responses:thingResponseMessage>.");
        }};
    }

    @Override
    protected void verifyPublishedMessageToReplyTarget() throws Exception {
        final HttpRequest request = received.take();
        assertThat(received).isEmpty();
        assertThat(request.method()).isEqualTo(HttpMethods.POST);
        assertThat(request.getUri().getPathString()).isEqualTo("/replyTarget/thing:id");
        assertThat(request.getHeader("correlation-id"))
                .contains(HttpHeader.parse("correlation-id", TestConstants.CORRELATION_ID));
        assertThat(request.getHeader("mappedHeader2"))
                .contains(HttpHeader.parse("mappedHeader2", "thing:id"));
    }

    @Test
    public void testAzMonitorRequestSigning() throws Exception {
        new TestKit(actorSystem) {{
            // GIVEN: HTTP publisher actor configured to authenticate by HMAC request signing
            httpPushFactory = mockHttpPushFactory("none", HttpStatus.OK, "");
            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address("POST:/api/logs?api-version=2016-04-01")
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final HmacCredentials hmacCredentials = HmacCredentials.of("az-monitor-2016-04-01", JsonObject.newBuilder()
                    .set("workspaceId", "xxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
                    .set("sharedKey", "SGFsbG8gV2VsdCEgSXN0IGRhcyBhbG")
                    .build());

            final Connection connection = TestConstants.createConnection()
                    .toBuilder()
                    .credentials(hmacCredentials)
                    .build();
            final Props props = HttpPublisherActor.props(connection, httpPushFactory, "clientId",
                    mock(ConnectivityStatusResolver.class));
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            // WHEN: HTTP publisher sends an HTTP request
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(MessageDirection.FROM, TestConstants.Things.THING_ID, "please-respond")
                            .build()
            ).build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, DittoHeaders.empty());
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            // THEN: The request is signed by the configured request signing process.
            final HttpRequest signedRequest = received.take();
            final HttpRequest unsignedRequest = signedRequest.withHeaders(List.of());

            final Instant xMsDate = ZonedDateTime.parse(signedRequest.getHeader("x-ms-date").orElseThrow().value(),
                    AzMonitorRequestSigning.X_MS_DATE_FORMAT).toInstant();

            final HttpRequest expectedSignedRequest =
                    new AzMonitorRequestSigningFactory().create(actorSystem, hmacCredentials)
                            .sign(unsignedRequest, xMsDate)
                            .runWith(Sink.head(), actorSystem)
                            .toCompletableFuture()
                            .join();

            assertThat(signedRequest).isEqualTo(expectedSignedRequest);
        }};
    }

    @Test
    public void testAwsRequestSigning() throws Exception {
        new TestKit(actorSystem) {{
            // GIVEN: HTTP publisher actor configured to authenticate by HMAC request signing
            httpPushFactory = mockHttpPushFactory("none", HttpStatus.OK, "");
            final Target target = ConnectivityModelFactory.newTargetBuilder()
                    .address("POST:/api/logs?api-version=2016-04-01")
                    .authorizationContext(TestConstants.Authorization.AUTHORIZATION_CONTEXT)
                    .topics(Topic.LIVE_MESSAGES)
                    .build();

            final HmacCredentials hmacCredentials = HmacCredentials.of("aws4-hmac-sha256", JsonObject.newBuilder()
                    .set("region", "us-east-1")
                    .set("service", "iam")
                    .set("accessKey", "MyAwesomeAccessKey")
                    .set("secretKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
                    .set("doubleEncode", false)
                    .set("canonicalHeaders", JsonArray.newBuilder().add("x-amz-date", "host").build())
                    .build());

            final Connection connection = TestConstants.createConnection()
                    .toBuilder()
                    .credentials(hmacCredentials)
                    .build();
            final Props props = HttpPublisherActor.props(connection, httpPushFactory, "clientId",
                    mock(ConnectivityStatusResolver.class));
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);

            // WHEN: HTTP publisher sends an HTTP request
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(MessageDirection.FROM, TestConstants.Things.THING_ID, "please-respond")
                            .build()
            ).build();
            final Signal<?> source = SendThingMessage.of(TestConstants.Things.THING_ID, message, DittoHeaders.empty());
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);

            publisherActor.tell(
                    OutboundSignalFactory.newMultiMappedOutboundSignal(Collections.singletonList(mapped), getRef()),
                    getRef());

            // THEN: The request is signed by the configured request signing process.
            final HttpRequest signedRequest = received.take();
            final HttpRequest unsignedRequest = signedRequest.withHeaders(List.of());

            final Instant xAmzDate = ZonedDateTime.parse(signedRequest.getHeader("x-amz-date").orElseThrow().value(),
                    AwsRequestSigning.X_AMZ_DATE_FORMATTER).toInstant();

            final HttpRequest expectedSignedRequest =
                    new AwsRequestSigningFactory().create(actorSystem, hmacCredentials)
                            .sign(unsignedRequest, xAmzDate)
                            .runWith(Sink.head(), actorSystem)
                            .toCompletableFuture()
                            .join();

            assertThat(signedRequest).isEqualTo(expectedSignedRequest);
        }};
    }

    @Test
    public void testReservedHeaders() throws Exception {
        // GIVEN: reserved headers are set
        final Map<String, String> reservedHeaders = Map.of(
                "http.query", "a=b&c=d&e=f",
                "http.path", "my/awesome/path"
        );

        // WHEN: publisher actor is asked to publish a message with reserved headers
        final HttpRequest request = publishMessageWithHeaders(reservedHeaders);

        // THEN: reserved headers do not appear as HTTP headers
        assertThat(request.getHeader("http.query")).isEmpty();
        assertThat(request.getHeader("http.path")).isEmpty();

        // THEN: reserved headers are evaluated
        assertThat(request.getUri().queryString(StandardCharsets.UTF_8)).contains("a=b&c=d&e=f");
        assertThat(request.getUri().getPathString()).isEqualTo("/my/awesome/path");
    }

    @Test
    public void testHttpQueryReservedHeaderWithLeadingSlash() throws Exception {
        // GIVEN: reserved headers are set
        final Map<String, String> reservedHeaders = Map.of(
                "http.query", "a=b&c=d&e=f"
        );

        // WHEN: publisher actor is asked to publish a message with reserved headers
        final HttpRequest request = publishMessageWithHeaders(reservedHeaders);

        // THEN: reserved headers do not appear as HTTP headers
        assertThat(request.getHeader("http.query")).isEmpty();

        // THEN: reserved headers are evaluated
        assertThat(request.getUri().queryString(StandardCharsets.UTF_8)).contains("a=b&c=d&e=f");
    }

    @Test
    public void testHttpPathReservedHeaderWithLeadingSlash() throws Exception {
        // GIVEN: reserved headers are set
        final Map<String, String> reservedHeaders = Map.of(
                "http.path", "/my/awesome/path"
        );

        // WHEN: publisher actor is asked to publish a message with reserved headers
        final HttpRequest request = publishMessageWithHeaders(reservedHeaders);

        // THEN: reserved headers do not appear as HTTP headers
        assertThat(request.getHeader("http.path")).isEmpty();

        // THEN: reserved headers are evaluated
        assertThat(request.getUri().getPathString()).isEqualTo("/my/awesome/path");
    }

    private HttpRequest publishMessageWithHeaders(final Map<String, String> headers) throws InterruptedException {
        final Container<HttpRequest> published = new Container<>();
        new TestKit(actorSystem) {{

            // WHEN: publisher actor is asked to publish a message with reserved headers
            final TestProbe probe = new TestProbe(actorSystem);
            setupMocks(probe);
            final Target target = decorateTarget(createTestTarget());
            final Message<?> message = Message.newBuilder(
                    MessageHeaders.newBuilder(MessageDirection.FROM, TestConstants.Things.THING_ID,
                            "please-respond")
                            .build()
            ).build();
            final Signal<?> source =
                    SendThingMessage.of(TestConstants.Things.THING_ID, message, DittoHeaders.empty());
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(source, Collections.singletonList(target));
            final ExternalMessage externalMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(headers)
                            .withText("payload")
                            .build();
            final Adaptable adaptable = DittoProtocolAdapter.newInstance().toAdaptable(source);
            final OutboundSignal.Mapped mapped =
                    OutboundSignalFactory.newMappedOutboundSignal(outboundSignal, adaptable, externalMessage);
            final OutboundSignal.MultiMapped multiMapped =
                    OutboundSignalFactory.newMultiMappedOutboundSignal(List.of(mapped), getRef());
            final Props props = getPublisherActorProps();
            final ActorRef publisherActor = childActorOf(props);
            publisherCreated(this, publisherActor);
            publisherActor.tell(multiMapped, getRef());

            // THEN: reserved headers do not appear as HTTP headers
            published.setValue(received.take());
        }};
        return published.getValue();
    }

    private static class Container<T> {
        private T value;

        private void setValue(final T value) {
            this.value = value;
        }

        private T getValue() {
            return value;
        }

    }

    private OutboundSignal.MultiMapped newMultiMappedWithContentType(final Target target,
            final ActorRef sender) {
        return OutboundSignalFactory.newMultiMappedOutboundSignal(
                List.of(getMockOutboundSignal(target, "requested-acks",
                        JsonArray.of(JsonValue.of("please-verify")).toString())),
                sender
        );
    }

    private HttpPushFactory mockHttpPushFactory(final String contentType, final HttpStatus httpStatus,
            final String body) {

        return new DummyHttpPushFactory("8.8.4.4", request -> {
            received.offer(request);
            return HttpResponse.create()
                    .withStatus(httpStatus.getCode())
                    .addHeader(HttpHeader.parse(CUSTOM_HEADER_NAME, CUSTOM_HEADER_VALUE))
                    .withEntity(new akka.http.scaladsl.model.HttpEntity.Strict(
                            (akka.http.scaladsl.model.ContentType) ContentTypes.parse(contentType),
                            ByteString.fromString(body)
                    ));
        });
    }

    private static final class DummyHttpPushFactory implements HttpPushFactory {

        private final String hostname;
        private final Function<HttpRequest, HttpResponse> mapper;

        private DummyHttpPushFactory(final String hostname, final Function<HttpRequest, HttpResponse> mapper) {
            this.hostname = hostname;
            this.mapper = mapper;
        }

        @Override
        public HttpRequest newRequest(final HttpPublishTarget httpPublishTarget) {
            final String separator = httpPublishTarget.getPathWithQuery().startsWith("/") ? "" : "/";
            final Uri uri =
                    Uri.create("http://" + hostname + ":12345" + separator + httpPublishTarget.getPathWithQuery());
            return HttpRequest.create().withMethod(httpPublishTarget.getMethod()).withUri(uri);
        }

        @Override
        public <T> Flow<Pair<HttpRequest, T>, Pair<Try<HttpResponse>, T>, ?> createFlow(final ActorSystem system,
                final LoggingAdapter log, final Duration requestTimeout, @Nullable final PreparedTimer timer,
                @Nullable final Consumer<Duration> consumer) {
            return Flow.<Pair<HttpRequest, T>>create()
                    .map(pair -> Pair.create(Try.apply(() -> mapper.apply(pair.first())), pair.second()));
        }

    }

}
