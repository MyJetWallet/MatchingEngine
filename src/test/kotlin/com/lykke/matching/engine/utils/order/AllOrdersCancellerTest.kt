package com.lykke.matching.engine.utils.order

import com.lykke.matching.engine.AbstractTest
import com.lykke.matching.engine.config.TestApplicationContext
import com.lykke.matching.engine.daos.Asset
import com.lykke.matching.engine.daos.order.LimitOrderType
import com.lykke.matching.engine.daos.setting.AvailableSettingGroup
import com.lykke.matching.engine.database.TestDictionariesDatabaseAccessor
import com.lykke.matching.engine.database.TestSettingsDatabaseAccessor
import com.lykke.matching.engine.utils.MessageBuilder
import com.lykke.matching.engine.utils.MessageBuilder.Companion.buildLimitOrder
import com.lykke.matching.engine.utils.assertEquals
import com.lykke.matching.engine.utils.balance.ReservedVolumesRecalculator
import com.lykke.matching.engine.utils.createAssetPair
import com.lykke.matching.engine.utils.getSetting
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit4.SpringRunner
import java.math.BigDecimal

@RunWith(SpringRunner::class)
@SpringBootTest(classes = [(TestApplicationContext::class), (AllOrdersCancellerTest.Config::class)])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AllOrdersCancellerTest : AbstractTest() {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun testDictionariesDatabaseAccessor(): TestDictionariesDatabaseAccessor {
            val testDictionariesDatabaseAccessor = TestDictionariesDatabaseAccessor()

            testDictionariesDatabaseAccessor.addAsset(Asset("", "BTC", 8))
            testDictionariesDatabaseAccessor.addAsset(Asset("", "USD", 2))
            testDictionariesDatabaseAccessor.addAsset(Asset("", "EUR", 2))
            testDictionariesDatabaseAccessor.addAsset(Asset("", "LKK1Y", 2))
            testDictionariesDatabaseAccessor.addAsset(Asset("", "LKK", 2))

            return testDictionariesDatabaseAccessor
        }

        @Bean
        @Primary
        fun testConfig(): TestSettingsDatabaseAccessor {
            val testSettingsDatabaseAccessor = TestSettingsDatabaseAccessor()
            testSettingsDatabaseAccessor.createOrUpdateSetting(
                AvailableSettingGroup.TRUSTED_CLIENTS,
                getSetting("TrustedClient")
            )
            return testSettingsDatabaseAccessor
        }
    }

    @Autowired
    private lateinit var messageBuilder: MessageBuilder

    @Autowired
    private lateinit var allOrdersCanceller: AllOrdersCanceller

    @Autowired
    private lateinit var reservedVolumesRecalculator: ReservedVolumesRecalculator

    @Before
    fun init() {
        testBalanceHolderWrapper.updateBalance("Client1", "USD", 10000.0)
        testBalanceHolderWrapper.updateBalance("Client2", "BTC", 0.5)

        testDictionariesDatabaseAccessor.addAssetPair(createAssetPair("", "BTCUSD", "BTC", "USD", 5))
        testDictionariesDatabaseAccessor.addAssetPair(createAssetPair("", "EURUSD", "EUR", "USD", 2))
        testDictionariesDatabaseAccessor.addAssetPair(createAssetPair("", "BTCEUR", "BTC", "EUR", 5))
        testDictionariesDatabaseAccessor.addAssetPair(createAssetPair("", "LKK1YLKK", "LKK1Y", "LKK", 5))

        initServices()
    }

    @Test
    fun testCancelAllOrders() {
        //given
        singleLimitOrderService.processMessage(
            messageBuilder.buildLimitOrderWrapper(
                buildLimitOrder(
                    clientId = "Client1",
                    assetId = "BTCUSD",
                    price = 3500.0,
                    volume = 0.5
                )
            )
        )
        singleLimitOrderService.processMessage(
            messageBuilder.buildLimitOrderWrapper(
                buildLimitOrder(
                    clientId = "Client1",
                    assetId = "BTCUSD",
                    price = 6500.0,
                    volume = 0.5
                )
            )
        )
        singleLimitOrderService.processMessage(
            messageBuilder.buildLimitOrderWrapper(
                buildLimitOrder(
                    clientId = "Client2",
                    assetId = "BTCUSD",
                    price = 6000.0,
                    volume = -0.25
                )
            )
        )
        singleLimitOrderService.processMessage(
            messageBuilder.buildLimitOrderWrapper(
                buildLimitOrder(
                    clientId = "Client1", assetId = "EURUSD", volume = 10.0,
                    type = LimitOrderType.STOP_LIMIT, lowerLimitPrice = 10.0, lowerPrice = 10.5
                )
            )
        )
        //when
        allOrdersCanceller.cancelAllOrders()

        //then
        assertEquals(BigDecimal.ZERO, testWalletDatabaseAccessor.getReservedBalance("Client1", "USD"))
        assertEquals(BigDecimal.ZERO, testWalletDatabaseAccessor.getReservedBalance("Client2", "BTC"))

        assertEquals(BigDecimal.valueOf(0.25), testWalletDatabaseAccessor.getBalance("Client1", "BTC"))
        assertEquals(BigDecimal.valueOf(8375.0), testWalletDatabaseAccessor.getBalance("Client1", "USD"))
        assertEquals(BigDecimal.valueOf(1625.0), testWalletDatabaseAccessor.getBalance("Client2", "USD"))
        assertEquals(BigDecimal.valueOf(0.25), testWalletDatabaseAccessor.getBalance("Client2", "BTC"))
    }

    @Test
    fun testCancelAllOrdersWithRemovedAssetPairs() {
        //given
        singleLimitOrderService.processMessage(
            messageBuilder.buildLimitOrderWrapper(
                buildLimitOrder(
                    uid = "order1",
                    clientId = "Client1",
                    assetId = "BTCUSD",
                    price = 6000.0,
                    volume = 1.0
                )
            )
        )

        testDictionariesDatabaseAccessor.clear()
        initServices()

        //when
        allOrdersCanceller.cancelAllOrders()

        //then
        assertEquals(BigDecimal.valueOf(6000), testWalletDatabaseAccessor.getReservedBalance("Client1", "USD"))
        assertEquals(BigDecimal.valueOf(10000), testWalletDatabaseAccessor.getBalance("Client1", "USD"))
    }

    @Test
    fun testCancelAllOrdersWithEmptyReservedLimitVolume() {
        testBalanceHolderWrapper.updateBalance("Client1", "LKK", 1.0)
        testOrderBookWrapper.addLimitOrder(
            buildLimitOrder(
                clientId = "Client1",
                assetId = "LKK1YLKK",
                volume = 5.0,
                price = 0.021
            )
        )
        testOrderBookWrapper.addLimitOrder(
            buildLimitOrder(
                clientId = "Client1",
                assetId = "LKK1YLKK",
                volume = 5.0,
                price = 0.021
            )
        )

        reservedVolumesRecalculator.recalculate()

        allOrdersCanceller.cancelAllOrders()

        assertOrderBookSize("LKK1YLKK", true, 0)
        assertBalance("Client1", "LKK", 1.0, 0.0)
        assertBalance("Client1", "LKK1Y", 0.0, 0.0)
    }

}