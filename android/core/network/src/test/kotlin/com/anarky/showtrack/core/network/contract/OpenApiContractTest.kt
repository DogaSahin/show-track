package com.anarky.showtrack.core.network.contract

import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
import com.anarky.showtrack.core.network.dto.EpisodeDto
import com.anarky.showtrack.core.network.dto.EpisodeListDto
import com.anarky.showtrack.core.network.dto.FeedItemDto
import com.anarky.showtrack.core.network.dto.FeedPageDto
import com.anarky.showtrack.core.network.dto.GenreCountDto
import com.anarky.showtrack.core.network.dto.GroupActorDto
import com.anarky.showtrack.core.network.dto.GroupDto
import com.anarky.showtrack.core.network.dto.GroupWithInviteDto
import com.anarky.showtrack.core.network.dto.ImportAniListRequest
import com.anarky.showtrack.core.network.dto.ImportSummaryDto
import com.anarky.showtrack.core.network.dto.JoinGroupRequestDto
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryEntryRefDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.LibraryStatsDto
import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.MemberDto
import com.anarky.showtrack.core.network.dto.PersistedMediaDto
import com.anarky.showtrack.core.network.dto.ProgressEntryDto
import com.anarky.showtrack.core.network.dto.ProposeTitleRequestDto
import com.anarky.showtrack.core.network.dto.RecommendationDto
import com.anarky.showtrack.core.network.dto.RecommendationPageDto
import com.anarky.showtrack.core.network.dto.RecommendationReasonDto
import com.anarky.showtrack.core.network.dto.RefreshRequest
import com.anarky.showtrack.core.network.dto.RegisterRequest
import com.anarky.showtrack.core.network.dto.ResolveMediaRequestDto
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.SearchItemDto
import com.anarky.showtrack.core.network.dto.SeasonDto
import com.anarky.showtrack.core.network.dto.SetWatchedRequestDto
import com.anarky.showtrack.core.network.dto.TokenPairDto
import com.anarky.showtrack.core.network.dto.UserDto
import com.anarky.showtrack.core.network.dto.WatchedEpisodesDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import com.anarky.showtrack.core.network.dto.WatchlistPreviewDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Every DTO against the backend's committed OpenAPI snapshot (`backend/openapi.json`), so a field
 * renamed, dropped or made nullable on the server fails the Android gate instead of a decode on
 * the phone. Gradle passes the snapshot's path in `showtrack.openapi`.
 *
 * Responses are checked field-by-field (see [SchemaChecker]); requests only for carrying every
 * field the server requires. Nested DTOs are covered by recursion from their parents; they are
 * listed on their own anyway, so the table is the complete inventory of the `dto` package.
 */
class OpenApiContractTest {
    private val snapshotFile: File? = System.getProperty(SNAPSHOT_PROPERTY)?.let(::File)

    private val checker by lazy {
        SchemaChecker(Json.parseToJsonElement(checkNotNull(snapshotFile).readText()).jsonObject)
    }

    private val responses: List<Pair<SerialDescriptor, String>> =
        listOf(
            EpisodeListDto.serializer().descriptor to "EpisodeList",
            SeasonDto.serializer().descriptor to "SeasonEpisodes",
            EpisodeDto.serializer().descriptor to "EpisodeItem",
            WatchedEpisodesDto.serializer().descriptor to "WatchedEpisodes",
            GroupDto.serializer().descriptor to "GroupSummary",
            WatchlistPreviewDto.serializer().descriptor to "WatchlistPreview",
            GroupWithInviteDto.serializer().descriptor to "GroupWithInvite",
            MemberDto.serializer().descriptor to "MemberRead",
            // One client shape for three identical server ones; FeedActor is the canonical one,
            // MemberPreview and ReviewAuthor are reached through GroupDto and ReviewDto.
            GroupActorDto.serializer().descriptor to "FeedActor",
            FeedItemDto.serializer().descriptor to "FeedItem",
            FeedPageDto.serializer().descriptor to "FeedPage",
            WatchlistItemDto.serializer().descriptor to "WatchlistItem",
            WatchlistPageDto.serializer().descriptor to "WatchlistPage",
            ProgressEntryDto.serializer().descriptor to "ProgressEntry",
            ImportSummaryDto.serializer().descriptor to "ImportSummary",
            LibraryEntryDto.serializer().descriptor to "LibraryEntry",
            LibraryPageDto.serializer().descriptor to "LibraryPage",
            LibraryStatsDto.serializer().descriptor to "LibraryStats",
            GenreCountDto.serializer().descriptor to "GenreCount",
            MediaDto.serializer().descriptor to "MediaDetail",
            PersistedMediaDto.serializer().descriptor to "PersistedMedia",
            RecommendationReasonDto.serializer().descriptor to "RecommendationReason",
            RecommendationDto.serializer().descriptor to "RecommendationItem",
            RecommendationPageDto.serializer().descriptor to "RecommendationPage",
            UserDto.serializer().descriptor to "UserOut",
            ReviewDto.serializer().descriptor to "ReviewRead",
            SearchItemDto.serializer().descriptor to "SearchItem",
            LibraryEntryRefDto.serializer().descriptor to "LibraryEntryRef",
            MediaSearchResponseDto.serializer().descriptor to "MediaSearchResponse",
            TokenPairDto.serializer().descriptor to "TokenPair",
        )

