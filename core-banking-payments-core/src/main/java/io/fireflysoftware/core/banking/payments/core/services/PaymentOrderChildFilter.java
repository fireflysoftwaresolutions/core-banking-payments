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

package io.fireflysoftware.core.banking.payments.core.services;

import java.util.UUID;
import java.util.function.Function;
import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationRequest;
import org.fireflyframework.core.queries.PaginationResponse;
import org.fireflyframework.core.queries.PaginationUtils;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class PaymentOrderChildFilter {
  private final R2dbcEntityTemplate template;

  public PaymentOrderChildFilter(R2dbcEntityTemplate template) {
    this.template = template;
  }

  public <E, D> Mono<PaginationResponse<D>> filter(
      UUID parent, FilterRequest<?> request, Class<E> entity, Function<E, D> mapper) {
    var input = request == null ? new FilterRequest<>() : request;
    var criteria =
        FilterUtils.createFilter(entity, mapper)
            .getCriteria(input)
            .and("paymentOrderId")
            .is(parent);
    var pagination =
        input.getPagination() == null ? new PaginationRequest() : input.getPagination();
    return PaginationUtils.paginateQuery(
        pagination,
        mapper,
        page -> template.select(entity).matching(Query.query(criteria).with(page)).all(),
        () -> template.count(Query.query(criteria), entity));
  }
}
