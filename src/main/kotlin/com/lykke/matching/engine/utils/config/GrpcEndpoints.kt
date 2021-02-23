package com.lykke.matching.engine.utils.config

data class GrpcEndpoints(
    val cashApiServicePort: Int,
    val tradingApiServicePort: Int,
    val balancesServicePort: Int,
    val orderBooksServicePort: Int,

    val dictionariesConnection: String
)