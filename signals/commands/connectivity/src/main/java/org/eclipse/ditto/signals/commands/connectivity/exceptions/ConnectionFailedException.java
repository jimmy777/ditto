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
package org.eclipse.ditto.signals.commands.connectivity.exceptions;

import java.net.URI;
import java.text.MessageFormat;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeExceptionBuilder;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonParsableException;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.model.connectivity.ConnectivityException;

/**
 * Thrown if a {@link Connection} failed to connect.
 */
@Immutable
@JsonParsableException(errorCode = ConnectionFailedException.ERROR_CODE)
public final class ConnectionFailedException extends DittoRuntimeException implements ConnectivityException {

    /**
     * Error code of this exception.
     */
    public static final String ERROR_CODE = ERROR_CODE_PREFIX + "connection.failed";

    private static final String MESSAGE_TEMPLATE = "The Connection with ID ''{0}'' failed to connect.";

    private static final String DEFAULT_DESCRIPTION = "The requested Connection could not be connected due to an " +
            "internal failure of the underlying driver.";

    private static final long serialVersionUID = 897914540900650802L;

    private ConnectionFailedException(final DittoHeaders dittoHeaders,
            @Nullable final String message,
            @Nullable final String description,
            @Nullable final Throwable cause,
            @Nullable final URI href) {
        super(ERROR_CODE, HttpStatusCode.GATEWAY_TIMEOUT, dittoHeaders, message, description, cause, href);
    }

    /**
     * A mutable builder for a {@code ConnectionFailedException}.
     *
     * @param connectionId the ID of the connection.
     * @return the builder.
     */
    public static Builder newBuilder(final ConnectionId connectionId) {
        return new Builder(connectionId);
    }

    /**
     * Constructs a new {@code ConnectionFailedException} object with given message.
     *
     * @param message detail message. This message can be later retrieved by the {@link #getMessage()} method.
     * @param description error description, may be @{@code null}.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new ConnectionFailedException.
     */
    public static ConnectionFailedException from(final String message, @Nullable final String description,
            final DittoHeaders dittoHeaders) {
        return new Builder()
                .dittoHeaders(dittoHeaders)
                .message(message)
                .description(description)
                .build();
    }

    /**
     * Constructs a new {@code ConnectionFailedException} object with the exception message extracted from the
     * given JSON object.
     *
     * @param jsonObject the JSON to read the
     * {@link org.eclipse.ditto.model.base.exceptions.DittoRuntimeException.JsonFields#MESSAGE} field from.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new ConnectionFailedException.
     * @throws org.eclipse.ditto.json.JsonMissingFieldException if the {@code jsonObject} does not have the {@link
     * org.eclipse.ditto.model.base.exceptions.DittoRuntimeException.JsonFields#MESSAGE} field.
     */
    public static ConnectionFailedException fromJson(final JsonObject jsonObject, final DittoHeaders dittoHeaders) {
        return new Builder()
                .dittoHeaders(dittoHeaders)
                .message(readMessage(jsonObject))
                .description(readDescription(jsonObject).orElse(DEFAULT_DESCRIPTION))
                .href(readHRef(jsonObject).orElse(null))
                .build();
    }

    /**
     * A mutable builder with a fluent API for a {@link ConnectionFailedException}.
     */
    @NotThreadSafe
    public static final class Builder extends DittoRuntimeExceptionBuilder<ConnectionFailedException> {

        private Builder() {
            description(DEFAULT_DESCRIPTION);
        }

        private Builder(final ConnectionId connectionId) {
            this();
            message(MessageFormat.format(MESSAGE_TEMPLATE, String.valueOf(connectionId)));
        }

        @Override
        protected ConnectionFailedException doBuild(final DittoHeaders dittoHeaders,
                @Nullable final String message,
                @Nullable final String description,
                @Nullable final Throwable cause,
                @Nullable final URI href) {
            return new ConnectionFailedException(dittoHeaders, message, description, cause, href);
        }

    }

}
