package cn.cutemc.rokidmcp.phone.gateway

class FakeRelayWebSocket : RelayWebSocket {
    val sentTexts: MutableList<String> = mutableListOf()
    val closeCalls: MutableList<Pair<Int, String>> = mutableListOf()

    override fun sendText(text: String) {
        sentTexts += text
    }

    override fun close(code: Int, reason: String) {
        closeCalls += code to reason
    }
}
