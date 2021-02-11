package com.lykke.matching.engine.incoming.grpc

import com.lykke.matching.engine.AppInitialData
import com.lykke.matching.engine.messages.MessageProcessor
import com.lykke.matching.engine.messages.wrappers.*
import com.lykke.matching.engine.utils.config.Config
import com.lykke.utils.AppVersion
import com.lykke.utils.logging.MetricsLogger
import com.lykke.utils.logging.ThrottlingLogger
import io.grpc.ServerBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.BlockingQueue

@Component
class GrpcServicesInit(
    private val messageProcessor: MessageProcessor,
    private val appInitialData: AppInitialData
) : Runnable {

    @Autowired
    private lateinit var config: Config

    @Autowired
    private lateinit var cashInOutInputQueue: BlockingQueue<CashInOutOperationMessageWrapper>

    @Autowired
    private lateinit var cashTransferInputQueue: BlockingQueue<CashTransferOperationMessageWrapper>

    @Autowired
    private lateinit var limitOrderInputQueue: BlockingQueue<SingleLimitOrderMessageWrapper>

    @Autowired
    private lateinit var limitOrderCancelInputQueue: BlockingQueue<LimitOrderCancelMessageWrapper>

    @Autowired
    private lateinit var preProcessedMessageQueue: BlockingQueue<MessageWrapper>

    companion object {
        private val LOGGER = ThrottlingLogger.getLogger(GrpcServicesInit::class.java.name)
    }

    override fun run() {
        messageProcessor.start()

        MetricsLogger.getLogger().logWarning(
            "Spot.${config.matchingEngine.name} ${AppVersion.VERSION} : " +
                    "Started : ${appInitialData.ordersCount} orders, ${appInitialData.stopOrdersCount} " +
                    "stop orders,${appInitialData.balancesCount} " +
                    "balances for ${appInitialData.clientsCount} clients"
        )

        LOGGER.info("Starting gRpc services")
        with(config.matchingEngine.grpcEndpoints) {
            ServerBuilder.forPort(cashApiServicePort)
                .addService(CashApiService(cashInOutInputQueue, cashTransferInputQueue)).build().start()
            LOGGER.info("Starting CashApiService at $cashApiServicePort port")
            ServerBuilder.forPort(tradingApiServicePort).addService(
                TradingApiService(
                    limitOrderInputQueue,
                    limitOrderCancelInputQueue,
                    preProcessedMessageQueue
                )
            ).build().start()
        }
    }
}