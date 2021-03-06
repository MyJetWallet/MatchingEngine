package com.lykke.matching.engine.database.common

import com.lykke.matching.engine.database.*
import com.lykke.matching.engine.database.common.entity.PersistenceData
import com.lykke.matching.engine.deduplication.ProcessedMessage
import org.apache.log4j.Logger

class DefaultPersistenceManager(
    private val walletDatabaseAccessor: WalletDatabaseAccessor,
    private val orderBookDatabaseAccessor: OrderBookDatabaseAccessor,
    private val stopOrderBookDatabaseAccessor: StopOrderBookDatabaseAccessor,
    private val fileProcessedMessagesDatabaseAccessor: ProcessedMessagesDatabaseAccessor
) : PersistenceManager {

    companion object {
        private val LOGGER = Logger.getLogger(DefaultPersistenceManager::class.java.name)
    }

    override fun persist(data: PersistenceData): Boolean {
        if (data.isEmpty()) {
            return true
        }
        return try {
            persistData(data)
            true
        } catch (e: Exception) {
            val retryMessage = "Unable to save data (${data.getSummary()}), retrying"
            LOGGER.error(retryMessage, e)

            return try {
                persistData(data)
                true
            } catch (e: Exception) {
                val message = "Unable to save data (${data.getSummary()})"
                LOGGER.error(message, e)
                false
            }
        }
    }

    private fun persistData(data: PersistenceData) {
        if (data.balancesData?.wallets?.isNotEmpty() == true) {
            walletDatabaseAccessor.insertOrUpdateWallets(data.balancesData.wallets.toList())
        }
        data.orderBooksData?.orderBooks?.forEach {
            orderBookDatabaseAccessor.updateOrderBook(it.assetPairId, it.isBuy, it.orders)
        }
        data.stopOrderBooksData?.orderBooks?.forEach {
            stopOrderBookDatabaseAccessor.updateStopOrderBook(it.assetPairId, it.isBuy, it.orders)
        }
        persistProcessedMessages(data.processedMessage)
    }

    private fun persistProcessedMessages(processedMessage: ProcessedMessage?) {
        if (processedMessage != null) {
            fileProcessedMessagesDatabaseAccessor.saveProcessedMessage(processedMessage)
        }
    }
}