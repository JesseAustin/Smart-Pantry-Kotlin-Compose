package smart.pantry.smartpantrykotlincompose

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientManager {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}