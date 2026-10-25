package io.fireflysoftware.core.banking.payments.models.entities.managed;

import java.util.Objects;

/** An owner JSON document, distinct from ordinary text columns. */
public record PaymentJson(String value) {
    public PaymentJson { Objects.requireNonNull(value, "JSON document required"); }
}
