package com.kamerzing.payment_core.shared.web

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.type.LogicalType

@Configuration(proxyBeanMethods = false)
class JsonConfiguration {

    @Bean
    fun strictIntegerJson(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            // Monetary amounts must be JSON integers, never coerced strings or decimals.
            builder.withCoercionConfig(LogicalType.Integer) { coercion ->
                coercion.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                coercion.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                coercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
        }
}
