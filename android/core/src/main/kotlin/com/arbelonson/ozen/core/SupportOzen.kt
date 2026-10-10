package com.arbelonson.ozen.core

data class DonationAddress(
    val coin: String,
    val address: String,
    val scheme: String,
    val chainSuffix: String = "",
) {
    val id: String get() = coin
    val paymentURI: String get() = "$scheme:$address$chainSuffix"
}

object SupportOzen {
    val bitcoin = DonationAddress(
        coin = "Bitcoin (BTC)",
        address = "bc1qk5aym0mch042200s2wrc366r3hsxxmgc9nu7tm",
        scheme = "bitcoin",
    )
    val ethereum = DonationAddress(
        coin = "Ethereum (ETH, USDC, USDT)",
        address = "0x0Ea2210fcB0BbF2C3202d9663dB762F1f51b1BBC",
        scheme = "ethereum",
        chainSuffix = "@1",
    )
    val monero = DonationAddress(
        coin = "Monero (XMR)",
        address = "46otohcpNKQfFi9F21ZHTcSiNVrLMw4yMS1SFM5hbDfu5LZCzLGkEZ2Vx4YD5kwK3nKUG6GjMf37z7i6sFQR2NEC1W9ubhb",
        scheme = "monero",
    )

    val addresses = listOf(bitcoin, ethereum, monero)
}
