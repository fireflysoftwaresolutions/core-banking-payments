package io.fireflysoftware.core.banking.payments.core.managed;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;

class ManagedPaymentServiceArchitectureTest {
    @Test
    void allBusinessImplementationsUseFunctionalImplPackagesAndParentContracts() throws Exception {
        Path root = Path.of("src/main/java");
        int count = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (!source.contains("@Service")) continue;
                count++;
                assertThat(path.getParent().getFileName().toString()).as(path.toString()).isEqualTo("impl");
                assertThat(path.getFileName().toString()).endsWith("ServiceImpl.java");
                assertThat(source).contains(" implements ");
            }
        }
        assertThat(count).isEqualTo(20);
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Profile("owned-payment-service-contracts")
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void mockBean(org.springframework.context.annotation.AnnotationConfigApplicationContext context, Class<?> type) {
        context.registerBean((Class) type, () -> org.mockito.Mockito.mock(type));
    }

    private static Class<?> sourceType(Path file) throws Exception {
        String text = Files.readString(file);
        var matcher = java.util.regex.Pattern.compile("package ([\\w.]+);").matcher(text);
        assertThat(matcher.find()).isTrue();
        return Class.forName(matcher.group(1) + "." + file.getFileName().toString().replace(".java", ""));
    }

    @Test
    void actualSpringContextResolvesAllTwentyBusinessContractsWithOriginalBeanNamesAndAdvice() throws Exception {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("owned-payment-service-contracts");
        context.register(Transactions.class);
        var factory = org.mockito.Mockito.mock(io.r2dbc.spi.ConnectionFactory.class);
        var database = org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.class);
        var template = org.mockito.Mockito.mock(org.springframework.data.r2dbc.core.R2dbcEntityTemplate.class);
        org.mockito.Mockito.when(database.getConnectionFactory()).thenReturn(factory);
        org.mockito.Mockito.when(template.getDatabaseClient()).thenReturn(database);
        context.registerBean(org.springframework.data.r2dbc.core.R2dbcEntityTemplate.class, () -> template);
        context.registerBean(org.springframework.transaction.ReactiveTransactionManager.class,
                () -> new org.springframework.r2dbc.connection.R2dbcTransactionManager(factory));
        Path models = Path.of("../core-banking-payments-models/src/main/java/io/fireflysoftware/core/banking/payments/models/repositories");
        try (var files = Files.walk(models)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Class<?> type = sourceType(file);
                if (type.isInterface() && org.springframework.data.repository.Repository.class.isAssignableFrom(type)) mockBean(context, type);
            }
        }
        Path source = Path.of("src/main/java/io/fireflysoftware/core/banking/payments/core");
        List<Class<?>> services = new ArrayList<>();
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Class<?> type = sourceType(file);
                if (type.isAnnotationPresent(org.springframework.stereotype.Service.class)) { services.add(type); context.register(type); }
                else if (type.isInterface() && type.getSimpleName().endsWith("Mapper")) mockBean(context, type);
            }
        }
        mockBean(context, LedgerFundsObservationPort.class);
        mockBean(context, io.fireflysoftware.core.banking.payments.core.instructions.PaymentTccPort.class);
        for (String name : List.of("managed.impl.ManagedPaymentTransactions", "managed.impl.ManagedPaymentStore",
                "managed.impl.ManagedPaymentDatabaseContext", "services.closure.impl.PaymentAccountQuiescenceStore",
                "services.closure.impl.PaymentAccountQuiescenceDatabaseContext", "services.closure.impl.PaymentAccountQuiescenceAuthority",
                "services.PaymentOrderChildFilter")) {
            context.register(Class.forName("io.fireflysoftware.core.banking.payments.core." + name));
        }
        try {
            context.refresh();
            assertThat(services).hasSize(20);
            Set<String> originalNames = Set.of("paymentInstructionService", "paymentCorrespondenceManagerServiceImpl",
                    "payrollOrderManagerServiceImpl", "paymentBeneficiaryManagerServiceImpl", "paymentInstructionManagerServiceImpl",
                    "directDebitMandateService", "directDebitCollectionService", "paymentAuditManagerServiceImpl",
                    "paymentComplianceManagerServiceImpl", "paymentProviderManagerServiceImpl", "paymentOrderManagerServiceImpl",
                    "paymentProofManagerServiceImpl", "paymentScheduleManagerServiceImpl", "paymentMethodManagerServiceImpl",
                    "paymentFeeManagerServiceImpl", "paymentExchangeRateManagerServiceImpl", "paymentAccountQuiescenceService",
                    "managedPaymentService", "managedPaymentCoordinator", "managedPaymentObservationService");
            assertThat(context.getBeansWithAnnotation(org.springframework.stereotype.Service.class).keySet())
                    .containsExactlyInAnyOrderElementsOf(originalNames);
            var attributes = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
            for (Class<?> implementation : services) {
                var annotation = implementation.getAnnotation(org.springframework.stereotype.Service.class);
                String beanName = annotation.value().isEmpty() ? java.beans.Introspector.decapitalize(implementation.getSimpleName()) : annotation.value();
                Object bean = context.getBean(beanName);
                Class<?> contract = implementation.getInterfaces()[0];
                assertThat(contract.isInterface()).isTrue();
                assertThat(contract.getPackageName()).isEqualTo(implementation.getPackageName().replaceFirst("\\.impl$", ""));
                assertThat(contract.isInstance(bean)).as(beanName).isTrue();
                boolean advised = java.util.Arrays.stream(implementation.getDeclaredMethods())
                        .anyMatch(method -> attributes.getTransactionAttribute(method, implementation) != null);
                assertThat(advised).as("original advice boundary for " + beanName).isEqualTo(beanName.endsWith("Impl"));
                assertThat(org.springframework.aop.support.AopUtils.isAopProxy(bean)).as(beanName).isEqualTo(advised);
            }
            org.mockito.Mockito.verifyNoInteractions(factory);
        } finally { context.close(); }
    }

    @Test
    void sharedManagedTransactionHelperRetainsPackagePrivateBoundaries() throws Exception {
        Class<?> type = Class.forName("io.fireflysoftware.core.banking.payments.core.managed.impl.ManagedPaymentTransactions");
        assertThat(java.lang.reflect.Modifier.isPublic(type.getModifiers())).isFalse();
        assertThat(java.util.Arrays.stream(type.getDeclaredConstructors()))
                .noneMatch(c -> java.lang.reflect.Modifier.isPublic(c.getModifiers()));
        assertThat(java.util.Arrays.stream(type.getDeclaredMethods()))
                .noneMatch(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()));
        assertThat(java.util.Arrays.stream(type.getDeclaredFields()))
                .noneMatch(f -> java.lang.reflect.Modifier.isPublic(f.getModifiers()));
    }
}
