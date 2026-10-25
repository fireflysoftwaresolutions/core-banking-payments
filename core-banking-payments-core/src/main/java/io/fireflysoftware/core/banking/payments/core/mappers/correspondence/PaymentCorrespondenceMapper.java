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


package io.fireflysoftware.core.banking.payments.core.mappers.correspondence;

import io.fireflysoftware.core.banking.payments.interfaces.dtos.correspondence.PaymentCorrespondenceDTO;
import io.fireflysoftware.core.banking.payments.models.entities.correspondence.PaymentCorrespondence;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PaymentCorrespondenceMapper {
    PaymentCorrespondenceDTO toDTO(PaymentCorrespondence paymentCorrespondence);
    PaymentCorrespondence toEntity(PaymentCorrespondenceDTO paymentCorrespondenceDTO);
}
