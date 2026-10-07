package com.viscord.message_service.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Spring builds StorageProperties through its constructor, so a check in the record's compact
 * constructor runs at startup: a bad viscord.storage.cdn-endpoint stops the app instead of
 * producing broken attachment links at request time.
 */
public class StoragePropertiesTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static StorageProperties withCdn(URI cdnEndpoint) {
        return new StorageProperties(Duration.ofMinutes(5), cdnEndpoint, null, DataSize.ofMegabytes(25), List.of(MediaType.ALL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.dev.viscord.app",
            "https://localhost:3002/cdn",
            "http://localhost:9000/viscord"
    })
    @DisplayName("Happy path: an absolute URL with a scheme and host is accepted as is")
    void constructor_AbsoluteUrl_IsAccepted(String value) {
        URI cdnEndpoint = URI.create(value);

        StorageProperties properties = Assertions.assertDoesNotThrow(() -> withCdn(cdnEndpoint));

        Assertions.assertEquals(cdnEndpoint, properties.cdnEndpoint());
    }

    // Each of these binds without error from a property string, so only an explicit check catches them.
    @ParameterizedTest
    @ValueSource(strings = {
            "cdn.dev.viscord.app",   // no scheme: a relative URI
            "localhost:3002/cdn",    // "localhost" is parsed as the scheme, so there is no host
            "https:///cdn",          // scheme but no host
            "/cdn"                   // a path only
    })
    @DisplayName("Unhappy path: a CDN endpoint without a scheme and host fails when the properties are created")
    void constructor_NotAbsoluteUrl_Throws(String value) {
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class, () -> withCdn(URI.create(value)));

        Assertions.assertTrue(e.getMessage().contains("viscord.storage.cdn-endpoint"),
                "the message should name the property to fix, but was: " + e.getMessage());
    }

    @Test
    @DisplayName("Edge case: a missing CDN endpoint is left to @NotNull, not rejected by the constructor")
    void constructor_NullCdnEndpoint_DoesNotThrow() {
        Assertions.assertDoesNotThrow(() -> withCdn(null));
    }

    @Test
    @DisplayName("Unhappy path: bean validation reports a missing CDN endpoint")
    void validate_NullCdnEndpoint_ReportsViolation() {
        Set<ConstraintViolation<StorageProperties>> violations = validator.validate(withCdn(null));

        Assertions.assertEquals(Set.of("cdnEndpoint"),
                violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("Happy path: complete properties have no bean validation violations")
    void validate_CompleteProperties_NoViolations() {
        Assertions.assertTrue(validator.validate(withCdn(URI.create("https://cdn.test"))).isEmpty());
    }
}