    private val requests: List<Pair<SerialDescriptor, String>> =
        listOf(
            AddLibraryEntryRequest.serializer().descriptor to "AddLibraryEntryRequest",
            SetWatchedRequestDto.serializer().descriptor to "SetWatchedRequest",
            CreateGroupRequestDto.serializer().descriptor to "CreateGroupRequest",
            JoinGroupRequestDto.serializer().descriptor to "JoinGroupRequest",
            ProposeTitleRequestDto.serializer().descriptor to "ProposeTitleRequest",
            ImportAniListRequest.serializer().descriptor to "ImportRequest",
            LoginRequest.serializer().descriptor to "LoginRequest",
            RefreshRequest.serializer().descriptor to "RefreshRequest",
            RegisterRequest.serializer().descriptor to "RegisterRequest",
            CreateReviewRequestDto.serializer().descriptor to "CreateReviewRequest",
            ResolveMediaRequestDto.serializer().descriptor to "ResolveMediaRequest",
        )

    private data class EnumRow(
        val component: String,
        val property: String,
        val values: List<String>,
    )

    private val enums: List<EnumRow> =
        listOf(
            EnumRow("MediaDetail", "status", wire(MediaStatus.entries - MediaStatus.UNKNOWN)),
            EnumRow("MediaDetail", "type", wire(MediaType.selectable)),
            EnumRow("MediaDetail", "source", wire(MediaSource.entries - MediaSource.UNKNOWN)),
            EnumRow("LibraryEntry", "status", wire(UserMediaStatus.selectable)),
            EnumRow("MemberRead", "role", wire(GroupRole.entries - GroupRole.UNKNOWN)),
            EnumRow("GroupSummary", "my_role", wire(GroupRole.entries - GroupRole.UNKNOWN)),
            EnumRow("FeedItem", "kind", wire(ActivityKind.entries - ActivityKind.UNKNOWN)),
        )

    @Test
    fun `the snapshot is present`() {
        val file = snapshotFile
        assertTrue("system property $SNAPSHOT_PROPERTY is not set", file != null)
        assertTrue("snapshot not found at $file", file!!.isFile)
    }

