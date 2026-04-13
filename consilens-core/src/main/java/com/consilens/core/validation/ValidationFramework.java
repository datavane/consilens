package com.consilens.core.validation;

import com.consilens.core.util.LogUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Unified configuration validation framework.
 * Provides a fluent validation API and common validation rules.
 */
@Slf4j
public class ValidationFramework {

    private final List<ValidationError> errors = new ArrayList<>();
    private final String context;

    private ValidationFramework(String context) {
        this.context = context;
    }

    /**
     * Create a validator instance.
     * @param context validation context (used in error messages)
     * @return validator instance
     */
    public static ValidationFramework forContext(String context) {
        return new ValidationFramework(context);
    }

    /**
     * Validate that value is not null.
     * @param value value to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework notNull(Object value, String fieldName) {
        if (value == null) {
            addError(fieldName, "不能为空");
        }
        return this;
    }

    /**
     * Validate that string is not empty.
     * @param value string to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework notEmpty(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            addError(fieldName, "不能为空字符串");
        }
        return this;
    }

    /**
     * Validate string length is within bounds.
     * @param value string to validate
     * @param fieldName field name
     * @param minLength minimum length
     * @param maxLength maximum length
     * @return this instance for chaining
     */
    public ValidationFramework length(String value, String fieldName, int minLength, int maxLength) {
        if (value != null) {
            int length = value.length();
            if (length < minLength || length > maxLength) {
                addError(fieldName, String.format("长度必须在%d到%d之间", minLength, maxLength));
            }
        }
        return this;
    }

    /**
     * Validate number is within range.
     * @param value value to validate
     * @param fieldName field name
     * @param min minimum value
     * @param max maximum value
     * @return this instance for chaining
     */
    public ValidationFramework range(Number value, String fieldName, Number min, Number max) {
        if (value != null) {
            double doubleValue = value.doubleValue();
            double minVal = min.doubleValue();
            double maxVal = max.doubleValue();

            if (doubleValue < minVal || doubleValue > maxVal) {
                addError(fieldName, String.format("值必须在%s到%s之间", min, max));
            }
        }
        return this;
    }

    /**
     * Validate number is positive.
     * @param value value to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework positive(Number value, String fieldName) {
        if (value != null && value.doubleValue() <= 0) {
            addError(fieldName, "必须是正数");
        }
        return this;
    }

    /**
     * Validate collection is not empty.
     * @param collection collection to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework notEmpty(Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            addError(fieldName, "不能为空集合");
        }
        return this;
    }

    /**
     * Validate array is not empty.
     * @param array array to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework notEmpty(Object[] array, String fieldName) {
        if (array == null || array.length == 0) {
            addError(fieldName, "不能为空数组");
        }
        return this;
    }

    /**
     * Apply a custom validation predicate.
     * @param value value to validate
     * @param fieldName field name
     * @param predicate validation predicate
     * @param errorMessage error message if predicate fails
     * @return this instance for chaining
     */
    public ValidationFramework validate(Object value, String fieldName, Predicate<Object> predicate, String errorMessage) {
        if (value != null && !predicate.test(value)) {
            addError(fieldName, errorMessage);
        }
        return this;
    }

