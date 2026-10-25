/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.core.banking.payments.core.services.closure.impl;

import io.fireflysoftware.core.banking.payments.core.services.closure.*;

import static io.fireflysoftware.core.banking.payments.interfaces.dtos.closure.PaymentAccountQuiescenceContracts.*;

import java.util.*;

public final class PaymentAccountQuiescenceRules {
    private PaymentAccountQuiescenceRules() {}

    public static Quiesce decode(byte[] raw) {
        var value =
                object(
                        raw,
                        java.util.Set.of(
                                "actionId", "closureId", "enrollmentId", "expectedFenceVersion"));
        var input =
                new Quiesce(
                        identifier(value, "actionId"),
                        identifier(value, "closureId"),
                        identifier(value, "enrollmentId"),
                        number(value, "expectedFenceVersion"));
        basic(input);
        return input;
    }

    public static Release decodeRelease(byte[] raw) {
        var value = object(raw, Set.of("actionId", "closureId", "expectedFenceVersion"));
        var input =
                new Release(
                        identifier(value, "actionId"),
                        identifier(value, "closureId"),
                        number(value, "expectedFenceVersion"));
        release(input);
        return input;
    }

    static void release(Release input) {
        if (input == null
                || !uuid(input.actionId())
                || !uuid(input.closureId())
                || input.expectedFenceVersion() == null
                || input.expectedFenceVersion() < 0) throw bad();
    }

    public static void page(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw bad();
    }

    static void basic(Quiesce input) {
        if (input == null
                || !uuid(input.actionId())
                || !uuid(input.closureId())
                || !uuid(input.enrollmentId())
                || input.expectedFenceVersion() == null
                || input.expectedFenceVersion() < 0) throw bad();
    }

    public static void admit(Selection selected, Quiesce input, long currentVersion) {
        selection(selected);
        basic(input);
        if (currentVersion < 0) throw bad();
        if (!selected.enrollmentId().equals(input.enrollmentId())
                || input.expectedFenceVersion() != currentVersion)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "QUIESCENCE_ADMISSION_CONFLICT");
    }

    static void selection(Selection value) {
        if (value == null
                || value.tenantId() == null
                || !value.tenantId().matches("[A-Za-z0-9_-]{1,64}")
                || !uuid(value.accountId())
                || !uuid(value.enrollmentId())
                || !uuid(value.contractId())
                || value.currency() == null
                || !value.currency().matches("[A-Z]{3}")
                || value.currencyScale() == null
                || value.currencyScale() < 0
                || value.currencyScale() > 18
                || value.currencyPolicy() == null
                || value.currencyPolicy().isBlank()
                || value.currencyPolicy().length() > 100
                || !java.nio.charset.StandardCharsets.UTF_8
                        .newEncoder()
                        .canEncode(value.currencyPolicy())
                || value.currencyPolicy().codePoints().anyMatch(Character::isISOControl))
            throw bad();
    }

    private static final int MAX_BYTES = 8192;
    private static final com.fasterxml.jackson.databind.json.JsonMapper JSON =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build();

    static {
        JSON.getFactory()
                .setStreamReadConstraints(
                        com.fasterxml.jackson.core.StreamReadConstraints.builder()
                                .maxStringLength(MAX_BYTES)
                                .maxNestingDepth(4)
                                .maxNumberLength(20)
                                .build());
    }

    static org.springframework.web.server.ResponseStatusException bad() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "EXACT_CLOSURE_REQUEST_REQUIRED");
    }

    static boolean uuid(java.util.UUID value) {
        return value != null && !value.equals(new java.util.UUID(0L, 0L));
    }

    static com.fasterxml.jackson.databind.JsonNode object(
            byte[] raw, java.util.Set<String> fields) {
        try {
            if (raw == null || raw.length == 0 || raw.length > MAX_BYTES) throw bad();
            String text =
                    java.nio.charset.StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(raw))
                            .toString();
            try (var tokens = JSON.createParser(text)) {
                com.fasterxml.jackson.core.JsonToken token;
                while ((token = tokens.nextToken()) != null) {
                    if (token == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_FLOAT
                            || (token == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT
                                    && !tokens.getText().matches("0|[1-9][0-9]*"))) throw bad();
                }
            }
            try (var parser = JSON.createParser(text)) {
                com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(parser);
                if (tree == null || !tree.isObject() || parser.nextToken() != null) throw bad();
                var actual = new java.util.HashSet<String>();
                tree.fieldNames().forEachRemaining(actual::add);
                if (!actual.equals(fields)) throw bad();
                return tree;
            }
        } catch (org.springframework.web.server.ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw bad();
        }
    }

    static String text(com.fasterxml.jackson.databind.JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isTextual()) throw bad();
        return value.textValue();
    }

    static Long number(com.fasterxml.jackson.databind.JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw bad();
        return value.longValue();
    }

    static java.util.UUID identifier(com.fasterxml.jackson.databind.JsonNode root, String field) {
        String value = text(root, field);
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw bad();
        java.util.UUID id = java.util.UUID.fromString(value);
        if (!uuid(id)) throw bad();
        return id;
    }
}
