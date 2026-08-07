# Attachments and the Android share sheet

A design for letting Cadence receive text, links, images and files from other apps, and for storing
what it receives. Written before any of it is built, so the decisions can be argued with cheaply.

Nothing here is implemented yet. Line references are against `main` at `32fd8d2`.

## The problem

Cadence has no intent handling at all. `MainActivity` carries only `MAIN`/`LAUNCHER`, there is no
`onNewIntent`, no `FileProvider`, no `shortcuts.xml`. Cadence does not appear in any share sheet, and
a task holds one nullable `notes` string and nothing else.

What we want: share a page from the browser, a photo from the gallery, a PDF from Drive, a selected
sentence from anywhere — and have Cadence ask, in one sheet, whether that belongs on a task you
already have or on a new one.

Two constraints shape everything below. The app is local-first, with no account and no server. And a
web companion that reads the backup format is a stated direction (`ROADMAP.md`), so whatever we
store has to be something a browser could eventually be handed.

## A. Storage

### The decision

**Copy the bytes into app-private storage, content-addressed by SHA-256, with one metadata row per
attachment. Two kinds — `LINK` (a URL, no blob) and `FILE` (blob-backed).** Never a stored URI.

### Why not just keep a link to the file on the device

Because it cannot work. An `ACTION_SEND` content URI arrives with a one-shot
`FLAG_GRANT_READ_URI_PERMISSION`. `takePersistableUriPermission` throws `SecurityException` on it,
because the sender never set `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` — only
`ACTION_OPEN_DOCUMENT`/`GET_CONTENT` URIs can be persisted, and only when the app asks. The grant
dies with the activity. Read the bytes while the share activity is alive or lose them.

The app already knows this distinction, which makes the contrast concrete: `PersistableCreateDocument`
(`ui/settings/SettingsScreen.kt:472`) exists precisely so automatic backup sync can *ask* for a
persistable grant. That subclass works because we do the asking. A share sender never offers it.