    /**
     * Validate HTTP/HTTPS URL format.
     * @param url URL to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework validUrl(String url, String fieldName) {
        if (url != null && !url.trim().isEmpty()) {
            if (!url.matches("^https?://.*")) {
                addError(fieldName, "必须是有效的HTTP或HTTPS URL");
            }
        }
        return this;
    }

    /**
     * Validate JDBC URL format.
     * @param jdbcUrl JDBC URL to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework validJdbcUrl(String jdbcUrl, String fieldName) {
        if (jdbcUrl != null && !jdbcUrl.trim().isEmpty()) {
            if (!jdbcUrl.matches("^jdbc:[a-zA-Z0-9]+:.*")) {
                addError(fieldName, "必须是有效的JDBC URL格式");
            }
        }
        return this;
    }

    /**
     * Validate email address format.
     * @param email email to validate
     * @param fieldName field name
     * @return this instance for chaining
     */
    public ValidationFramework validEmail(String email, String fieldName) {
        if (email != null && !email.trim().isEmpty()) {
            if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                addError(fieldName, "必须是有效的邮箱地址");
            }
        }
        return this;
    }

    /**
     * Validate value is a valid enum constant.
     * @param value value to validate
     * @param fieldName field name
     * @param enumClass enum class
     * @param <T> enum type
     * @return this instance for chaining
     */
    public <T extends Enum<T>> ValidationFramework validEnum(String value, String fieldName, Class<T> enumClass) {
        if (value != null && !value.trim().isEmpty()) {
            try {
                Enum.valueOf(enumClass, value.toUpperCase());
            } catch (IllegalArgumentException e) {
                addError(fieldName, String.format("必须是有效的枚举值: %s", Arrays.toString(enumClass.getEnumConstants())));
            }
        }
        return this;
    }

    /**
     * Apply nested validation on a nested object.
     * @param value object to validate
     * @param fieldName field name
     * @param validator nested validator function
     * @param <T> object type
     * @return this instance for chaining
     */
    public <T> ValidationFramework nest(T value, String fieldName, Function<T, ValidationFramework> validator) {
        if (value != null) {
            ValidationFramework nestedValidator = validator.apply(value);
            if (!nestedValidator.isValid()) {
                for (ValidationError error : nestedValidator.getErrors()) {
                    addError(fieldName + "." + error.getField(), error.getMessage());
                }
            }
        }
        return this;
    }

    /**
     * Validate each element in a collection.
     * @param collection collection to validate
     * @param fieldName field name
     * @param validator element validator function
     * @param <T> element type
     * @return this instance for chaining
     */
    public <T> ValidationFramework forEach(Collection<T> collection, String fieldName, Function<T, ValidationFramework> validator) {
        if (collection != null) {
            int index = 0;
            for (T item : collection) {
                ValidationFramework itemValidator = validator.apply(item);
                if (!itemValidator.isValid()) {
                    for (ValidationError error : itemValidator.getErrors()) {
                        addError(String.format("%s[%d].%s", fieldName, index, error.getField()), error.getMessage());
                    }
                }
                index++;
            }
        }
        return this;
    }

    /**
     * Check whether validation passed.
     * @return true if no errors, false otherwise
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Get all validation errors.
     * @return list of validation errors
     */
    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }

    /**
     * Get formatted error message string.
     * @return formatted error message, or null if valid
     */
    public String getErrorMessage() {
        if (errors.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("配置验证失败 (").append(context).append("):\n");

        for (ValidationError error : errors) {
            sb.append("  - ").append(error.getField()).append(": ").append(error.getMessage()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Throw an exception if validation failed.
     * @throws ValidationException if validation failed
     */
    public void throwIfInvalid() throws ValidationException {
        if (!isValid()) {
            String errorMessage = getErrorMessage();
            LogUtils.logConfigurationValidationFailure(context, errorMessage);
            throw ValidationException.forConfiguration(errorMessage, new ArrayList<>());
        }
    }

    /**
     * Add a validation error.
     * @param field field name
     * @param message error message
     */
    private void addError(String field, String message) {
        errors.add(new ValidationError(field, message));
    }

    /**
     * Validation error class.
     */
    public static class ValidationError {
        private final String field;
        private final String message;

        public ValidationError(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public String getField() {
            return field;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return field + ": " + message;
        }
    }

    /**
     * Predefined common validation rules.
     */
    public static class CommonValidators {

        /**
         * Validate database configuration.
         */
        public static ValidationFramework validateDatabaseConfig(String url, String username, String password) {
            return ValidationFramework.forContext("数据库配置")
                    .notNull(url, "url")
                    .validJdbcUrl(url, "url")
                    .notEmpty(username, "username")
                    .notEmpty(password, "password");
        }

        /**
         * Validate table configuration.
         */
        public static ValidationFramework validateTableConfig(String table, List<String> keyColumns) {
            return ValidationFramework.forContext("表配置")
                    .notEmpty(table, "table")
                    .notEmpty(keyColumns, "keyColumns");
        }

        /**
         * Validate algorithm configuration.
         */
        public static ValidationFramework validateAlgorithmConfig(String algorithm, Map<String, Object> parameters) {
            return ValidationFramework.forContext("算法配置")
                    .notEmpty(algorithm, "algorithm")
                    .validate(algorithm, "algorithm",
                        value -> "HASH_DIFF".equals(value) || "JOIN_DIFF".equals(value),
                        "必须是有效的算法类型: HASH_DIFF, JOIN_DIFF")
                    .notNull(parameters, "parameters");
        }
    }
}