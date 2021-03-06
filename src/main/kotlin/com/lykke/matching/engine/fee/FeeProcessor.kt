package com.lykke.matching.engine.fee

import com.lykke.matching.engine.balance.BalancesGetter
import com.lykke.matching.engine.daos.*
import com.lykke.matching.engine.daos.fee.v2.Fee
import com.lykke.matching.engine.daos.fee.v2.NewFeeInstruction
import com.lykke.matching.engine.daos.fee.v2.NewLimitOrderFeeInstruction
import com.lykke.matching.engine.daos.v2.FeeInstruction
import com.lykke.matching.engine.daos.v2.LimitOrderFeeInstruction
import com.lykke.matching.engine.holders.AssetsHolder
import com.lykke.matching.engine.holders.AssetsPairsHolder
import com.lykke.matching.engine.services.GenericLimitOrderService
import com.lykke.matching.engine.utils.NumberUtils
import org.apache.log4j.Logger
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.*

@Component
class FeeProcessor(
    private val assetsHolder: AssetsHolder,
    private val assetsPairsHolder: AssetsPairsHolder,
    private val genericLimitOrderService: GenericLimitOrderService
) {

    companion object {
        private val LOGGER = Logger.getLogger(FeeProcessor::class.java.name)
        private const val FEE_COEF_ACCURACY = 12
    }

    fun processMakerFee(
        brokerId: String, accountId: String, feeInstructions: List<FeeInstruction>,
        receiptOperation: WalletOperation,
        operations: MutableList<WalletOperation>,
        relativeSpread: BigDecimal? = null,
        convertPrices: Map<String, BigDecimal> = emptyMap(),
        balances: MutableMap<String, MutableMap<String, BigDecimal>>? = null,
        balancesGetter: BalancesGetter
    ) =
        processFees(
            brokerId, accountId, feeInstructions,
            receiptOperation,
            operations,
            MakerFeeCoefCalculator(relativeSpread),
            convertPrices,
            true,
            balances,
            balancesGetter
        )

    fun processFee(
        brokerId: String, accountId: String, feeInstructions: List<FeeInstruction>?,
        receiptOperation: WalletOperation,
        operations: MutableList<WalletOperation>,
        convertPrices: Map<String, BigDecimal> = emptyMap(),
        balances: MutableMap<String, MutableMap<String, BigDecimal>>? = null,
        balancesGetter: BalancesGetter
    ) =
        processFees(
            brokerId, accountId, feeInstructions,
            receiptOperation,
            operations,
            DefaultFeeCoefCalculator(),
            convertPrices,
            false,
            balances,
            balancesGetter
        )

    private fun processFees(
        brokerId: String, accountId: String, feeInstructions: List<FeeInstruction>?,
        receiptOperation: WalletOperation,
        operations: MutableList<WalletOperation>,
        feeCoefCalculator: FeeCoefCalculator,
        convertPrices: Map<String, BigDecimal>,
        isMakerFee: Boolean,
        externalBalances: MutableMap<String, MutableMap<String, BigDecimal>>? = null,
        balancesGetter: BalancesGetter
    ): List<Fee> {
        if (feeInstructions?.isNotEmpty() != true) {
            return listOf()
        }
        val receiptOperationWrapper = ReceiptOperationWrapper(receiptOperation)
        val balances = HashMap<String, MutableMap<String, BigDecimal>>() // clientId -> assetId -> balance
        externalBalances?.let { clientBalances ->
            balances.putAll(clientBalances.mapValues { HashMap<String, BigDecimal>(it.value) })
        }
        val newOperations = LinkedList(operations)
        val fees = feeInstructions.map { feeInstruction ->
            val feeTransfer = if (isMakerFee) {
                feeCoefCalculator as MakerFeeCoefCalculator
                when (feeInstruction) {
                    is LimitOrderFeeInstruction -> {
                        feeCoefCalculator.feeModificator = null
                        processFee(
                            brokerId,
                            accountId,
                            feeInstruction,
                            receiptOperationWrapper,
                            newOperations,
                            feeInstruction.makerSizeType,
                            feeInstruction.makerSize,
                            feeCoefCalculator.calculate(),
                            balances,
                            balancesGetter,
                            convertPrices
                        )
                    }
                    is NewLimitOrderFeeInstruction -> {
                        feeCoefCalculator.feeModificator = feeInstruction.makerFeeModificator
                        processFee(
                            brokerId,
                            accountId,
                            feeInstruction,
                            receiptOperationWrapper,
                            newOperations,
                            feeInstruction.makerSizeType,
                            feeInstruction.makerSize,
                            feeCoefCalculator.calculate(),
                            balances,
                            balancesGetter,
                            convertPrices
                        )
                    }
                    else -> throw FeeException("Fee instruction should be instance of LimitOrderFeeInstruction")
                }
            } else {
                processFee(
                    brokerId, accountId, feeInstruction,
                    receiptOperationWrapper,
                    newOperations,
                    feeInstruction.sizeType,
                    feeInstruction.size,
                    feeCoefCalculator.calculate(),
                    balances,
                    balancesGetter,
                    convertPrices
                )
            }
            Fee(feeInstruction, feeTransfer)
        }

        val totalFeeAmount = fees.stream()
            .map {
                if (it.instruction.type == FeeType.CLIENT_FEE && it.transfer != null && it.transfer.asset == receiptOperation.assetId)
                    it.transfer.volume else BigDecimal.ZERO
            }
            .reduce(BigDecimal.ZERO, BigDecimal::add)

        if (totalFeeAmount > BigDecimal.ZERO && totalFeeAmount > receiptOperation.amount.abs()) {
            throw FeeException(
                "Total fee amount should be not more than " +
                        "${if (receiptOperation.amount < BigDecimal.ZERO) "abs " else ""}operation amount (total fee: ${
                            NumberUtils.roundForPrint(
                                totalFeeAmount
                            )
                        }, operation amount ${NumberUtils.roundForPrint(receiptOperation.amount)})"
            )
        }
        externalBalances?.putAll(balances)
        operations.clear()
        operations.addAll(newOperations)
        return fees
    }

    private fun processFee(
        brokerId: String, accountId: String, feeInstruction: FeeInstruction,
        receiptOperationWrapper: ReceiptOperationWrapper,
        operations: MutableList<WalletOperation>,
        feeSizeType: FeeSizeType?,
        feeSize: BigDecimal?,
        feeCoef: BigDecimal?,
        balances: MutableMap<String, MutableMap<String, BigDecimal>>,
        balancesGetter: BalancesGetter,
        convertPrices: Map<String, BigDecimal>
    ): FeeTransfer? {
        if (feeInstruction.type == FeeType.NO_FEE || feeSize == null) {
            return null
        }
        if (feeSizeType == null || feeSize < BigDecimal.ZERO || feeInstruction.targetWalletId == null) {
            throw FeeException("Invalid fee instruction (size type: $feeSizeType, size: $feeSize, targetClientId: ${feeInstruction.targetWalletId})")
        }
        val receiptOperation = receiptOperationWrapper.baseReceiptOperation
        val operationAsset = assetsHolder.getAsset(receiptOperation.assetId)
        val feeAsset = getFeeAsset(feeInstruction, operationAsset)
        val isAnotherAsset = operationAsset.symbol != feeAsset.symbol

        val absFeeAmount = NumberUtils.setScaleRoundUp(
            when (feeSizeType) {
                FeeSizeType.PERCENTAGE -> {
                    // In case of cash out receipt operation has a negative amount, but fee amount should be positive
                    val absBaseAssetFeeAmount = receiptOperation.amount.abs() * feeSize
                    (if (isAnotherAsset) absBaseAssetFeeAmount * computeInvertCoef(
                        operationAsset.symbol,
                        feeAsset.symbol,
                        convertPrices
                    ) else absBaseAssetFeeAmount) * (feeCoef ?: BigDecimal.ONE)
                }
                FeeSizeType.ABSOLUTE -> feeSize * (feeCoef ?: BigDecimal.ONE)
            }, feeAsset.accuracy
        )

        return when (feeInstruction.type) {
            FeeType.CLIENT_FEE -> processClientFee(
                brokerId,
                accountId,
                feeInstruction,
                receiptOperationWrapper,
                operations,
                absFeeAmount,
                feeAsset,
                isAnotherAsset,
                feeCoef,
                balances,
                balancesGetter
            )
            FeeType.EXTERNAL_FEE -> processExternalFee(
                brokerId,
                accountId,
                feeInstruction,
                operations,
                absFeeAmount,
                feeAsset,
                feeCoef,
                balances,
                balancesGetter
            )
            else -> {
                LOGGER.error("Unknown fee type: ${feeInstruction.type}")
                null
            }
        }
    }

    private fun processExternalFee(
        brokerId: String,
        accountId: String,
        feeInstruction: FeeInstruction,
        operations: MutableList<WalletOperation>,
        absFeeAmount: BigDecimal,
        feeAsset: Asset,
        feeCoef: BigDecimal?,
        balances: MutableMap<String, MutableMap<String, BigDecimal>>,
        balancesGetter: BalancesGetter
    ): FeeTransfer {
        if (feeInstruction.sourceWalletId == null) {
            throw FeeException("Source client is null for external fee")
        }
        val clientBalances = balances.getOrPut(feeInstruction.sourceWalletId) { HashMap() }
        val balance = clientBalances.getOrPut(feeAsset.symbol) {
            balancesGetter.getAvailableBalance(
                brokerId,
                accountId,
                feeInstruction.sourceWalletId,
                feeAsset.symbol
            )
        }
        if (balance < absFeeAmount) {
            throw NotEnoughFundsFeeException("Not enough funds for fee (asset: ${feeAsset.symbol}, available balance: $balance, feeAmount: $absFeeAmount)")
        }
        clientBalances[feeAsset.symbol] = NumberUtils.setScaleRoundHalfUp(balance - absFeeAmount, feeAsset.accuracy)
        operations.add(
            WalletOperation(
                brokerId,
                accountId,
                feeInstruction.sourceWalletId,
                feeAsset.symbol,
                -absFeeAmount
            )
        )
        operations.add(
            WalletOperation(
                brokerId,
                accountId,
                feeInstruction.targetWalletId!!,
                feeAsset.symbol,
                absFeeAmount
            )
        )
        return FeeTransfer(
            feeInstruction.sourceWalletId,
            feeInstruction.targetWalletId,
            absFeeAmount,
            feeAsset.symbol,
            if (feeCoef != null) NumberUtils.setScaleRoundHalfUp(feeCoef, FEE_COEF_ACCURACY) else null
        )
    }

    private fun processClientFee(
        brokerId: String,
        accountId: String,
        feeInstruction: FeeInstruction,
        receiptOperationWrapper: ReceiptOperationWrapper,
        operations: MutableList<WalletOperation>,
        absFeeAmount: BigDecimal,
        feeAsset: Asset,
        isAnotherAsset: Boolean,
        feeCoef: BigDecimal?,
        balances: MutableMap<String, MutableMap<String, BigDecimal>>,
        balancesGetter: BalancesGetter
    ): FeeTransfer {
        val receiptOperation = receiptOperationWrapper.currentReceiptOperation
        val clientBalances = balances.getOrPut(receiptOperation.clientId) { HashMap() }
        val balance = clientBalances.getOrPut(feeAsset.symbol) {
            balancesGetter.getAvailableBalance(
                brokerId,
                accountId,
                receiptOperation.clientId,
                feeAsset.symbol
            )
        }

        if (isAnotherAsset) {
            if (balance < absFeeAmount) {
                throw NotEnoughFundsFeeException("Not enough funds for fee (asset: ${feeAsset.symbol}, available balance: $balance, feeAmount: $absFeeAmount)")
            }
            clientBalances[feeAsset.symbol] =
                NumberUtils.setScaleRoundHalfUp(balance - absFeeAmount, feeAsset.accuracy)
            operations.add(
                WalletOperation(
                    brokerId,
                    accountId,
                    receiptOperation.clientId,
                    feeAsset.symbol,
                    -absFeeAmount
                )
            )
        } else {
            val baseReceiptOperationAmount = receiptOperationWrapper.baseReceiptOperation.amount
            if (absFeeAmount > baseReceiptOperationAmount.abs()) {
                throw FeeException("Base asset fee amount ($absFeeAmount) should be in [0, ${baseReceiptOperationAmount.abs()}]")
            }
            val newReceiptAmount =
                if (baseReceiptOperationAmount > BigDecimal.ZERO) receiptOperation.amount - absFeeAmount else receiptOperation.amount
            operations.remove(receiptOperation)
            val newReceiptOperation = WalletOperation(
                brokerId, accountId, receiptOperation.clientId,
                receiptOperation.assetId, NumberUtils.setScaleRoundHalfUp(newReceiptAmount, feeAsset.accuracy)
            )
            operations.add(newReceiptOperation)
            receiptOperationWrapper.currentReceiptOperation = newReceiptOperation
        }

        operations.add(
            WalletOperation(
                brokerId, accountId, feeInstruction.targetWalletId!!,
                feeAsset.symbol, absFeeAmount
            )
        )

        return FeeTransfer(
            receiptOperation.clientId, feeInstruction.targetWalletId, absFeeAmount,
            feeAsset.symbol, if (feeCoef != null) NumberUtils.setScaleRoundHalfUp(feeCoef, FEE_COEF_ACCURACY) else null
        )
    }

    private fun getFeeAsset(feeInstruction: FeeInstruction, operationAsset: Asset): Asset {
        val assetIds = when (feeInstruction) {
            is NewFeeInstruction -> feeInstruction.assetIds
            else -> listOf()
        }
        return if (assetIds.isNotEmpty()) assetsHolder.getAsset(assetIds.first()) else operationAsset
    }

    private fun computeInvertCoef(
        operationAssetId: String,
        feeAssetId: String,
        convertPrices: Map<String, BigDecimal>
    ): BigDecimal {
        val assetPair = try {
            assetsPairsHolder.getAssetPair(operationAssetId, feeAssetId)
        } catch (e: Exception) {
            throw FeeException(e.message ?: "Unable to get asset pair for ($operationAssetId, $feeAssetId})")
        }
        val price = if (convertPrices.containsKey(assetPair.symbol)) {
            convertPrices[assetPair.symbol]!!
        } else {
            val orderBook = genericLimitOrderService.getOrderBook(assetPair.brokerId, assetPair.symbol)
            val askPrice = orderBook.getAskPrice()
            val bidPrice = orderBook.getBidPrice()
            if (askPrice > BigDecimal.ZERO && bidPrice > BigDecimal.ZERO) NumberUtils.divideWithMaxScale(
                (askPrice + bidPrice),
                BigDecimal.valueOf(2)
            ) else BigDecimal.ZERO
        }

        if (price <= BigDecimal.ZERO) {
            throw FeeException("Unable to get a price to convert to fee asset (price is not positive or order book is empty)")
        }
        return if (assetPair.baseAssetId == feeAssetId) NumberUtils.divideWithMaxScale(BigDecimal.ONE, price) else price
    }

}