Even where a persisted URI were possible — an in-app "attach an existing document" picker — storing
it would leave the app with two storage modes, two failure modes ("the file moved" vs "the blob is
gone"), and a web export that can fetch bytes for only half the rows. It also breaks the moment the
source app is uninstalled or the SD card comes out.

### Why content addressing

Dedupe is free: the same PDF shared onto three tasks is one file on disk. Integrity is checkable. And
it is precisely the shape a web version needs — `PUT /blobs/<sha256>` is idempotent, `HEAD` tells the
client whether to upload at all, blobs are immutable so uploads commute and need no conflict
resolution, and the metadata rows sync as ordinary records beside tasks and projects.

Retrofitting content addressing later means rehashing every user's store during an upgrade. Doing it
now costs one `MessageDigest` in the copy loop.

### Domain model — `domain/model/Attachment.kt`

```kotlin
enum class AttachmentKind { LINK, FILE }

data class Attachment(
    val id: Long = 0L,
    val taskId: Long,
    val kind: AttachmentKind,
    /** File name, or the link's title. Never null — a row always has something to draw. */
    val name: String,
    /** The blob's media type, or "text/uri-list" for a LINK. */
    val mimeType: String,
    /** Lowercase hex SHA-256 of the bytes — the blob's only name. Null for a LINK. */
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    /** The URL for a LINK, null for a FILE. */
    val url: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Int = 0,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}
```

One-to-many via `taskId`, not a join table. The *blob* is already shared by content address; the
*attachment* is a per-task fact with its own name, order and creation time, which a join table would
have to carry anyway while turning every delete into two steps.

No `IMAGE` kind: that would be a denormalised copy of `mimeType.startsWith("image/")` that can
disagree with it. Thumbnails branch on the mime prefix.

No Android imports, so the ranking function in section D can reach it from `domain/`.

### Room — `data/db/Entities.kt`

```kotlin
@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["taskId"], name = "idx_attachments_task"),
        Index(value = ["sha256"], name = "idx_attachments_sha"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    /** [AttachmentKind] by name, not ordinal — reordering the enum must not rewrite history. */
    val kind: String,
    val name: String,
    val mimeType: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0L,
    val url: String? = null,
    /** Epoch millis, matching every other instant in this file. */
    val createdAt: Long = 0L,
    val sortOrder: Int = 0,
)
```

With `toDomain()`/`toEntity()` beside the existing pairs (`Entities.kt:106`/`:123`), in the same
exhaustive field-by-field style. `kind` decodes with a fallback rather than throwing —
`AttachmentKind.entries.firstOrNull { it.name == kind } ?: if (sha256 != null) FILE else LINK` — the
rule `RecurrenceCodec` already follows.

`idx_attachments_sha` is not decorative. The blob-reclaim query filters on `sha256` and runs on every
delete.

### The migration

`CadenceDatabase.kt` goes to `version = 5` (`:91`), adds `AttachmentEntity::class` to `entities`
(`:90`), an `attachmentDao()`, and `MIGRATION_4_5` to `.addMigrations` (`:116`), written in
`MIGRATION_3_4`'s additive style (`:82`):

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                taskId INTEGER NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                sha256 TEXT,
                sizeBytes INTEGER NOT NULL DEFAULT 0,
                url TEXT,
                createdAt INTEGER NOT NULL DEFAULT 0,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(taskId) REFERENCES tasks(id) ON DELETE CASCADE
            )
            """,
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_task ON attachments(taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_sha ON attachments(sha256)")
    }
}
```

**This is the one place a mistake bricks every existing install.** `exportSchema = false` means Room
has no schema JSON to diff against, and there is no destructive fallback, so any disagreement between
this hand-written DDL and what Room generates surfaces as
`IllegalStateException: Migration didn't properly handle attachments` — at open, on every device that
already has data.

Mitigation, and it is not optional: mirror `MIGRATION_2_3`'s column-declaration style exactly (it
ships and works); before merging, flip `exportSchema = true` temporarily, build, and diff the
generated `5.json` `createSql` against the string above; and install a v5 build over a populated v4
one by hand. CI cannot check this — `room-testing` needs a device and there is no emulator.

### The blob store — `data/BlobStore.kt`

Takes a `File` root, not a `Context`, so `AppContainer` passes `File(context.filesDir, "attachments")`
and JVM unit tests pass a JUnit `TemporaryFolder`. No Robolectric.

```kotlin
class BlobStore(private val root: File, private val tmp: File) {
    fun store(source: InputStream, maxBytes: Long): StoreResult
    fun file(sha256: String): File?              // null when the blob is gone
    fun present(hashes: Collection<String>): Set<String>
    fun deleteAll(hashes: Collection<String>)
    /** Removes anything on disk nobody names, plus leftover temp files. */
    fun sweepOrphans(referenced: Set<String>)
}

sealed interface StoreResult {
    data class Ok(val sha256: String, val sizeBytes: Long) : StoreResult
    data object TooLarge : StoreResult
    data class Failed(val cause: Throwable) : StoreResult
}
```

Layout `attachments/<first two hex chars>/<full sha256>`, extensionless. The two-character fan-out
keeps any one directory small.

The copy streams through a `DigestInputStream` into `tmp/<random>`, aborts past `maxBytes`, then
`renameTo`s into place — both directories sit under `/data/user/0/<pkg>/`, so the rename is atomic.
If the destination already exists the bytes were a duplicate: drop the temp file and reuse the blob.
That is the whole dedupe.

Caps, in the style of `MAX_SUBTASKS_PER_PARENT` (`CadenceRepository.kt:19`):

```kotlin
const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
const val MAX_ATTACHMENTS_PER_TASK = 20
```

25 MiB per file because the copy has no progress UI, and because it is the number Android's own
auto-backup ceiling already trains people to expect.

### No refcount column

The `attachments` table *is* the refcount, read by one indexed query:

```kotlin
/** Which of these hashes some row still names. The rest are garbage on disk. */
@Query("SELECT DISTINCT sha256 FROM attachments WHERE sha256 IN (:hashes)")
suspend fun stillReferenced(hashes: List<String>): List<String>
```

A stored counter is a second source of truth that drifts the first time a process dies mid-write, and
nothing ever repairs it.

### Reclaiming blobs, on every delete path

```kotlin
suspend fun deleteTask(id: Long) {
    val ids = listOf(id) + taskDao.subtasksOf(id).map { it.id }
    val hashes = attachmentDao.hashesForTasks(ids)
    attachmentDao.deleteForTasks(ids)
    taskDao.deleteWithSubtasks(id)
    reclaim(hashes)
}

/** A blob is garbage the moment no row names it — the table is the only refcount. */
private suspend fun reclaim(hashes: List<String>) {
    if (hashes.isEmpty()) return
    val kept = attachmentDao.stillReferenced(hashes).toSet()
    blobStore.deleteAll(hashes.toSet() - kept)
}
```

The attachment rows are deleted **explicitly**, not left to the FK cascade, for two reasons. First,
`TaskDao.deleteWithSubtasks` (`Daos.kt:67`) is a raw `DELETE` whose cascade depends on
`PRAGMA foreign_keys` staying on. Second and decisively: the repository tests use hand-written
in-memory fakes (`CadenceRepositoryTest.kt:213`, `:275`, `:294`) that model no foreign-key semantics
at all. An implicit delete would make those fakes silently diverge from SQLite — exactly the class of
bug the fakes exist to catch. Keep the cascade as a safety net; never rely on it.

`ProjectDao.deleteWithChildren` (`Daos.kt:113`) already deals with tasks first, inside its
`@Transaction`, so an attachment delete goes ahead of `deleteTasksIn` on the same subtree query, with
`reclaim` in the repository afterwards (`CadenceRepository.kt:276`). With `deleteTasks = false` the
tasks move to the Inbox and nothing is reclaimed.

`restore(snapshot)` (`:294`) replaces everything, so per-hash reclaim is meaningless — run a full
`sweepOrphans(attachmentDao.referencedHashes().toSet())` afterwards. Cold start runs the same sweep
once, which heals leaks from a process killed mid-copy and clears the share cache. Listing a few
hundred files is free.

### Recurrence: attachments carry over

When a recurring task is completed, `setCompleted` (`CadenceRepository.kt:130`) keeps the finished row
and inserts the next occurrence. **Its attachments come with it** — the rows are cloned with
`id = 0L, taskId = nextId`, mirroring what already happens to subtasks.

The rule is the subtask rule: a checklist is the *method* of doing the task, the method repeats, and
it hands over unticked. Attachments follow. Because the store is content-addressed, cloning a FILE row
copies no bytes — it is a row insert pointing at the same blob.

The rejected alternative was "links repeat, files don't", on the theory that a URL is a pointer that
stays true (the rent portal, the meeting link) while a file is evidence of one occasion (the photo of
last month's meter reading), and that copying last month's photo forward makes the new occurrence look
already done. That reasoning is worth remembering, but it makes the rule harder to explain than it is
worth, and a user who does not want the photo can delete it. If it turns out to grate in use, the
escape hatch is additive: a `carryOver: Boolean` column defaulting to true.

### `android:allowBackup="true"` is a live regression risk

The manifest sets `allowBackup="true"` (`AndroidManifest.xml:9`) with no rules file, so Android
auto-backup currently ships `cadence.db` and the `SharedPreferences` to the user's Drive. Auto-backup
has a **25 MB per-app ceiling, and exceeding it makes the platform silently stop backing the app up
entirely** — one large attachment would kill the working database backup with no error anywhere.

So the storage phase must add both rules files (Android 12+ ignores `fullBackupContent` and reads
`dataExtractionRules`, so you need the pair) and, critically, **re-state what works implicitly
today** — otherwise adding the file *is* the regression:

```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <include domain="database" path="cadence.db" />
    <include domain="sharedpref" path="." />
    <exclude domain="file" path="attachments/" />
</full-backup-content>
```

`res/xml/data_extraction_rules.xml` takes the same shape under `<cloud-backup>` and
`<device-transfer>`; device transfer may keep `attachments/`, being a direct phone-to-phone copy with
no quota.

The blobs are therefore not in the cloud backup. That is correct, and it is exactly why the bundle
export in section B has to exist.

### When a blob is missing

Never crash, never block, never look like corruption. The row is the truth of "the user attached
this"; the blob is a cache that can be gone — restored from a plain JSON backup, restored on a new
device, killed mid-copy.

The row renders greyed with a broken-file icon and a "file is not on this device" line, and tapping
offers **Find file**: an `OpenDocument` picker that re-imports the bytes and heals the row when the
hash matches (and updates hash and size when it does not, keeping the name).

Presence must not be a filesystem hit per row per emission. The Android-side store exposes a
`StateFlow<Set<String>>` of present hashes, and the derived helpers live on `CadenceUiState` as the
architecture requires:

```kotlin
fun attachmentsFor(taskId: Long): List<Attachment>
fun attachmentCount(taskId: Long): Int
fun isPresent(attachment: Attachment): Boolean =
    attachment.kind == AttachmentKind.LINK || attachment.sha256 in presentBlobs
```

## B. The backup contract

### `VERSION` stays 1

This is purely additive — a new optional key with a default — and both `BackupCodec.kt:48` and
`CLAUDE.md` are explicit that bumping would make every existing install refuse the whole file over one
key it could ignore. The shipped `ignoreUnknownKeys = true` (`BackupCodec.kt:58`) means older installs
already tolerate the new key today.

### Nested under the task, with no id

`BackupTask` (`BackupCodec.kt:150`) gains `val attachments: List<BackupAttachment> = emptyList()`.

Nested rather than a sibling top-level array, because containment is strict — an attachment has no
life without its task — and nesting deletes an entire repair class ("attachment names a task the file
lacks") that a flat array would need.

**No `id` key at all.** Attachment ids are referenced by nothing, so they need not survive a round
trip, and writing them would create a real bug: two tasks' attachments could share an id and
`OnConflictStrategy.REPLACE` would silently drop one. On decode every attachment gets `id = 0` and the
database assigns.

```json
{
  "format": "cadence.backup",
  "version": 1,
  "exportedAt": "2026-08-07T09:12:00Z",
  "projects": [],
  "tasks": [
    {
      "id": 42,
      "title": "Send the August invoice",
      "priority": 2,
      "attachments": [
        {
          "kind": "FILE",
          "name": "invoice-2026-08.pdf",
          "mimeType": "application/pdf",
          "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
          "sizeBytes": 184320,
          "url": null,
          "createdAt": "2026-08-07T09:10:11Z",
          "sortOrder": 0
        },
        {
          "kind": "LINK",
          "name": "Client portal",
          "mimeType": "text/uri-list",
          "sha256": null,
          "sizeBytes": 0,
          "url": "https://portal.example.com/invoices",
          "createdAt": "2026-08-07T09:11:00Z",
          "sortOrder": 1
        }
      ]
    }
  ]
}
```

Decode repairs rather than trusts, in the codec's existing style:

- unknown `kind` → `FILE` when `sha256` is present, else `LINK`
- a `LINK` with a blank `url` → dropped
- a `FILE` whose `sha256` is not 64 lowercase hex → **keep the row, null the hash**. "There was a file
  called X here" is still information; dropping it loses that silently
- negative `sizeBytes` → 0; a blank `name` → derived from the url host, else from the mime type
- more than 1000 attachments on one task → truncated. A memory-bomb guard, deliberately far above
  `MAX_ATTACHMENTS_PER_TASK` so a file written by a future Cadence with a higher limit still imports
  whole

`BackupDao.replaceAll` (`Daos.kt:140`) takes a third list. Order is load-bearing in both directions:
delete attachments first, insert them last, because the `taskId` foreign key needs its tasks in place.
That signature change breaks `FakeBackupDao` (`CadenceRepositoryTest.kt:294`).

### Restoring with the blobs absent

After restoring a plain `.json` on a new device, *every* FILE attachment is missing. That must not read
as corruption, so `BackupOutcome.Imported` (`BackupIo.kt:33`) gains a `missingFiles` count and the
Settings screen says "3 files are not on this device" through a plurals resource. The rows themselves
render in the missing state above, with **Find file** available.

### Automatic backup sync makes this urgent

Automatic sync (`data/backup/AutoBackupSync.kt`, `domain/backup/AutoBackupPolicy.kt`) writes
`backup.json` on every change. Blobs are not in the JSON. So **a user with auto-sync on would believe
attachments are backed up when they are not** — which moves the bundle below from "nice to have" to a
prerequisite for shipping attachments at all.

It also raises a question this document deliberately leaves open: should auto-sync write a bundle
instead? A debounced 2 s rewrite of a multi-megabyte zip on every keystroke is a very different cost
profile from rewriting a JSON file, and the answer probably involves writing blobs beside the JSON
rather than inside a zip. Worth deciding before the bundle ships, not before it is designed.

And because `AutoBackupPolicy.shouldImport` feeds a replace-everything restore, a second device
following the same file gets every attachment row in the missing state. That is precisely what the
healing affordance and the `missingFiles` count are for.

### The bundle export

Without it the backup quietly stops being a backup for anyone who uses attachments, and "your data is
yours, in a file you control" is the whole local-first promise.

```
cadence-2026-08-07.zip
├── backup.json        ← byte-identical to today's export
└── blobs/<sha256>     ← extensionless, exactly the store's own naming
```

Zip because `java.util.zip` is in the JDK, so no new dependency joins a deliberately tiny list;
because `ZipInputStream` streams, so a 200 MB bundle never lands in memory; because every desktop OS
opens it by double-click; and because a future web importer gets it free from `jszip`, which is the
stated direction.

Read side sniffs the first four bytes for `PK\x03\x04`. If it is a zip, `backup.json` goes through the
unchanged `BackupCodec.decode`, then each `blobs/*` entry streams into `BlobStore` **with the hash
verified while writing** — an entry whose content does not hash to its name is dropped, because a
bundle is untrusted input. Entry names are never used as paths: the only thing taken from `blobs/<x>`
is `<x>`, validated as exactly 64 lowercase hex characters. That is the zip-slip guard, and it is a
pure function.

The pure parts (`isBundle`, `blobHashFromEntryName`) belong in `domain/backup/BackupBundle.kt` so they
are JVM-tested; the streaming and the `contentResolver` in `data/backup/BundleIo.kt`.

**The import UI needs no change at all** — `SettingsScreen.kt:271` already launches `OpenDocument()`
with `arrayOf("*/*")`, and the sniff routes it. Export gains a second button, "Export with files",
showing the total blob size in its supporting line so the cost is visible before the tap.

## C. The intent surface

### A separate transparent activity, not `MainActivity` + `onNewIntent`

Five reasons, the second decisive:

1. `MainActivity` is `launchMode` standard. Making it a SEND target forces either `singleTop`/
   `singleTask` — which changes back-stack behaviour for the launcher icon too, and `singleTask`
   collapses the app into one task — or a second `MainActivity` instance with the whole nav graph
   loading behind the sheet.
2. **The back stack is the point.** After saving, the user must land back in the *sending* app. A
   dedicated activity with `excludeFromRecents` and `taskAffinity=""` gets that by finishing itself.
   `MainActivity` cannot: finishing it would close an app the user may already have open in another
   task.
3. It keeps the manifest honest. `MainActivity` keeps exactly `MAIN`/`LAUNCHER`, and every intent
   concern lives in one readable file.
4. It bounds the URI-grant problem. The one-shot grant lives as long as *that* activity, and nothing
   else in the app can outlive the grant while holding a `Uri`.
5. The cost is one extra `CadenceViewModel`. Acceptable — it is stateless apart from transient UI and
   reaches the same container. One wrinkle to comment on: `CadenceViewModel`'s `init` subscribes to
   `repository.tasks` and calls `reminderScheduler.sync`, so a share triggers a second sync pass. That
   is harmless because `sync` schedules-or-cancels deterministically for every task — it is idempotent
   by design — but it should be acknowledged, not discovered.

Either way, **copy the bytes on receipt, before composing any UI.**

### Manifest

```xml
<activity
    android:name=".share.ShareTargetActivity"
    android:exported="true"
    android:label="@string/share_target_label"
    android:theme="@style/Theme.Cadence.Transparent"
    android:excludeFromRecents="true"
    android:launchMode="singleTop"
    android:taskAffinity=""
    android:windowSoftInputMode="adjustResize">

    <intent-filter android:label="@string/share_target_label">
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
        <data android:mimeType="image/*" />
        <data android:mimeType="application/pdf" />
        <data android:mimeType="*/*" />
    </intent-filter>

    <intent-filter>
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
        <data android:mimeType="*/*" />
    </intent-filter>

    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>

<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.files"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

`*/*` subsumes the specific types for matching, but listing `text/plain` and `image/*` explicitly is
not redundant — several senders and the share-sheet ranking treat a specific match as stronger than a
wildcard. `taskAffinity=""` keeps the share activity out of `MainActivity`'s task so it never drags
the app forward.

**The FileProvider authority must use `${applicationId}`, not a literal.** Debug builds carry
`applicationIdSuffix = ".debug"` and both builds install side by side; a hardcoded authority makes the
second install fail outright with `INSTALL_FAILED_CONFLICTING_PROVIDER`.

`Theme.Cadence.Transparent` inherits `Theme.Cadence` and adds `windowIsTranslucent`, a transparent
`windowBackground` and `backgroundDimEnabled`, so the sheet floats over the sending app rather than
over a slab of Cadence background.

`res/xml/file_paths.xml` exposes **only** the share cache:

```xml
<paths>
    <cache-path name="shared" path="share/" />
</paths>
```

Never `filesDir/attachments`. Handing a file out means first materialising a named copy at
`cacheDir/share/<sanitised name>`, because a content-addressed blob has no extension and
`FileProvider.getType()` derives the mime from the extension via `MimeTypeMap` — otherwise it returns
`application/octet-stream`, which most viewers refuse. The named copy fixes the mime *and* gives the
receiving app the real filename, and `cacheDir` is reclaimable. Subclassing `FileProvider` to override
`getType()` would instead put a Room query on a binder thread and still show a hex string as the
display name.

### Reading the intent

The parsing layer is `domain/share/`, with **no `android.net.Uri` anywhere in it** — the same reason
`org.json` is banned from `domain/`: `Uri.parse` throws `RuntimeException("Stub!")` under JVM tests.
URLs travel as `String`.

```kotlin
data class SharedText(val subject: String? = null, val text: String? = null)

data class CaptureLine(
    val title: String,
    val notes: String? = null,
    val links: List<SharedLink> = emptyList(),
    /** False for prose: a shared headline must not turn "Friday" into a due date. */
    val parseable: Boolean = true,
)

fun SharedText.toCaptureLine(lexicon: QuickAddLexicon): CaptureLine
```

`EXTRA_SUBJECT` and `EXTRA_TEXT` arrive together far more often than not, and getting this wrong is
the difference between "Article title" and "Article title https://very.long/url?utm_source=…" as your
task title:

| subject | text | title | notes | links |
|---|---|---|---|---|
| — | `buy milk tomorrow` | `buy milk` (parsed) | — | — |
| `Article title` | `https://x/y` | `Article title` | — | `x/y`, named from the subject |
| `Article title` | `Article title https://x/y` | `Article title` | — | deduped, one link |
| — | `https://x/y` | derived from host or slug | — | `x/y` |
| — | `Look at this https://x/y` | `Look at this` | — | `x/y` |
| `Fwd: Q3 numbers` | 900-char body | `Q3 numbers` | the body | any urls in it |

Each rule is a testable clause:

1. Every URL is extracted out of `text` and becomes a link; the remainder is the title candidate.
2. `subject` wins when the candidate is blank, or is a prefix or near-duplicate of it.
3. Reply and forward prefixes (`Re:`, `Fwd:`, `WG:`, `AW:`) are stripped — and **that is vocabulary,
   so it lives in `QuickAddLexicon`**, not in the grammar, exactly as the localisation rules require.
4. A candidate over 120 characters or containing a newline becomes a first-line title cut at a word
   boundary, with the whole text as notes.
5. `parseable` is false for long or multi-line text. Short shares run through `QuickAddParser`, so
   "buy milk tomorrow !p1" typed into a keyboard's share still works; long prose does not, because a
   shared headline containing "Friday" would otherwise silently acquire a due date. The chip row still
   lets the user set one by hand.

The Android side, a new `share/` package beside `reminders/`:

```kotlin
sealed interface ShareRequest {
    data class Text(val payload: SharedText) : ShareRequest
    data class Files(val payload: SharedText, val uris: List<Uri>, val dropped: Int) : ShareRequest
    data object Empty : ShareRequest
}
```

Details that have to be right:

- Use `IntentCompat.getParcelableExtra(...)` / `getParcelableArrayListExtra(...)`. The typed platform
  overloads only exist from API 33 and the untyped ones are deprecated. `androidx.core:core-ktx` is
  already a dependency and ships `IntentCompat` — no new dependency.
- `ACTION_SEND` with a file **also** carries `EXTRA_TEXT` from several senders (Gmail, Drive). Hence
  `Files(payload, uris)`, never files-XOR-text.
- `ACTION_PROCESS_TEXT` reads `EXTRA_PROCESS_TEXT`. Cadence never rewrites the selection, so always
  `setResult(RESULT_CANCELED)`.
- `SEND_MULTIPLE` from a gallery can be a hundred images. Cap at `MAX_ATTACHMENTS_PER_TASK`, report
  what was dropped, and say so.
- Mime per URI: `contentResolver.getType(uri)` first, then `intent.type`, and **reject any type
  containing `*`** — for `SEND_MULTIPLE`, `intent.type` is the *common* type like `image/*`, which is
  not a real media type and must never be stored. Fall back to `application/octet-stream`.
- Display name from `OpenableColumns.DISPLAY_NAME`, then the last path segment, then generated from
  the mime — and always through a sanitiser that strips `/`, `..` and control characters and caps the
  length. The name comes from another app and ends up as a filename in `cacheDir/share`, so that is a
  genuine path-traversal guard, and a pure function worth its own tests.

Ingest runs on `Dispatchers.IO` in the activity's `onCreate`, before anything composes. The blobs land
in the store immediately and the staged attachments carry `taskId = 0` until the user picks a
destination. If the sheet is cancelled, the staged hashes are reclaimed by the same sweep, so nothing
leaks.

## D. Deciding where it lands

### New task first, matches below

The sheet, top to bottom:

1. **What you shared** — a preview in the established card recipe (`TaskDetailScreen.kt:327`): a 48dp
   thumbnail for an image or a mime icon otherwise, the name, the size. Several files become a row of
   thumbnails with a "+3" overflow chip. This answers "did I share the right thing" before anything
   else is asked.
2. **New task**, first, because it is the common case and it never fails — the prefilled title, the
   same token highlighting, the same chip row, the same submit button as quick-add.
3. **Add to an existing task** — up to four ranked candidates, each drawn with the existing `TaskRow`
   so it honours `LocalCadenceDensity`, the `PrioritySpine` and the due chip identically to every
   other list. The match reason renders as a small trailing label through `ui/format/`, never colour
   alone.
4. **Find another task** — one row expanding into an inline field filtering on the same
   title-or-notes contains that Search uses, so the sheet is never a dead end when the ranking guesses
   wrong.

Picking a candidate re-parents the staged attachments onto it and **appends** any leftover prose to
`notes` after a blank line — never overwrites. The sheet then holds a confirmation for about a second
and finishes. A `SnackbarHost` would have nowhere to live in an activity that is about to finish.

With no tasks at all, the candidates section is omitted entirely; an empty state inside a sheet is
noise.

The share activity must wrap its content in `CadenceTheme(theme = …, density = …)` exactly as
`MainActivity` does, or every row silently falls back to default density and default priority colours.

### The enabling refactor

Extract the body of `QuickAddSheet` (`ui/quickadd/QuickAddSheet.kt:80`, currently one 371-line file
whose `ModalBottomSheet` starts at `:116`) into a `QuickAddBody(...)` composable, leaving
`QuickAddSheet` a thin wrapper. Both capture sheets then host one widget, and the share flow inherits
every future quick-add improvement for free. This is the highest-leverage change in the document.

### The ranking, as a pure function

`domain/share/ShareTargetRanking.kt`, no Android imports, so it is a plain JUnit4 test with frozen
date literals in the style of `RecurrenceEngineTest`:

```kotlin
/** One candidate and why it scored, so the sheet can say what it matched on. */
data class ShareCandidate(val task: Task, val score: Int, val reason: MatchReason)

/** The wording lives in `ui/format/` — domain/ produces no prose. */
enum class MatchReason { SAME_LINK, SAME_SITE, WORDS, RECENT }

object ShareTargetRanking {
    /** Below this a match is noise, and "New task" should not have to compete with noise. */
    const val MIN_SCORE = 20

    fun rank(
        text: String,
        urls: List<String>,
        tasks: List<Task>,
        attachments: List<Attachment>,
        today: LocalDate,
        now: Instant,
        limit: Int = 4,
    ): List<ShareCandidate>
}
```

`today` and `now` are parameters, not `Instant.now()`, so tests freeze them. `attachments` is a
parameter because the strongest signal by far is "this exact link is already on that task".

| signal | points | why |
|---|---|---|
| an existing LINK's normalised url equals a shared url | **+100** | as close to certain as this gets |
| an existing LINK has the same host | +25 | "the Amazon order thread" |
| the shared url's host appears as a title token | +20 | a task literally called "GitHub milestone" |
| title token overlap, weighted by rarity | +12 each, capped at +48 | the bulk of the ordinary case |
| the shared text names the task's project | +10 | |
| task is open | +15 | you almost always mean an open task |
| task is done | −40 | but a just-finished one can still be right |
| due today or overdue | +10 | |
| created within 24 h / 7 days | +12 / +6 | recency, decaying |
| task is a subtask | −5 | prefer the parent, marginally |

Tokenisation is where the design earns its keep: lowercase, split on non-alphanumerics, drop tokens
under three characters, and then **no stemming and no stopword list** — rarity does that job instead.

```kotlin
// A word half the tasks contain tells you nothing. Rarity does the stopword list's job,
// and does it in every language, which a hand-written list cannot.
private fun weight(token: String, titlesContaining: Int, total: Int): Int =
    if (titlesContaining * 4 > total) 0 else 12
```

Language-independent, which matters for a bilingual app whose rule is that adding a language means
adding a lexicon, not touching logic.

URL normalisation is also pure — lowercase the scheme and host, drop `www.`, the trailing slash, the
fragment, and `utm_*`/`fbclid`/`gclid`/`ref`. Without the tracking-parameter stripping the +100 signal
almost never fires, because the same product page shared from an app and from a browser differs only
by `utm_source`.

## E. The other integrations, assessed

| integration | verdict | reasoning |
|---|---|---|
| Opening an attachment via `ACTION_VIEW` | **Required.** | An attachment you cannot open is a dead row. Materialise the named copy, `FileProvider.getUriForFile`, `setDataAndType` plus the read grant, and wrap `startActivity` in `runCatching` so a missing viewer is a message rather than an `ActivityNotFoundException` crash. |
| `cadence://task/{id}` deep link | **Do it. ~10 lines, and it fixes a live bug.** | `navDeepLink` on the task destination (`ui/CadenceApp.kt:223`) plus a BROWSABLE filter. `ReminderReceiver` already puts `EXTRA_TASK_ID` into its content intent (`:31`) and **nobody reads it** — so a reminder currently drops you on Today rather than on the task it just reminded you about. No verified App Links: that needs a domain and a hosted `assetlinks.json`, which contradicts the no-cloud stance. |
| Outgoing "share this task as text" | **Do it. Tiny, high value.** | One overflow item on the detail screen. The text is user-visible prose, so the formatter is a `@Composable` in `ui/format/` built from string resources and `domain/` contributes nothing. With attachments present, escalate to `ACTION_SEND_MULTIPLE` with FileProvider URIs. |
| Static launcher shortcuts | **Do it.** | Three — Add task, Today, Inbox. One XML plus one `<meta-data>`. The only real work is that quick-add is `remember` state (`ui/CadenceApp.kt:77`), not a route, so "Add task" needs the activity to read an extra and `CadenceApp` to take a start action that opens the sheet once. |
| Dynamic sharing shortcuts / Direct Share per project | **Defer.** | Real value — one tap to "Cadence · Work" from the share sheet's top row — but genuinely expensive: `ShortcutInfoCompat` lifecycle on every project create, rename and delete, a re-push after a backup restore, a `<share-target>` element whose data spec must mirror the intent filter, and OS-owned ranking you cannot test. Reserve the hook (the sheet already takes a default project, and the shortcut id arrives as `EXTRA_SHORTCUT_ID`) and revisit if the share flow proves popular. |

## F. Thumbnails: hand-rolled, not Coil

The only images to decode are files the app itself wrote to its own `filesDir`, at two known small
sizes — a 48dp row thumbnail and a preview. No network, no cache invalidation, no placeholder or
crossfade machinery, which is most of what Coil buys.

Coil plus its Compose integration adds roughly 600 KB and an OkHttp transitive graph to a dependency
list that is fourteen lines and deliberately minimal, and the integration must then be kept in step
with the Compose BOM on every bump.

The decode is about thirty lines: `inJustDecodeBounds` for the bounds, `inSampleSize` as the largest
power of two that keeps the result at or above the target, decode, `asImageBitmap()`. Cache in an
`LruCache` keyed by `sha256 + targetPx` and bounded by byte count at around 8 MB. On the Compose side,
`produceState(null, sha256) { … }` handles async and cancellation with no library at all, falling back
to the mime icon.

The one thing Coil would genuinely add is EXIF rotation on camera photos. If photos come out sideways,
add `androidx.exifinterface` — about 40 KB and dependency-free — not Coil.

## G. Phases

Each leaves the app shippable.

**Phase 0 — refactors, no behaviour change.** The `QuickAddBody` extraction. And free a `combine`
slot in `CadenceViewModel`: `combine` has typed overloads for at most five flows, the state combine
already uses exactly five (`:162`), and the codebase has already hit this once — `settingsWithSync`
(`:157`) exists for precisely this reason, with the comment saying so. Pairing `backupOutcome` and
`snackbarMessage` (`:152`–`:153`) the same way makes room for the attachments flow. Do it before
Phase 1, not during.

**Phase 1 — storage, no UI.** `domain/model/Attachment.kt`, `data/BlobStore.kt`, the entity and
mappers, `AttachmentDao`, `MIGRATION_4_5` and version 5, the repository's flow and CRUD and every
reclaim path, the container wiring, and the two backup-rules files.

**Phase 2 — attachments in the app.** The card on the task detail screen between the subtask card
(`TaskDetailScreen.kt:313`) and the notes card, an `OpenDocument` picker, a paste-a-link dialog, the
FileProvider and viewer, thumbnails, an attachment count on the task row. Ships as a complete feature
on its own, which is exactly why it comes before the intents.

**Phase 3 — the share target.** `domain/share/`, the `share/` package, the activity, the sheet. Note
that `CadenceViewModel.addParsedTask` (`:263`) currently returns a `Job`; the share flow needs the new
task's id, so it has to start returning it.

**Phase 4 — the bundle.** Attachment metadata in `BackupCodec`, `BackupBundle`/`BundleIo`, the export
button, the missing-file count. Plus the auto-sync question from section B.

**Phase 5 — the extras.** Shortcuts, the deep link (and the reminder fix that comes with it), outgoing
share.

## Testing

The repo's style: plain JUnit4, backticked sentence names, frozen date literals, hand-written fakes,
heavy KDoc saying why each case exists.

JVM tests carry almost all of it — url normalisation and title derivation; every row of the
subject/text table; the ranking (an exact link match outranking a strong word match, a done task
losing to an open one, a word in half the titles scoring zero, nothing under `MIN_SCORE` returned);
filename sanitising against `../../etc/passwd`, control characters and a 400-character name; the blob
store against a `TemporaryFolder` (same bytes twice writes one file, a stream past the cap leaves no
temp behind, `sweepOrphans` keeps the referenced); the codec (a round trip with both kinds, a v1 file
*without* the key still decoding, unknown `kind` falling back, a malformed hash keeping the row);
`isBundle` and the entry-name validator; and an attachment lifecycle test in the shape of
`RecurrenceChainTest` covering delete, shared blobs, project delete and the recurrence carry-over.

The fakes gain a `FakeAttachmentDao` in the same shape as the others, `FakeBackupDao` gains the new
`replaceAll` arguments, and `FakeTaskDao.deleteWithSubtasks` **must not** grow a fake cascade — the
repository deletes attachment rows explicitly precisely so the fake stays honest.

One instrumented test, `ShareIntentReaderDeviceTest`, because `Intent` and `Uri` are throwing stubs on
the JVM — the same justification `QuickAddPatternsDeviceTest` already carries. It covers SEND with
subject and text, SEND with both a stream and text, SEND_MULTIPLE over the cap, PROCESS_TEXT, and a
wildcard `intent.type` never being stored as a mime.

**Not covered automatically, and it must be said out loud in the PR:** the migration. See the warning
in section A.

## Strings

All of it goes into both `values/` and `values-de/`, under new box-drawing banner sections, in the
existing naming convention, with counts through `<plurals>` even where English and German agree — an
attachments group (add, open, remove, empty, missing, locate, sizes, too large, copy failed, no
viewer, count), a share group (target label, new task, existing task, find another, the four match
reasons, added-to, working, failed), backup additions (export with files, the bundle filename, the
missing-file count) and the phase 5 shortcut and share-text strings.
