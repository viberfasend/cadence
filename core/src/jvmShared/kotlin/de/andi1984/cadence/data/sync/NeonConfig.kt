package de.andi1984.cadence.data.sync

/**
 * Which Neon project a build syncs with (docs/adr/0005-neon-sync.md): the branch's Data API
 * endpoint, and the Neon Auth (Better Auth) endpoint that signs it in.
 *
 * There is no default. The values are compiled in from `CADENCE_NEON_DATA_API_URL` and
 * `CADENCE_NEON_AUTH_URL` at *build* time (`:core`'s `generateNeonConfig` task writes them into
 * [NeonBuildConfig]), and a build made without them carries `null` here — sync is then simply
 * not offered, and the app is the local-first app it always was. They used to be committed
 * defaults naming the maintainer's own project, which was fine while the repository was private
 * and wrong the moment it was not: every build from source would have pointed at one person's
 * database. They used to be read from the environment at *run* time, too, which never worked on
 * Android — a phone has no shell environment — and only looked like it worked on the desktop.
 *
 * The URLs identify a project and grant nothing: row-level security, forced on all four tables
 * in `neon/migrations/0001_cadence_sync.sql`, is what protects the rows. There is no API key at
 * all in this design; the credential is the account. `docs/self-hosting.md` walks through
 * pointing a build at a project of your own.
 */
data class NeonConfig(
    /** The branch's Data API endpoint — PostgREST, spoken to directly by [CadenceSyncEngine]. */
    val dataApiUrl: String,
    /** The project's Neon Auth endpoint — Better Auth's REST routes hang off it. */
    val authUrl: String,
) {
    companion object {
        /**
         * What this build was compiled with, or `null` when either variable was unset — one URL
         * without the other configures nothing, since sign-in needs both.
         */
        val fromBuild: NeonConfig? = run {
            val dataApiUrl = NeonBuildConfig.DATA_API_URL?.takeIf { it.isNotBlank() }
            val authUrl = NeonBuildConfig.AUTH_URL?.takeIf { it.isNotBlank() }
            if (dataApiUrl != null && authUrl != null) NeonConfig(dataApiUrl, authUrl) else null
        }
    }
}
