package io.fireflysoftware.core.banking.payments.core.managed.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentStore.EffectRow;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.managed.ManagedPaymentContracts.EffectKind;
import io.fireflysoftware.core.banking.payments.models.repositories.managed.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ManagedPaymentEffectRescheduleTest {
    private final List<String> events = new ArrayList<>();
    private final List<Object[]> updates = new ArrayList<>();
    private final List<OffsetDateTime> times = List.of(
            OffsetDateTime.parse("2026-10-30T12:00:00.123456Z"),
            OffsetDateTime.parse("2026-10-30T12:00:01.654321Z"));
    private int clockReads;
    private Mono<Long> affected = Mono.just(1L);
    private Mono<Void> gate = Mono.empty();
    private final ManagedPaymentNativeRepository nativeRepository = mock(ManagedPaymentNativeRepository.class, call -> {
        return switch (call.getMethod().getName()) {
            case "databaseTime" -> {
                events.add("clock");
                yield Mono.just(times.get(clockReads++));
            }
            case "lockPayment" -> Mono.defer(() -> { events.add("gate"); return gate; });
            case "deferEffect", "resetEffect" -> throw new AssertionError("Ordinary effect updates must use the mapped fragment");
            default -> RETURNS_DEFAULTS.answer(call);
        };
    });
    private final ManagedPaymentJournalRepository journal = mock(ManagedPaymentJournalRepository.class, call -> {
        if (call.getMethod().getName().equals("rescheduleEffect")) {
            events.add("mapped");updates.add(call.getArguments().clone());return affected;
        }
        return RETURNS_DEFAULTS.answer(call);
    });
    private final ManagedPaymentStore store = new ManagedPaymentStore(mock(ManagedPaymentOperationRepository.class),
            mock(ManagedPaymentActionRepository.class), mock(ManagedPaymentEffectRepository.class),
            mock(ManagedPaymentHistoryRepository.class), journal, nativeRepository);
    private final UUID effectId = UUID.fromString("00000000-0000-0000-0000-000000000041");

    private EffectRow effect(int attempts) {
        return new EffectRow(effectId, UUID.fromString("00000000-0000-0000-0000-000000000042"),
                EffectKind.OBSERVE, "CLAIMED", 7L, attempts, "actor", Instant.parse("2026-10-30T11:59:59Z"));
    }

    @Test
    void reschedulingIsLazyAndSamplesFreshDatabaseTimeAfterTheOriginalGateOnEverySubscription() {
        var work = store.gate("bank", UUID.randomUUID()).then(store.deferEffect("bank", effect(2)));
        assertThat(events).isEmpty();
        work.block();work.block();
        assertThat(events).containsExactly("gate", "clock", "mapped", "gate", "clock", "mapped");
        assertThat(updates.get(0)[2]).isEqualTo(Instant.parse("2026-10-30T12:00:02.123456Z"));
        assertThat(updates.get(1)[2]).isEqualTo(Instant.parse("2026-10-30T12:00:03.654321Z"));
    }

    @Test
    void deferPreservesBackoffAndExhaustionWithoutChangingTheExpectedFence() {
        int[] attempts = {0, 1, 2, 6, 9, 10};
        int[] seconds = {1, 1, 2, 30, 30, 30};
        for (int i = 0; i < attempts.length; i++) {
            clockReads = 0;updates.clear();store.deferEffect("bank", effect(attempts[i])).block();
            assertThat(updates).hasSize(1);
            assertThat(updates.getFirst()[0]).isEqualTo(new ManagedPaymentJournalOperations.EffectVersion(
                    new io.fireflysoftware.core.banking.payments.models.entities.managed.ManagedPaymentIds.EffectId("bank", effectId), 7L, attempts[i]));
            assertThat(updates.getFirst()[1]).isEqualTo(attempts[i] == 10 ? "EXHAUSTED" : "PENDING");
            assertThat(updates.getFirst()[2]).isEqualTo(times.getFirst().toInstant().plusSeconds(seconds[i]));
        }
    }

    @Test
    void resetUsesTheFreshDatabaseInstantAndPendingState() {
        store.resetEffect("bank", effect(10)).block();
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst()[1]).isEqualTo("PENDING");
        assertThat(updates.getFirst()[2]).isEqualTo(Instant.parse("2026-10-30T12:00:00.123456Z"));
    }

    @Test
    void nonSingleOrAbsentAffectedRowsRemainFenceConflicts() {
        for (Mono<Long> result : List.of(Mono.just(0L), Mono.just(2L), Mono.<Long>empty())) {
            affected = result;clockReads = 0;
            StepVerifier.create(store.resetEffect("bank", effect(3))).expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                var refusal = (ResponseStatusException) error;
                assertThat(refusal.getStatusCode().value()).isEqualTo(409);
                assertThat(refusal.getReason()).isEqualTo("PAYMENT_EFFECT_FENCE_CONFLICT");
            }).verify();
        }
    }

    @Test
    void failedOriginalGateCannotSampleAnotherClockOrWrite() {
        var expired = new IllegalStateException("Original authority expired");gate = Mono.error(expired);
        var work = store.gate("bank", UUID.randomUUID()).then(store.resetEffect("bank", effect(3)));
        StepVerifier.create(work).expectErrorMatches(error -> error == expired).verify();
        assertThat(events).containsExactly("gate");assertThat(updates).isEmpty();
    }
}
