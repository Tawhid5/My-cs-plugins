plugins {
    id("com.android.library")
    id("kotlin-android")
}

cloudstream {
    setPlugin(
        name = "Rtally",
        description = "Stream from Rtally",
        authors = listOf("MyName"),
        version = 1
    )
}
