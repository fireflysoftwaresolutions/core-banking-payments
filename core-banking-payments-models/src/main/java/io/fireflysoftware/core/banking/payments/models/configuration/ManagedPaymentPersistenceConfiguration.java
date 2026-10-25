package io.fireflysoftware.core.banking.payments.models.configuration;

import io.fireflysoftware.core.banking.payments.models.entities.managed.PaymentJson;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class ManagedPaymentPersistenceConfiguration {
    public static List<Object> converters() { return List.of(new JsonReader(), new JsonWriter()); }

    @Bean
    R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory factory) {
        return R2dbcCustomConversions.of(DialectResolver.getDialect(factory), converters());
    }

    @ReadingConverter
    private static class JsonReader implements Converter<Json, PaymentJson> {
        public PaymentJson convert(Json value) { return new PaymentJson(value.asString()); }
    }

    @WritingConverter
    private static class JsonWriter implements Converter<PaymentJson, Json> {
        public Json convert(PaymentJson value) { return Json.of(value.value()); }
    }
}
