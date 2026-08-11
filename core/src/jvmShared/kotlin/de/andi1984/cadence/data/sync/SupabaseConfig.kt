package de.andi1984.cadence.data.sync

/**
 * Which Supabase project this build syncs with.
 *
 * The anon key is committed on purpose — that is what it is for. It identifies the project and
 * grants nothing: row-level security, forced on both tables in
 * `supabase/migrations/20260811120000_cadence_sync.sql`, is what protects the rows, and the
 * `service_role` key never leaves the dashboard (ADR 0002, decision 2).
 *
 * Both values are read from the environment when it names them, so a fork points at its own
 * project without editing Kotlin — and so the throwaway project this was developed against can be
 * swapped for the real one by changing two environment variables rather than a release.
 */
object SupabaseConfig {

    private const val DEFAULT_URL = "https://mhhezrddzbyjwjnrurlt.supabase.co"

    private const val DEFAULT_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1oaGV6cmRkemJ5andqbnJ1cmx0Iiwicm9sZSI6ImFub24i" +
            "LCJpYXQiOjE3ODY0NzM4NDYsImV4cCI6MjEwMjA0OTg0Nn0." +
            "SHGYQHMnTc_A2MU9fJ1Ag_ICyeA2eSmQOwUEcMq_Ueg"

    val url: String = env("CADENCE_SUPABASE_URL") ?: DEFAULT_URL

    val anonKey: String = env("CADENCE_SUPABASE_ANON_KEY") ?: DEFAULT_ANON_KEY

    /** `System.getenv` throws on a sandboxed platform rather than returning null, and a missing
     *  variable is the ordinary case here, not a failure. */
    private fun env(name: String): String? =
        runCatching { System.getenv(name) }.getOrNull()?.takeIf { it.isNotBlank() }
}
