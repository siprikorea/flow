package dataflow

expect object Platform {
    fun loadState(): String?
    fun saveState(json: String)
    fun exportJson(json: String)
    fun importJson(onLoaded: (String) -> Unit)
    fun currentTimeHms(): String
}
