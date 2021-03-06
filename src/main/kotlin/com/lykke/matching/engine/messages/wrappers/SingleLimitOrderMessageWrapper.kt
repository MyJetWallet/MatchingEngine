package com.lykke.matching.engine.messages.wrappers

import com.google.protobuf.StringValue
import com.lykke.matching.engine.daos.context.SingleLimitOrderContext
import com.lykke.matching.engine.messages.MessageStatus
import com.lykke.matching.engine.messages.MessageType
import com.myjetwallet.messages.incoming.grpc.GrpcIncomingMessages
import io.grpc.stub.StreamObserver
import io.micrometer.core.instrument.Timer
import java.io.IOException
import java.util.concurrent.TimeUnit

class SingleLimitOrderMessageWrapper(
    var parsedMessage: GrpcIncomingMessages.LimitOrder,
    val callback: StreamObserver<GrpcIncomingMessages.LimitOrderResponse>,
    private val limitOrderTimer: Timer? = null,
    private val closeStream: Boolean = false,
    var context: SingleLimitOrderContext? = null
) : MessageWrapper(
    MessageType.LIMIT_ORDER,
    parsedMessage.id,
    if (parsedMessage.hasMessageId()) parsedMessage.messageId.value else parsedMessage.id
) {

    @Suppress("DuplicatedCode")
    fun writeResponse(
        status: MessageStatus,
        errorMessage: String? = null,
        orderId: String? = null,
        version: Long? = null
    ) {
        limitOrderTimer?.record(System.nanoTime() - startTimestamp, TimeUnit.NANOSECONDS)
        val responseBuilder = GrpcIncomingMessages.LimitOrderResponse.newBuilder()
        responseBuilder.id = id
        responseBuilder.status = GrpcIncomingMessages.Status.forNumber(status.type)
        if (errorMessage != null) {
            responseBuilder.statusReason = StringValue.of(errorMessage)
        }

        if (orderId != null) {
            responseBuilder.matchingEngineId = StringValue.of(orderId)
        }

        responseBuilder.messageId = StringValue.of(messageId)

        if (version != null) {
            responseBuilder.walletVersion = version
        }

        writeClientResponse(responseBuilder.build())
    }

    @Suppress("DuplicatedCode")
    private fun writeClientResponse(response: GrpcIncomingMessages.LimitOrderResponse) {
        try {
            val start = System.nanoTime()
            callback.onNext(response)
            writeResponseTime = System.nanoTime() - start
            if (closeStream) {
                callback.onCompleted()
            }
        } catch (exception: IOException) {
            LOGGER.error("Unable to write for message with id $messageId response: ${exception.message}", exception)
        }
    }
}