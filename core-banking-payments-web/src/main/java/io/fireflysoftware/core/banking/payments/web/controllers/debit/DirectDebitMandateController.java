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

import io.fireflysoftware.core.banking.payments.core.services.debit.DirectDebitMandateService;
import io.fireflysoftware.core.banking.payments.interfaces.dtos.debit.DirectDebitMandateDTO;
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
@RequestMapping("/api/v1/direct-debit-mandates")
@Tag(name = "DirectDebitMandate", description = "Stored direct-debit mandate. Not a scheme clearing file.")
public class DirectDebitMandateController {

    private final DirectDebitMandateService service;

    public DirectDebitMandateController(DirectDebitMandateService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(operationId = "createDirectDebitMandate", summary = "Store a direct-debit mandate")
    public Mono<ResponseEntity<DirectDebitMandateDTO>> create(@RequestBody DirectDebitMandateDTO body,
                                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return service.create(body).map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    @GetMapping("/{mandateId}")
    @Operation(operationId = "getDirectDebitMandate", summary = "Read a direct-debit mandate")
    public Mono<ResponseEntity<DirectDebitMandateDTO>> get(@PathVariable UUID mandateId,
                                                           @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return service.get(mandateId).map(ResponseEntity::ok);
    }
}