    @Test
    fun `every response dto matches its schema`() {
        // distinct: a nested DTO is reached from its own row and from every parent's.
        val problems =
            responses.flatMap { (descriptor, component) -> checker.checkResponse(descriptor, component) }.distinct()
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every request dto carries what the schema requires`() {
        val problems = requests.flatMap { (descriptor, component) -> checker.checkRequest(descriptor, component) }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every client enum value exists on the server`() {
        val problems = enums.flatMap { checker.checkEnum(it.component, it.property, it.values) }
        assertEquals(emptyList<String>(), problems)
    }

    /** A DTO added to the package without a row here would otherwise go unchecked. */
    @Test
    fun `every dto in the package has a row`() {
        val covered = (responses + requests).map { it.first.serialName }.toSet()
        // Equality, not "nothing missing": an empty scan must fail rather than pass vacuously.
        assertEquals(covered, serializableDtoNames())
    }

    // --- The checker itself, against synthetic schemas -------------------------------------

    @Serializable
    private data class Proposal(
        @SerialName("proposed_by") val proposedBy: String,
    )

    @Serializable
    private data class Titled(
        val title: String,
    )

    @Serializable
    private data class MaybeTitled(
        val title: String?,
    )

    @Serializable
    private data class Holder(
        val inner: Titled,
    )

    @Test
    fun `a field renamed on the server is reported`() {
        val checker = synthetic("Proposal" to obj(""""suggested_by": $STRING""", "suggested_by"))

        val problems = checker.checkResponse(Proposal.serializer().descriptor, "Proposal")

        assertEquals(listOf("Proposal.proposed_by: not in the server schema"), problems)
    }

    @Test
    fun `a non-null client field the server makes nullable is reported`() {
        val checker = synthetic("T" to obj(""""title": {"anyOf": [$STRING, {"type": "null"}]}""", "title"))

        val problems = checker.checkResponse(Titled.serializer().descriptor, "T")

        assertEquals(listOf("T.title: non-null on the client but nullable on the server"), problems)
    }

    @Test
    fun `a nullable client field over a required server field passes`() {
        val checker = synthetic("T" to obj(""""title": $STRING""", "title"))

        assertEquals(emptyList<String>(), checker.checkResponse(MaybeTitled.serializer().descriptor, "T"))
    }

    @Test
    fun `a nested class mismatch is reported`() {
        val checker =
            synthetic(
                "Holder" to obj(""""inner": ${ref("Inner")}""", "inner"),
                "Inner" to obj(""""title": {"type": "integer"}""", "title"),
            )

        val problems = checker.checkResponse(Holder.serializer().descriptor, "Holder")

        assertEquals(listOf("Inner.title: client expects STRING but the server sends integer"), problems)
    }

    @Test
    fun `an unsupported schema shape is reported, not passed`() {
        val checker = synthetic("T" to obj(""""title": {"oneOf": [$STRING, {"type": "integer"}]}""", "title"))

        val problems = checker.checkResponse(Titled.serializer().descriptor, "T")

        assertEquals(listOf("T.title: unsupported schema shape"), problems)
    }

    @Test
    fun `a request missing a required field is reported`() {
        val checker = synthetic("Req" to obj(""""title": $STRING, "year": {"type": "integer"}""", "title", "year"))

        val problems = checker.checkRequest(Titled.serializer().descriptor, "Req")

        assertEquals(listOf("Req.year: required by the server but the client never sends it"), problems)
    }

    @Test
    fun `an enum value missing on the server is reported`() {
        val checker =
            synthetic(
                "M" to obj(""""status": ${ref("S")}""", "status"),
                "S" to """{"type": "string", "enum": ["airing", "finished"]}""",
            )

        val problems = checker.checkEnum("M", "status", listOf("airing", "not_yet_aired"))

        assertEquals(listOf("M.status: value 'not_yet_aired' is not a server value"), problems)
    }

    /** A snapshot holding only [components], each a schema written as JSON. */
    private fun synthetic(vararg components: Pair<String, String>): SchemaChecker {
        val schemas = components.joinToString { (name, schema) -> "\"$name\": $schema" }
        return SchemaChecker(Json.parseToJsonElement("""{"components": {"schemas": {$schemas}}}""").jsonObject)
    }

    private fun obj(
        properties: String,
        vararg required: String,
    ): String {
        val names = required.joinToString { "\"$it\"" }
        return """{"type": "object", "properties": {$properties}, "required": [$names]}"""
    }

    private fun ref(component: String): String = """{"${'$'}ref": "#/components/schemas/$component"}"""

    private companion object {
        const val SNAPSHOT_PROPERTY = "showtrack.openapi"
        const val DTO_PACKAGE = "com.anarky.showtrack.core.network.dto"
        const val SERIALIZER_SUFFIX = "\$\$serializer.class"
        const val STRING = """{"type": "string"}"""

        fun wire(values: List<Enum<*>>): List<String> = values.map { it.name.lowercase() }

        /**
         * The `@Serializable` classes of the dto package, found by the `$$serializer` class the
         * plugin generates for each (the annotation itself is not visible at runtime). Reads the
         * compiled main classes, whether Gradle hands them over as a directory or a jar.
         */
        fun serializableDtoNames(): Set<String> {
            val root =
                File(
                    MediaDto::class.java.protectionDomain.codeSource.location
                        .toURI(),
                )
            val dir = DTO_PACKAGE.replace('.', '/')
            val entries =
                if (root.isDirectory) {
                    root
                        .resolve(dir)
                        .listFiles()
                        .orEmpty()
                        .map { "$dir/${it.name}" }
                } else {
                    JarFile(root).use { jar -> jar.entries().toList().map { it.name } }
                }
            return entries
                .filter { it.substringBeforeLast('/') == dir && it.endsWith(SERIALIZER_SUFFIX) }
                .map { it.removeSuffix(SERIALIZER_SUFFIX).replace('/', '.') }
                .toSet()
        }
    }
}
