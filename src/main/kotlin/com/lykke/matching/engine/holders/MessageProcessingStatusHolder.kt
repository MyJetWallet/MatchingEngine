package com.lykke.matching.engine.holders

import com.lykke.matching.engine.daos.Asset
import com.lykke.matching.engine.daos.AssetPair
import com.lykke.matching.engine.utils.monitoring.HealthMonitor
import org.springframework.stereotype.Component

@Component
class MessageProcessingStatusHolder(
    private val generalHealthMonitor: HealthMonitor,
    private val applicationSettingsHolder: ApplicationSettingsHolder,
    private val disabledFunctionalityRulesHolder: DisabledFunctionalityRulesHolder
) {

    fun isTradeDisabled(assetPair: AssetPair?): Boolean {
        return !applicationSettingsHolder.isMessageProcessingEnabled() || disabledFunctionalityRulesHolder.isTradeDisabled(
            assetPair
        )
    }

    fun isCashInDisabled(asset: Asset?): Boolean {
        return !applicationSettingsHolder.isMessageProcessingEnabled() || disabledFunctionalityRulesHolder.isCashInDisabled(
            asset
        )
    }

    fun isCashOutDisabled(asset: Asset?): Boolean {
        return !applicationSettingsHolder.isMessageProcessingEnabled() || disabledFunctionalityRulesHolder.isCashOutDisabled(
            asset
        )
    }

    fun isCashTransferDisabled(asset: Asset?): Boolean {
        return !applicationSettingsHolder.isMessageProcessingEnabled() || disabledFunctionalityRulesHolder.isCashTransferDisabled(
            asset
        )
    }

    fun isCashSwapDisabled(asset1: Asset?, asset2: Asset?): Boolean {
        return !applicationSettingsHolder.isMessageProcessingEnabled() || disabledFunctionalityRulesHolder.isCashSwapDisabled(
            asset1
        ) || disabledFunctionalityRulesHolder.isCashSwapDisabled(asset2)
    }

    fun isMessageProcessingEnabled(): Boolean {
        return applicationSettingsHolder.isMessageProcessingEnabled()
    }

    fun isHealthStatusOk(): Boolean {
        return generalHealthMonitor.ok()
    }
}