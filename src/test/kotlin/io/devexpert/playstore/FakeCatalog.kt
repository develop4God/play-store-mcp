package io.devexpert.playstore

/** Answers from fixed data and never touches credentials or the network. [failure] makes the call throw. */
internal class FakeCatalog(private val failure: Exception? = null) : PlayCatalog {
    val calls = mutableListOf<Pair<String, String>>()

    override fun getAppDetails(packageName: String, language: String): AppDetailsView {
        calls += packageName to language
        failure?.let { throw it }
        return AppDetailsView(
            language = language,
            defaultLanguage = "es-419",
            contactEmail = "dev@example.com",
            contactPhone = null,
            contactWebsite = null,
            title = TextField.of("My App", 30),
            shortDescription = TextField.of("short", 80),
            fullDescription = TextField.of("full", 4000),
            video = null,
            images = mapOf("icon" to ImageGroup(1, 1, listOf(StoreImage("i1", "https://img/1", "abc")))),
        )
    }
}
