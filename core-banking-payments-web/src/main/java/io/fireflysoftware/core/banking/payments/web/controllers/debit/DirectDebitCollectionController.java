/*
 * Copyright 2025 Firefly Software Foundation
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


package io.fireflysoftware.core.banking.payments.web.controllers.debit;

import io.fireflysoftware.core.banking.payments.core.services.debit.DirectDebitCollectionService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.DirectDebitCollectionDTO;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.StandingOrderRunDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DirectDebitCollectionController {

    private final DirectDebitCollectionService service;

    public DirectDebitCollectionController(DirectDebitCollectionService service) {
        this.service = service;
    }

    @PostMapping("/direct-debit-collections")
    @Tag(name = "DirectDebitCollection")
    @Operation(operationId = "collectDirectDebit", summary = "Store a direct-debit collection")
    public Mono<ResponseEntity<DirectDebitCollectionDTO>> collect(@RequestBody DirectDebitCollectionDTO body,
                                                                  @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return service.collect(body).map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @GetMapping("/direct-debit-collections/{collectionId}")
    @Tag(name = "DirectDebitCollection")
    @Operation(operationId = "getDirectDebitCollection", summary = "Read a direct-debit collection")
    public Mono<ResponseEntity<DirectDebitCollectionDTO>> getCollection(@PathVariable UUID collectionId,
                                                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return service.getCollection(collectionId).map(ResponseEntity::ok);
    }

    @PostMapping("/standing-order-runs")
    @Tag(name = "StandingOrderRun")
    @Operation(operationId = "runStandingOrder", summary = "Store a standing-order run")
    public Mono<ResponseEntity<StandingOrderRunDTO>> run(@RequestBody StandingOrderRunDTO body,
                                                         @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return service.runStandingOrder(body).map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @GetMapping("/standing-order-runs/{runId}")
    @Tag(name = "StandingOrderRun")
    @Operation(operationId = "getStandingOrderRun", summary = "Read a standing-order run")
    public Mono<ResponseEntity<StandingOrderRunDTO>> getRun(@PathVariable UUID runId,
                                                            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return service.getRun(runId).map(ResponseEntity::ok);
    }
}
