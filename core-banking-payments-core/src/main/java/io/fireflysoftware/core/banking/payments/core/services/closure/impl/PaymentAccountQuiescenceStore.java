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
import io.fireflysoftware.core.banking.payments.models.entities.closure.*;
import io.fireflysoftware.core.banking.payments.models.repositories.closure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.*;
import java.util.*;

@Component
public final class PaymentAccountQuiescenceStore {
    private final PaymentAccountActionRepository actions;
    private final PaymentAccountFenceRepository fences;
    private final PaymentAccountObservationRepository observations;
    private final PaymentAccountObservationMemberRepository members;
    private final PaymentAccountNativeRepository nativeRepository;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public PaymentAccountQuiescenceStore(PaymentAccountActionRepository actions,
            PaymentAccountFenceRepository fences, PaymentAccountObservationRepository observations,
            PaymentAccountObservationMemberRepository members, PaymentAccountNativeRepository nativeRepository) {
        this.actions = actions; this.fences = fences; this.observations = observations;
        this.members = members; this.nativeRepository = nativeRepository;
    }

    public record Action(String kind, String digest, Selection selection, String request, String receipt) {}
    public record Fence(UUID fenceId, UUID closureId, Selection selection, FenceState state,
            long version, QuiescenceReceipt acceptance) {}

    <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw unavailable(); }
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw unavailable(); }
    }

    Mono<Void> accountGate(String tenant, UUID account) { return nativeRepository.lockAccount(tenant, account); }

    Mono<Action> action(String tenant, UUID account, UUID action) {
        return actions.findByIdTenantIdAndIdActionIdAndAccountId(tenant, action, account)
                .map(row -> new Action(row.kind(), row.intentDigest(), decode(row.selection().value(), Selection.class),
                        row.request().value(), row.receipt().value()));
    }

    Mono<Fence> fence(String tenant, UUID account, UUID closure) {
        return fences.findByIdTenantIdAndIdAccountIdAndClosureId(tenant, account, closure)
                .map(row -> new Fence(row.id().fenceId(), row.closureId(), decode(row.selection().value(), Selection.class),
                        FenceState.valueOf(row.state()), row.version(), decode(row.acceptance().value(), QuiescenceReceipt.class)));
    }

    Mono<QuiescenceReceipt> quiesce(Selection selected, Quiesce input, long ledgerVersion, String digest) {
        return nativeRepository.quiesce(selected.tenantId(), selected.accountId(), input.actionId(), input.closureId(),
                        input.enrollmentId(), input.expectedFenceVersion(), encode(selected), ledgerVersion, digest)
                .map(row -> decode(row.receipt().value(), QuiescenceReceipt.class))
                .switchIfEmpty(Mono.error(unavailable()));
    }

    Mono<ReleaseReceipt> release(String tenant, UUID account, Release input, String digest) {
        return nativeRepository.release(tenant, account, input.actionId(), input.closureId(), input.expectedFenceVersion(), digest)
                .map(row -> decode(row.receipt().value(), ReleaseReceipt.class)).switchIfEmpty(Mono.error(unavailable()));
    }

    Mono<ObservationReceipt> observation(String tenant, UUID account, UUID id) {
        return observations.findById(new PaymentAccountIds.ObservationId(tenant, account, id))
                .flatMap(row -> fences.findById(new PaymentAccountIds.FenceId(tenant, account, row.fenceId()))
                        .filter(fence -> fence.state().equals("FENCED") && fence.version().equals(row.fenceVersion()))
                        .map(fence -> decode(row.receipt().value(), ObservationReceipt.class)));
    }

    Mono<ObservationReceipt> capture(String tenant, UUID account, UUID closure, UUID id) {
        return nativeRepository.capture(tenant, account, closure, id)
                .map(row -> decode(row.receipt().value(), ObservationReceipt.class)).switchIfEmpty(Mono.error(unavailable()));
    }

    Mono<ObligationPage> page(ObservationReceipt receipt, long after, int limit) {
        return members.findByIdTenantIdAndIdAccountIdAndIdObservationIdAndOrdinalGreaterThanOrderByOrdinalAsc(
                        receipt.selection().tenantId(), receipt.selection().accountId(), receipt.observationId(),
                        after, PageRequest.of(0, Math.addExact(limit, 1)))
                .map(row -> new Obligation(row.id().obligationId(), row.ordinal(), row.direction(), row.state(),
                        row.effectState(), Boolean.TRUE.equals(row.blocking()), row.operationVersion()))
                .collectList().map(rows -> {
                    boolean more = rows.size() > limit;
                    var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
                    return new ObligationPage(receipt, items, more ? items.getLast().ordinal() : null);
                });
    }

    Mono<View> view(Fence fence) {
        return nativeRepository.latest(fence.selection().tenantId(), fence.selection().accountId(), fence.fenceId())
                .map(row -> decode(row.receipt().value(), ObservationReceipt.class))
                .map(o -> new View(fence.fenceId(), fence.closureId(), fence.selection(), fence.state(), fence.version(),
                        fence.state() == FenceState.FENCED && fence.version() == o.fenceVersion() ? o.status() : ObservationStatus.UNKNOWN,
                        o.observedSourceVersion(), o.observationId()))
                .defaultIfEmpty(new View(fence.fenceId(), fence.closureId(), fence.selection(), fence.state(),
                        fence.version(), ObservationStatus.UNKNOWN, null, null));
    }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "RETAINED_PAYMENT_CLOSURE_EVIDENCE_UNAVAILABLE");
    }
}
