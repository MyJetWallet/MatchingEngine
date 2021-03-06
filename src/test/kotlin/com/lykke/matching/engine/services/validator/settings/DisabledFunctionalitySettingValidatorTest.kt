package com.lykke.matching.engine.services.validator.settings

import com.lykke.matching.engine.config.TestApplicationContext
import com.lykke.matching.engine.daos.Asset
import com.lykke.matching.engine.database.TestDictionariesDatabaseAccessor
import com.lykke.matching.engine.services.validators.impl.ValidationException
import com.lykke.matching.engine.services.validators.settings.impl.DisabledFunctionalitySettingValidator
import com.lykke.matching.engine.utils.createAssetPair
import com.lykke.matching.engine.web.dto.DisabledFunctionalityRuleDto
import com.lykke.matching.engine.web.dto.OperationType
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit4.SpringRunner

@RunWith(SpringRunner::class)
@SpringBootTest(classes = [(TestApplicationContext::class), (DisabledFunctionalitySettingValidatorTest.Config::class)])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DisabledFunctionalitySettingValidatorTest {

    @TestConfiguration
    class Config {

        @Bean
        @Primary
        fun testDictionariesDatabaseAccessor(): TestDictionariesDatabaseAccessor {
            val testDictionariesDatabaseAccessor = TestDictionariesDatabaseAccessor()
            testDictionariesDatabaseAccessor.addAssetPair(createAssetPair("", "BTCUSD", "BTC", "USD", 5))
            testDictionariesDatabaseAccessor.addAsset(Asset("", "BTC", 8))
            testDictionariesDatabaseAccessor.addAsset(Asset("", "USD", 4))
            return testDictionariesDatabaseAccessor
        }
    }

    @Autowired
    private lateinit var disabledFunctionalitySettingValidator: DisabledFunctionalitySettingValidator

    @Test(expected = ValidationException::class)
    fun testEmptyRuleIsInvalid() {
        disabledFunctionalitySettingValidator.validate(
            DisabledFunctionalityRuleDto(
                null,
                null,
                "",
                null,
                true,
                "test",
                "test"
            )
        )
    }

    @Test(expected = ValidationException::class)
    fun testAssetDoesNotExist() {
        disabledFunctionalitySettingValidator.validate(
            DisabledFunctionalityRuleDto(
                null,
                "TEST",
                "",
                OperationType.CASH_IN.name,
                true,
                "test",
                "test"
            )
        )
    }

    @Test(expected = ValidationException::class)
    fun testAssetPairDoesNotExist() {
        disabledFunctionalitySettingValidator.validate(
            DisabledFunctionalityRuleDto(
                null,
                "TEST",
                "",
                OperationType.CASH_OUT.name,
                true,
                "test",
                "test"
            )
        )
    }

    @Test(expected = ValidationException::class)
    fun operationDoesNotExist() {
        disabledFunctionalitySettingValidator.validate(
            DisabledFunctionalityRuleDto(
                null,
                "TEST",
                "",
                "NOT_EXIST",
                true,
                "test",
                "test"
            )
        )

    }

    @Test(expected = ValidationException::class)
    fun nonTradOperationSuppliedWithAssetPair() {
        disabledFunctionalitySettingValidator.validate(
            DisabledFunctionalityRuleDto(
                null,
                null,
                "BTCUSD",
                OperationType.CASH_OUT.name,
                true,
                "test",
                "test"
            )
        )
    }

    @Test
    fun validRule() {
        disabledFunctionalitySettingValidator.validate(
            DisabledFunctionalityRuleDto(
                null,
                "BTC",
                "BTCUSD",
                OperationType.TRADE.name,
                true,
                "test",
                "test"
            )
        )
    }
}