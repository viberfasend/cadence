package de.andi1984.cadence.data.sync

/**
 * Which Neon project this build syncs with, and the Stack Auth project that signs it in
 * (docs/adr/0005-neon-sync.md).
 *
 * The publishable client key is committed on purpose — that is what it is for, same as the anon
 * key it replaces. It identifies the Stack Auth project and grants nothing: row-level security,
 * forced on all four tables in `neon/migrations/0001_cadence_sync.sql`, is what protects the
 * rows, and the secret server key never leaves the Neon console.
 *
 * Every value is read from the environment when it names them, so a fork points at its own
 * project without editing Kotlin — and so a throwaway project can be swapped for the real one by
 * changing environment variables rather than a release.
 */
object NeonConfig {

    /** The branch's Data API endpoint — PostgREST, spoken to directly by [CadenceSyncEngine]. */
    private const val DEFAULT_DATA_API_URL = ""

    /** Stack Auth's hosted API; Neon Auth projects live there. */
    private const val DEFAULT_STACK_API_URL = "https://api.stack-auth.com"

    private const val DEFAULT_STACK_PROJECT_ID = ""

    private const val DEFAULT_STACK_PUBLISHABLE_CLIENT_KEY = ""

    val dataApiUrl: String = env("CADENCE_NEON_DATA_API_URL") ?: DEFAULT_DATA_API_URL

    val stackApiUrl: String = env("CADENCE_STACK_API_URL") ?: DEFAULT_STACK_API_URL

    val stackProjectId: String = env("CADENCE_STACK_PROJECT_ID") ?: DEFAULT_STACK_PROJECT_ID

    val stackPublishableClientKey: String =
        env("CADENCE_STACK_PUBLISHABLE_CLIENT_KEY") ?: DEFAULT_STACK_PUBLISHABLE_CLIENT_KEY

    /** `System.getenv` throws on a sandboxed platform rather than returning null, and a missing
     *  variable is the ordinary case here, not a failure. */
    private fun env(name: String): String? =
        runCatching { System.getenv(name) }.getOrNull()?.takeIf { it.isNotBlank() }
}
