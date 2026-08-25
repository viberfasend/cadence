package de.andi1984.cadence.data.sync

/**
 * Which Neon project this build syncs with (docs/adr/0005-neon-sync.md): the branch's Data API
 * endpoint, and the Neon Auth (Better Auth) endpoint that signs it in.
 *
 * Both URLs are committed on purpose — they identify the project and grant nothing: row-level
 * security, forced on all four tables in `neon/migrations/0001_cadence_sync.sql`, is what
 * protects the rows. There is no API key at all in this design; the credential is the account.
 *
 * Both values are read from the environment when it names them, so a fork points at its own
 * project without editing Kotlin — and so a throwaway project can be swapped for the real one by
 * changing environment variables rather than a release.
 */
object NeonConfig {

    /** The branch's Data API endpoint — PostgREST, spoken to directly by [CadenceSyncEngine]. */
    private const val DEFAULT_DATA_API_URL =
        "https://ep-patient-tree-b2vb7u61.apirest.c-6.eu-central-1.aws.neon.tech/neondb/rest/v1"

    /** The project's Neon Auth endpoint — Better Auth's REST routes hang off it. */
    private const val DEFAULT_AUTH_URL =
        "https://ep-patient-tree-b2vb7u61.neonauth.c-6.eu-central-1.aws.neon.tech/neondb/auth"

    val dataApiUrl: String = env("CADENCE_NEON_DATA_API_URL") ?: DEFAULT_DATA_API_URL

    val authUrl: String = env("CADENCE_NEON_AUTH_URL") ?: DEFAULT_AUTH_URL

    /** `System.getenv` throws on a sandboxed platform rather than returning null, and a missing
     *  variable is the ordinary case here, not a failure. */
    private fun env(name: String): String? =
        runCatching { System.getenv(name) }.getOrNull()?.takeIf { it.isNotBlank() }
}
